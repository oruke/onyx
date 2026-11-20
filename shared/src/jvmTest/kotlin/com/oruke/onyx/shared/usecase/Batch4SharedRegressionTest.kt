package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.RoutableVfsContentService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsContentSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Batch 4 shared 模块回归测试（SEARCH-008 内容搜索二进制 NUL 嗅探跳过）。
 */
class Batch4SharedRegressionTest {
    /**
     * 校验内容搜索在首 8KB 嗅探到 NUL（0x00）字节时会立即跳过二进制文件，
     * 即便后续内容包含待检索 ASCII 关键词也不误报命中；而纯文本文件正常命中。
     */
    @Test
    fun skipsBinaryFilesContainingNulByteWhileMatchingPlainUtf8Text() = runBlocking {
        val contentService = BytesContentService("mem://")
        val binaryPayload = byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x00, 0x01, 0x02) +
            "secret-keyword-inside-binary".encodeToByteArray()
        val textPayload = "Header line\nThis file contains secret-keyword-inside-binary in UTF-8.\n".encodeToByteArray()

        val binaryEntry = testFile("mem://bin/app.bin", binaryPayload.size.toLong())
        val textEntry = testFile("mem://docs/readme.txt", textPayload.size.toLong())
        contentService.seed(binaryEntry.location, binaryPayload)
        contentService.seed(textEntry.location, textPayload)

        val service = JvmVfsFileContentSearchService(listOf(contentService))
        val binaryMatch = service.contains(binaryEntry, query = "secret-keyword", maxBytes = 64 * 1024).getOrThrow()
        val textMatch = service.contains(textEntry, query = "secret-keyword", maxBytes = 64 * 1024).getOrThrow()

        assertFalse(binaryMatch, "含 NUL 字节的二进制文件应被嗅探跳过，不应参与文本全文匹配")
        assertTrue(textMatch, "正常 UTF-8 文本文件应准确命中关键词")
    }

    private fun testFile(location: String, sizeBytes: Long): VFile = VFile(
        id = location,
        name = location.substringAfterLast('/'),
        location = location,
        parentLocation = location.substringBeforeLast('/'),
        kind = VFileKind.FILE,
        sizeBytes = sizeBytes,
        modifiedAtEpochMillis = null,
        hidden = false,
        capabilities = setOf(VFileCapability.READ_CONTENT),
    )

    private class BytesContentService(private val prefix: String) : RoutableVfsContentService {
        private val payloads = mutableMapOf<String, ByteArray>()

        fun seed(location: String, bytes: ByteArray) {
            payloads[location] = bytes
        }

        override fun supports(location: String): Boolean = location.startsWith(prefix)

        override suspend fun readFile(entry: VFile): Result<VfsContentSource> {
            val bytes = payloads[entry.location]
                ?: return Result.failure(IllegalArgumentException("Missing: ${entry.location}"))
            return Result.success(
                VfsContentSource(
                    name = entry.name,
                    sizeBytes = bytes.size.toLong(),
                    chunks = flowOf(bytes),
                ),
            )
        }

        override suspend fun writeFile(
            parentLocation: String,
            name: String,
            chunks: Flow<ByteArray>,
            conflictStrategy: TransferConflictStrategy,
        ): Result<VFile?> = Result.failure(UnsupportedOperationException())
    }
}
