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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.oruke.onyx.app.filesystem.PreviewTextRequest
import com.oruke.onyx.app.filesystem.PreviewTextResult
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.fileIconKey
import com.oruke.onyx.ui.theme.formatFileSize
import com.oruke.onyx.ui.theme.formatModifiedTime
import com.oruke.onyx.ui.theme.resolve
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_inspector_directory
import onyx.composeapp.generated.resources.label_inspector_file
import onyx.composeapp.generated.resources.label_inspector_modified
import onyx.composeapp.generated.resources.label_inspector_size
import onyx.composeapp.generated.resources.label_inspector_type
import onyx.composeapp.generated.resources.label_preview_loading
import onyx.composeapp.generated.resources.label_preview_no_selection
import onyx.composeapp.generated.resources.label_preview_too_large
import onyx.composeapp.generated.resources.label_preview_unavailable
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys


/**
 * 右侧预览面板组件 (Preview Pane)
 *
 * 负责在主面板右侧显示当前选中文件的详细信息与预览。
 * - 支持图片类型的高清大图预览（基于 Coil 异步加载）。
 * - 支持文本、代码类型的只读预览（限制 1MB 大小，截取前 100 行，避免内存溢出）。
 * - 显示基础的元数据信息（名称、类型、大小、修改时间）。
 *
 * @param selectedEntry 当前选中的虚拟文件对象。若为空则显示占位提示。
 * @param modifier 外部传入的布局修饰符。
 */
@Composable
internal fun PreviewPane(
    selectedEntry: VFile?,
    modifier: Modifier = Modifier,
    loadThumbnail: suspend (String, Int) -> ImageBitmap?,
    loadTextPreview: suspend (PreviewTextRequest) -> PreviewTextResult,
    isImageFileName: (String) -> Boolean,
    isTextPreviewFileName: (String) -> Boolean,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LocalOnyxPalette.current.surface)
    ) {
        if (selectedEntry == null) {
            Text(
                text = stringResource(Res.string.label_preview_no_selection),
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
                    val isImage = isImageFileName(selectedEntry.name)
                    if (isImage) {
                        val (thumbnail, _) = rememberAsyncBitmap(selectedEntry.location, 800, loadThumbnail)
                        if (thumbnail != null) {
                            Image(
                                bitmap = thumbnail,
                                contentDescription = selectedEntry.name,
                                contentScale = ContentScale.Fit,
                                filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
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

                val typeLabel = stringResource(Res.string.label_inspector_type)
                val typeValue = if (selectedEntry.kind == VFileKind.DIRECTORY) {
                    stringResource(Res.string.label_inspector_directory)
                } else {
                    selectedEntry.name.substringAfterLast('.', stringResource(Res.string.label_inspector_file)).uppercase()
                }
                Text(
                    text = "$typeLabel: $typeValue",
                    fontSize = 12.sp,
                    color = LocalOnyxPalette.current.mutedForeground
                )

                if (selectedEntry.kind == VFileKind.FILE) {
                    val sizeLabel = stringResource(Res.string.label_inspector_size)
                    Text(
                        text = "$sizeLabel: ${formatFileSize(selectedEntry.sizeBytes)}",
                        fontSize = 12.sp,
                        color = LocalOnyxPalette.current.mutedForeground
                    )
                }

                val modifiedLabel = stringResource(Res.string.label_inspector_modified)
                Text(
                    text = "$modifiedLabel: ${formatModifiedTime(selectedEntry.modifiedAtEpochMillis)}",
                    fontSize = 12.sp,
                    color = LocalOnyxPalette.current.mutedForeground
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 3. 纯文本预览区域
                // 针对常见的文本/代码类型进行安全读取
                val isText = isTextPreviewFileName(selectedEntry.name)

                if (isText && selectedEntry.kind == VFileKind.FILE) {
                    val loadingText = stringResource(Res.string.label_preview_loading)
                    val tooLargeText = stringResource(Res.string.label_preview_too_large)
                    val unavailableText = stringResource(Res.string.label_preview_unavailable)
                    var previewText by remember(selectedEntry.location) { mutableStateOf<String?>(loadingText) }
                    var previewFailure by remember(selectedEntry.location) { mutableStateOf<I18nMessage?>(null) }

                    LaunchedEffect(selectedEntry.location) {
                        previewFailure = null
                        when (
                            val result = loadTextPreview(
                                PreviewTextRequest(
                                    entry = selectedEntry,
                                    maxBytes = 1024 * 1024,
                                    maxLines = 100,
                                ),
                            )
                        ) {
                            is PreviewTextResult.Text -> previewText = result.value
                            PreviewTextResult.TooLarge -> previewText = tooLargeText
                            PreviewTextResult.Unavailable -> previewText = unavailableText
                            is PreviewTextResult.Failed -> {
                                previewText = null
                                previewFailure = result.reason
                            }
                        }
                    }

                    Text(
                        text = previewFailure?.resolve() ?: previewText ?: "",
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
