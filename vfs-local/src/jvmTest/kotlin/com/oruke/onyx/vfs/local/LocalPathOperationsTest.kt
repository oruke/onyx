package com.oruke.onyx.vfs.local

import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 本地路径校验与传输操作测试。
 */
class LocalPathOperationsTest {
    /**
     * 本地文件复制应按实际写入字节持续回调，累计值与源文件大小一致。
     */
    @Test
    fun copyReportsTransferredBytes() {
        withTempDirectory { root ->
            val sourceDirectory = Files.createDirectory(root.resolve("source"))
            val targetDirectory = Files.createDirectory(root.resolve("target"))
            val content = ByteArray(TEST_CONTENT_SIZE) { index -> (index % BYTE_VALUE_RANGE).toByte() }
            val source = Files.write(sourceDirectory.resolve("payload.bin"), content)
            var reportedBytes = 0L

            LocalPathTransferOperations.copyPathToDirectory(
                source = source,
                targetDirectory = targetDirectory,
                conflictStrategy = TransferConflictStrategy.KEEP_BOTH,
                progressSink = { byteCount -> reportedBytes += byteCount },
            )

            assertEquals(content.size.toLong(), reportedBytes)
            assertContentEquals(content, Files.readAllBytes(targetDirectory.resolve("payload.bin")))
        }
    }

    /**
     * KEEP_BOTH 应保留原文件并生成带副本后缀的新文件。
     */
    @Test
    fun copyWithKeepBothCreatesAvailableName() {
        withTempDirectory { root ->
            val sourceDirectory = Files.createDirectory(root.resolve("source"))
            val targetDirectory = Files.createDirectory(root.resolve("target"))
            val source = Files.writeString(sourceDirectory.resolve("note.txt"), "new")
            Files.writeString(targetDirectory.resolve("note.txt"), "old")

            LocalPathTransferOperations.copyPathToDirectory(
                source = source,
                targetDirectory = targetDirectory,
                conflictStrategy = TransferConflictStrategy.KEEP_BOTH,
            )

            assertEquals("old", targetDirectory.resolve("note.txt").readText())
            assertEquals("new", targetDirectory.resolve("note copy.txt").readText())
        }
    }

    /**
     * 目录创建路径不得通过父级片段逃逸当前目录。
     */
    @Test
    fun createDirectoryRejectsParentTraversal() {
        withTempDirectory { root ->
            val failure = assertFailsWith<LocalizedException> {
                LocalPathOperations.resolveCreateDirectoryTarget(root, "../outside")
            }
            assertEquals(MessageKey.MSG_DIRECTORY_PATH_INVALID_SEGMENTS, failure.userMessage.key)
        }
    }

    /**
     * SKIP 移动应同时保留源文件和已有目标文件。
     */
    @Test
    fun moveWithSkipLeavesBothFilesUntouched() {
        withTempDirectory { root ->
            val sourceDirectory = Files.createDirectory(root.resolve("source"))
            val targetDirectory = Files.createDirectory(root.resolve("target"))
            val source = Files.writeString(sourceDirectory.resolve("note.txt"), "source")
            val target = Files.writeString(targetDirectory.resolve("note.txt"), "target")

            LocalPathTransferOperations.movePathToDirectory(
                source = source,
                targetDirectory = targetDirectory,
                conflictStrategy = TransferConflictStrategy.SKIP,
            )

            assertTrue(Files.exists(source))
            assertEquals("target", target.readText())
        }
    }

    /**
     * 仅变更大小写的重命名在 Windows/大小写不敏感文件系统上必须真实修改磁盘文件名并返回新大小写。
     */
    @Test
    fun renameAppliesCaseOnlyChangeAndRejectsDistinctCollision() {
        withTempDirectory { root ->
            runBlocking {
                val provider = JvmLocalFileProvider()
                val file = Files.writeString(root.resolve("foo.txt"), "case-payload")
                Files.writeString(root.resolve("bar.txt"), "other-payload")
                val entry = provider.list(root.toString()).getOrThrow().first { it.name == "foo.txt" }

                val renamed = provider.rename(entry, "FOO.TXT").getOrThrow()
                assertEquals("FOO.TXT", renamed.name)
                assertTrue(renamed.location.endsWith("FOO.TXT"))
                val actualOnDisk = Path.of(renamed.location).toRealPath(LinkOption.NOFOLLOW_LINKS).fileName.toString()
                assertEquals("FOO.TXT", actualOnDisk)
                assertEquals("case-payload", Path.of(renamed.location).readText())

                // 同名同大小写保持幂等无报错。
                val unchanged = provider.rename(renamed, "FOO.TXT").getOrThrow()
                assertEquals("FOO.TXT", unchanged.name)

                // 碰撞另一不同文件仍必须返回 AlreadyExists。
                val collision = provider.rename(unchanged, "bar.txt").exceptionOrNull()
                assertTrue(collision is VfsProviderException && collision.error is VfsProviderError.AlreadyExists)
                assertTrue(Files.exists(file.resolveSibling("FOO.TXT")))

                // 非空目录仅大小写重命名必须同步保留全部子文件。
                val subDir = Files.createDirectory(root.resolve("mediaDir"))
                Files.writeString(subDir.resolve("inside.txt"), "nested-data")
                val dirEntry = provider.list(root.toString()).getOrThrow().first { it.name == "mediaDir" }
                val renamedDir = provider.rename(dirEntry, "MEDIADIR").getOrThrow()
                assertEquals("MEDIADIR", renamedDir.name)
                assertEquals(
                    "MEDIADIR",
                    Path.of(renamedDir.location).toRealPath(LinkOption.NOFOLLOW_LINKS).fileName.toString(),
                )
                assertEquals("nested-data", Path.of(renamedDir.location).resolve("inside.txt").readText())

                // 拒绝非法相对段名称 `.` / `..` 及 Windows 盘符前缀/NTFS ADS。
                assertFailsWith<LocalizedException> { LocalPathOperations.validateTargetName(".") }
                assertFailsWith<LocalizedException> { LocalPathOperations.validateTargetName("..") }
                assertFailsWith<LocalizedException> { LocalPathOperations.validateTargetName("C:evil.txt") }
                assertFailsWith<LocalizedException> { LocalPathOperations.validateTargetName("file.txt:ads") }
                assertFailsWith<LocalizedException> { LocalPathOperations.resolveCreateTarget(root, "C:evil.txt") }
                assertFailsWith<LocalizedException> { LocalPathOperations.renamePath(Path.of(renamed.location), "C:evil.txt") }
            }
        }
    }

    /**
     * 在临时目录中执行断言并可靠清理目录树。
     *
     * @param block 测试逻辑。
     */
    private fun withTempDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("onyx-local-path-test")
        try {
            block(directory)
        } finally {
            LocalPathTransferOperations.deletePathRecursively(directory)
        }
    }

    private companion object {
        /** 测试文件跨越多个复制缓冲区。 */
        const val TEST_CONTENT_SIZE = 2 * 1024 * 1024 + 137
        /** 生成稳定二进制测试内容的取值范围。 */
        const val BYTE_VALUE_RANGE = 251
    }
}
