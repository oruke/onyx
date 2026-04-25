package com.oruke.onyx.ui.theme

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.File
import java.util.Collections
import javax.swing.Icon
import javax.swing.filechooser.FileSystemView

internal object SystemIconLoader {
    private const val MAX_CACHE_SIZE = 500

    // 使用 LinkedHashMap 实现简易 LRU 缓存，加锁保证线程安全
    private val iconCache = Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean {
                return size > MAX_CACHE_SIZE
            }
        }
    )

    suspend fun loadSystemIcon(file: File): ImageBitmap? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null

        // 智能缓存键：普通文件使用后缀名复用缓存；特定可执行文件和快捷方式使用全路径；文件夹使用全路径（有些特定文件夹如图库、下载有独立图标）
        val cacheKey = when {
            file.isDirectory -> "DIR_${file.absolutePath}"
            file.extension.lowercase() in listOf("exe", "ico", "lnk", "app", "dll") -> "EXEC_${file.absolutePath}"
            file.extension.isNotEmpty() -> "EXT_${file.extension.lowercase()}"
            else -> "FILE_${file.absolutePath}"
        }

        iconCache[cacheKey]?.let { return@withContext it }

        try {
            val icon: Icon = FileSystemView.getFileSystemView().getSystemIcon(file)
            val width = icon.iconWidth.coerceAtLeast(1)
            val height = icon.iconHeight.coerceAtLeast(1)
            
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val g2d: Graphics2D = image.createGraphics()
            icon.paintIcon(null, g2d, 0, 0)
            g2d.dispose()

            val bitmap = image.toComposeImageBitmap()
            iconCache[cacheKey] = bitmap
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}
