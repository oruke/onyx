package com.oruke.onyx.app.platform

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.SystemFileMaterializer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import javax.swing.JPanel
import javax.swing.TransferHandler
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** JVM 系统外拖服务线程与生命周期测试。 */
class JvmExternalFileDragServiceTest {
    /**
     * 校验远程文件物化在后台启动，不阻塞准备拖放的调用线程。
     */
    @Test
    fun startsRemoteMaterializationWithoutBlockingDragPreparation() = runTest {
        val materializer = DelayedMaterializer()
        val service = JvmExternalFileDragService(materializer)

        service.preparePendingFiles(listOf(remoteFile()))

        withTimeout(TEST_TIMEOUT_MILLIS) { materializer.started.await() }
        assertFalse(materializer.result.isCompleted)
        materializer.result.complete(Result.success(localFile()))
        service.clearPending()
    }

    /**
     * 校验卸载外拖桥接时恢复组件原有的 TransferHandler。
     */
    @Test
    fun restoresOriginalTransferHandlerWhenBridgeIsUninstalled() {
        val service = JvmExternalFileDragService(DelayedMaterializer())
        val component = JPanel()
        val originalHandler = TransferHandler("name")
        component.transferHandler = originalHandler

        service.installOnComponent(component)

        assertNotSame(originalHandler, component.transferHandler)
        service.uninstall()
        assertSame(originalHandler, component.transferHandler)
    }

    /** 可控完成时机的远程文件物化器。 */
    private class DelayedMaterializer : SystemFileMaterializer {
        /** 物化调用已经开始的信号。 */
        val started = CompletableDeferred<Unit>()

        /** 物化调用最终结果。 */
        val result = CompletableDeferred<Result<VFile>>()

        /**
         * 测试物化器支持所有条目。
         *
         * @param entry 待检查条目。
         * @return 始终返回 true。
         */
        override fun supports(entry: VFile): Boolean = true

        /**
         * 等待测试代码提供物化结果。
         *
         * @param entry 待物化条目。
         * @return 测试代码提供的结果。
         */
        override suspend fun materialize(entry: VFile): Result<VFile> {
            started.complete(Unit)
            return result.await()
        }
    }

    /**
     * 创建远程测试文件。
     *
     * @return 不具备本地系统路径的 VFS 文件。
     */
    private fun remoteFile(): VFile {
        return VFile(
            id = "smb://host/share/file.txt",
            name = "file.txt",
            location = "smb://host/share/file.txt",
            parentLocation = "smb://host/share/",
            kind = VFileKind.FILE,
            sizeBytes = 1L,
            modifiedAtEpochMillis = null,
            hidden = false,
            capabilities = setOf(VFileCapability.READ_CONTENT),
        )
    }

    /**
     * 创建已物化的本地测试文件。
     *
     * @return 指向临时文件的 VFS 文件。
     */
    private fun localFile(): VFile {
        val path = Files.createTempFile("onyx-drag-test-", ".txt")
        path.toFile().deleteOnExit()
        return VFile(
            id = path.toString(),
            name = path.fileName.toString(),
            location = path.toString(),
            parentLocation = path.parent.toString(),
            kind = VFileKind.FILE,
            sizeBytes = 0L,
            modifiedAtEpochMillis = null,
            hidden = false,
            capabilities = setOf(VFileCapability.READ_CONTENT),
        )
    }

    private companion object {
        /** 后台物化启动的最长等待时间。 */
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }
}
