package com.oruke.onyx.vfs.local

import com.oruke.onyx.vfs.api.TransferConflictStrategy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 本地路径校验与传输操作测试。
 */
class LocalPathOperationsTest {
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
            assertFailsWith<IllegalArgumentException> {
                LocalPathOperations.resolveCreateDirectoryTarget(root, "../outside")
            }
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
}
