package com.oruke.onyx.app.filesystem

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import org.jetbrains.skia.Image as SkiaImage
import com.oruke.onyx.vfs.api.RoutableVfsContentService
import com.oruke.onyx.vfs.archive.ArchiveService

/**
 * 纯 Skia 原生缩略图加载器
 *
 * 完全绕过 Java2D/AWT，全链路使用 Skia 引擎：
 * 1. 解码：Skia 原生解码器（支持 JPEG/PNG/WebP/GIF/BMP/ICO/HEIF 等）
 * 2. 缩放：逐步半分缩小 (Progressive Half-Step) + 最终 Mitchell 三次插值
 *    - 每次缩小一半时使用双线性插值，保证所有原始像素参与混合
 *    - 最后一步使用 Mitchell-Netravali 三次重采样，确保锐度与平滑的最佳平衡
 * 3. 输出：直接转为 Compose ImageBitmap，无跨引擎转码损失
 *
 * @param contentServices 可路由内容服务列表，用于读取远程图片内容。
 * @param archiveService 压缩包服务，用于统一读取 archive:// 内部图片条目。
 */
internal class JvmThumbnailService(
    private val contentServices: List<RoutableVfsContentService> = emptyList(),
    private val archiveService: ArchiveService = ArchiveService(),
) : ThumbnailService {

    private companion object {
        /** 缩略图与查看器大图缓存分池阈值（超过 1024px 归入查看器大图池）。 */
        const val VIEWER_DIMENSION_THRESHOLD = 1024
        const val MAX_THUMBNAIL_CACHE_ENTRIES = 500
        const val MAX_THUMBNAIL_CACHE_BYTES = 96L * 1024 * 1024
        const val MAX_VIEWER_CACHE_ENTRIES = 6
        const val MAX_VIEWER_CACHE_BYTES = 128L * 1024 * 1024
        const val MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024
        const val MAX_ARCHIVE_IMAGE_BYTES = 20L * 1024 * 1024
    }

    /** 画廊与预览缩略图专用字节封顶 LRU 池。 */
    internal val thumbnailCache = ByteBoundedBitmapLruCache(
        maxEntries = MAX_THUMBNAIL_CACHE_ENTRIES,
        maxBytes = MAX_THUMBNAIL_CACHE_BYTES,
    )

    /** 独立图片查看器全尺寸大图专用字节封顶 LRU 池，避免挤占画廊缩略图或造成 OOM。 */
    internal val viewerCache = ByteBoundedBitmapLruCache(
        maxEntries = MAX_VIEWER_CACHE_ENTRIES,
        maxBytes = MAX_VIEWER_CACHE_BYTES,
    )

    private fun cachePoolFor(maxDimension: Int): ByteBoundedBitmapLruCache {
        return if (maxDimension > VIEWER_DIMENSION_THRESHOLD) viewerCache else thumbnailCache
    }

    private fun getCached(key: String, maxDimension: Int): ImageBitmap? {
        return cachePoolFor(maxDimension).get(key)
    }

    private fun putCache(key: String, maxDimension: Int, bitmap: ImageBitmap) {
        cachePoolFor(maxDimension).put(key, bitmap)
    }

    override fun clearCache() {
        thumbnailCache.clear()
        viewerCache.clear()
    }

    /**
     * 使用纯 Skia 管线将 [skImage] 逐步缩放到 [targetW] x [targetH]。
     *
     * 算法：
     * 1. 反复将图像宽高各减半（双线性），直到某一边再减半就会小于目标尺寸。
     * 2. 最后一步精确缩放到目标尺寸，使用 Mitchell 三次重采样。
     *
     * 为什么不一步到位？
     * 双线性/三次插值在单步大比例缩小时只采样 4~16 个源像素，
     * 其余像素被跳过，导致摩尔纹和马赛克。逐步半分能让每个
     * 原始像素都对最终结果有贡献。
     */
    private fun progressiveDownscale(
        skImage: SkiaImage,
        targetW: Int,
        targetH: Int
    ): SkiaImage {
        var current = skImage
        var cw = skImage.width
        var ch = skImage.height

        // 如果原图已经不大于目标，直接返回
        if (cw <= targetW && ch <= targetH) return skImage

        try {
            // 逐步半分缩小：每次宽高各减半，直到再减半会小于目标尺寸
            while (cw / 2 >= targetW && ch / 2 >= targetH) {
                val nw = cw / 2
                val nh = ch / 2
                val nextImage = Surface.makeRasterN32Premul(nw, nh).use { surface ->
                    surface.canvas.drawImageRect(
                        current,
                        Rect.makeWH(cw.toFloat(), ch.toFloat()),
                        Rect.makeWH(nw.toFloat(), nh.toFloat()),
                        FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE),
                        null,
                        true
                    )
                    surface.makeImageSnapshot()
                }
                val previous = current
                current = nextImage
                // 释放中间 SkiaImage 的 native 内存（不释放原始输入图像）
                if (previous !== skImage) previous.close()
                cw = nw
                ch = nh
            }

            // 最后一步精确缩放（Mitchell 三次重采样 B=1/3 C=1/3）
            return if (cw != targetW || ch != targetH) {
                val result = Surface.makeRasterN32Premul(targetW, targetH).use { surface ->
                    surface.canvas.drawImageRect(
                        current,
                        Rect.makeWH(cw.toFloat(), ch.toFloat()),
                        Rect.makeWH(targetW.toFloat(), targetH.toFloat()),
                        SamplingMode.MITCHELL,
                        null,
                        true
                    )
                    surface.makeImageSnapshot()
                }
                // 释放最后一个中间图像
                if (current !== skImage) current.close()
                result
            } else {
                current
            }
        } catch (failure: Throwable) {
            if (current !== skImage) current.close()
            throw failure
        }
    }

    /**
     * 解码图片字节、按目标边界高质量缩放并转换为 [ImageBitmap]，确保及时释放所有原生 [SkiaImage]。
     *
     * @param bytes 原始编码图片字节。
     * @param maxDimension 最大宽高边界像素。
     * @return 可供 Compose 绘制的独立位图副本。
     */
    private fun decodeAndDownscale(
        bytes: ByteArray,
        maxDimension: Int,
    ): ImageBitmap {
        val skImage = SkiaImage.makeFromEncoded(bytes)
        try {
            val width = skImage.width
            val height = skImage.height
            val scale = min(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
                .coerceAtMost(1f)
            val targetWidth = max(1, (width * scale).toInt())
            val targetHeight = max(1, (height * scale).toInt())
            val scaled = progressiveDownscale(skImage, targetWidth, targetHeight)
            try {
                return scaled.toComposeImageBitmap()
            } finally {
                if (scaled !== skImage) {
                    scaled.close()
                }
            }
        } finally {
            skImage.close()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun loadThumbnail(
        location: String,
        maxDimension: Int,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "$location@$maxDimension"
        getCached(cacheKey, maxDimension)?.let { return@withContext it }

        return@withContext try {
            val bytes: ByteArray
            if (ArchiveService.isArchiveLocation(location)) {
                // archive:// 协议 → 从压缩包中提取字节
                val (archivePath, innerPath) = ArchiveService.parseArchiveLocation(location)
                    ?: return@withContext null
                if (innerPath.isBlank()) return@withContext null
                bytes = archiveService.extractToBytes(
                    archivePath = archivePath,
                    innerPath = innerPath,
                    maxBytes = MAX_ARCHIVE_IMAGE_BYTES,
                ).getOrNull() ?: return@withContext null
                if (bytes.isEmpty()) return@withContext null
            } else {
                val routedBytes = readRoutedContentBytes(location)
                if (routedBytes != null) {
                    bytes = routedBytes
                } else if (location.contains("://")) {
                    return@withContext null
                } else {
                    val file = File(location)
                    if (!file.exists() || !file.isFile) return@withContext null
                    if (file.length() > MAX_FILE_SIZE_BYTES) return@withContext null
                    bytes = file.readBytes()
                }
            }

            val composeBitmap = decodeAndDownscale(bytes, maxDimension)
            putCache(cacheKey, maxDimension, composeBitmap)
            composeBitmap
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            OnyxLogger.warn("JvmThumbnailService", "缩略图加载失败: $location", failure)
            null
        }
    }

    /**
     * 通过统一内容服务读取远程图片字节。
     *
     * @param location 图片位置。
     * @return 可解码图片字节；没有可路由内容服务或超过大小限制时返回 `null`。
     */
    private suspend fun readRoutedContentBytes(location: String): ByteArray? {
        val contentService = contentServices.firstOrNull { service -> service.supports(location) } ?: return null
        val source = contentService.readFile(location.toSyntheticVFile()).getOrNull() ?: return null
        val sourceSize = source.sizeBytes
        if (sourceSize != null && sourceSize > MAX_FILE_SIZE_BYTES) return null
        val buffer = ByteArrayOutputStream()
        var readBytes = 0L
        return try {
            source.chunks.collect { chunk ->
                if (readBytes + chunk.size > MAX_FILE_SIZE_BYTES) {
                    throw ThumbnailContentTooLargeException()
                }
                readBytes += chunk.size
                buffer.write(chunk)
            }
            buffer.toByteArray()
        } catch (_: ThumbnailContentTooLargeException) {
            null
        }
    }

    /**
     * 为只按 location 调用的缩略图服务构造最小 VFS 文件条目。
     *
     * @return 合成的文件条目。
     */
    private fun String.toSyntheticVFile(): VFile {
        val normalized = trimEnd('/')
        return VFile(
            id = this,
            name = normalized.substringAfterLast('/', missingDelimiterValue = normalized),
            location = this,
            parentLocation = normalized.substringBeforeLast('/', missingDelimiterValue = ""),
            kind = VFileKind.FILE,
            sizeBytes = null,
            modifiedAtEpochMillis = null,
            hidden = false,
            capabilities = setOf(VFileCapability.READ_CONTENT),
        )
    }

    /**
     * 缩略图远程内容超过读取上限时使用的内部中断信号。
     */
    private class ThumbnailContentTooLargeException : RuntimeException()

    /**
     * 从压缩包中提取第一张图片，生成缩略图。
     *
     * 扫描压缩包内所有条目，按路径排序后取第一张图片类型的文件，
     * 解压到内存中并复用 Skia 管线生成缩略图。
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun loadArchiveThumbnail(
        location: String,
        maxDimension: Int,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "archive:$location@$maxDimension"
        getCached(cacheKey, maxDimension)?.let { return@withContext it }

        return@withContext try {
            val imageBytes = archiveService.extractFirstImageToBytes(
                archivePath = location,
                maxBytes = MAX_ARCHIVE_IMAGE_BYTES,
            ).getOrNull() ?: return@withContext null
            val composeBitmap = decodeAndDownscale(imageBytes, maxDimension)
            putCache(cacheKey, maxDimension, composeBitmap)
            composeBitmap
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            OnyxLogger.warn("JvmThumbnailService", "压缩包缩略图加载失败: $location", failure)
            null
        }
    }
}
