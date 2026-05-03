package com.oruke.onyx.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import com.oruke.onyx.app.filesystem.ArchiveService
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min
import org.jetbrains.skia.Image as SkiaImage

/**
 * 纯 Skia 原生缩略图加载器
 *
 * 完全绕过 Java2D/AWT，全链路使用 Skia 引擎：
 * 1. 解码：Skia 原生解码器（支持 JPEG/PNG/WebP/GIF/BMP/ICO/HEIF 等）
 * 2. 缩放：逐步半分缩小 (Progressive Half-Step) + 最终 Mitchell 三次插值
 *    - 每次缩小一半时使用双线性插值，保证所有原始像素参与混合
 *    - 最后一步使用 Mitchell-Netravali 三次重采样，确保锐度与平滑的最佳平衡
 * 3. 输出：直接转为 Compose ImageBitmap，无跨引擎转码损失
 */
object ThumbnailLoader {

    private const val MAX_CACHE_SIZE = 500

    private val cache = object : LinkedHashMap<String, ImageBitmap>(
        MAX_CACHE_SIZE + 1, 0.75f, true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ImageBitmap>?
        ): Boolean = size > MAX_CACHE_SIZE
    }

    @Synchronized
    private fun getCached(key: String): ImageBitmap? = cache[key]

    @Synchronized
    private fun putCache(key: String, bitmap: ImageBitmap) {
        cache[key] = bitmap
    }

    @Synchronized
    fun clearCache() {
        cache.clear()
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

        // 逐步半分缩小：每次宽高各减半，直到再减半会小于目标尺寸
        while (cw / 2 >= targetW && ch / 2 >= targetH) {
            val nw = cw / 2
            val nh = ch / 2
            val surface = Surface.makeRasterN32Premul(nw, nh)
            surface.canvas.drawImageRect(
                current,
                Rect.makeWH(cw.toFloat(), ch.toFloat()),
                Rect.makeWH(nw.toFloat(), nh.toFloat()),
                FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE),
                null,
                true
            )
            val previous = current
            current = surface.makeImageSnapshot()
            surface.close()
            // 释放中间 SkiaImage 的 native 内存（不释放原始输入图像）
            if (previous !== skImage) previous.close()
            cw = nw
            ch = nh
        }

        // 最后一步精确缩放（Mitchell 三次重采样 B=1/3 C=1/3）
        if (cw != targetW || ch != targetH) {
            val surface = Surface.makeRasterN32Premul(targetW, targetH)
            surface.canvas.drawImageRect(
                current,
                Rect.makeWH(cw.toFloat(), ch.toFloat()),
                Rect.makeWH(targetW.toFloat(), targetH.toFloat()),
                SamplingMode.MITCHELL,
                null,
                true
            )
            val result = surface.makeImageSnapshot()
            surface.close()
            // 释放最后一个中间图像
            if (current !== skImage) current.close()
            return result
        }

        return current
    }

    /** 缩略图加载的文件大小上限（50MB），超过此大小跳过缩略图生成以避免 OOM */
    private const val MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024

    /** 压缩包内单张图片的提取大小上限（20MB） */
    private const val MAX_ARCHIVE_IMAGE_BYTES = 20L * 1024 * 1024

    /** 图片扩展名集合（用于压缩包内条目筛选） */
    private val IMAGE_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "tiff", "tif",
    )

    fun loadThumbnail(filePath: String, maxDimension: Int = 400): ImageBitmap? {
        val cacheKey = "$filePath@$maxDimension"
        getCached(cacheKey)?.let { return it }

        return try {
            val bytes: ByteArray
            if (ArchiveService.isArchiveLocation(filePath)) {
                // archive:// 协议 → 从压缩包中提取字节
                val (archivePath, innerPath) = ArchiveService.parseArchiveLocation(filePath)
                    ?: return null
                if (innerPath.isBlank()) return null
                bytes = extractArchiveEntryBytes(archivePath, innerPath) ?: return null
            } else {
                val file = File(filePath)
                if (!file.exists() || !file.isFile) return null
                if (file.length() > MAX_FILE_SIZE_BYTES) return null
                bytes = file.readBytes()
            }

            val skImage = SkiaImage.makeFromEncoded(bytes) ?: return null

            val w = skImage.width
            val h = skImage.height

            val scale = min(maxDimension.toFloat() / w, maxDimension.toFloat() / h)
                .coerceAtMost(1f)
            val newW = max(1, (w * scale).toInt())
            val newH = max(1, (h * scale).toInt())

            val scaled = progressiveDownscale(skImage, newW, newH)
            val composeBitmap = scaled.toComposeImageBitmap()

            putCache(cacheKey, composeBitmap)
            composeBitmap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从压缩包中提取单个条目的字节数据（同步，需在 IO 线程调用）。
     */
    private fun extractArchiveEntryBytes(archivePath: String, innerPath: String): ByteArray? {
        val raf = RandomAccessFile(archivePath, "r")
        val inStream = RandomAccessFileInStream(raf)
        val archive = SevenZip.openInArchive(null, inStream)
        try {
            val numItems = archive.numberOfItems
            var targetIndex = -1
            for (i in 0 until numItems) {
                val itemPath = archive.getProperty(i, PropID.PATH) as? String ?: continue
                if (itemPath == innerPath) {
                    targetIndex = i
                    break
                }
            }
            if (targetIndex < 0) return null

            val size = (archive.getProperty(targetIndex, PropID.SIZE) as? Long) ?: 0L
            if (size > MAX_ARCHIVE_IMAGE_BYTES) return null
            val buffer = ByteArrayOutputStream(size.toInt().coerceAtLeast(1024))
            val idx = targetIndex
            archive.extract(
                intArrayOf(idx),
                false,
                object : net.sf.sevenzipjbinding.IArchiveExtractCallback {
                    override fun getStream(
                        index: Int,
                        extractAskMode: net.sf.sevenzipjbinding.ExtractAskMode,
                    ): ISequentialOutStream? {
                        if (extractAskMode != net.sf.sevenzipjbinding.ExtractAskMode.EXTRACT) return null
                        if (index != idx) return null
                        return ISequentialOutStream { data ->
                            buffer.write(data)
                            data.size
                        }
                    }
                    override fun prepareOperation(extractAskMode: net.sf.sevenzipjbinding.ExtractAskMode) {}
                    override fun setOperationResult(result: net.sf.sevenzipjbinding.ExtractOperationResult) {}
                    override fun setTotal(total: Long) {}
                    override fun setCompleted(complete: Long) {}
                },
            )
            val result = buffer.toByteArray()
            return if (result.isEmpty()) null else result
        } finally {
            archive.close()
            inStream.close()
            raf.close()
        }
    }

    /**
     * 从压缩包中提取第一张图片，生成缩略图。
     *
     * 扫描压缩包内所有条目，按路径排序后取第一张图片类型的文件，
     * 解压到内存中并复用 Skia 管线生成缩略图。
     */
    fun loadArchiveThumbnail(archivePath: String, maxDimension: Int = 400): ImageBitmap? {
        val cacheKey = "archive:$archivePath@$maxDimension"
        getCached(cacheKey)?.let { return it }

        return try {
            val file = File(archivePath)
            if (!file.exists() || !file.isFile) return null

            val raf = RandomAccessFile(archivePath, "r")
            val inStream = RandomAccessFileInStream(raf)
            val archive = SevenZip.openInArchive(null, inStream)

            try {
                val numItems = archive.numberOfItems

                // 找到第一张图片条目（按路径排序，优先取浅层文件）
                data class ImageEntry(val index: Int, val path: String, val size: Long)

                val imageEntries = mutableListOf<ImageEntry>()
                for (i in 0 until numItems) {
                    val isDir = archive.getProperty(i, PropID.IS_FOLDER) as? Boolean ?: false
                    if (isDir) continue
                    val itemPath = archive.getProperty(i, PropID.PATH) as? String ?: continue
                    val ext = itemPath.substringAfterLast('.', "").lowercase()
                    if (ext !in IMAGE_EXTENSIONS) continue
                    val size = (archive.getProperty(i, PropID.SIZE) as? Long) ?: 0L
                    if (size > MAX_ARCHIVE_IMAGE_BYTES) continue
                    imageEntries += ImageEntry(i, itemPath, size)
                }

                if (imageEntries.isEmpty()) return null

                // 按路径排序取第一张（确保稳定）
                val target = imageEntries.sortedBy { it.path }.first()

                // 解压到内存
                val buffer = ByteArrayOutputStream(target.size.toInt().coerceAtLeast(1024))
                archive.extract(
                    intArrayOf(target.index),
                    false,
                    object : net.sf.sevenzipjbinding.IArchiveExtractCallback {
                        override fun getStream(
                            index: Int,
                            extractAskMode: net.sf.sevenzipjbinding.ExtractAskMode,
                        ): ISequentialOutStream? {
                            if (extractAskMode != net.sf.sevenzipjbinding.ExtractAskMode.EXTRACT) return null
                            if (index != target.index) return null
                            return ISequentialOutStream { data ->
                                buffer.write(data)
                                data.size
                            }
                        }
                        override fun prepareOperation(extractAskMode: net.sf.sevenzipjbinding.ExtractAskMode) {}
                        override fun setOperationResult(result: net.sf.sevenzipjbinding.ExtractOperationResult) {}
                        override fun setTotal(total: Long) {}
                        override fun setCompleted(complete: Long) {}
                    },
                )

                val imageBytes = buffer.toByteArray()
                if (imageBytes.isEmpty()) return null

                val skImage = SkiaImage.makeFromEncoded(imageBytes) ?: return null

                val w = skImage.width
                val h = skImage.height
                val scale = min(maxDimension.toFloat() / w, maxDimension.toFloat() / h)
                    .coerceAtMost(1f)
                val newW = max(1, (w * scale).toInt())
                val newH = max(1, (h * scale).toInt())

                val scaled = progressiveDownscale(skImage, newW, newH)
                val composeBitmap = scaled.toComposeImageBitmap()

                putCache(cacheKey, composeBitmap)
                composeBitmap
            } finally {
                archive.close()
                inStream.close()
                raf.close()
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Composable：异步加载高质量缩略图
 *
 * @param filePath 文件路径
 * @param maxDimension 最大边长（像素），推荐 300~600
 * @return (ImageBitmap?, isLoading)
 */
@Composable
fun rememberThumbnail(filePath: String, maxDimension: Int = 400): Pair<ImageBitmap?, Boolean> {
    var bitmap by remember(filePath, maxDimension) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(filePath, maxDimension) { mutableStateOf(true) }

    LaunchedEffect(filePath, maxDimension) {
        loading = true
        bitmap = withContext(Dispatchers.IO) {
            ThumbnailLoader.loadThumbnail(filePath, maxDimension)
        }
        loading = false
    }

    return bitmap to loading
}

/**
 * Composable：异步加载压缩包缩略图（提取压缩包内第一张图片）
 *
 * @param archivePath 压缩包文件路径
 * @param maxDimension 最大边长（像素），推荐 300~600
 * @return (ImageBitmap?, isLoading)
 */
@Composable
fun rememberArchiveThumbnail(archivePath: String, maxDimension: Int = 400): Pair<ImageBitmap?, Boolean> {
    var bitmap by remember(archivePath, maxDimension) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(archivePath, maxDimension) { mutableStateOf(true) }

    LaunchedEffect(archivePath, maxDimension) {
        loading = true
        bitmap = withContext(Dispatchers.IO) {
            ThumbnailLoader.loadArchiveThumbnail(archivePath, maxDimension)
        }
        loading = false
    }

    return bitmap to loading
}
