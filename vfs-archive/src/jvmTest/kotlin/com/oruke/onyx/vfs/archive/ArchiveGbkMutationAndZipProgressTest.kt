package com.oruke.onyx.vfs.archive

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.RoutableVfsContentService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsContentSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Batch 4 OPS-013 归档回归测试：
 * 1. GBK 编码中文条目 ZIP 的重命名/新建目录/删除回退能力；
 * 2. ZIP 压缩预扫描总量与分块确定性进度上报。
 */
class ArchiveGbkMutationAndZipProgressTest {
    /**
     * 校验非 UTF-8（GBK/CP936）编码的中文条目 ZIP 在执行内部修改时能自动回退 GBK 解码并无损重写。
     */
    @Test
    fun mutatesGbkEncodedZipWithoutMalformedInputError() {
        val tempDir = Files.createTempDirectory("onyx-gbk-zip-")
        try {
            val zipPath = tempDir.resolve("legacy-gbk.zip")
            val gbk = Charset.forName("GBK")
            ZipOutputStream(Files.newOutputStream(zipPath), gbk).use { zos ->
                zos.putNextEntry(ZipEntry("中文目录/"))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("中文目录/报告.txt"))
                zos.write("第一份内容".encodeToByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("待删除.txt"))
                zos.write("remove-me".encodeToByteArray())
                zos.closeEntry()
            }

            val mutationService = ZipArchiveMutationService()
            mutationService.createDirectory(zipPath.toString(), "中文目录/新建子目录")
            mutationService.renameEntry(zipPath.toString(), "中文目录/报告.txt", "中文目录/最终报告.txt")
            mutationService.deleteEntries(zipPath.toString(), listOf("待删除.txt"))

            ZipFile(zipPath.toFile(), StandardCharsets.UTF_8).use { zipFile ->
                val names = zipFile.entries().asSequence().map { it.name }.toSet()
                assertTrue("中文目录/" in names, "原中文目录应保留: $names")
                assertTrue("中文目录/新建子目录/" in names, "新建中文子目录应写入: $names")
                assertTrue("中文目录/最终报告.txt" in names, "重命名后的中文文件应存在: $names")
                assertTrue("中文目录/报告.txt" !in names, "旧文件名应被替换: $names")
                assertTrue("待删除.txt" !in names, "已删除条目不应残留: $names")
                val entry = requireNotNull(zipFile.getEntry("中文目录/最终报告.txt"))
                val content = zipFile.getInputStream(entry).readBytes().decodeToString()
                assertEquals("第一份内容", content)
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    /**
     * 校验 ZIP 压缩服务会在写入前预扫描总文件数与总字节数，并在大文件写入期间分段上报递增字节进度。
     */
    @Test
    fun reportsDeterministicTotalsAndChunkedByteProgressForLargeFile() = runBlocking {
        val largeBytes = ByteArray(700_000) { index -> ((index * 31) and 0xFF).toByte() }
        val smallBytes = "tail-file".encodeToByteArray()
        val largeEntry = testFile("mem-src://dir/large.bin", "large.bin", largeBytes.size.toLong())
        val smallEntry = testFile("mem-src://dir/tail.txt", "tail.txt", smallBytes.size.toLong())

        val sourceService = MemoryContentService("mem-src://").apply {
            seed(largeEntry.location, largeBytes)
            seed(smallEntry.location, smallBytes)
        }
        val targetService = MemoryContentService("mem-dst://")
        val service = ZipArchiveCreationService(
            fileRepository = EmptyFileRepository,
            contentServices = listOf(sourceService, targetService),
        )

        val progressEvents = mutableListOf<ZipArchiveCreationProgress>()
        val result = service.create(
            request = ZipArchiveCreationRequest(
                entries = listOf(largeEntry, smallEntry),
                targetDirectoryLocation = "mem-dst://out",
                archiveName = "bundle.zip",
            ),
            progressSink = { event -> progressEvents += event },
        )

        val created = result.getOrThrow()
        assertEquals("bundle.zip", created.archiveName)
        assertTrue(progressEvents.size >= 3, "700KB 文件应产生多次分块进度上报，实际仅 ${progressEvents.size} 次")
        val expectedTotalBytes = largeBytes.size.toLong() + smallBytes.size.toLong()
        assertTrue(progressEvents.all { it.totalFileCount == 2 }, "总文件数应始终为 2: $progressEvents")
        assertTrue(progressEvents.all { it.totalBytes == expectedTotalBytes }, "总字节数应始终为 $expectedTotalBytes: $progressEvents")

        val byteSeries = progressEvents.map { it.writtenBytes }
        assertTrue(byteSeries.zipWithNext().all { (a, b) -> b >= a }, "已写入字节应单调非递减: $byteSeries")
        assertTrue(
            progressEvents.any { it.writtenBytes in 1L until largeBytes.size.toLong() },
            "应包含大文件写入途中的中间字节进度: $byteSeries",
        )
        assertEquals(expectedTotalBytes, progressEvents.last().writtenBytes)
        assertEquals(2, progressEvents.last().writtenFileCount)

        val writtenZip = requireNotNull(targetService.written["mem-dst://out/bundle.zip"])
        val extracted = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(ByteArrayInputStream(writtenZip)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                extracted[entry.name] = zis.readBytes()
                zis.closeEntry()
            }
        }
        assertContentEquals(largeBytes, extracted["large.bin"])
        assertContentEquals(smallBytes, extracted["tail.txt"])
    }

    private fun testFile(location: String, name: String, sizeBytes: Long): VFile = VFile(
        id = location,
        name = name,
        location = location,
        parentLocation = location.substringBeforeLast('/'),
        kind = VFileKind.FILE,
        sizeBytes = sizeBytes,
        modifiedAtEpochMillis = 1_700_000_000_000L,
        hidden = false,
        capabilities = setOf(VFileCapability.READ_CONTENT),
    )

    private object EmptyFileRepository : FileRepository {
        override suspend fun list(location: String): Result<List<VFile>> = Result.success(emptyList())
        override fun defaultLocation(): String = "mem-src://dir"
    }

    private class MemoryContentService(private val prefix: String) : RoutableVfsContentService {
        private val files = mutableMapOf<String, ByteArray>()
        val written = mutableMapOf<String, ByteArray>()

        fun seed(location: String, bytes: ByteArray) {
            files[location] = bytes
        }

        override fun supports(location: String): Boolean = location.startsWith(prefix)

        override suspend fun readFile(entry: VFile): Result<VfsContentSource> {
            val bytes = files[entry.location]
                ?: return Result.failure(IllegalArgumentException("Not found: ${entry.location}"))
            val chunkSize = 64 * 1024
            return Result.success(
                VfsContentSource(
                    name = entry.name,
                    sizeBytes = bytes.size.toLong(),
                    chunks = flow {
                        var offset = 0
                        while (offset < bytes.size) {
                            val end = (offset + chunkSize).coerceAtMost(bytes.size)
                            emit(bytes.copyOfRange(offset, end))
                            offset = end
                        }
                    },
                ),
            )
        }

        override suspend fun writeFile(
            parentLocation: String,
            name: String,
            chunks: Flow<ByteArray>,
            conflictStrategy: TransferConflictStrategy,
        ): Result<VFile?> {
            val out = ByteArrayOutputStream()
            chunks.collect { chunk -> out.write(chunk) }
            val fullLocation = "${parentLocation.trimEnd('/')}/$name"
            val bytes = out.toByteArray()
            written[fullLocation] = bytes
            return Result.success(
                VFile(
                    id = fullLocation,
                    name = name,
                    location = fullLocation,
                    parentLocation = parentLocation,
                    kind = VFileKind.FILE,
                    sizeBytes = bytes.size.toLong(),
                    modifiedAtEpochMillis = null,
                    hidden = false,
                    capabilities = setOf(VFileCapability.READ_CONTENT),
                ),
            )
        }
    }
}
