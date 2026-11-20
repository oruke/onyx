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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.filesystem.ArchiveInfoRequest
import com.oruke.onyx.app.filesystem.ArchiveInfoResult
import com.oruke.onyx.app.filesystem.FileHashRequest
import com.oruke.onyx.app.filesystem.FileHashResult
import com.oruke.onyx.app.filesystem.MediaMetadataResult
import com.oruke.onyx.core.model.PaneInspectorState
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.core.model.decodeSfmDisplayText
import com.oruke.onyx.core.model.isTrashItemLocation
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.formatModifiedTime
import kotlinx.coroutines.delay
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_copy
import onyx.composeapp.generated.resources.label_copied_brief
import onyx.composeapp.generated.resources.label_inspector_directory
import onyx.composeapp.generated.resources.label_inspector_file
import onyx.composeapp.generated.resources.label_inspector_location
import onyx.composeapp.generated.resources.label_inspector_modified
import onyx.composeapp.generated.resources.label_inspector_permissions
import onyx.composeapp.generated.resources.label_inspector_size
import onyx.composeapp.generated.resources.label_inspector_type
import onyx.composeapp.generated.resources.label_inspector_unknown
import onyx.composeapp.generated.resources.label_list_separator
import onyx.composeapp.generated.resources.label_metadata_section_basic
import onyx.composeapp.generated.resources.label_permission_delete
import onyx.composeapp.generated.resources.label_permission_list
import onyx.composeapp.generated.resources.label_permission_read
import onyx.composeapp.generated.resources.label_permission_rename
import onyx.composeapp.generated.resources.label_permission_write
import onyx.composeapp.generated.resources.label_preview_no_selection
import onyx.composeapp.generated.resources.label_trash_deleted_at
import onyx.composeapp.generated.resources.label_trash_original_location
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** 检查器图片预览请求的最大边长。 */
private const val INSPECTOR_THUMBNAIL_DIMENSION = 480

/** 一键复制后展示“已复制”短反馈的毫秒时长。 */
private const val INSPECTOR_COPIED_FEEDBACK_MS = 1_500L

/**
 * 独立元数据面板，支持外层垂直滚动与可见滚动条。
 */
@Composable
internal fun InspectorPanel(
    entry: VFile?,
    state: PaneInspectorState,
    loadThumbnail: suspend (String, Int) -> ImageBitmap?,
    readFileHash: suspend (FileHashRequest) -> FileHashResult,
    readArchiveInfo: suspend (ArchiveInfoRequest) -> ArchiveInfoResult,
    isImageFileName: (String) -> Boolean,
    isArchiveFileName: (String) -> Boolean,
) {
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(260.dp)
            .background(LocalOnyxPalette.current.surfaceVariant),
    ) {
        if (entry == null) {
            InspectorEmptyState()
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(start = 14.dp, top = 12.dp, end = 18.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.previewVisible) {
                    InspectorPreview(entry, loadThumbnail, isImageFileName)
                }
                if (state.detailsVisible) {
                    InspectorDetailsContent(
                        entry = entry,
                        readFileHash = readFileHash,
                        readArchiveInfo = readArchiveInfo,
                        isArchiveFileName = isArchiveFileName,
                        isImageFileName = isImageFileName,
                    )
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp),
            )
        }
    }
}

@Composable
private fun InspectorEmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(Res.string.label_preview_no_selection),
            color = LocalOnyxPalette.current.mutedForeground,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun InspectorPreview(
    entry: VFile,
    loadThumbnail: suspend (String, Int) -> ImageBitmap?,
    isImageFileName: (String) -> Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(LocalOnyxPalette.current.surface, RoundedCornerShape(8.dp))
            .border(1.dp, LocalOnyxPalette.current.outlineVariant, RoundedCornerShape(8.dp))
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (!isTrashItemLocation(entry.location) && isImageFileName(entry.name)) {
            val (thumbnail, _) = rememberAsyncBitmap(
                location = entry.location,
                maxDimension = INSPECTOR_THUMBNAIL_DIMENSION,
                loader = loadThumbnail,
                versionKey = entry.modifiedAtEpochMillis to entry.sizeBytes,
            )
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = entry.name,
                    contentScale = ContentScale.Fit,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                OnyxFileIcon(entry = entry, size = 64.dp)
            }
        } else {
            OnyxFileIcon(entry = entry, size = 64.dp)
        }
    }
}

/**
 * 渲染分组结构化元数据视图：基础信息、图片尺寸、音视频流参数、压缩包概况与按需 SHA-256 校验。
 *
 * 与 [PreviewPane] 保持完全一致的互斥文件类型路由（如文本文件不会误触发音视频媒体探测）。
 */
@Composable
internal fun InspectorDetailsContent(
    entry: VFile,
    readFileHash: suspend (FileHashRequest) -> FileHashResult,
    readArchiveInfo: suspend (ArchiveInfoRequest) -> ArchiveInfoResult,
    isArchiveFileName: (String) -> Boolean,
    isImageFileName: (String) -> Boolean = { false },
    isTextPreviewFileName: (String) -> Boolean = { false },
    isAudioFileName: (String) -> Boolean = { false },
    isVideoFileName: (String) -> Boolean = { false },
    readImageSize: (suspend (VFile) -> IntSize?)? = null,
    readMediaMetadata: (suspend (VFile) -> MediaMetadataResult)? = null,
) {
    val palette = LocalOnyxPalette.current
    val isTrashEntry = isTrashItemLocation(entry.location)
    val readableName = entry.name.decodeSfmDisplayText()
    val locationValue = entry.parentLocation ?: stringResource(Res.string.label_inspector_unknown)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OnyxFileIcon(entry = entry, size = 20.dp)
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(
                    text = readableName,
                    color = palette.foreground,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        MetadataSection(title = stringResource(Res.string.label_metadata_section_basic)) {
            val typeDisplay = if (entry.kind == VFileKind.DIRECTORY) {
                stringResource(Res.string.label_inspector_directory)
            } else {
                val ext = readableName.substringAfterLast('.', "")
                if (ext.isNotBlank() && ext != readableName) {
                    "${stringResource(Res.string.label_inspector_file)} (${ext.uppercase()})"
                } else {
                    stringResource(Res.string.label_inspector_file)
                }
            }
            InspectorDetailRow(
                label = stringResource(Res.string.label_inspector_type),
                value = typeDisplay,
                compact = true,
            )
            if (entry.kind != VFileKind.DIRECTORY) {
                InspectorDetailRow(
                    label = stringResource(Res.string.label_inspector_size),
                    value = formatExactFileSize(entry.sizeBytes),
                    compact = true,
                )
            }
            val modifiedLabel = if (isTrashEntry) {
                Res.string.label_trash_deleted_at
            } else {
                Res.string.label_inspector_modified
            }
            val locationLabel = if (isTrashEntry) {
                Res.string.label_trash_original_location
            } else {
                Res.string.label_inspector_location
            }
            InspectorDetailRow(
                label = stringResource(modifiedLabel),
                value = formatModifiedTime(entry.modifiedAtEpochMillis),
                compact = true,
            )
            InspectorDetailRow(
                label = stringResource(locationLabel),
                value = locationValue,
                copyValue = entry.parentLocation?.takeIf { it.isNotBlank() },
            )
            InspectorDetailRow(
                label = stringResource(Res.string.label_inspector_permissions),
                value = inspectorPermissionValue(entry),
                compact = true,
            )
        }

        if (!isTrashEntry && entry.kind == VFileKind.FILE && isImageFileName(entry.name) && readImageSize != null) {
            MetadataImageSection(entry = entry, readImageSize = readImageSize)
        }

        if (!isTrashEntry &&
            entry.kind == VFileKind.FILE &&
            !isImageFileName(entry.name) &&
            !isTextPreviewFileName(entry.name) &&
            (isAudioFileName(entry.name) || isVideoFileName(entry.name)) &&
            readMediaMetadata != null
        ) {
            MetadataMediaSection(entry = entry, readMediaMetadata = readMediaMetadata)
        }

        if (!isTrashEntry && entry.kind == VFileKind.FILE && isArchiveFileName(entry.name)) {
            MetadataArchiveSection(entry = entry, readArchiveInfo = readArchiveInfo)
        }

        if (!isTrashEntry && entry.kind == VFileKind.FILE) {
            MetadataChecksumSection(entry = entry, readFileHash = readFileHash)
        }
    }
}

@Composable
private fun inspectorPermissionValue(entry: VFile): String {
    val capabilities = entry.capabilities
    val labels = buildList {
        if (VFileCapability.LIST_CHILDREN in capabilities) {
            add(stringResource(Res.string.label_permission_list))
        }
        if (VFileCapability.READ_CONTENT in capabilities) {
            add(stringResource(Res.string.label_permission_read))
        }
        if (VFileCapability.WRITE_CONTENT in capabilities) {
            add(stringResource(Res.string.label_permission_write))
        }
        if (VFileCapability.RENAME in capabilities) {
            add(stringResource(Res.string.label_permission_rename))
        }
        if (VFileCapability.DELETE in capabilities) {
            add(stringResource(Res.string.label_permission_delete))
        }
    }
    return labels
        .ifEmpty { listOf(stringResource(Res.string.label_inspector_unknown)) }
        .joinToString(separator = stringResource(Res.string.label_list_separator))
}

/**
 * 渲染元数据单项属性行，支持文本选择、可选的一键复制按钮及单行紧凑键值排版。
 *
 * @param label 属性标签名称。
 * @param value 属性展示文本。
 * @param copyValue 非空时在标签行右侧提供一键复制按钮。
 * @param monospace 是否使用等宽字体展示（如 SHA-256 摘要）。
 * @param compact 为 `true` 且不含复制按钮时采用单行左键右值高密度排版。
 */
@Composable
internal fun InspectorDetailRow(
    label: String,
    value: String,
    copyValue: String? = null,
    monospace: Boolean = false,
    compact: Boolean = false,
) {
    val palette = LocalOnyxPalette.current
    if (compact && copyValue.isNullOrBlank()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = palette.mutedForeground,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd,
            ) {
                SelectionContainer {
                    Text(
                        text = value,
                        color = palette.foreground,
                        fontSize = 11.5.sp,
                        fontFamily = if (monospace) FontFamily.Monospace else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = palette.mutedForeground,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            if (!copyValue.isNullOrBlank()) {
                InlineCopyTextButton(textToCopy = copyValue)
            }
        }
        SelectionContainer {
            Text(
                text = value,
                color = palette.foreground,
                fontSize = 12.sp,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 轻量内联一键复制按钮，点击后写入系统剪贴板并短暂提示“已复制”。
 *
 * @param textToCopy 待复制文本。
 * @param modifier 可选布局修饰符。
 */
@Composable
internal fun InlineCopyTextButton(
    textToCopy: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalOnyxPalette.current
    val clipboard = LocalClipboardManager.current
    var copiedToken by remember { mutableStateOf(0) }
    var recentlyCopied by remember { mutableStateOf(false) }

    LaunchedEffect(copiedToken) {
        if (copiedToken > 0) {
            recentlyCopied = true
            delay(INSPECTOR_COPIED_FEEDBACK_MS)
            recentlyCopied = false
        }
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable {
                clipboard.setText(AnnotatedString(textToCopy))
                copiedToken += 1
            }
            .padding(horizontal = 5.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            key = AllIconsKeys.Actions.Copy,
            contentDescription = stringResource(Res.string.action_copy),
            tint = if (recentlyCopied) palette.success else palette.foreground.copy(alpha = 0.78f),
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = stringResource(if (recentlyCopied) Res.string.label_copied_brief else Res.string.action_copy),
            color = if (recentlyCopied) palette.success else palette.mutedForeground,
            fontSize = 11.sp,
        )
    }
}
