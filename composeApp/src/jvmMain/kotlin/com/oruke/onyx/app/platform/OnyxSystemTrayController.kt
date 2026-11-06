package com.oruke.onyx.app.platform

import com.oruke.onyx.BuildConfig
import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.cache.OnyxStartupPreloader
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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

    /** 托盘回退主色（Onyx 蓝）。 */
    private val fallbackPrimaryColor = Color(0x25, 0x63, 0xEB)

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

    /** 加载应用图标并生成托盘图像，优先复用启动预加载缓存，不可读时绘制轻量矢量回退图。 */
    private fun loadTrayImage(): Image {
        val resourcePaths = listOf(
            "/composeResources/onyx.composeapp.generated.resources/drawable/onyx_logo.png",
            "/drawable/onyx_logo.png",
        )
        val loaded = OnyxStartupPreloader.peekLogoImage() ?: resourcePaths.firstNotNullOfOrNull { path ->
            runCatching {
                OnyxSystemTrayController::class.java.getResourceAsStream(path)?.use(ImageIO::read)
            }.getOrNull()
        }
        if (loaded != null) return loaded
        return BufferedImage(FALLBACK_ICON_SIZE, FALLBACK_ICON_SIZE, BufferedImage.TYPE_INT_ARGB).apply {
            val graphics = createGraphics()
            try {
                graphics.color = fallbackPrimaryColor
                val span = FALLBACK_ICON_SIZE - FALLBACK_ICON_INSET * 2
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
}

