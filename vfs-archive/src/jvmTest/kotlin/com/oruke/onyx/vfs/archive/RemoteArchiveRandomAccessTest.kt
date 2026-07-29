package com.oruke.onyx.vfs.archive

import com.oruke.onyx.vfs.api.RoutableVfsRandomAccessService
import com.oruke.onyx.vfs.api.VfsRandomAccessHandle
import com.oruke.onyx.vfs.api.VfsRandomAccessMode
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证 7-Zip 能通过统一随机访问接口直接读取非本地归档。
 */
class RemoteArchiveRandomAccessTest {
    /**
     * `smb://` 归档应在没有本地物理路径的情况下完成列目录和条目提取。
     */
    @Test
    fun opensRemoteZipThroughRandomAccessStream() = runBlocking {
        val zipBytes = createZip("folder/note.txt", "direct remote archive".encodeToByteArray())
        val randomAccessService = CountingByteArrayRandomAccessService(zipBytes)
        val archiveService = ArchiveService(randomAccessService = randomAccessService)
        val archiveLocation = "smb://server/share/books/sample.zip"

        val entries = archiveService.list(archiveLocation).getOrThrow()
        val extracted = archiveService.extractToBytes(
            archivePath = archiveLocation,
            innerPath = "folder/note.txt",
        ).getOrThrow()

        assertEquals(listOf("folder"), entries.map { entry -> entry.name })
        assertContentEquals("direct remote archive".encodeToByteArray(), extracted)
        assertTrue(randomAccessService.readOperations > 0)
        assertTrue(randomAccessService.readBytes > 0)
    }

    /**
     * 远程归档封面提取应复用随机访问源，并稳定选择路径排序最靠前的图片。
     */
    @Test
    fun extractsRemoteArchiveThumbnailThroughRandomAccessStream() = runBlocking {
        val expectedImage = "first-image".encodeToByteArray()
        val zipBytes = createZip(
            "images/z-last.png" to "last-image".encodeToByteArray(),
            "images/a-first.png" to expectedImage,
            "readme.txt" to "not-an-image".encodeToByteArray(),
        )
        val randomAccessService = CountingByteArrayRandomAccessService(zipBytes)
        val archiveService = ArchiveService(randomAccessService = randomAccessService)

        val extracted = archiveService.extractFirstImageToBytes(
            archivePath = "smb://server/share/books/gallery.zip",
            maxBytes = 1024,
        ).getOrThrow()

        assertContentEquals(expectedImage, extracted)
        assertTrue(randomAccessService.readOperations > 0)
    }

    /**
     * 创建包含单个条目的 ZIP 字节。
     *
     * @param path ZIP 内部路径。
     * @param content 条目内容。
     * @return 完整 ZIP 文件字节。
     */
    private fun createZip(path: String, content: ByteArray): ByteArray {
        return createZip(path to content)
    }

    /**
     * 创建包含多个条目的 ZIP 字节。
     *
     * @param entries ZIP 内部路径及其条目内容。
     * @return 完整 ZIP 文件字节。
     */
    private fun createZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}

/**
 * 记录读取次数与字节数的内存随机访问服务。
 *
 * @param content 远端文件内容。
 */
private class CountingByteArrayRandomAccessService(
    private val content: ByteArray,
) : RoutableVfsRandomAccessService {
    /** 发生的随机读请求数。 */
    var readOperations: Int = 0
        private set

    /** 随机读累计传输字节数。 */
    var readBytes: Long = 0
        private set

    override fun supports(location: String): Boolean = location.startsWith("smb://")

    override suspend fun openRandomAccess(
        location: String,
        mode: VfsRandomAccessMode,
    ): Result<VfsRandomAccessHandle> {
        return Result.success(
            object : VfsRandomAccessHandle {
                override val mode: VfsRandomAccessMode = VfsRandomAccessMode.READ

                override suspend fun length(): Long = content.size.toLong()

                override suspend fun readAt(
                    position: Long,
                    buffer: ByteArray,
                    bufferOffset: Int,
                    byteCount: Int,
                ): Int {
                    if (position >= content.size) return -1
                    val read = minOf(byteCount, content.size - position.toInt())
                    content.copyInto(buffer, bufferOffset, position.toInt(), position.toInt() + read)
                    readOperations += 1
                    readBytes += read
                    return read
                }

                override fun close() = Unit
            }
        )
    }
}
