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
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * 系统托盘菜单与提示本地化文案模型。
 *
 * @property openLabel 打开主窗口菜单项文案。
 * @property exitLabel 退出应用菜单项文案。
 * @property tooltip 托盘图标悬停提示文案。
 */
internal data class TrayMenuLabels(
    val openLabel: String,
    val exitLabel: String,
    val tooltip: String = "Onyx ${BuildConfig.VERSION}",
)

/**
 * 系统托盘安装句柄，明确暴露真实安装状态并支持运行期随语言环境刷新菜单文案。
 */
internal interface OnyxTrayHandle : AutoCloseable {
    /** 托盘图标是否已在宿主桌面真实安装成功。 */
    val installed: Boolean

    /**
     * 在切换应用语言时动态刷新托盘右键菜单与提示文案。
     *
     * @param labels 最新本地化文案。
     */
    fun updateLabels(labels: TrayMenuLabels)

    companion object {
        /** 宿主不支持托盘或安装失败时的降级空句柄。 */
        val NotInstalled: OnyxTrayHandle = object : OnyxTrayHandle {
            override val installed: Boolean = false
            override fun updateLabels(labels: TrayMenuLabels) = Unit
            override fun close() = Unit
        }
    }
}

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

    /** 托盘左键单击/双击联合触发去重窗口（毫秒）。 */
    internal const val OPEN_WINDOW_DEBOUNCE_MS = 300L

    /** 托盘多分辨率位图阶梯（覆盖 100%~300% DPI 缩放）。 */
    private val trayVariantSizes = intArrayOf(16, 20, 24, 32, 40, 48)

    /** 托盘回退主色（Onyx 清透天青蓝）。 */
    private val fallbackPrimaryColor = Color(0x0E, 0xA5, 0xE9)

    /**
     * 创建带时间窗防抖的窗口唤醒包装器，防止 Windows 下左键双击同时触发 `mouseClicked` 与 `ActionListener`。
     *
     * @param nowMillis 当前毫秒时钟。
     * @param debounceMs 防抖窗口毫秒数。
     * @param onOpenWindow 实际窗口激活回调。
     * @return 去重后的唤醒回调。
     */
    internal fun debouncedOpenTrigger(
        nowMillis: () -> Long = System::currentTimeMillis,
        debounceMs: Long = OPEN_WINDOW_DEBOUNCE_MS,
        onOpenWindow: () -> Unit,
    ): () -> Unit {
        val lastTriggeredAt = AtomicLong(Long.MIN_VALUE)
        return {
            val now = nowMillis()
            val previous = lastTriggeredAt.get()
            if (previous == Long.MIN_VALUE || now - previous >= debounceMs) {
                if (lastTriggeredAt.compareAndSet(previous, now)) {
                    onOpenWindow()
                }
            }
        }
    }

    /**
     * 安装系统托盘图标；宿主环境不支持托盘或安装失败时返回 [OnyxTrayHandle.NotInstalled]，
     * 供窗口管理器禁用后台无托盘隐身驻留。
     *
     * @param labels 初始本地化菜单文案。
     * @param onOpenWindow 点击托盘图标或打开菜单项时的回调。
     * @param onExitApplication 点击退出菜单项时的回调。
     * @return 带真实安装状态的托盘句柄。
     */
    @Suppress("TooGenericExceptionCaught")
    fun install(
        labels: TrayMenuLabels,
        onOpenWindow: () -> Unit,
        onExitApplication: () -> Unit,
    ): OnyxTrayHandle {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            return OnyxTrayHandle.NotInstalled
        }
        return try {
            val systemTray = SystemTray.getSystemTray()
            val openWindowSafely = debouncedOpenTrigger {
                SwingUtilities.invokeLater(onOpenWindow)
            }
            val openMenuItem = MenuItem(labels.openLabel).apply {
                addActionListener { SwingUtilities.invokeLater(onOpenWindow) }
            }
            val exitMenuItem = MenuItem(labels.exitLabel).apply {
                addActionListener { SwingUtilities.invokeLater(onExitApplication) }
            }
            val popupMenu = PopupMenu().apply {
                add(openMenuItem)
                addSeparator()
                add(exitMenuItem)
            }
            val trayIcon = TrayIcon(loadTrayImage(), labels.tooltip, popupMenu).apply {
                isImageAutoSize = true
                addActionListener { openWindowSafely() }
                addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseClicked(event: MouseEvent) {
                            if (event.button == MouseEvent.BUTTON1) {
                                openWindowSafely()
                            }
                        }
                    },
                )
            }
            systemTray.add(trayIcon)
            object : OnyxTrayHandle {
                override val installed: Boolean = true

                override fun updateLabels(labels: TrayMenuLabels) {
                    SwingUtilities.invokeLater {
                        runCatching {
                            openMenuItem.label = labels.openLabel
                            exitMenuItem.label = labels.exitLabel
                            trayIcon.toolTip = labels.tooltip
                        }
                    }
                }

                override fun close() {
                    runCatching { systemTray.remove(trayIcon) }
                }
            }
        } catch (failure: Exception) {
            OnyxLogger.warn("Startup", "安装系统托盘图标失败，已禁用后台驻留以防窗口无法唤回", failure)
            OnyxTrayHandle.NotInstalled
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
