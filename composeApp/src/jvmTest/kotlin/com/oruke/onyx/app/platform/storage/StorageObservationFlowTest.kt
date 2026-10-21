package com.oruke.onyx.app.platform.storage

import com.oruke.onyx.app.platform.DesktopOperatingSystem
import com.oruke.onyx.app.platform.currentDesktopOperatingSystem
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.StorageDevice
import com.oruke.onyx.core.model.StorageDeviceAction
import com.oruke.onyx.core.model.StorageDeviceKind
import com.oruke.onyx.core.model.StorageDeviceState
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/** 验证慢盘容量隔离、重复查询合并，以及 Windows 原生通知的完整资源生命周期。 */
class StorageObservationFlowTest {
    /** 一个阻塞容量读取不会阻止其他真实文件系统容量到达，也不会在每次通知后重复堆积。 */
    @Test
    fun blockedMetadataDoesNotDelayOtherVolumesOrMultiplyReads() = runBlocking {
        val workspace = Files.createTempDirectory("onyx-storage-observation-")
        val payload = Files.writeString(workspace.resolve("preserved.txt"), "preserved")
        val backend = SlowMetadataBackend(workspace)
        val service = JvmStorageDeviceService(backend)
        val observed = MutableStateFlow(emptyList<StorageDevice>())
        val observer = launch { service.observe().collect { observed.value = it.getOrThrow() } }
        try {
            withTimeout(5_000) { backend.started.await() }
            val available =
                withTimeout(5_000) {
                    observed.first { devices ->
                        devices.any { it.id == "fast-volume" && it.totalBytes != null } &&
                            devices.any {
                                it.id == "slow-volume" &&
                                    it.failure?.key == MessageKey.MSG_DEVICE_METADATA_TIMEOUT
                            }
                    }
                }
            assertEquals(
                Files.getFileStore(workspace).totalSpace,
                available.single { it.id == "fast-volume" }.totalBytes,
            )
            backend.extraAction = true
            backend.publish()
            withTimeout(5_000) {
                observed.first { devices ->
                    devices.any {
                        it.id == "fast-volume" && StorageDeviceAction.PROPERTIES in it.actions
                    }
                }
            }
            assertEquals(1, backend.slowReads.get())
            backend.release.countDown()
            backend.publish()
            withTimeout(10_000) {
                observed.first { devices ->
                    devices.any {
                        it.id == "slow-volume" && it.totalBytes != null && it.failure == null
                    }
                }
            }
            assertEquals(1, backend.slowReads.get())
            assertEquals("preserved", Files.readString(payload))
        } finally {
            backend.release.countDown()
            observer.cancelAndJoin()
            service.close()
            Files.deleteIfExists(payload)
            Files.deleteIfExists(workspace)
        }
    }

    /** 只向本测试新建的隐藏窗口发送设备通知，取消订阅后窗口必须真实销毁。 */
    @Test
    fun windowsDeviceNotificationUsesAndReleasesDedicatedWindow() = runBlocking {
        assumeTrue(currentDesktopOperatingSystem() == DesktopOperatingSystem.WINDOWS)
        val baseline = storageNotificationWindows().map { Pointer.nativeValue(it.pointer) }.toSet()
        val notification = CompletableDeferred<Unit>()
        val observer =
            launch(Dispatchers.IO) {
                WindowsStorageChanges.observe().collect { notification.complete(Unit) }
            }
        var owned: HWND? = null
        try {
            owned =
                withTimeout(5_000) {
                    var found: HWND? = null
                    while (found == null) {
                        found =
                            storageNotificationWindows().firstOrNull {
                                Pointer.nativeValue(it.pointer) !in baseline
                            }
                        if (found == null) delay(25)
                    }
                    found
                }
            assertEquals(
                0L,
                User32.INSTANCE.SendMessage(owned, WinUser.WM_DEVICECHANGE, WPARAM(7), LPARAM(0))
                    .toLong(),
            )
            withTimeout(5_000) { notification.await() }
        } finally {
            observer.cancelAndJoin()
        }
        assertFalse(User32.INSTANCE.IsWindow(checkNotNull(owned)))
        assertEquals(
            baseline,
            storageNotificationWindows().map { Pointer.nativeValue(it.pointer) }.toSet(),
        )
    }
}

/**
 * 保留真实容量查询，仅阻塞指定卷的查询以稳定复现慢盘。
 *
 * @param location 本测试拥有的真实样本路径。
 */
private class SlowMetadataBackend(private val location: Path) : StorageDeviceBackend {
    /** 慢容量查询已经在后台线程开始。 */
    val started = CompletableDeferred<Unit>()
    /** 允许慢容量调用结束。 */
    val release = CountDownLatch(1)
    /** 慢卷实际启动的元数据查询次数。 */
    val slowReads = AtomicInteger()
    /** 第二次扫描的可观察能力变化。 */
    @Volatile var extraAction = false
    /** 可控原生变化通知。 */
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** @return 两个指向真实样本文件系统的独立设备。 */
    override fun discover(): List<StorageDevice> =
        listOf("slow-volume", "fast-volume").map {
            StorageDevice(
                it,
                it,
                StorageDeviceKind.LOCAL,
                listOf(location.toString()),
                StorageDeviceState.MOUNTED,
                if (extraAction) setOf(StorageDeviceAction.PROPERTIES) else emptySet(),
            )
        }

    /**
     * 慢卷等待放行，最终仍通过生产 NIO 实现读取真实容量。
     *
     * @param device 当前查询的设备。
     * @return 真实文件系统的容量快照。
     */
    override fun metadata(device: StorageDevice): StorageDevice {
        if (device.id == "slow-volume") {
            slowReads.incrementAndGet()
            started.complete(Unit)
            check(release.await(15, TimeUnit.SECONDS)) { "metadata gate timed out" }
        }
        return super.metadata(device)
    }

    /**
     * 本场景不执行设备变更，误调用必须让测试失败。
     *
     * @param device 被误操作的设备。
     * @param action 被误请求的原生动作。
     * @return 本方法不正常返回。
     */
    override fun execute(device: StorageDevice, action: StorageDeviceAction): String? =
        error("Unexpected device action: ${device.id} $action")

    /** @return 由生产服务管理的变更触发流。 */
    override fun changes(): Flow<Unit> = changes

    /** 发布一次设备变化，元数据在途任务应被合并复用。 */
    fun publish() {
        changes.tryEmit(Unit)
    }
}

/** @return 当前进程拥有的设备观察专用窗口，不接触其他应用窗口。 */
private fun storageNotificationWindows(): List<HWND> = buildList {
    val process = Kernel32.INSTANCE.GetCurrentProcessId()
    User32.INSTANCE.EnumWindows(
        { window, _ ->
            val owner = IntByReference()
            User32.INSTANCE.GetWindowThreadProcessId(window, owner)
            val name = CharArray(256)
            User32.INSTANCE.GetClassName(window, name, name.size)
            if (owner.value == process && Native.toString(name).startsWith("OnyxStorage-"))
                add(window)
            true
        },
        null,
    )
}
