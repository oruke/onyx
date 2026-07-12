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

/** 预览面板图片请求的最大边长。 */
private const val PREVIEW_THUMBNAIL_DIMENSION = 800


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
                PreviewVisual(selectedEntry, loadThumbnail, isImageFileName)
                Spacer(modifier = Modifier.height(16.dp))
                PreviewMetadata(selectedEntry)
                Spacer(modifier = Modifier.height(16.dp))
                TextFilePreview(selectedEntry, loadTextPreview, isTextPreviewFileName)
            }
        }
    }
}

/**
 * 渲染图片缩略图或通用文件图标。
 *
 * @param entry 当前文件条目。
 * @param loadThumbnail 缩略图加载函数。
 * @param isImageFileName 图片文件名判断函数。
 */
@Composable
private fun PreviewVisual(
    entry: VFile,
    loadThumbnail: suspend (String, Int) -> ImageBitmap?,
    isImageFileName: (String) -> Boolean,
) {
    Box(
        modifier = Modifier.fillMaxWidth().height(200.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (isImageFileName(entry.name)) {
            val (thumbnail, _) = rememberAsyncBitmap(
                entry.location,
                PREVIEW_THUMBNAIL_DIMENSION,
                loadThumbnail,
            )
            thumbnail?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = entry.name,
                    contentScale = ContentScale.Fit,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            val iconKey = if (entry.kind == VFileKind.DIRECTORY) {
                AllIconsKeys.Nodes.Folder
            } else {
                fileIconKey(entry.name)
            }
            Icon(key = iconKey, contentDescription = null, modifier = Modifier.size(96.dp))
        }
    }
}

/**
 * 渲染预览面板中的名称、类型、大小与修改时间。
 *
 * @param entry 当前文件条目。
 */
@Composable
private fun PreviewMetadata(entry: VFile) {
    Text(
        text = entry.name,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = LocalOnyxPalette.current.foreground,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(16.dp))
    val typeValue = if (entry.kind == VFileKind.DIRECTORY) {
        stringResource(Res.string.label_inspector_directory)
    } else {
        entry.name.substringAfterLast(
            '.',
            stringResource(Res.string.label_inspector_file),
        ).uppercase()
    }
    PreviewMetadataText(stringResource(Res.string.label_inspector_type), typeValue)
    if (entry.kind == VFileKind.FILE) {
        PreviewMetadataText(
            stringResource(Res.string.label_inspector_size),
            formatFileSize(entry.sizeBytes),
        )
    }
    PreviewMetadataText(
        stringResource(Res.string.label_inspector_modified),
        formatModifiedTime(entry.modifiedAtEpochMillis),
    )
}

/**
 * 渲染单行预览元数据。
 *
 * @param label 元数据名称。
 * @param value 元数据值。
 */
@Composable
private fun PreviewMetadataText(label: String, value: String) {
    Text(
        text = "$label: $value",
        fontSize = 12.sp,
        color = LocalOnyxPalette.current.mutedForeground,
    )
}

/**
 * 按需读取并渲染文本文件预览。
 *
 * @param entry 当前文件条目。
 * @param loadTextPreview 文本预览读取函数。
 * @param isTextPreviewFileName 文本文件名判断函数。
 */
@Composable
private fun TextFilePreview(
    entry: VFile,
    loadTextPreview: suspend (PreviewTextRequest) -> PreviewTextResult,
    isTextPreviewFileName: (String) -> Boolean,
) {
    if (isTextPreviewFileName(entry.name) && entry.kind == VFileKind.FILE) {
        val loadingText = stringResource(Res.string.label_preview_loading)
        val tooLargeText = stringResource(Res.string.label_preview_too_large)
        val unavailableText = stringResource(Res.string.label_preview_unavailable)
        var previewText by remember(entry.location) { mutableStateOf<String?>(loadingText) }
        var previewFailure by remember(entry.location) { mutableStateOf<I18nMessage?>(null) }
        LaunchedEffect(entry.location) {
            previewFailure = null
            when (val result = loadTextPreview(PreviewTextRequest(entry, 1024 * 1024, 100))) {
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
            text = previewFailure?.resolve() ?: previewText.orEmpty(),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = LocalOnyxPalette.current.foreground,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.05f))
                .padding(8.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}
