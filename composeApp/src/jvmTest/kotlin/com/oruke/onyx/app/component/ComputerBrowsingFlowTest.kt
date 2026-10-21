package com.oruke.onyx.app.component

import com.oruke.onyx.app.filesystem.DesktopLocationPathService
import com.oruke.onyx.app.platform.storage.JvmStorageDeviceService
import com.oruke.onyx.app.platform.storage.StorageDeviceBackend
import com.oruke.onyx.core.model.COMPUTER_LOCATION
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.StorageDevice
import com.oruke.onyx.core.model.StorageDeviceAction
import com.oruke.onyx.core.model.StorageDeviceKind
import com.oruke.onyx.core.model.StorageDeviceState
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.core.model.PaneSessionSnapshot
import com.oruke.onyx.core.model.TabSnapshot
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 经过真实面板、共享设备服务与 NIO 仓储，验证挂载结果和多标签、多面板状态闭环。 */
class ComputerBrowsingFlowTest {
    /** 普通目录与概览的视图独立，后退、真实设备打开和序列化恢复均不能互相覆盖。 */
    @Test
    fun presentationPreferenceSurvivesNavigationAndSessionRestore() = computerScenario { backend, _ ->
        Files.writeString(source.resolve("presentation.txt"), "visible-after-open")
        backend.devices = listOf(backend.device.copy(
            locations = listOf(source.toString()), state = StorageDeviceState.MOUNTED,
        ))
        primary.dispatch(PaneIntent.OpenDirectory(COMPUTER_LOCATION))
        primary.awaitComputer { it.devices.isNotEmpty() }
        assertEquals(ViewMode.GALLERY, primary.state.value.viewMode)
        primary.dispatch(PaneIntent.SetViewMode(ViewMode.DETAILS))
        primary.dispatch(PaneIntent.SelectEntry(backend.device.id))
        assertEquals(backend.device.id, primary.state.value.selectionFocusId)
        primary.dispatch(PaneIntent.OpenSelectedEntry)
        val opening = checkNotNull(primary.deviceOperationJobs[backend.device.id]) {
            "设备打开未开始：${primary.state.value.entriesState}"
        }
        withTimeout(15_000) { opening.join() }
        assertNull((primary.state.value.entriesState as? ComputerEntriesState)?.operationFailure)
        assertEquals(source.toString(), primary.state.value.location)
        primary.awaitBrowsing {
            it.location == source.toString() &&
                (it.entriesState as? PaneEntriesState.Ready)?.entries?.any { entry ->
                    entry.name == "presentation.txt"
                } == true
        }
        assertEquals(ViewMode.DETAILS, primary.state.value.viewMode)
        assertEquals("visible-after-open", Files.readString(source.resolve("presentation.txt")))
        primary.dispatch(PaneIntent.SetViewMode(ViewMode.GALLERY))
        primary.dispatch(PaneIntent.GoBack)
        primary.awaitComputer { it.devices.isNotEmpty() }
        assertEquals(ViewMode.DETAILS, primary.state.value.viewMode)
        assertEquals(setOf(backend.device.id), primary.state.value.selectedEntryIds)

        val snapshot = primary.tabStates.value.first { it.id == primary.state.value.activeTabId }.toTabSnapshot()
        val restored = Json.decodeFromString<TabSnapshot>(Json.encodeToString(snapshot))
        assertEquals(ViewMode.GALLERY, restored.viewMode)
        assertEquals(ViewMode.DETAILS, restored.computerViewMode)
        secondary.restoreSession(PaneSessionSnapshot(restored.id, listOf(restored)))
        secondary.awaitComputer { it.devices.isNotEmpty() }
        assertEquals(ViewMode.DETAILS, secondary.state.value.viewMode)
        secondary.dispatch(PaneIntent.OpenDirectory(source.toString()))
        secondary.awaitBrowsing {
            it.location == source.toString() && it.entriesState is PaneEntriesState.Ready
        }
        assertEquals(ViewMode.GALLERY, secondary.state.value.viewMode)
    }

    /** 同一设备改换挂载点后保留选择，打开必须读取新目录的真实内容。 */
    @Test
    fun stableIdentityOpensCurrentMountWithoutLosingSelection() = computerScenario { backend, _ ->
        Files.writeString(target.resolve("current.txt"), "current-mount")
        secondary.dispatch(PaneIntent.Refresh)
        secondary.awaitBrowsing {
            (it.entriesState as? PaneEntriesState.Ready)?.entries?.any {
                it.name == "current.txt"
            } == true
        }
        backend.devices = listOf(backend.device.copy(locations = listOf(source.toString())))
        primary.dispatch(PaneIntent.OpenDirectory(COMPUTER_LOCATION))
        primary.awaitComputer { it.devices.isNotEmpty() }
        primary.dispatch(PaneIntent.SelectEntry(backend.device.id))
        backend.devices = listOf(backend.device.copy(locations = listOf(target.toString())))
        backend.publish()
        primary.awaitComputer { it.devices.singleOrNull()?.locations == listOf(target.toString()) }
        assertEquals(setOf(backend.device.id), primary.state.value.selectedEntryIds)
        primary.dispatch(PaneIntent.OpenSelectedEntry)
        val operation =
            checkNotNull(primary.deviceOperationJobs[backend.device.id]) {
                "设备打开没有启动：focus=${primary.state.value.selectionFocusId}, state=${primary.state.value.entriesState}"
            }
        withTimeout(5_000) { operation.join() }
        assertNull((primary.state.value.entriesState as? ComputerEntriesState)?.operationFailure)
        assertEquals(target.toString(), primary.state.value.location)
        val opened = primary.awaitBrowsing {
            it.location == target.toString() &&
                (it.entriesState as? PaneEntriesState.Ready)?.entries?.any {
                    it.name == "current.txt"
                } == true
        }
        assertEquals("current-mount", Files.readString(target.resolve("current.txt")))
        assertTrue(
            assertIs<PaneEntriesState.Ready>(opened.entriesState).entries.any {
                it.name == "current.txt"
            }
        )
    }

    /** 挂载等待时切走标签，另一个面板不能重复挂载，完成后不抢导航且后台忙碌标记清除。 */
    @Test
    fun mountCompletionRespectsVisitAndClearsInactiveTab() = computerScenario { backend, service ->
        backend.mountLocation = source.toString()
        val gate = MountOperationGate()
        backend.gate = gate
        primary.dispatch(PaneIntent.OpenDirectory(COMPUTER_LOCATION))
        primary.awaitComputer { it.devices.isNotEmpty() }
        val computerTab = primary.state.value.activeTabId
        primary.dispatch(PaneIntent.SelectEntry(backend.device.id))
        primary.dispatch(PaneDeviceIntent.Open(backend.device.id))
        withTimeout(5_000) { gate.started.await() }
        primary.awaitComputer { backend.device.id in it.busyDeviceIds }
        primary.dispatch(PaneIntent.CreateTab(target.toString()))
        primary.awaitBrowsing {
            it.location == target.toString() && it.entriesState is PaneEntriesState.Ready
        }
        val duplicate = service.execute(backend.device.id, StorageDeviceAction.MOUNT)
        assertEquals(
            MessageKey.MSG_DEVICE_BUSY,
            assertIs<LocalizedException>(duplicate.exceptionOrNull()).userMessage.key,
        )
        secondary.dispatch(PaneIntent.OpenDirectory(COMPUTER_LOCATION))
        secondary.awaitComputer { it.devices.isNotEmpty() }
        secondary.dispatch(PaneDeviceIntent.Open(backend.device.id))
        secondary.awaitComputer { it.operationFailure?.key == MessageKey.MSG_DEVICE_BUSY }
        val operation = checkNotNull(primary.deviceOperationJobs[backend.device.id])
        gate.release.countDown()
        withTimeout(10_000) { operation.join() }
        assertEquals(target.toString(), primary.state.value.location)
        val background = primary.tabStates.value.single { it.id == computerTab }
        assertTrue(assertIs<ComputerEntriesState>(background.entriesState).busyDeviceIds.isEmpty())
        primary.dispatch(PaneIntent.SelectTab(computerTab))
        primary.awaitComputer { it.devices.singleOrNull()?.locations == listOf(source.toString()) }
        assertTrue(
            assertIs<ComputerEntriesState>(primary.state.value.entriesState).busyDeviceIds.isEmpty()
        )
        assertEquals(setOf(backend.device.id), primary.state.value.selectedEntryIds)
        assertEquals(1, backend.operations.get())
    }

    /** 操作失败不会被设备热更新隐藏，用户显式刷新后可恢复，并且真实文件内容不变。 */
    @Test
    fun deviceUpdatesKeepActionFailureUntilExplicitRetry() = computerScenario { backend, _ ->
        Files.writeString(source.resolve("preserved.txt"), "preserved")
        backend.failure = IOException("controlled device failure")
        primary.dispatch(PaneIntent.OpenDirectory(COMPUTER_LOCATION))
        primary.awaitComputer { it.devices.isNotEmpty() }
        primary.dispatch(PaneDeviceIntent.Execute(backend.device.id, StorageDeviceAction.MOUNT))
        val failed = primary.awaitComputer {
            it.operationFailure != null && it.busyDeviceIds.isEmpty()
        }
        backend.devices = listOf(backend.device.copy(name = "Updated device"))
        backend.publish()
        val updated = primary.awaitComputer { it.devices.singleOrNull()?.name == "Updated device" }
        assertEquals(failed.operationFailure, updated.operationFailure)
        primary.dispatch(PaneIntent.Refresh)
        primary.awaitComputer { !it.loading && it.operationFailure == null }
        assertEquals("preserved", Files.readString(source.resolve("preserved.txt")))
    }

    /** 不经过电脑历史直接打开本地根目录，再向上时也能定位该设备。 */
    @Test
    fun upFromDirectlyOpenedRootFocusesItsDevice() = computerScenario { backend, _ ->
        backend.devices = listOf(backend.device.copy(locations = listOf(source.root.toString())))
        primary.dispatch(PaneIntent.OpenDirectory(source.root.toString()))
        primary.awaitBrowsing {
            it.location == source.root.toString() && it.entriesState is PaneEntriesState.Ready
        }
        primary.dispatch(PaneIntent.GoUp)
        primary.awaitBrowsing {
            it.location == COMPUTER_LOCATION && it.selectedEntryIds == setOf(backend.device.id)
        }
        assertEquals(backend.device.id, primary.state.value.selectionFocusId)
        assertNull(primary.pendingFocusEntryName[primary.state.value.activeTabId])
    }
}

/** 平台挂载的确定性闸门，测试不会挂载或卸载任何用户设备。 */
private class MountOperationGate {
    /** 原生动作已经进入设备服务的 I/O 线程。 */
    val started = CompletableDeferred<Unit>()
    /** 允许模拟平台动作完成。 */
    val release = CountDownLatch(1)
}

/** 只替代无法安全制造的设备热插拔边界，目录读取、状态、操作互斥仍运行生产实现。 */
private class ComputerScenarioBackend : StorageDeviceBackend {
    /** 用于所有场景的稳定身份，不包含测试宿主信息。 */
    val device =
        StorageDevice(
            "sample-device",
            "Sample device",
            StorageDeviceKind.REMOVABLE,
            emptyList(),
            StorageDeviceState.UNMOUNTED,
            setOf(StorageDeviceAction.MOUNT),
        )
    /** 当前设备清单，所有已挂载路径由测试创建的真实目录提供。 */
    @Volatile var devices = listOf(device)
    /** 挂载完成后的真实目录。 */
    @Volatile var mountLocation: String? = null
    /** 下一次动作需要呈现的明确失败。 */
    @Volatile var failure: IOException? = null
    /** 当前可选的挂载等待闸门。 */
    @Volatile var gate: MountOperationGate? = null
    /** 实际进入平台动作的次数，用于核对跨面板去重。 */
    val operations = AtomicInteger()
    /** 模拟原生通知，清单本身仍由共享设备服务重新查询。 */
    private val updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** @return 当前线程安全的设备清单。 */
    override fun discover(): List<StorageDevice> = devices

    /**
     * 经过闸门后发布实际临时目录，或返回场景指定的失败。
     *
     * @param device 服务重新校验后的设备。
     * @param action 已经校验过能力的操作。
     * @return 挂载后可供真实文件仓储浏览的位置。
     */
    override fun execute(device: StorageDevice, action: StorageDeviceAction): String? {
        check(action == StorageDeviceAction.MOUNT)
        operations.incrementAndGet()
        gate?.let {
            it.started.complete(Unit)
            check(it.release.await(10, TimeUnit.SECONDS)) { "mount gate timed out" }
        }
        failure?.let { throw it }
        val location = checkNotNull(mountLocation)
        devices =
            listOf(device.copy(locations = listOf(location), state = StorageDeviceState.MOUNTED))
        publish()
        return location
    }

    /** @return 清单变动通知流，由生产服务管理订阅与生命周期。 */
    override fun changes(): Flow<Unit> = updates

    /** 通知生产共享服务读取最新清单。 */
    fun publish() {
        updates.tryEmit(Unit)
    }
}

/**
 * 使用真实主线程、双面板、设备服务与临时目录执行完整场景，所有资源由本场景回收。
 *
 * @param scenario 完整业务流程与最终状态断言。
 */
private fun computerScenario(
    scenario:
        suspend DirectoryBrowsingFixture.(ComputerScenarioBackend, JvmStorageDeviceService) -> Unit
) = runBlocking {
    withContext(Dispatchers.Main) {
        val backend = ComputerScenarioBackend()
        val service = JvmStorageDeviceService(backend)
        val fixture =
            DirectoryBrowsingFixture(
                storageDevices = service,
                pathService = DesktopLocationPathService(),
            )
        try {
            fixture.scenario(backend, service)
        } finally {
            backend.gate?.release?.countDown()
            fixture.close()
            service.close()
        }
    }
}

/**
 * 等待经过生产面板发布的设备最终状态。
 *
 * @param predicate 本场景必须满足的设备业务条件。
 * @return 满足条件的概览状态。
 */
private suspend fun PaneComponent.awaitComputer(
    predicate: (ComputerEntriesState) -> Boolean
): ComputerEntriesState =
    assertIs<ComputerEntriesState>(
        awaitBrowsing {
            (it.entriesState as? ComputerEntriesState)?.let(predicate) == true
        }
            .entriesState
    )
