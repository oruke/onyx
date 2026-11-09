package com.oruke.onyx.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import javax.swing.filechooser.FileSystemView

/**
 * 系统菜单动态图标位图加载器。
 *
 * Windows Shell、Linux desktop entry 和 macOS app bundle 都可能把图标以路径或平台标识形式传到 UI。
 * UI 层只消费抽象后的字符串，不接触平台菜单对象；本加载器负责把可解析路径转成 Compose 位图并缓存。
 */
internal object SystemMenuIconBitmapLoader {
    /** 已成功解析的图标位图缓存，避免每次展开菜单都访问系统图标服务。 */
    private val bitmapCache = ConcurrentHashMap<String, ImageBitmap>()

    /**
     * 读取平台图标位图。
     *
     * @param iconPath 平台图标路径或带资源索引的图标位置。
     * @return 可渲染位图；路径不可解析时返回 `null`。
     */
    suspend fun load(iconPath: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val spec = parseIconLocationSpec(iconPath) ?: return@withContext null
        val cacheKey = "${spec.filePath}:${spec.iconIndex ?: "default"}"
        bitmapCache[cacheKey] ?: (
            SystemFileIconLoader.extractIndexedOrFileIcon(spec.filePath, spec.iconIndex, large = true)
                ?: loadSystemIcon(spec.filePath)
            )?.also { bitmap ->
            bitmapCache[cacheKey] = bitmap
        }
    }

    /**
     * 使用 Swing 文件系统视图读取系统图标。
     *
     * @param path 已标准化的本地文件路径。
     * @return Compose 可显示的位图；失败时返回 `null`。
     */
    private fun loadSystemIcon(path: String): ImageBitmap? {
        return runCatching {
            val file = File(path).takeIf { value -> value.exists() } ?: return null
            val icon = FileSystemView.getFileSystemView().getSystemIcon(file)
            icon.toBufferedImage().toComposeImageBitmap()
        }.getOrNull()
    }

    /**
     * 把 Swing 图标绘制成 ARGB 位图。
     *
     * @return 图标位图。
     */
    private fun Icon.toBufferedImage(): BufferedImage {
        val width = iconWidth.coerceAtLeast(DEFAULT_ICON_SIZE)
        val height = iconHeight.coerceAtLeast(DEFAULT_ICON_SIZE)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.configureIconRendering()
            paintIcon(NULL_COMPONENT, graphics, 0, 0)
        } finally {
            graphics.dispose()
        }
        return image
    }

    /**
     * 配置图标绘制质量。
     */
    private fun Graphics2D.configureIconRendering() {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    }

    /**
     * 解析平台图标位置中的真实文件路径与可选资源索引。
     *
     * @param rawSpec 原始图标位置描述符。
     * @return 可交给本地文件系统或 Shell 提取的图标定位信息；无法解析时返回 `null`。
     */
    internal fun parseIconLocationSpec(rawSpec: String): IconLocationSpec? {
        val raw = rawSpec.trim()
            .removePrefix("@")
            .takeIf { value -> value.isNotBlank() }
        val parsed = if (raw == null) {
            null
        } else {
            val filePart: String
            val indexPart: String?
            if (raw.startsWith("\"")) {
                filePart = raw.substringAfter("\"").substringBefore("\"")
                val remainder = raw.substringAfter("\"").substringAfter("\"", "")
                indexPart = remainder.substringAfter(",", "").takeIf { it.isNotBlank() }
            } else {
                filePart = raw.substringBefore(",")
                indexPart = if (',' in raw) raw.substringAfterLast(",") else null
            }
            val expanded = filePart.expandWindowsEnvironmentVariables().trim()
            expanded.takeIf { value -> value.isNotBlank() }?.let { validPath ->
                IconLocationSpec(
                    filePath = validPath,
                    iconIndex = indexPart?.trim()?.toIntOrNull(),
                )
            }
        }
        return parsed
    }

    internal data class IconLocationSpec(
        val filePath: String,
        val iconIndex: Int?,
    )

    /**
     * 展开 Windows `%NAME%` 环境变量。
     *
     * @return 环境变量替换后的路径。
     */
    private fun String.expandWindowsEnvironmentVariables(): String {
        return WINDOWS_ENVIRONMENT_VARIABLE.replace(this) { match ->
            System.getenv(match.groupValues[1]) ?: match.value
        }
    }

    private const val DEFAULT_ICON_SIZE = 16

    /** 用于 Swing `Icon.paintIcon` 的空组件。 */
    private val NULL_COMPONENT = object : Component() {}

    /** Windows `%NAME%` 环境变量匹配表达式。 */
    private val WINDOWS_ENVIRONMENT_VARIABLE = Regex("%([^%]+)%")
}
