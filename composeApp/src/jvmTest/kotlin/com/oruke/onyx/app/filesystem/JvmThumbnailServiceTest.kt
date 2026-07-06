package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.RoutableVfsContentService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsContentSource
import com.oruke.onyx.vfs.archive.ArchiveService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * JVM 缩略图服务测试。
 */
class JvmThumbnailServiceTest {
    /**
     * 校验远程内容服务中的图片可以生成缩略图。
     *
     * @return 无返回值。
     */
    @Test
    fun loadsThumbnailFromRoutableContentService() = runBlocking {
        val contentService = FakeThumbnailContentService("s3://")
        contentService.seed("s3://bucket/images/pixel.png", testPngBytes())
        val thumbnailService = JvmThumbnailService(listOf(contentService))

        val thumbnail = thumbnailService.loadThumbnail(
            location = "s3://bucket/images/pixel.png",
            maxDimension = 16,
        )

        assertNotNull(thumbnail)
        assertEquals(2, thumbnail.width)
        assertEquals(2, thumbnail.height)
    }

    /**
     * 校验压缩包内部路径使用反斜杠时，图片查看器仍能按归一化后的 archive:// 路径加载位图。
     *
     * @return 无返回值。
     */
    @Test
    fun loadsThumbnailFromArchiveEntryWithWindowsSeparator() = runBlocking {
        val archivePath = Files.createTempFile("onyx-thumbnail-", ".epub")
        try {
            writeArchiveEntry(
                archivePath = archivePath,
                entryName = "Images\\pixel.png",
                bytes = testPngBytes(),
            )
            val archiveService = ArchiveService()
            val archiveEntryLocation = ArchiveService.archiveLocation(archivePath.toString(), "Images/pixel.png")
            val extractedBytes = archiveService.extractToBytes(
                archivePath = archivePath.toString(),
                innerPath = "Images/pixel.png",
            ).getOrThrow()
            assertNotNull(extractedBytes)
            val thumbnailService = JvmThumbnailService(archiveService = archiveService)

            val thumbnail = thumbnailService.loadThumbnail(
                location = archiveEntryLocation,
                maxDimension = 16,
            )

            assertNotNull(thumbnail)
            assertEquals(2, thumbnail.width)
            assertEquals(2, thumbnail.height)
        } finally {
            Files.deleteIfExists(archivePath)
        }
    }

    private companion object {
        /**
         * 向测试压缩包写入单个图片条目。
         *
         * @param archivePath 压缩包路径。
         * @param entryName 压缩包内部条目名称。
         * @param bytes 条目内容字节。
         * @return 无返回值。
         */
        fun writeArchiveEntry(
            archivePath: Path,
            entryName: String,
            bytes: ByteArray,
        ) {
            ZipOutputStream(Files.newOutputStream(archivePath)).use { zip ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            }
        }

        /**
         * 生成 2x2 PNG 测试图片。
         *
         * @return PNG 字节数组。
         */
        fun testPngBytes(): ByteArray {
            val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0, 0, 0xFFFF0000.toInt())
            image.setRGB(1, 0, 0xFFFF0000.toInt())
            image.setRGB(0, 1, 0xFFFF0000.toInt())
            image.setRGB(1, 1, 0xFFFF0000.toInt())
            val output = ByteArrayOutputStream()
            ImageIO.write(image, "png", output)
            return output.toByteArray()
        }
    }
}

/**
 * 缩略图测试用内容服务。
 *
 * @property locationPrefix 支持的位置前缀。
 */
private class FakeThumbnailContentService(
    private val locationPrefix: String,
) : RoutableVfsContentService {
    private val contents = mutableMapOf<String, ByteArray>()

    /**
     * 写入测试图片内容。
     *
     * @param location 文件位置。
     * @param bytes 图片字节。
     */
    fun seed(
        location: String,
        bytes: ByteArray,
    ) {
        contents[location] = bytes
    }

    /**
     * 判断位置是否由当前测试服务处理。
     *
     * @param location 待判断位置。
     * @return 是否支持。
     */
    override fun supports(location: String): Boolean = location.startsWith(locationPrefix)

    /**
     * 读取测试图片内容。
     *
     * @param entry 待读取条目。
     * @return 内容源。
     */
    override suspend fun readFile(entry: VFile): Result<VfsContentSource> {
        val bytes = contents[entry.location]
            ?: return Result.failure(IllegalStateException("Missing fake image: ${entry.location}"))
        return Result.success(
            VfsContentSource(
                name = entry.name,
                sizeBytes = bytes.size.toLong(),
                chunks = flowOf(bytes),
            )
        )
    }

    /**
     * 测试不覆盖写入路径。
     *
     * @param parentLocation 父目录位置。
     * @param name 文件名。
     * @param chunks 内容块。
     * @param conflictStrategy 冲突策略。
     * @return 失败结果。
     */
    override suspend fun writeFile(
        parentLocation: String,
        name: String,
        chunks: Flow<ByteArray>,
        conflictStrategy: TransferConflictStrategy,
    ): Result<VFile?> = Result.failure(UnsupportedOperationException("write is not used"))
}
