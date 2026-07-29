package com.oruke.onyx.vfs.archive

import com.oruke.onyx.vfs.api.TransferConflictStrategy
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 压缩包服务基础行为测试。
 */
class ArchiveServiceTest {
    /**
     * 7-Zip 解压应把原生完成字节回调传递给任务进度接收器。
     *
     * @return 无返回值。
     */
    @Test
    fun reportsExtractionByteProgress() = runBlocking {
        val content = ByteArray(EXTRACTION_TEST_SIZE) { index -> (index % TEST_BYTE_RANGE).toByte() }
        val archive = createZipArchive(mapOf("payload.bin" to content))
        val targetDirectory = Files.createTempDirectory("onyx-archive-progress")
        val events = mutableListOf<Pair<Long, Long>>()
        try {
            val result = ArchiveService().extract(
                archivePath = archive.toString(),
                targetDirectory = targetDirectory.toString(),
                progressSink = { completedBytes, totalBytes ->
                    events += completedBytes to totalBytes
                },
            )

            assertTrue(result.isSuccess)
            assertTrue(events.isNotEmpty())
            assertTrue(events.last().first > 0L)
            assertEquals(content.size.toLong(), events.last().second)
            assertTrue(Files.exists(targetDirectory.resolve("payload.bin")))
        } finally {
            archive.toFile().delete()
            targetDirectory.toFile().deleteRecursively()
        }
    }

    /**
     * 校验 zstd tar 复合扩展名能进入压缩包 provider。
     *
     * @return 无返回值。
     */
    @Test
    fun recognizesTarZstdArchives() {
        assertTrue(ArchiveService.isArchive("backup.tar.zst"))
        assertTrue(ArchiveService.isArchive("backup.TZST"))
    }

    /**
     * 校验 `archive://` 解析能识别 `.tar.zst` 路径边界。
     *
     * @return 无返回值。
     */
    @Test
    fun parsesTarZstdArchiveLocations() {
        val parsed = ArchiveService.parseArchiveLocation("archive://C:/tmp/backup.tar.zst!/src/main")

        assertEquals("C:/tmp/backup.tar.zst", parsed?.first)
        assertEquals("src/main", parsed?.second)
    }

    /**
     * 校验远程压缩包根位置能返回原协议父目录和稳定标题。
     *
     * @return 无返回值。
     */
    @Test
    fun resolvesRemoteArchiveParentLocationsAndTitles() {
        val locations = mapOf(
            "smb://server/share/books/sample.zip" to "smb://server/share/books",
            "webdav://storage/library/sample.zip" to "webdav://storage/library",
            "s3://bucket/library/sample.zip" to "s3://bucket/library",
        )

        locations.forEach { (archivePath, expectedParent) ->
            val archiveLocation = ArchiveService.archiveLocation(archivePath)
            assertEquals(expectedParent, ArchiveService.archiveParentLocation(archiveLocation))
            assertEquals("sample.zip", ArchiveService.archiveLocationTitle(archiveLocation))
        }
    }

    /**
     * 校验 ZIP 系列压缩包支持创建目录、追加文件、重命名和删除内部条目。
     *
     * @return 无返回值。
     */
    @Test
    fun mutatesZipArchiveEntries() = runBlocking {
        val archive = createZipArchive(
            entries = mapOf(
                "old.txt" to "old".encodeToByteArray(),
            )
        )
        val service = ArchiveService()

        service.createDirectoryInArchive(archive.toString(), "folder").getOrThrow()
        val appended = service.appendFileToArchive(
            archivePath = archive.toString(),
            innerPath = "folder/new.txt",
            bytes = "new".encodeToByteArray(),
        ).getOrThrow()
        service.renameEntryInArchive(
            archivePath = archive.toString(),
            sourceInnerPath = "old.txt",
            targetInnerPath = "renamed.txt",
        ).getOrThrow()
        service.deleteEntriesInArchive(
            archivePath = archive.toString(),
            innerPaths = listOf("folder/new.txt"),
        ).getOrThrow()

        val entries = archive.zipEntries()
        assertEquals("folder/new.txt", appended)
        assertTrue("folder/" in entries)
        assertTrue("renamed.txt" in entries)
        assertTrue("old.txt" !in entries)
        assertTrue("folder/new.txt" !in entries)
    }

    /**
     * 校验 ZIP 保存失败时会回滚到原始压缩包。
     *
     * @return 无返回值。
     */
    @Test
    fun rollsBackZipArchiveWhenMutationFails() = runBlocking {
        val archive = createZipArchive(
            entries = mapOf(
                "old.txt" to "old".encodeToByteArray(),
                "target.txt" to "target".encodeToByteArray(),
            )
        )
        val service = ArchiveService()

        val result = service.renameEntryInArchive(
            archivePath = archive.toString(),
            sourceInnerPath = "old.txt",
            targetInnerPath = "target.txt",
        )

        assertTrue(result.isFailure)
        assertEquals(setOf("old.txt", "target.txt"), archive.zipEntries())
    }

    /**
     * 校验追加文件在 SKIP 冲突策略下不改写已有条目。
     *
     * @return 无返回值。
     */
    @Test
    fun appendFileSkipsExistingEntry() = runBlocking {
        val archive = createZipArchive(
            entries = mapOf(
                "same.txt" to "original".encodeToByteArray(),
            )
        )
        val service = ArchiveService()

        val written = service.appendFileToArchive(
            archivePath = archive.toString(),
            innerPath = "same.txt",
            bytes = "changed".encodeToByteArray(),
            conflictStrategy = TransferConflictStrategy.SKIP,
        ).getOrThrow()

        assertNull(written)
        assertEquals("original", archive.zipText("same.txt"))
    }

    /**
     * 校验 `.tar.zst` 运行时检测会给出明确错误。
     *
     * @return 无返回值。
     */
    @Test
    fun reportsUnavailableTarRuntime() = runBlocking {
        val service = ArchiveService(tarCommand = "__onyx_missing_tar__")

        val failure = service.checkTarZstdRuntime().exceptionOrNull()

        assertTrue(failure is ArchiveRuntimeException)
        assertTrue(failure.message?.contains("系统 tar 不可用") == true)
    }

    private companion object {
        /** 让原生解压产生多次进度采样的测试内容大小。 */
        const val EXTRACTION_TEST_SIZE = 2 * 1024 * 1024
        /** 生成稳定二进制内容的取值范围。 */
        const val TEST_BYTE_RANGE = 251
    }
}

/**
 * 创建临时 ZIP 压缩包。
 *
 * @param entries 条目路径到内容的映射。
 * @return 压缩包路径。
 */
private fun createZipArchive(entries: Map<String, ByteArray>): Path {
    val archive = Files.createTempFile("onyx-archive-test", ".zip")
    ZipOutputStream(Files.newOutputStream(archive)).use { output ->
        entries.forEach { (name, bytes) ->
            output.putNextEntry(ZipEntry(name))
            output.write(bytes)
            output.closeEntry()
        }
    }
    return archive
}

/**
 * 读取 ZIP 压缩包条目集合。
 *
 * @return 条目名集合。
 */
private fun Path.zipEntries(): Set<String> {
    return ZipFile(toFile()).use { zip ->
        zip.entries().asSequence().map { entry -> entry.name }.toSet()
    }
}

/**
 * 读取 ZIP 压缩包内文本。
 *
 * @param entryName 条目名。
 * @return UTF-8 文本内容。
 */
private fun Path.zipText(entryName: String): String {
    return ZipFile(toFile()).use { zip ->
        zip.getInputStream(zip.getEntry(entryName)).use { input ->
            input.readBytes().decodeToString()
        }
    }
}
