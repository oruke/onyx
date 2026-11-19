package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.archive.ArchiveService
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 文件哈希服务归档字节上限保护回归测试。
 */
class JvmFileHashServiceTest {
    /**
     * 归档条目元数据 `sizeBytes` 为空且实际内容超出 `maxBytes` 时，必须返回 `TooLarge` 而不整块解压 OOM。
     */
    @Test
    fun returnsTooLargeForArchiveEntryWithNullMetadataSizeExceedingMaxBytes() = runBlocking {
        val archive = Files.createTempFile("onyx-hash-limit-", ".zip")
        try {
            val payload = ByteArray(2048) { index -> (index % 127).toByte() }
            ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("big.bin"))
                zip.write(payload)
                zip.closeEntry()
            }
            val location = ArchiveService.archiveLocation(archive.toString(), "big.bin")
            val entry = VFile(
                id = location,
                name = "big.bin",
                location = location,
                parentLocation = ArchiveService.archiveLocation(archive.toString()),
                kind = VFileKind.FILE,
                sizeBytes = null, // 模拟压缩包元数据缺失大小
                modifiedAtEpochMillis = null,
                hidden = false,
                capabilities = setOf(VFileCapability.READ_CONTENT),
            )
            val hashService = JvmFileHashService(ArchiveService())

            val tooLarge = hashService.readHash(FileHashRequest(entry = entry, maxBytes = 256L))
            assertEquals(FileHashResult.TooLarge, tooLarge)

            val computed = hashService.readHash(FileHashRequest(entry = entry, maxBytes = 4096L))
            val result = assertIs<FileHashResult.Hash>(computed)
            assertEquals(64, result.value.length)
        } finally {
            Files.deleteIfExists(archive)
        }
    }
}
