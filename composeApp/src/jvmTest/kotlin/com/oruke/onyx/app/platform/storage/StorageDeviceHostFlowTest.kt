package com.oruke.onyx.app.platform.storage

import com.oruke.onyx.app.filesystem.DesktopLocationPathService
import com.oruke.onyx.app.filesystem.SystemLocationVfsProvider
import com.oruke.onyx.core.model.COMPUTER_LOCATION
import com.oruke.onyx.core.model.StorageDeviceAction
import com.oruke.onyx.core.model.StorageDeviceState
import com.oruke.onyx.core.model.VFileCapability
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** 在实际宿主系统上验证设备发现、异步容量、统一 VFS 和观察资源释放。 */
class StorageDeviceHostFlowTest {
    /** 真实设备能够进入统一仓库，容量查询完成后保留稳定身份与挂载路径。 */
    @Test
    fun nativeHostVolumesFlowThroughProviderAndMetadata() = runBlocking {
        val sample = Files.createTempDirectory("onyx-storage-host-")
        val payload = Files.writeString(sample.resolve("preserved.txt"), "storage-sample")
        val service = JvmStorageDeviceService()
        try {
            val root = sample.root.toString()
            val before = service.snapshot().getOrThrow()
            val device =
                assertNotNull(before.firstOrNull { root in it.locations }, "实际样本所在文件系统必须出现在设备清单")
            val observed =
                withTimeout(15_000) {
                        service
                            .observe()
                            .first { result ->
                                result.getOrNull()?.any {
                                    it.id == device.id && it.totalBytes != null
                                } == true
                            }
                            .getOrThrow()
                    }
                    .single { it.id == device.id }
            assertEquals(device.locations, observed.locations)
            assertEquals(StorageDeviceState.MOUNTED, observed.state)
            assertTrue(assertNotNull(observed.totalBytes) > 0)
            assertTrue(assertNotNull(observed.availableBytes) in 0..observed.totalBytes!!)
            val entries = SystemLocationVfsProvider(service).list(COMPUTER_LOCATION).getOrThrow()
            assertTrue(
                entries.any {
                    it.location == root && VFileCapability.LIST_CHILDREN in it.capabilities
                }
            )
            assertTrue(
                entries.none {
                    VFileCapability.DELETE in it.capabilities ||
                        VFileCapability.RENAME in it.capabilities
                }
            )
            assertFalse(
                service.execute("missing-storage-device", StorageDeviceAction.MOUNT).isSuccess
            )
            assertEquals("storage-sample", Files.readString(payload))
            println(
                "STORAGE_HOST: host=${System.getProperty("os.name")}, volumes=${before.size}, root-capacity=${observed.totalBytes}, stable-identity=true"
            )
        } finally {
            service.close()
            Files.deleteIfExists(payload)
            Files.deleteIfExists(sample)
        }
    }

    /** 本地根上级进入概览，但虚拟导航不能变成物理祖先或文件传输目的地。 */
    @Test
    fun rootNavigationPreservesPhysicalPathSemantics() {
        val sample = Files.createTempDirectory("onyx-storage-path-")
        try {
            val paths = DesktopLocationPathService()
            val root = sample.root.toString()
            assertEquals(COMPUTER_LOCATION, paths.navigationParent(root))
            assertEquals(null, paths.parentLocation(root))
            assertEquals(null, paths.navigationParent(COMPUTER_LOCATION))
            assertEquals(COMPUTER_LOCATION, paths.normalizeLocation("shell:MyComputerFolder"))
            assertTrue(paths.isLocationAvailable(COMPUTER_LOCATION))
            assertEquals(
                COMPUTER_LOCATION,
                paths.buildBreadcrumbs(sample.toString()).first().location,
            )
            assertFalse(paths.isSameOrChildOf(sample.toString(), COMPUTER_LOCATION))
        } finally {
            Files.deleteIfExists(sample)
        }
    }
}
