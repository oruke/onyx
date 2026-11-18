package com.oruke.onyx.vfs.local

import com.oruke.onyx.vfs.api.FileTransferProgressSink
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.sun.nio.file.ExtendedOpenOption
import java.nio.channels.FileChannel
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 从真实本地 Provider 入口验证覆盖失败、取消和成功提交时的文件内容。 */
class LocalTransferSafetyTest {
    /** 验证 Windows 拒绝读取源文件时，旧目标内容及源均保留。 */
    @Test
    fun lockedSourcePreservesPreviousDestination() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows")) return@runBlocking
        withFixture { source, target, provider ->
            val entry = provider.list(source.parent.toString()).getOrThrow().single()
            val result = FileChannel.open(source, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_READ).use {
                provider.copy(listOf(entry), target.parent.toString(), TransferConflictStrategy.OVERWRITE)
            }
            assertTrue(result.isFailure)
            assertEquals("source-new", Files.readString(source))
            assertEquals("destination-old", Files.readString(target))
            assertCleanTargetDirectory(target)
        }
    }

    /** 验证用户在真实字节回调中取消后，原目标与完整源文件保留。 */
    @Test
    fun cancelledOverwritePreservesPreviousDestination() = runBlocking {
        withFixture { source, target, provider ->
            val bytes = ByteArray(2 * 1024 * 1024) { 65 }
            Files.write(source, bytes)
            val entry = provider.list(source.parent.toString()).getOrThrow().single()
            coroutineScope {
                val work = launch {
                    provider.copyWithProgress(
                        listOf(entry),
                        target.parent.toString(),
                        TransferConflictStrategy.OVERWRITE
                    ) {
                        cancel("测试取消复制")
                    }
                }
                work.join()
                assertTrue(work.isCancelled)
            }
            assertContentEquals(bytes, Files.readAllBytes(source))
            assertEquals("destination-old", Files.readString(target))
            assertCleanTargetDirectory(target)
        }
    }

    /** 验证单块复制的最后一次回调取消，不会提交已写完的暂存内容。 */
    @Test
    fun cancelledFinalChunkPreservesPreviousDestination() = runBlocking {
        withFixture { source, target, provider ->
            val entry = provider.list(source.parent.toString()).getOrThrow().single()
            coroutineScope {
                val work = launch {
                    provider.copyWithProgress(
                        listOf(entry),
                        target.parent.toString(),
                        TransferConflictStrategy.OVERWRITE
                    ) {
                        cancel("测试最后一个数据块取消")
                    }
                }
                work.join()
                assertTrue(work.isCancelled)
            }
            assertEquals("source-new", Files.readString(source))
            assertEquals("destination-old", Files.readString(target))
            assertCleanTargetDirectory(target)
        }
    }

    /** 验证完整复制提交后旧文件才被替换，成功时不遗留暂存目录。 */
    @Test
    fun overwriteCommitsCompleteContent() = runBlocking {
        withFixture { source, target, provider ->
            val entry = provider.list(source.parent.toString()).getOrThrow().single()
            provider.copy(listOf(entry), target.parent.toString(), TransferConflictStrategy.OVERWRITE).getOrThrow()
            assertEquals("source-new", Files.readString(source))
            assertEquals("source-new", Files.readString(target))
            assertCleanTargetDirectory(target)
        }
    }

    /** 验证覆盖移动成功后完整内容在目标，源被移除且暂存清理完成。 */
    @Test
    fun overwriteMoveCommitsAndRemovesSource() = runBlocking {
        withFixture { source, target, provider ->
            val entry = provider.list(source.parent.toString()).getOrThrow().single()
            provider.move(listOf(entry), target.parent.toString(), TransferConflictStrategy.OVERWRITE).getOrThrow()
            assertFalse(Files.exists(source))
            assertEquals("source-new", Files.readString(target))
            assertCleanTargetDirectory(target)
        }
    }

    /** 验证复制期间新出现的目标不会被部分目标清理误删。 */
    @Test
    fun concurrentDestinationIsPreservedOnCommitFailure() = runBlocking {
        withFixture { source, target, provider ->
            Files.delete(target)
            val entry = provider.list(source.parent.toString()).getOrThrow().single()
            val result = provider.copyWithProgress(
                listOf(entry), target.parent.toString(), TransferConflictStrategy.KEEP_BOTH,
            ) { Files.writeString(target, "concurrent-content") }
            assertTrue(result.isFailure)
            assertEquals("source-new", Files.readString(source))
            assertEquals("concurrent-content", Files.readString(target))
            assertCleanTargetDirectory(target)
        }
    }

    /** 验证新增目录复制在传输期间即创建可见目标目录并上报子文件名，中途取消时完整清理新建目录。 */
    @Test
    fun newDirectoryCopyExposesTargetDuringTransferAndRollsBackOnCancel() = runBlocking {
        val root = Files.createTempDirectory("onyx-dir-visible-")
        try {
            val sourceParent = Files.createDirectory(root.resolve("src"))
            val sourceDir = Files.createDirectory(sourceParent.resolve("episodes"))
            Files.write(sourceDir.resolve("ep01.mkv"), ByteArray(512 * 1024) { 7 })
            val targetParent = Files.createDirectory(root.resolve("dst"))
            val provider = JvmLocalFileProvider()
            val dirEntry = provider.list(sourceParent.toString()).getOrThrow().single()

            var visibleMidTransfer = false
            val reportedFiles = mutableListOf<String>()
            provider.copyWithProgress(
                entries = listOf(dirEntry),
                targetDirectoryLocation = targetParent.toString(),
                conflictStrategy = TransferConflictStrategy.KEEP_BOTH,
                progressSink = object : FileTransferProgressSink {
                    override fun onBytesTransferred(byteCount: Long) {
                        visibleMidTransfer = Files.isDirectory(targetParent.resolve("episodes"))
                    }

                    override fun onCurrentFileChanged(fileName: String) {
                        reportedFiles += fileName
                    }
                },
            ).getOrThrow()
            assertTrue(visibleMidTransfer)
            assertTrue(reportedFiles.any { name -> name.endsWith("ep01.mkv") })
            assertCleanTargetDirectory(targetParent.resolve("episodes"))

            val cancelTargetParent = Files.createDirectory(root.resolve("dst-cancel"))
            coroutineScope {
                val job = launch {
                    provider.copyWithProgress(
                        entries = listOf(dirEntry),
                        targetDirectoryLocation = cancelTargetParent.toString(),
                        conflictStrategy = TransferConflictStrategy.KEEP_BOTH,
                    ) {
                        cancel("取消目录复制")
                    }
                }
                job.join()
                assertTrue(job.isCancelled)
            }
            assertFalse(Files.exists(cancelTargetParent.resolve("episodes")))
        } finally {
            LocalPathDeletion.delete(root)
        }
    }

    /** 验证目标文件名超出文件系统单组件上限时，在读取和写入任何源字节之前立即失败且不暴露暂存路径。 */
    @Test
    fun nearLimitInvalidTargetFailsBeforeTransferringSourceBytes() = runBlocking {
        val root = Files.createTempDirectory("onyx-near-limit-")
        try {
            val sourceDir = Files.createDirectory(root.resolve("src"))
            val targetDir = Files.createDirectory(root.resolve("dst"))
            val sourceFile = sourceDir.resolve("payload.zip")
            Files.write(sourceFile, ByteArray(2 * 1024 * 1024) { 42 })

            val provider = JvmLocalFileProvider()
            val realEntry = provider.list(sourceDir.toString()).getOrThrow().single()
            // 伪装为来自其它文件系统的超长文件名源（或者在 KEEP_BOTH 冲突重命名导致超过 255 字符上限的场景）：
            // 这里用真实冲突 + KEEP_BOTH 构造最终目标超过 255 字符的场景：源文件名 253 字符，目标已存在同名文件，
            // KEEP_BOTH 追加 " copy" 后变成 258 字符（> 255 字符上限），真实复现源合法但最终目标名超限！
            val maxValidName = "a".repeat(249) + ".zip" // 253 chars
            val nearLimitSource = sourceDir.resolve(maxValidName)
            Files.move(sourceFile, nearLimitSource)
            Files.writeString(targetDir.resolve(maxValidName), "existing")
            val nearLimitEntry = provider.list(sourceDir.toString()).getOrThrow()
                .first { it.name == maxValidName && it.id != realEntry.id }

            var transferredBytes = 0L
            val result = provider.copyWithProgress(
                entries = listOf(nearLimitEntry),
                targetDirectoryLocation = targetDir.toString(),
                conflictStrategy = TransferConflictStrategy.KEEP_BOTH,
                progressSink = FileTransferProgressSink { count -> transferredBytes += count },
            )
            assertTrue(result.isFailure)
            assertEquals(0L, transferredBytes)
            val failureMessage = assertNotNull(result.exceptionOrNull()?.message)
            assertFalse(failureMessage.contains(STAGING_DIRECTORY_PREFIX))
            assertCleanTargetDirectory(targetDir.resolve(maxValidName))
        } finally {
            LocalPathDeletion.delete(root)
        }
    }

    /** 验证暂存提交阶段的 FileSystemException 会剥离内部 `.onyx-transfer-*` 路径并保留目标路径与原因。 */
    @Test
    fun stagingFailureUnwrapsInternalTemporaryPathToTarget() {
        val root = Files.createTempDirectory("onyx-unwrap-staging-")
        try {
            val staging = Files.createTempDirectory(root, STAGING_DIRECTORY_PREFIX)
            val content = staging.resolve("content")
            val target = root.resolve("final-archive.zip")
            val rawFailure = FileSystemException(
                content.toString(),
                target.toString(),
                "文件名、目录名或卷标语法不正确。",
            )
            val unwrapped = LocalTransferStagingDiagnostics.unwrapStagingFailure(rawFailure, staging, target)
            assertTrue(unwrapped is FileSystemException)
            assertEquals(target.toString(), unwrapped.file)
            assertEquals("文件名、目录名或卷标语法不正确。", unwrapped.reason)
            assertFalse(unwrapped.message.orEmpty().contains(STAGING_DIRECTORY_PREFIX))
        } finally {
            LocalPathDeletion.delete(root)
        }
    }

    /**
     * 创建测试独占的真实文件并在断言后清理，不使用用户配置或真实资源。
     *
     * @param block 源路径、目标路径与生产 Provider 上的验证流程。
     */
    private suspend fun withFixture(block: suspend (Path, Path, JvmLocalFileProvider) -> Unit) {
        val root = Files.createTempDirectory("onyx-transfer-safety-")
        try {
            val source = Files.createDirectory(root.resolve("source")).resolve("note.txt")
            val target = Files.createDirectory(root.resolve("target")).resolve("note.txt")
            Files.writeString(source, "source-new")
            Files.writeString(target, "destination-old")
            block(source, target, JvmLocalFileProvider())
        } finally {
            LocalPathDeletion.delete(root)
        }
    }

    /**
     * 核对目标父目录只有最终文件，没有被清理遗漏的部分副本。
     *
     * @param target 最终目标路径。
     */
    private fun assertCleanTargetDirectory(target: Path) {
        Files.newDirectoryStream(target.parent).use { entries ->
            assertEquals(listOf(target.fileName.toString()), entries.map { it.fileName.toString() })
        }
    }
}
