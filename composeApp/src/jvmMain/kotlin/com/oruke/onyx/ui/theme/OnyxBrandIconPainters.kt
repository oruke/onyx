package com.oruke.onyx.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.cache.OnyxStartupPreloader
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.onyx_logo
import onyx.composeapp.generated.resources.onyx_logo_small
import org.jetbrains.compose.resources.painterResource

/** 标题栏图标缓存允许的最小物理像素边长。 */
private const val MIN_TITLE_BAR_ICON_PX = 16

/** 标题栏图标缓存允许的最大物理像素边长。 */
private const val MAX_TITLE_BAR_ICON_PX = 128

/** 切换使用小尺寸光学简化版图标的最大物理像素阈值。 */
private const val SMALL_OPTICAL_THRESHOLD_PX = 28

/** 渐进二分降采样除数。 */
private const val PROGRESSIVE_HALF_DIVISOR = 2

/** 类路径下的主应用图标资源候选路径。 */
private val fullLogoResourcePaths = listOf(
    "/composeResources/onyx.composeapp.generated.resources/drawable/onyx_logo.png",
    "/drawable/onyx_logo.png",
)

/** 类路径下的小尺寸光学优化图标资源候选路径。 */
private val smallLogoResourcePaths = listOf(
    "/composeResources/onyx.composeapp.generated.resources/drawable/onyx_logo_small.png",
    "/drawable/onyx_logo_small.png",
)

/** 按目标物理像素边长缓存预缩放完成的标题栏图标位图。 */
private val titleBarBitmapCache = ConcurrentHashMap<Int, ImageBitmap>()

/** 懒加载缓存的小尺寸光学优化源图。 */
private val cachedSmallSourceImage: BufferedImage? by lazy {
    loadBufferedImageFromResources(smallLogoResourcePaths)
}

/** 懒加载缓存的完整高精度源图。 */
private val cachedFullSourceImage: BufferedImage? by lazy {
    OnyxStartupPreloader.peekLogoImage() ?: loadBufferedImageFromResources(fullLogoResourcePaths)
}

/**
 * 优先复用启动预加载缓存的 Onyx 窗口级高分图标 [Painter]，供原生窗口装饰、任务栏与大图展示复用。
 *
 * @return 应用高分辨率窗口图标绘制器。
 */
@Composable
internal fun rememberOnyxWindowIconPainter(): Painter {
    val preloadedPainter = remember {
        runCatching {
            (OnyxStartupPreloader.peekLogoImage() ?: cachedFullSourceImage)
                ?.toComposeImageBitmap()
                ?.let { bitmap -> BitmapPainter(image = bitmap, filterQuality = FilterQuality.High) }
        }.getOrNull()
    }
    return preloadedPainter ?: painterResource(Res.drawable.onyx_logo)
}

/**
 * 根据当前窗口物理像素密度返回精确预缩放到目标像素网格的标题栏品牌图标 [Painter]，
 * 在小尺寸（<=28px）自动切换光学简化版母版并采用渐进双三次降采样，消除 Skia 大倍率缩图摩尔纹与噪点。
 *
 * @param iconSize 标题栏图标逻辑尺寸。
 * @return 与当前 DPI 像素网格对齐的抗锯齿图标绘制器。
 */
@Composable
internal fun rememberOnyxTitleBarIconPainter(iconSize: Dp = 16.dp): Painter {
    val density = LocalDensity.current
    val targetPx = remember(density.density, iconSize) {
        with(density) { iconSize.roundToPx() }.coerceIn(MIN_TITLE_BAR_ICON_PX, MAX_TITLE_BAR_ICON_PX)
    }
    val exactPainter = remember(targetPx) {
        runCatching {
            val bitmap = titleBarBitmapCache.computeIfAbsent(targetPx) { px ->
                val source = if (px <= SMALL_OPTICAL_THRESHOLD_PX) {
                    cachedSmallSourceImage ?: cachedFullSourceImage
                } else {
                    cachedFullSourceImage ?: cachedSmallSourceImage
                } ?: error("Missing Onyx logo resource")
                downscaleProgressively(source, px).toComposeImageBitmap()
            }
            BitmapPainter(image = bitmap, filterQuality = FilterQuality.High)
        }.getOrNull()
    }
    return exactPainter ?: painterResource(Res.drawable.onyx_logo_small)
}

/** 从候选类路径依次尝试读取 PNG 图像。 */
private fun loadBufferedImageFromResources(paths: List<String>): BufferedImage? =
    paths.firstNotNullOfOrNull { path ->
        runCatching {
            OnyxStartupPreloader::class.java.getResourceAsStream(path)?.use(ImageIO::read)
        }.getOrNull()
    }

/** 通过逐级减半双三次插值将高分源图平滑缩放到目标边长。 */
private fun downscaleProgressively(source: BufferedImage, targetSize: Int): BufferedImage {
    var current = source
    var width = source.width
    var height = source.height
    while (width / PROGRESSIVE_HALF_DIVISOR >= targetSize && height / PROGRESSIVE_HALF_DIVISOR >= targetSize) {
        width /= PROGRESSIVE_HALF_DIVISOR
        height /= PROGRESSIVE_HALF_DIVISOR
        current = scaleSingleStep(current, width, height)
    }
    return if (width == targetSize && height == targetSize) {
        current
    } else {
        scaleSingleStep(current, targetSize, targetSize)
    }
}

/** 执行单步高质量抗锯齿双三次插值绘制。 */
private fun scaleSingleStep(source: BufferedImage, width: Int, height: Int): BufferedImage =
    BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
        val g2 = createGraphics()
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.drawImage(source, 0, 0, width, height, null)
        } finally {
            g2.dispose()
        }
    }
