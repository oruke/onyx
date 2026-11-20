package com.oruke.onyx.vfs.archive

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验证压缩包密码透传、已记住密码查询及非加密损坏包不被误判为加密（`OPS-005`）。
 */
class ArchiveHeaderEncryptionAndSmartExtractTest {
    /** 验证普通损坏压缩包不会触发头加密误报，避免界面陷入无限弹密码框死循环。 */
    @Test
    fun corruptedArchiveIsNotMisclassifiedAsEncrypted() = runBlocking {
        val broken = Files.createTempFile("onyx-corrupt-", ".7z")
        try {
            Files.write(
                broken,
                byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0x00, 0x01, 0xFF.toByte()),
            )
            assertFalse(ArchiveService().isEncrypted(broken.toString()))
        } finally {
            Files.deleteIfExists(broken)
        }
    }

    /** 验证 `extractSmart` 与 `list` 接收显式 `password` 参数并正常解压多根归档，`rememberedPasswordOrNull` 正确反映缓存。 */
    @Test
    fun extractSmartAndListAcceptExplicitPasswordParameter() = runBlocking {
        val archive = Files.createTempFile("onyx-smart-", ".zip")
        val target = Files.createTempDirectory("onyx-smart-out-")
        try {
            val bytes = ByteArrayOutputStream().use { out ->
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry("a.txt"))
                    zip.write("alpha".toByteArray())
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("b.txt"))
                    zip.write("beta".toByteArray())
                    zip.closeEntry()
                }
                out.toByteArray()
            }
            Files.write(archive, bytes)

            val service = ArchiveService()
            assertNull(service.rememberedPasswordOrNull(archive.toString()))
            assertFalse(service.isEncrypted(archive.toString()))

            val listed = service.list(archive.toString(), innerPath = "", password = null).getOrThrow()
            assertEquals(2, listed.size)

            service.extractSmart(
                archivePath = archive.toString(),
                targetDirectory = target.toString(),
                password = null,
            ).getOrThrow()
            val expectedSubDir = target.resolve(archive.fileName.toString().removeSuffix(".zip"))
            assertTrue(Files.exists(expectedSubDir.resolve("a.txt")))
            assertTrue(Files.exists(expectedSubDir.resolve("b.txt")))
        } finally {
            Files.deleteIfExists(archive)
            target.toFile().deleteRecursively()
        }
    }
}
