package com.oruke.onyx.vfs.local

import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.sun.nio.file.ExtendedOpenOption
import java.nio.channels.FileChannel
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
