package com.oruke.onyx.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.filesystem.ArchiveInfoRequest
import com.oruke.onyx.app.filesystem.ArchiveInfoResult
import com.oruke.onyx.app.filesystem.FileHashRequest
import com.oruke.onyx.app.filesystem.FileHashResult
import com.oruke.onyx.app.filesystem.PreviewTextRequest
import com.oruke.onyx.app.filesystem.PreviewTextResult
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.core.model.decodeSfmDisplayText
import com.oruke.onyx.core.model.isTrashItemLocation
import com.oruke.onyx.ui.theme.LocalOnyxPalette
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
import onyx.composeapp.generated.resources.label_preview_tab_inspector
import onyx.composeapp.generated.resources.label_preview_tab_preview
import onyx.composeapp.generated.resources.label_preview_too_large
import onyx.composeapp.generated.resources.label_preview_unavailable
import onyx.composeapp.generated.resources.label_trash_deleted_at
import onyx.composeapp.generated.resources.label_trash_original_location
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

/** 预览面板图片请求的最大边长。 */
private const val PREVIEW_THUMBNAIL_DIMENSION = 800

/** 右侧预览/检查器侧栏的分段页签。 */
internal enum class PreviewSidebarTab {
    PREVIEW,
    INSPECTOR,
}

/**
 * 右侧统一预览与检查器面板（Preview & Inspector Pane）。
 *
 * - 顶部提供「预览 / 检查器」标签页切换（复活原本零入口的权限、归档能力与 SHA-256 检查器）；
 * - 外层支持垂直滚动与可见滚动条，矮屏不裁切；
 * - 图片预览提供加载中与不可用占位提示，且优先同步命中缩略图内存缓存避免闪烁；
 * - 元数据与文本预览均支持文本选择与一键复制。
 *
 * @param selectedEntry 当前活动面板聚焦选中的虚拟文件条目；为空时显示占位提示。
 * @param modifier 外部传入的布局修饰符。
 * @param loadThumbnail 图片缩略图异步加载回调。
 * @param loadTextPreview 文本预览异步加载回调。
 * @param isImageFileName 图片文件名判断函数。
 * @param isTextPreviewFileName 文本文件名判断函数。
 * @param peekCachedThumbnail 可选同步内存缓存查询回调（命中首帧零闪烁）。
 * @param loadVersionedThumbnail 可选带 mtime/size 指纹的缩略图异步加载回调。
 * @param readFileHash 可选文件 SHA-256 读取函数，提供后启用「检查器」标签页。
 * @param readArchiveInfo 可选归档元数据读取函数。
 * @param isArchiveFileName 可选归档文件名判断函数。
 */
@Composable
internal fun PreviewPane(
    selectedEntry: VFile?,
    modifier: Modifier = Modifier,
    loadThumbnail: suspend (String, Int) -> ImageBitmap?,
    loadTextPreview: suspend (PreviewTextRequest) -> PreviewTextResult,
    isImageFileName: (String) -> Boolean,
    isTextPreviewFileName: (String) -> Boolean,
    peekCachedThumbnail: (String, Int, Long?, Long?) -> ImageBitmap? = { _, _, _, _ -> null },
    loadVersionedThumbnail: (suspend (String, Int, Long?, Long?) -> ImageBitmap?)? = null,
    readFileHash: (suspend (FileHashRequest) -> FileHashResult)? = null,
    readArchiveInfo: (suspend (ArchiveInfoRequest) -> ArchiveInfoResult)? = null,
    isArchiveFileName: ((String) -> Boolean)? = null,
) {
    val palette = LocalOnyxPalette.current
    val inspectorAvailable = readFileHash != null && readArchiveInfo != null && isArchiveFileName != null
    var activeTab by remember { mutableStateOf(PreviewSidebarTab.PREVIEW) }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.surface),
    ) {
        if (inspectorAvailable) {
            PreviewSidebarTabStrip(
                activeTab = activeTab,
                onSelectTab = { activeTab = it },
            )
            Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (selectedEntry == null) {
                Text(
                    text = stringResource(Res.string.label_preview_no_selection),
                    color = palette.mutedForeground,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(start = 16.dp, top = 16.dp, end = 20.dp, bottom = 16.dp),
                ) {
                    if (inspectorAvailable && activeTab == PreviewSidebarTab.INSPECTOR) {
                        InspectorDetailsContent(
                            entry = selectedEntry,
                            readFileHash = readFileHash,
                            readArchiveInfo = readArchiveInfo,
                            isArchiveFileName = isArchiveFileName,
                        )
                    } else {
                        PreviewVisual(
                            entry = selectedEntry,
                            loadThumbnail = loadThumbnail,
                            loadVersionedThumbnail = loadVersionedThumbnail,
                            peekCachedThumbnail = peekCachedThumbnail,
                            isImageFileName = isImageFileName,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        PreviewMetadata(selectedEntry)
                        if (!isTrashItemLocation(selectedEntry.location)) {
                            Spacer(modifier = Modifier.height(16.dp))
                            TextFilePreview(selectedEntry, loadTextPreview, isTextPreviewFileName)
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scrollState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp),
                )
            }
        }
    }
}

/** 渲染右侧面板顶部的「预览 / 检查器」分段切换条。 */
@Composable
private fun PreviewSidebarTabStrip(
    activeTab: PreviewSidebarTab,
    onSelectTab: (PreviewSidebarTab) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.headerBackground)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreviewSidebarTabPill(
            label = stringResource(Res.string.label_preview_tab_preview),
            selected = activeTab == PreviewSidebarTab.PREVIEW,
            tag = "preview-sidebar-tab-preview",
            onClick = { onSelectTab(PreviewSidebarTab.PREVIEW) },
            modifier = Modifier.weight(1f),
        )
        PreviewSidebarTabPill(
            label = stringResource(Res.string.label_preview_tab_inspector),
            selected = activeTab == PreviewSidebarTab.INSPECTOR,
            tag = "preview-sidebar-tab-inspector",
            onClick = { onSelectTab(PreviewSidebarTab.INSPECTOR) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PreviewSidebarTabPill(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalOnyxPalette.current
    Box(
        modifier = modifier
            .testTag(tag)
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) palette.accent else palette.surfaceVariant)
            .border(
                width = 1.dp,
                color = if (selected) palette.accent else palette.outlineVariant,
                shape = RoundedCornerShape(5.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) palette.onAccent else palette.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 渲染图片缩略图（带加载中与加载失败占位）或通用文件图标。
 */
@Composable
private fun PreviewVisual(
    entry: VFile,
    loadThumbnail: suspend (String, Int) -> ImageBitmap?,
    loadVersionedThumbnail: (suspend (String, Int, Long?, Long?) -> ImageBitmap?)?,
    peekCachedThumbnail: (String, Int, Long?, Long?) -> ImageBitmap?,
    isImageFileName: (String) -> Boolean,
) {
    val palette = LocalOnyxPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(palette.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, palette.outlineVariant, RoundedCornerShape(6.dp))
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (!isTrashItemLocation(entry.location) && isImageFileName(entry.name)) {
            val versionKey = entry.modifiedAtEpochMillis to entry.sizeBytes
            val loader = remember(entry.modifiedAtEpochMillis, entry.sizeBytes, loadThumbnail, loadVersionedThumbnail) {
                if (loadVersionedThumbnail != null) {
                    suspend { loc: String, dim: Int ->
                        loadVersionedThumbnail(loc, dim, entry.modifiedAtEpochMillis, entry.sizeBytes)
                    }
                } else {
                    loadThumbnail
                }
            }
            val (thumbnail, isLoading) = rememberAsyncBitmap(
                location = entry.location,
                maxDimension = PREVIEW_THUMBNAIL_DIMENSION,
                loader = loader,
                versionKey = versionKey,
                peekCached = { loc, dim ->
                    peekCachedThumbnail(loc, dim, entry.modifiedAtEpochMillis, entry.sizeBytes)
                },
            )
            when {
                thumbnail != null -> Image(
                    bitmap = thumbnail,
                    contentDescription = entry.name,
                    contentScale = ContentScale.Fit,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                    modifier = Modifier.fillMaxSize(),
                )

                isLoading -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OnyxFileIcon(entry = entry, size = 48.dp)
                    Text(
                        text = stringResource(Res.string.label_preview_loading),
                        color = palette.mutedForeground,
                        fontSize = 11.sp,
                    )
                }

                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OnyxFileIcon(entry = entry, size = 56.dp)
                    Text(
                        text = stringResource(Res.string.label_preview_unavailable),
                        color = palette.mutedForeground,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            OnyxFileIcon(
                entry = entry,
                size = 96.dp,
            )
        }
    }
}

/**
 * 渲染可选择复制的预览面板名称、类型、大小与修改时间。
 */
@Composable
private fun PreviewMetadata(entry: VFile) {
    val isTrashEntry = isTrashItemLocation(entry.location)
    val originalParent = entry.parentLocation
    val readableName = entry.name.decodeSfmDisplayText()
    SelectionContainer {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = readableName,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = LocalOnyxPalette.current.foreground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(14.dp))
            val typeValue = if (entry.kind == VFileKind.DIRECTORY) {
                stringResource(Res.string.label_inspector_directory)
            } else {
                readableName.substringAfterLast(
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
                stringResource(if (isTrashEntry) Res.string.label_trash_deleted_at else Res.string.label_inspector_modified),
                formatModifiedTime(entry.modifiedAtEpochMillis),
            )
            if (isTrashEntry && !originalParent.isNullOrBlank()) {
                PreviewMetadataText(stringResource(Res.string.label_trash_original_location), originalParent)
            }
        }
    }
}

@Composable
private fun PreviewMetadataText(label: String, value: String) {
    Text(
        text = "$label: $value",
        fontSize = 12.sp,
        color = LocalOnyxPalette.current.mutedForeground,
    )
}

/**
 * 按需读取并渲染可选择、可一键复制且高度有界的文本文件预览。
 */
@Composable
private fun TextFilePreview(
    entry: VFile,
    loadTextPreview: suspend (PreviewTextRequest) -> PreviewTextResult,
    isTextPreviewFileName: (String) -> Boolean,
) {
    if (isTextPreviewFileName(entry.name) && entry.kind == VFileKind.FILE) {
        val palette = LocalOnyxPalette.current
        val loadingText = stringResource(Res.string.label_preview_loading)
        val tooLargeText = stringResource(Res.string.label_preview_too_large)
        val unavailableText = stringResource(Res.string.label_preview_unavailable)
        var previewText by remember(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
            mutableStateOf<String?>(loadingText)
        }
        var copyableText by remember(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
            mutableStateOf<String?>(null)
        }
        var previewFailure by remember(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
            mutableStateOf<I18nMessage?>(null)
        }
        LaunchedEffect(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
            previewFailure = null
            copyableText = null
            when (val result = loadTextPreview(PreviewTextRequest(entry, 1024 * 1024, 100))) {
                is PreviewTextResult.Text -> {
                    previewText = result.value
                    copyableText = result.value
                }

                PreviewTextResult.TooLarge -> previewText = tooLargeText
                PreviewTextResult.Unavailable -> previewText = unavailableText
                is PreviewTextResult.Failed -> {
                    previewText = null
                    previewFailure = result.reason
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.05f))
                .border(1.dp, palette.outlineVariant, RoundedCornerShape(6.dp)),
        ) {
            if (!copyableText.isNullOrEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.headerBackground.copy(alpha = 0.7f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InlineCopyTextButton(
                        textToCopy = copyableText.orEmpty(),
                        modifier = Modifier.testTag("preview-copy-text-button"),
                    )
                }
                Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
            }
            val textScroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 340.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = previewFailure?.resolve() ?: previewText.orEmpty(),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = palette.foreground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(textScroll)
                            .padding(start = 8.dp, top = 8.dp, end = 14.dp, bottom = 8.dp),
                    )
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(textScroll),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp),
                )
            }
        }
    }
}
