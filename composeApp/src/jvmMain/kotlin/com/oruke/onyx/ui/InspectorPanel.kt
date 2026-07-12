package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.oruke.onyx.app.filesystem.ArchiveInfoRequest
import com.oruke.onyx.app.filesystem.ArchiveInfoResult
import com.oruke.onyx.app.filesystem.FileHashRequest
import com.oruke.onyx.app.filesystem.FileHashResult
import com.oruke.onyx.core.model.PaneInspectorState
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.formatFileSize
import com.oruke.onyx.ui.theme.formatModifiedTime
import com.oruke.onyx.ui.theme.resolve
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_inspector_directory
import onyx.composeapp.generated.resources.label_inspector_file
import onyx.composeapp.generated.resources.label_inspector_location
import onyx.composeapp.generated.resources.label_inspector_modified
import onyx.composeapp.generated.resources.label_inspector_permissions
import onyx.composeapp.generated.resources.label_inspector_archive_capabilities
import onyx.composeapp.generated.resources.label_inspector_archive_encrypted
import onyx.composeapp.generated.resources.label_inspector_sha256
import onyx.composeapp.generated.resources.label_inspector_size
import onyx.composeapp.generated.resources.label_inspector_type
import onyx.composeapp.generated.resources.label_inspector_unknown
import onyx.composeapp.generated.resources.label_archive_capability_browse
import onyx.composeapp.generated.resources.label_archive_capability_extract
import onyx.composeapp.generated.resources.label_archive_capability_read_only
import onyx.composeapp.generated.resources.label_archive_encrypted_no
import onyx.composeapp.generated.resources.label_archive_encrypted_yes
import onyx.composeapp.generated.resources.label_list_separator
import onyx.composeapp.generated.resources.label_permission_delete
import onyx.composeapp.generated.resources.label_permission_list
import onyx.composeapp.generated.resources.label_permission_read
import onyx.composeapp.generated.resources.label_permission_rename
import onyx.composeapp.generated.resources.label_permission_write
import onyx.composeapp.generated.resources.label_preview_loading
import onyx.composeapp.generated.resources.label_preview_too_large
import onyx.composeapp.generated.resources.label_preview_unavailable
import onyx.composeapp.generated.resources.label_preview_no_selection
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** 检查器图片预览请求的最大边长。 */
private const val INSPECTOR_THUMBNAIL_DIMENSION = 480


// ── Inspector Panel ─────────────────────────────────────────────────────────

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
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(260.dp)
            .background(LocalOnyxPalette.current.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (entry == null) {
            InspectorEmptyState()
        } else {
            if (state.previewVisible) {
                InspectorPreview(entry, loadThumbnail, isImageFileName)
            }
            if (state.detailsVisible) {
                InspectorDetails(entry, readFileHash, readArchiveInfo, isArchiveFileName)
            }
        }
    }
}

/** 渲染检查器未选择文件时的空状态。 */
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

/**
 * 渲染检查器图片或文件类型预览。
 *
 * @param entry 当前文件条目。
 * @param loadThumbnail 缩略图加载函数。
 * @param isImageFileName 图片文件名判断函数。
 */
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
        if (isImageFileName(entry.name)) {
            val (thumbnail, _) = rememberAsyncBitmap(
                entry.location,
                INSPECTOR_THUMBNAIL_DIMENSION,
                loadThumbnail,
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
                Icon(AllIconsKeys.FileTypes.Image, null, Modifier.size(64.dp))
            }
        } else {
            val icon = if (entry.kind == VFileKind.DIRECTORY) {
                AllIconsKeys.Nodes.Folder
            } else {
                AllIconsKeys.FileTypes.Any_type
            }
            Icon(icon, null, Modifier.size(64.dp))
        }
    }
}

/**
 * 渲染检查器文件属性、压缩包能力与哈希信息。
 *
 * @param entry 当前文件条目。
 * @param readFileHash 文件哈希读取函数。
 * @param readArchiveInfo 压缩包元数据读取函数。
 * @param isArchiveFileName 压缩包文件名判断函数。
 */
@Composable
private fun InspectorDetails(
    entry: VFile,
    readFileHash: suspend (FileHashRequest) -> FileHashResult,
    readArchiveInfo: suspend (ArchiveInfoRequest) -> ArchiveInfoResult,
    isArchiveFileName: (String) -> Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = entry.name,
            color = LocalOnyxPalette.current.foreground,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
        InspectorDetailRow(
            label = stringResource(Res.string.label_inspector_type),
            value = if (entry.kind == VFileKind.DIRECTORY) {
                stringResource(Res.string.label_inspector_directory)
            } else {
                stringResource(Res.string.label_inspector_file)
            },
        )
        if (entry.kind != VFileKind.DIRECTORY) {
            InspectorDetailRow(stringResource(Res.string.label_inspector_size), formatFileSize(entry.sizeBytes))
        }
        InspectorDetailRow(
            stringResource(Res.string.label_inspector_modified),
            formatModifiedTime(entry.modifiedAtEpochMillis),
        )
        InspectorDetailRow(
            stringResource(Res.string.label_inspector_location),
            entry.parentLocation ?: stringResource(Res.string.label_inspector_unknown),
        )
        InspectorDetailRow(
            stringResource(Res.string.label_inspector_permissions),
            inspectorPermissionValue(entry),
        )
        InspectorArchiveDetails(entry, readArchiveInfo, isArchiveFileName)
        InspectorHashDetails(entry, readFileHash)
    }
}

/**
 * 按需渲染压缩包加密与能力信息。
 *
 * @param entry 当前文件条目。
 * @param readArchiveInfo 压缩包元数据读取函数。
 * @param isArchiveFileName 压缩包文件名判断函数。
 */
@Composable
private fun InspectorArchiveDetails(
    entry: VFile,
    readArchiveInfo: suspend (ArchiveInfoRequest) -> ArchiveInfoResult,
    isArchiveFileName: (String) -> Boolean,
) {
    if (entry.kind == VFileKind.FILE && isArchiveFileName(entry.name)) {
        var result by remember(entry.location) { mutableStateOf<ArchiveInfoResult?>(null) }
        LaunchedEffect(entry.location) { result = readArchiveInfo(ArchiveInfoRequest(entry)) }
        InspectorDetailRow(
            stringResource(Res.string.label_inspector_archive_encrypted),
            archiveEncryptionValue(result),
        )
        InspectorDetailRow(
            stringResource(Res.string.label_inspector_archive_capabilities),
            archiveCapabilityValue(result),
        )
    }
}

/**
 * 按需读取并渲染普通文件的 SHA-256。
 *
 * @param entry 当前文件条目。
 * @param readFileHash 文件哈希读取函数。
 */
@Composable
private fun InspectorHashDetails(
    entry: VFile,
    readFileHash: suspend (FileHashRequest) -> FileHashResult,
) {
    if (entry.kind == VFileKind.FILE) {
        var result by remember(entry.location) { mutableStateOf<FileHashResult?>(null) }
        LaunchedEffect(entry.location) {
            result = readFileHash(FileHashRequest(entry = entry, maxBytes = 64L * 1024L * 1024L))
        }
        val value = when (val current = result) {
            null -> stringResource(Res.string.label_preview_loading)
            is FileHashResult.Hash -> current.value
            FileHashResult.TooLarge -> stringResource(Res.string.label_preview_too_large)
            FileHashResult.Unavailable -> stringResource(Res.string.label_preview_unavailable)
            is FileHashResult.Failed -> current.reason.resolve()
        }
        InspectorDetailRow(stringResource(Res.string.label_inspector_sha256), value)
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

@Composable
private fun archiveEncryptionValue(result: ArchiveInfoResult?): String {
    return when (result) {
        null -> stringResource(Res.string.label_preview_loading)
        is ArchiveInfoResult.Info -> if (result.encrypted) {
            stringResource(Res.string.label_archive_encrypted_yes)
        } else {
            stringResource(Res.string.label_archive_encrypted_no)
        }

        ArchiveInfoResult.Unavailable -> stringResource(Res.string.label_preview_unavailable)
        is ArchiveInfoResult.Failed -> result.reason.resolve()
    }
}

@Composable
private fun archiveCapabilityValue(result: ArchiveInfoResult?): String {
    return when (result) {
        null -> stringResource(Res.string.label_preview_loading)
        is ArchiveInfoResult.Info -> buildList {
            if (result.canBrowse) {
                add(stringResource(Res.string.label_archive_capability_browse))
            }
            if (result.canExtract) {
                add(stringResource(Res.string.label_archive_capability_extract))
            }
            if (!result.canWrite) {
                add(stringResource(Res.string.label_archive_capability_read_only))
            }
        }.joinToString(separator = stringResource(Res.string.label_list_separator))
        ArchiveInfoResult.Unavailable -> stringResource(Res.string.label_preview_unavailable)
        is ArchiveInfoResult.Failed -> result.reason.resolve()
    }
}

@Composable
internal fun InspectorDetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            color = LocalOnyxPalette.current.mutedForeground,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            color = LocalOnyxPalette.current.foreground,
            fontSize = 12.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }
}
