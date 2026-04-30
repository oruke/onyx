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
import java.io.File
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

    fun loadThumbnail(filePath: String, maxDimension: Int = 400): ImageBitmap? {
        val cacheKey = "$filePath@$maxDimension"
        getCached(cacheKey)?.let { return it }

        return try {
            val file = File(filePath)
            if (!file.exists() || !file.isFile) return null
            // 文件大小上限检查，避免大文件 readBytes() 导致 OOM
            if (file.length() > MAX_FILE_SIZE_BYTES) return null

            // 1. 用 Skia 原生解码器读取（比 ImageIO 支持格式更多、颜色更准）
            val bytes = file.readBytes()
            val skImage = SkiaImage.makeFromEncoded(bytes) ?: return null

            val w = skImage.width
            val h = skImage.height

            // 2. 按比例计算目标尺寸（不放大）
            val scale = min(maxDimension.toFloat() / w, maxDimension.toFloat() / h)
                .coerceAtMost(1f)
            val newW = max(1, (w * scale).toInt())
            val newH = max(1, (h * scale).toInt())

            // 3. 纯 Skia 逐步缩放
            val scaled = progressiveDownscale(skImage, newW, newH)

            // 4. 转换为 Compose ImageBitmap（SkiaImage 可直接转换）
            val composeBitmap = scaled.toComposeImageBitmap()

            putCache(cacheKey, composeBitmap)
            composeBitmap
        } catch (e: Exception) {
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
