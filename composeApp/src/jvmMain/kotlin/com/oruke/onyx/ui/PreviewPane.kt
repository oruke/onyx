package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.fileIconKey
import com.oruke.onyx.ui.theme.formatFileSize
import com.oruke.onyx.ui.theme.formatModifiedTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.io.File

/**
 * 右侧预览面板组件 (Preview Pane)
 *
 * 负责在主面板右侧显示当前选中文件的详细信息与预览。
 * - 支持图片类型的高清大图预览（基于 Coil 异步加载）。
 * - 支持文本、代码类型的只读预览（限制 1MB 大小，截取前 100 行，避免内存溢出）。
 * - 显示基础的元数据信息（名称、类型、大小、修改时间）。
 *
 * @param selectedEntry 当前选中的虚拟文件对象。若为空则显示占位提示。
 * @param palette Onyx 动态外观调色板，用于保持全局视觉一致性。
 * @param modifier 外部传入的布局修饰符。
 */
@Composable
internal fun PreviewPane(
    selectedEntry: VFile?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LocalOnyxPalette.current.surface)
    ) {
        if (selectedEntry == null) {
            Text(
                text = "No item selected",
                color = LocalOnyxPalette.current.mutedForeground,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // 1. 顶部大图/图标预览区域
                // 采用 Box 限制最大高度，内部元素居中对齐
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val isImage = selectedEntry.name.lowercase().let {
                        it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".gif") || it.endsWith(".webp") || it.endsWith(".bmp")
                    }
                    if (isImage) {
                        AsyncImage(
                            model = selectedEntry.location,
                            contentDescription = selectedEntry.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val iconKey = if (selectedEntry.kind == VFileKind.DIRECTORY) AllIconsKeys.Nodes.Folder else fileIconKey(selectedEntry.name)
                        Icon(
                            key = iconKey,
                            contentDescription = null,
                            modifier = Modifier.size(96.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 2. 元数据展示区域
                // 包含文件名、类型、大小、最后修改时间
                Text(
                    text = selectedEntry.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = LocalOnyxPalette.current.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Type: ${if (selectedEntry.kind == VFileKind.DIRECTORY) "Folder" else selectedEntry.name.substringAfterLast('.', "File").uppercase()}",
                    fontSize = 12.sp,
                    color = LocalOnyxPalette.current.mutedForeground
                )

                if (selectedEntry.kind == VFileKind.FILE) {
                    Text(
                        text = "Size: ${formatFileSize(selectedEntry.sizeBytes)}",
                        fontSize = 12.sp,
                        color = LocalOnyxPalette.current.mutedForeground
                    )
                }

                Text(
                    text = "Modified: ${formatModifiedTime(selectedEntry.modifiedAtEpochMillis)}",
                    fontSize = 12.sp,
                    color = LocalOnyxPalette.current.mutedForeground
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 3. 纯文本预览区域
                // 针对常见的文本/代码类型进行安全读取
                val isText = selectedEntry.name.lowercase().let {
                    it.endsWith(".txt") || it.endsWith(".md") || it.endsWith(".log") || it.endsWith(".xml") || it.endsWith(".json") || it.endsWith(".java") || it.endsWith(".kt") || it.endsWith(".js") || it.endsWith(".css") || it.endsWith(".csv") || it.endsWith(".html")
                }

                if (isText && selectedEntry.kind == VFileKind.FILE) {
                    var previewText by remember(selectedEntry.location) { mutableStateOf<String?>("Loading preview...") }
                    
                    LaunchedEffect(selectedEntry.location) {
                        previewText = withContext(Dispatchers.IO) {
                            try {
                                val file = File(selectedEntry.location)
                                // 限制仅读取 1MB 以下的文件，防止 OOM (Out Of Memory)
                                if (file.exists() && file.length() < 1024 * 1024) {
                                    file.bufferedReader().useLines { lines ->
                                        // 截取前 100 行，避免长文本在 Compose 中渲染卡顿
                                        lines.take(100).joinToString("\n")
                                    }
                                } else if (file.length() >= 1024 * 1024) {
                                    "File too large for preview"
                                } else {
                                    "Preview not available"
                                }
                            } catch (e: Exception) {
                                "Preview not available"
                            }
                        }
                    }

                    Text(
                        text = previewText ?: "",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = LocalOnyxPalette.current.foreground,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.05f))
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}
