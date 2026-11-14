package com.oruke.onyx.app.platform

import com.oruke.onyx.BuildConfig
import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.cache.OnyxStartupPreloader
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.AbstractMultiResolutionImage
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * 系统托盘控制器，在主窗口关闭并隐藏驻留后台时提供托盘唤醒与完全退出入口。
 */
internal object OnyxSystemTrayController {
    /** 托盘回退图标边长（像素）。 */
    private const val FALLBACK_ICON_SIZE = 16

    /** 托盘回退图标圆角半径（像素）。 */
    private const val FALLBACK_ICON_ARC = 6

    /** 托盘回退图标内边距（像素）。 */
    private const val FALLBACK_ICON_INSET = 2

    /** 切换小尺寸光学优化母版的最大托盘像素边长。 */
    private const val SMALL_OPTICAL_THRESHOLD_PX = 24

    /** 渐进二分降采样倍率。 */
    private const val HALF_SCALE_DIVISOR = 2

    /** 托盘多分辨率位图阶梯（覆盖 100%~300% DPI 缩放）。 */
    private val trayVariantSizes = intArrayOf(16, 20, 24, 32, 40, 48)

    /** 托盘回退主色（Onyx 清透天青蓝）。 */
    private val fallbackPrimaryColor = Color(0x0E, 0xA5, 0xE9)

    /**
     * 安装系统托盘图标；宿主环境不支持托盘时安全降级为空操作。
     *
     * @param onOpenWindow 点击托盘图标或“打开 Onyx”菜单项时的回调。
     * @param onExitApplication 点击“退出 Onyx”菜单项时的回调。
     * @return 用于卸载托盘图标的句柄。
     */
    @Suppress("TooGenericExceptionCaught")
    fun install(
        onOpenWindow: () -> Unit,
        onExitApplication: () -> Unit,
    ): AutoCloseable {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            return AutoCloseable {}
        }
        return try {
            val systemTray = SystemTray.getSystemTray()
            val popupMenu = PopupMenu().apply {
                add(
                    MenuItem("Open Onyx").apply {
                        addActionListener { SwingUtilities.invokeLater(onOpenWindow) }
                    },
                )
                addSeparator()
                add(
                    MenuItem("Exit Onyx").apply {
                        addActionListener { SwingUtilities.invokeLater(onExitApplication) }
                    },
                )
            }
            val trayIcon = TrayIcon(loadTrayImage(), "Onyx ${BuildConfig.VERSION}", popupMenu).apply {
                isImageAutoSize = true
                addActionListener { SwingUtilities.invokeLater(onOpenWindow) }
                addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseClicked(event: MouseEvent) {
                            if (event.button == MouseEvent.BUTTON1) {
                                SwingUtilities.invokeLater(onOpenWindow)
                            }
                        }
                    },
                )
            }
            systemTray.add(trayIcon)
            AutoCloseable {
                runCatching { systemTray.remove(trayIcon) }
            }
        } catch (failure: Exception) {
            OnyxLogger.warn("Startup", "安装系统托盘图标失败，继续保持无托盘后台驻留", failure)
            AutoCloseable {}
        }
    }

    /** 加载应用图标并构建平滑抗锯齿的多分辨率托盘图像，避免 AWT 默认邻近采样导致边缘锯齿。 */
    private fun loadTrayImage(): Image {
        val fullPaths = listOf(
            "/composeResources/onyx.composeapp.generated.resources/drawable/onyx_logo.png",
            "/drawable/onyx_logo.png",
        )
        val smallPaths = listOf(
            "/composeResources/onyx.composeapp.generated.resources/drawable/onyx_logo_small.png",
            "/drawable/onyx_logo_small.png",
        )
        val fullImage = OnyxStartupPreloader.peekLogoImage() ?: fullPaths.firstNotNullOfOrNull(::readClasspathPng)
        val smallImage = smallPaths.firstNotNullOfOrNull(::readClasspathPng)
        if (fullImage != null || smallImage != null) {
            val variants = trayVariantSizes.map { targetSize ->
                val source = if (targetSize <= SMALL_OPTICAL_THRESHOLD_PX) {
                    smallImage ?: fullImage!!
                } else {
                    fullImage ?: smallImage!!
                }
                downscaleSmoothly(source, targetSize)
            }
            return TrayMultiResolutionImage(variants)
        }
        return BufferedImage(FALLBACK_ICON_SIZE, FALLBACK_ICON_SIZE, BufferedImage.TYPE_INT_ARGB).apply {
            val graphics = createGraphics()
            try {
                graphics.color = fallbackPrimaryColor
                val span = FALLBACK_ICON_SIZE - FALLBACK_ICON_INSET * HALF_SCALE_DIVISOR
                graphics.fillRoundRect(
                    FALLBACK_ICON_INSET,
                    FALLBACK_ICON_INSET,
                    span,
                    span,
                    FALLBACK_ICON_ARC,
                    FALLBACK_ICON_ARC,
                )
            } finally {
                graphics.dispose()
            }
        }
    }

    /** 从类路径读取 PNG 图标流并解码为位图。 */
    private fun readClasspathPng(path: String): BufferedImage? =
        runCatching {
            OnyxSystemTrayController::class.java.getResourceAsStream(path)?.use(ImageIO::read)
        }.getOrNull()

    /** 采用渐进二分双三次插值将高分源图平滑缩放到指定边长。 */
    private fun downscaleSmoothly(source: BufferedImage, targetSize: Int): BufferedImage {
        var current = source
        var width = source.width
        var height = source.height
        while (width / HALF_SCALE_DIVISOR >= targetSize && height / HALF_SCALE_DIVISOR >= targetSize) {
            width /= HALF_SCALE_DIVISOR
            height /= HALF_SCALE_DIVISOR
            current = renderScaledStep(current, width, height)
        }
        return if (width == targetSize && height == targetSize) {
            current
        } else {
            renderScaledStep(current, targetSize, targetSize)
        }
    }

    /** 单步高质量抗锯齿缩放。 */
    private fun renderScaledStep(source: BufferedImage, width: Int, height: Int): BufferedImage =
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

    /** 按目标物理像素尺寸精确匹配预缩放位图的多分辨率托盘图像封装。 */
    private class TrayMultiResolutionImage(
        private val variants: List<BufferedImage>,
    ) : AbstractMultiResolutionImage() {
        override fun getBaseImage(): Image = variants.first()

        override fun getResolutionVariants(): List<Image> = variants

        override fun getResolutionVariant(destImageWidth: Double, destImageHeight: Double): Image {
            val requestedSize = maxOf(destImageWidth, destImageHeight)
            return variants.firstOrNull { candidate -> candidate.width >= requestedSize } ?: variants.last()
        }
    }
}
