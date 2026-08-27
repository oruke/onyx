package com.oruke.onyx.app.filesystem

import com.oruke.onyx.shared.usecase.BatchRenameNameTransformations
import com.oruke.onyx.shared.usecase.BatchRenameUseCase
import com.oruke.onyx.vfs.local.JvmLocalFileProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 基于真实本地 VFS 的批量重命名查找替换流程测试。 */
class BatchRenameLocalFlowTest {
    /**
     * 查找替换生成的映射应经由批量重命名用例实际落盘。
     */
    @Test
    fun appliesLiteralFindReplaceToLocalFiles() = runBlocking {
        val directory = Files.createTempDirectory("onyx-batch-rename-")
        try {
            Files.writeString(directory.resolve("report-old-one.txt"), "first")
            Files.writeString(directory.resolve("report-old-two.txt"), "second")
            val provider = JvmLocalFileProvider()
            val renameMap = provider.list(directory.toString()).getOrThrow()
                .map { entry ->
                    entry to BatchRenameNameTransformations.applyFindReplace(
                        name = entry.name,
                        findText = "old",
                        replaceText = "new",
                        useRegex = false,
                    ).getOrThrow()
                }
                .filter { (entry, newName) -> entry.name != newName }

            val progress = BatchRenameUseCase(provider).execute(
                BatchRenameUseCase.BatchRenameRequest(renameMap),
            ).toList()

            assertEquals(2, progress.last().processedCount)
            assertTrue(Files.exists(directory.resolve("report-new-one.txt")))
            assertTrue(Files.exists(directory.resolve("report-new-two.txt")))
            assertFalse(Files.exists(directory.resolve("report-old-one.txt")))
            assertFalse(Files.exists(directory.resolve("report-old-two.txt")))
        } finally {
            deleteTemporaryDirectory(directory)
        }
    }

    /**
     * 删除本次测试创建的临时目录及其直接测试文件。
     *
     * @param directory 测试专用临时目录。
     */
    private fun deleteTemporaryDirectory(directory: Path) {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                Files.deleteIfExists(path)
            }
        }
    }
}
