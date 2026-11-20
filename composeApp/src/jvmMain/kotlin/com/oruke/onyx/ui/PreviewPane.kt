package com.oruke.onyx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.filesystem.ArchiveInfoRequest
import com.oruke.onyx.app.filesystem.ArchiveInfoResult
import com.oruke.onyx.app.filesystem.AudioPreviewPlayer
import com.oruke.onyx.app.filesystem.FileHashRequest
import com.oruke.onyx.app.filesystem.FileHashResult
import com.oruke.onyx.app.filesystem.MediaMetadataResult
import com.oruke.onyx.app.filesystem.NoOpAudioPreviewPlayer
import com.oruke.onyx.app.filesystem.PreviewTextRequest
import com.oruke.onyx.app.filesystem.PreviewTextResult
import com.oruke.onyx.core.model.PreviewPaneMode
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.core.model.isTrashItemLocation
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_preview_close
import onyx.composeapp.generated.resources.label_preview_directory_hint
import onyx.composeapp.generated.resources.label_preview_no_selection
import onyx.composeapp.generated.resources.label_preview_tab_inspector
import onyx.composeapp.generated.resources.label_preview_tab_preview
import onyx.composeapp.generated.resources.label_preview_trash_hint
import onyx.composeapp.generated.resources.label_preview_unsupported_hint
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * 右侧统一「预览与元数据」面板（Preview & Metadata Pane）。
 *
 * - 顶部采用约 29dp 紧凑单行工具栏：左显当前视图名（预览 / 元数据），右放紧凑 Icon + Tooltip 切换与关闭按钮；
 * - 「预览」视图专注内容本身：文本铺满高度支持多编码/换行/截断预览，图片铺满支持双击进查看器，
 *   音频支持专辑封面与手动轻量试听，视频支持 10%/35%/65% 关键帧切换，压缩包展示内嵌封面；
 * - 「元数据」视图分组呈现基础属性、图片尺寸、媒体流参数、归档概况与按需 SHA-256 校验。
 */
@Composable
internal fun PreviewPane(
    selectedEntry: VFile?,
    modifier: Modifier = Modifier,
    activeMode: PreviewPaneMode? = null,
    onSelectMode: ((PreviewPaneMode) -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    loadThumbnail: suspend (String, Int) -> ImageBitmap?,
    loadTextPreview: suspend (PreviewTextRequest) -> PreviewTextResult,
    isImageFileName: (String) -> Boolean,
    isTextPreviewFileName: (String) -> Boolean,
    isAudioFileName: (String) -> Boolean = { false },
    isVideoFileName: (String) -> Boolean = { false },
    peekCachedThumbnail: (String, Int, Long?, Long?) -> ImageBitmap? = { _, _, _, _ -> null },
    loadVersionedThumbnail: (suspend (String, Int, Long?, Long?) -> ImageBitmap?)? = null,
    peekCachedArchiveThumbnail: (String, Int, Long?, Long?) -> ImageBitmap? = { _, _, _, _ -> null },
    loadArchiveThumbnail: (suspend (String, Int, Long?, Long?) -> ImageBitmap?)? = null,
    readImageSize: (suspend (VFile) -> IntSize?)? = null,
    readFileHash: (suspend (FileHashRequest) -> FileHashResult)? = null,
    readArchiveInfo: (suspend (ArchiveInfoRequest) -> ArchiveInfoResult)? = null,
    isArchiveFileName: ((String) -> Boolean)? = null,
    readMediaMetadata: (suspend (VFile) -> MediaMetadataResult)? = null,
    loadVideoFrame: (suspend (VFile, Float, Long?, Int) -> ImageBitmap?)? = null,
    loadAudioCover: (suspend (VFile, Int) -> ImageBitmap?)? = null,
    audioPreviewPlayer: AudioPreviewPlayer = NoOpAudioPreviewPlayer,
    onOpenImageViewer: ((VFile) -> Unit)? = null,
    onOpenExternal: ((VFile) -> Unit)? = null,
) {
    val palette = LocalOnyxPalette.current
    val metadataAvailable = readFileHash != null && readArchiveInfo != null && isArchiveFileName != null
    var localMode by remember { mutableStateOf(activeMode ?: PreviewPaneMode.PREVIEW) }
    val resolvedMode = if (metadataAvailable) {
        activeMode ?: localMode
    } else {
        PreviewPaneMode.PREVIEW
    }
    val handleSelectMode: (PreviewPaneMode) -> Unit = { target ->
        if (target != PreviewPaneMode.PREVIEW) {
            audioPreviewPlayer.stop()
        }
        if (onSelectMode != null) {
            onSelectMode(target)
        } else {
            localMode = target
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.surface),
    ) {
        PreviewPaneHeader(
            activeMode = resolvedMode,
            metadataAvailable = metadataAvailable,
            onSelectMode = handleSelectMode,
            onClose = onClose,
        )
        Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (selectedEntry == null) {
                Text(
                    text = stringResource(Res.string.label_preview_no_selection),
                    color = palette.mutedForeground,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else if (metadataAvailable && resolvedMode == PreviewPaneMode.METADATA) {
                val metadataScroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(metadataScroll)
                        .padding(start = 14.dp, top = 12.dp, end = 18.dp, bottom = 14.dp),
                ) {
                    InspectorDetailsContent(
                        entry = selectedEntry,
                        readFileHash = readFileHash,
                        readArchiveInfo = readArchiveInfo,
                        isArchiveFileName = isArchiveFileName,
                        isImageFileName = isImageFileName,
                        isTextPreviewFileName = isTextPreviewFileName,
                        isAudioFileName = isAudioFileName,
                        isVideoFileName = isVideoFileName,
                        readImageSize = readImageSize,
                        readMediaMetadata = readMediaMetadata,
                    )
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(metadataScroll),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp),
                )
            } else {
                PreviewContentRouter(
                    entry = selectedEntry,
                    loadThumbnail = loadThumbnail,
                    loadVersionedThumbnail = loadVersionedThumbnail,
                    peekCachedThumbnail = peekCachedThumbnail,
                    loadArchiveThumbnail = loadArchiveThumbnail,
                    peekCachedArchiveThumbnail = peekCachedArchiveThumbnail,
                    loadTextPreview = loadTextPreview,
                    readImageSize = readImageSize,
                    readArchiveInfo = readArchiveInfo,
                    isImageFileName = isImageFileName,
                    isTextPreviewFileName = isTextPreviewFileName,
                    isAudioFileName = isAudioFileName,
                    isVideoFileName = isVideoFileName,
                    isArchiveFileName = isArchiveFileName ?: { false },
                    readMediaMetadata = readMediaMetadata,
                    loadVideoFrame = loadVideoFrame,
                    loadAudioCover = loadAudioCover,
                    audioPreviewPlayer = audioPreviewPlayer,
                    onOpenImageViewer = onOpenImageViewer,
                    onOpenExternal = onOpenExternal,
                )
            }
        }
    }
}

/** 渲染约 29dp 紧凑单行顶栏：左显当前视图名，右置「预览 / 元数据」紧凑 Icon + Tooltip 与关闭按钮。 */
@Composable
private fun PreviewPaneHeader(
    activeMode: PreviewPaneMode,
    metadataAvailable: Boolean,
    onSelectMode: (PreviewPaneMode) -> Unit,
    onClose: (() -> Unit)?,
) {
    val palette = LocalOnyxPalette.current
    val previewLabel = stringResource(Res.string.label_preview_tab_preview)
    val metadataLabel = stringResource(Res.string.label_preview_tab_inspector)
    val currentTitle = if (activeMode == PreviewPaneMode.METADATA) metadataLabel else previewLabel

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(29.dp)
            .background(palette.headerBackground)
            .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = currentTitle,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("preview-pane-header-title"),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (metadataAvailable) {
                LayoutIconButton(
                    selected = activeMode == PreviewPaneMode.PREVIEW,
                    onClick = { onSelectMode(PreviewPaneMode.PREVIEW) },
                    tooltip = previewLabel,
                    modifier = Modifier.testTag("preview-sidebar-tab-preview"),
                ) {
                    Icon(
                        key = AllIconsKeys.Actions.Preview,
                        contentDescription = previewLabel,
                        tint = if (activeMode == PreviewPaneMode.PREVIEW) {
                            palette.accent
                        } else {
                            palette.foreground.copy(alpha = 0.78f)
                        },
                        modifier = Modifier.size(14.dp),
                    )
                }
                LayoutIconButton(
                    selected = activeMode == PreviewPaneMode.METADATA,
                    onClick = { onSelectMode(PreviewPaneMode.METADATA) },
                    tooltip = metadataLabel,
                    modifier = Modifier.testTag("preview-sidebar-tab-inspector"),
                ) {
                    MetadataInfoOutlineIcon(
                        tint = if (activeMode == PreviewPaneMode.METADATA) {
                            palette.accent
                        } else {
                            palette.foreground.copy(alpha = 0.78f)
                        },
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            if (onClose != null) {
                if (metadataAvailable) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .width(1.dp)
                            .height(14.dp)
                            .background(palette.outlineVariant),
                    )
                }
                TitleBarIconButton(
                    onClick = onClose,
                    tooltip = stringResource(Res.string.action_preview_close),
                    modifier = Modifier.testTag("preview-sidebar-close-button"),
                ) {
                    Icon(
                        key = AllIconsKeys.Actions.Close,
                        contentDescription = stringResource(Res.string.action_preview_close),
                        tint = palette.foreground.copy(alpha = 0.78f),
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}

/** 单色线框信息图标（空心圆 + 内部 `i`），避免双色 SVG 着色后糊成实心圆点。 */
@Composable
private fun MetadataInfoOutlineIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = (size.minDimension * 0.105f).coerceAtLeast(1.25f)
        val radius = (size.minDimension - strokeWidth) / 2f
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawCircle(
            color = tint,
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = strokeWidth),
        )
        drawCircle(
            color = tint,
            radius = strokeWidth * 0.62f,
            center = Offset(cx, size.height * 0.30f),
        )
        drawLine(
            color = tint,
            start = Offset(cx, size.height * 0.46f),
            end = Offset(cx, size.height * 0.73f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

/** 按互斥文件类型分派内容优先的预览视图。 */
@Composable
private fun PreviewContentRouter(
    entry: VFile,
    loadThumbnail: suspend (String, Int) -> ImageBitmap?,
    loadVersionedThumbnail: (suspend (String, Int, Long?, Long?) -> ImageBitmap?)?,
    peekCachedThumbnail: (String, Int, Long?, Long?) -> ImageBitmap?,
    loadArchiveThumbnail: (suspend (String, Int, Long?, Long?) -> ImageBitmap?)?,
    peekCachedArchiveThumbnail: (String, Int, Long?, Long?) -> ImageBitmap?,
    loadTextPreview: suspend (PreviewTextRequest) -> PreviewTextResult,
    readImageSize: (suspend (VFile) -> IntSize?)?,
    readArchiveInfo: (suspend (ArchiveInfoRequest) -> ArchiveInfoResult)?,
    isImageFileName: (String) -> Boolean,
    isTextPreviewFileName: (String) -> Boolean,
    isAudioFileName: (String) -> Boolean,
    isVideoFileName: (String) -> Boolean,
    isArchiveFileName: (String) -> Boolean,
    readMediaMetadata: (suspend (VFile) -> MediaMetadataResult)?,
    loadVideoFrame: (suspend (VFile, Float, Long?, Int) -> ImageBitmap?)?,
    loadAudioCover: (suspend (VFile, Int) -> ImageBitmap?)?,
    audioPreviewPlayer: AudioPreviewPlayer,
    onOpenImageViewer: ((VFile) -> Unit)?,
    onOpenExternal: ((VFile) -> Unit)?,
) {
    when {
        isTrashItemLocation(entry.location) -> PreviewFallbackCard(
            entry = entry,
            hint = stringResource(Res.string.label_preview_trash_hint),
            onOpenExternal = null,
        )

        entry.kind == VFileKind.DIRECTORY -> PreviewFallbackCard(
            entry = entry,
            hint = stringResource(Res.string.label_preview_directory_hint),
            onOpenExternal = null,
        )

        isImageFileName(entry.name) -> PreviewImageContent(
            entry = entry,
            loadThumbnail = loadThumbnail,
            loadVersionedThumbnail = loadVersionedThumbnail,
            peekCachedThumbnail = peekCachedThumbnail,
            readImageSize = readImageSize,
            onOpenImageViewer = onOpenImageViewer,
            onOpenExternal = onOpenExternal,
        )

        isTextPreviewFileName(entry.name) -> PreviewTextContent(
            entry = entry,
            loadTextPreview = loadTextPreview,
            onOpenExternal = onOpenExternal,
        )

        isAudioFileName(entry.name) -> PreviewAudioContent(
            entry = entry,
            readMediaMetadata = readMediaMetadata ?: { MediaMetadataResult.ToolUnavailable },
            loadAudioCover = loadAudioCover ?: { _, _ -> null },
            audioPlayer = audioPreviewPlayer,
            onOpenExternal = onOpenExternal,
        )

        isVideoFileName(entry.name) -> PreviewVideoContent(
            entry = entry,
            readMediaMetadata = readMediaMetadata ?: { MediaMetadataResult.ToolUnavailable },
            loadVideoFrame = loadVideoFrame ?: { _, _, _, _ -> null },
            onOpenExternal = onOpenExternal,
        )

        isArchiveFileName(entry.name) -> PreviewArchiveContent(
            entry = entry,
            loadArchiveThumbnail = loadArchiveThumbnail,
            peekCachedArchiveThumbnail = peekCachedArchiveThumbnail,
            readArchiveInfo = readArchiveInfo,
            onOpenExternal = onOpenExternal,
        )

        else -> PreviewFallbackCard(
            entry = entry,
            hint = stringResource(Res.string.label_preview_unsupported_hint),
            onOpenExternal = onOpenExternal,
        )
    }
}
