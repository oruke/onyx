package com.oruke.onyx.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.component.FileTransferOperation
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.SortDirection
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_batch_rename
import onyx.composeapp.generated.resources.action_extract_here
import onyx.composeapp.generated.resources.action_extract_smart
import onyx.composeapp.generated.resources.action_extract_to_directory
import onyx.composeapp.generated.resources.label_task_status_cancelled
import onyx.composeapp.generated.resources.label_task_status_failed
import onyx.composeapp.generated.resources.label_task_status_paused
import onyx.composeapp.generated.resources.label_task_status_queued
import onyx.composeapp.generated.resources.label_task_status_running
import onyx.composeapp.generated.resources.label_task_status_succeeded
import onyx.composeapp.generated.resources.msg_archive_password_invalid
import onyx.composeapp.generated.resources.msg_batch_rename_files
import onyx.composeapp.generated.resources.msg_batch_rename_starting
import onyx.composeapp.generated.resources.msg_batch_renamed_files
import onyx.composeapp.generated.resources.msg_cancelled
import onyx.composeapp.generated.resources.msg_copied_items
import onyx.composeapp.generated.resources.msg_copy_failed
import onyx.composeapp.generated.resources.msg_copy_items
import onyx.composeapp.generated.resources.msg_create_folder_failed
import onyx.composeapp.generated.resources.msg_create_folders
import onyx.composeapp.generated.resources.msg_created_folders
import onyx.composeapp.generated.resources.msg_delete_failed
import onyx.composeapp.generated.resources.msg_delete_items
import onyx.composeapp.generated.resources.msg_deleted_items
import onyx.composeapp.generated.resources.msg_extract_failed
import onyx.composeapp.generated.resources.msg_extract_items
import onyx.composeapp.generated.resources.msg_extracted_items
import onyx.composeapp.generated.resources.msg_load_settings_failed
import onyx.composeapp.generated.resources.msg_move_failed
import onyx.composeapp.generated.resources.msg_move_items
import onyx.composeapp.generated.resources.msg_moved_items
import onyx.composeapp.generated.resources.msg_restore_session_failed
import onyx.composeapp.generated.resources.msg_string_literal
import onyx.composeapp.generated.resources.msg_unknown_error
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.awt.Cursor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// ── Constants ───────────────────────────────────────────────────────────────

internal val DetailsColumnGap = 6.dp
internal val PaneDividerHitSlop = 7.dp

// ── I18n Extensions ─────────────────────────────────────────────────────────

@Composable
internal fun I18nMessage.resolve(): String {
    return stringResource(key.toStringResource(), *args.toTypedArray())
}

private fun MessageKey.toStringResource(): StringResource {
    return when (this) {
        MessageKey.ACTION_BATCH_RENAME -> Res.string.action_batch_rename
        MessageKey.ACTION_EXTRACT_HERE -> Res.string.action_extract_here
        MessageKey.ACTION_EXTRACT_TO_DIRECTORY -> Res.string.action_extract_to_directory
        MessageKey.ACTION_EXTRACT_SMART -> Res.string.action_extract_smart
        MessageKey.MSG_BATCH_RENAME_FILES -> Res.string.msg_batch_rename_files
        MessageKey.MSG_BATCH_RENAME_STARTING -> Res.string.msg_batch_rename_starting
        MessageKey.MSG_BATCH_RENAMED_FILES -> Res.string.msg_batch_renamed_files
        MessageKey.MSG_CANCELLED -> Res.string.msg_cancelled
        MessageKey.MSG_COPIED_ITEMS -> Res.string.msg_copied_items
        MessageKey.MSG_COPY_FAILED -> Res.string.msg_copy_failed
        MessageKey.MSG_COPY_ITEMS -> Res.string.msg_copy_items
        MessageKey.MSG_CREATE_FOLDER_FAILED -> Res.string.msg_create_folder_failed
        MessageKey.MSG_CREATE_FOLDERS -> Res.string.msg_create_folders
        MessageKey.MSG_CREATED_FOLDERS -> Res.string.msg_created_folders
        MessageKey.MSG_DELETE_FAILED -> Res.string.msg_delete_failed
        MessageKey.MSG_DELETE_ITEMS -> Res.string.msg_delete_items
        MessageKey.MSG_DELETED_ITEMS -> Res.string.msg_deleted_items
        MessageKey.MSG_EXTRACT_FAILED -> Res.string.msg_extract_failed
        MessageKey.MSG_EXTRACT_ITEMS -> Res.string.msg_extract_items
        MessageKey.MSG_EXTRACTED_ITEMS -> Res.string.msg_extracted_items
        MessageKey.MSG_LOAD_SETTINGS_FAILED -> Res.string.msg_load_settings_failed
        MessageKey.MSG_MOVE_FAILED -> Res.string.msg_move_failed
        MessageKey.MSG_MOVE_ITEMS -> Res.string.msg_move_items
        MessageKey.MSG_MOVED_ITEMS -> Res.string.msg_moved_items
        MessageKey.MSG_RESTORE_SESSION_FAILED -> Res.string.msg_restore_session_failed
        MessageKey.MSG_STRING_LITERAL -> Res.string.msg_string_literal
        MessageKey.MSG_UNKNOWN_ERROR -> Res.string.msg_unknown_error
        MessageKey.MSG_ARCHIVE_PASSWORD_INVALID -> Res.string.msg_archive_password_invalid
    }
}

// ── Drag & Drop models ──────────────────────────────────────────────────────

internal data class TabDropZone(
    val bounds: IntRect,
    val tabIds: List<String>,
    val tabBounds: Map<String, IntRect>,
)

internal data class TabDropTarget(
    val paneId: PaneId,
    val index: Int,
)

internal data class FileDragState(
    val sourcePaneId: PaneId,
    val operation: FileTransferOperation,
    /** true = 用户通过 Ctrl 键强制指定操作，跳过自动卷检测 */
    val userForced: Boolean = false,
)

internal data class FileDropZone(
    val paneId: PaneId,
    val targetDirectoryLocation: String,
    val bounds: IntRect,
    val directoryEntryId: String? = null,
)

internal data class FileDropTarget(
    val paneId: PaneId,
    val targetDirectoryLocation: String,
    val directoryEntryId: String?,
)

// ── Tooltip ─────────────────────────────────────────────────────────────────

internal data class TooltipRequest(
    val owner: Any,
    val text: String,
    val anchorBounds: IntRect,
    val pointerPosition: IntOffset,
)

internal class TooltipController(
    val show: (TooltipRequest) -> Unit,
    val hide: (Any) -> Unit,
)

internal val LocalTooltipController = staticCompositionLocalOf {
    TooltipController(
        show = {},
        hide = {},
    )
}

// ── Formatting helpers ──────────────────────────────────────────────────────

internal fun formatFileSize(sizeBytes: Long?): String {
    if (sizeBytes == null) return "-"
    if (sizeBytes < 1024) return "${sizeBytes} B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = sizeBytes.toDouble()
    var unitIndex = -1
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex += 1
    }
    return String.format("%.1f %s", value, units[unitIndex])
}

internal fun formatModifiedTime(modifiedAtEpochMillis: Long?): String {
    if (modifiedAtEpochMillis == null) return "-"
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    return formatter.format(
        Instant.ofEpochMilli(modifiedAtEpochMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime(),
    )
}

// ── Column helpers ──────────────────────────────────────────────────────────

internal fun visibleDetailsColumns(
    columns: List<DetailsColumn>,
    hiddenColumns: Set<DetailsColumn> = emptySet(),
): List<DetailsColumn> {
    if (hiddenColumns.isEmpty()) return columns
    // NAME 列不可隐藏，确保始终可见
    return columns.filter { it == DetailsColumn.NAME || it !in hiddenColumns }
}

internal fun detailsColumnWidth(
    widths: Map<DetailsColumn, Float>,
    column: DetailsColumn,
): Float {
    return (widths[column] ?: defaultDetailsColumnWidth(column)).coerceAtLeast(40f)
}

internal fun defaultDetailsColumnWidth(column: DetailsColumn): Float {
    return when (column) {
        DetailsColumn.NAME -> 300f
        DetailsColumn.TYPE -> 80f
        DetailsColumn.SIZE -> 100f
        DetailsColumn.MODIFIED -> 180f
    }
}

// ── Pointer icons ───────────────────────────────────────────────────────────

internal fun horizontalResizePointerIcon(): PointerIcon {
    return PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR))
}

internal fun verticalResizePointerIcon(): PointerIcon {
    return PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR))
}

// ── Geometry extensions ─────────────────────────────────────────────────────

internal fun LayoutCoordinates.windowBounds(): IntRect {
    val topLeft = localToWindow(Offset.Zero)
    return IntRect(
        left = topLeft.x.roundToInt(),
        top = topLeft.y.roundToInt(),
        right = topLeft.x.roundToInt() + size.width,
        bottom = topLeft.y.roundToInt() + size.height,
    )
}

internal fun Offset.toIntOffset(): IntOffset {
    return IntOffset(x.roundToInt(), y.roundToInt())
}

internal fun IntRect.containsPoint(position: IntOffset): Boolean {
    return position.x >= left &&
            position.x <= right &&
            position.y >= top &&
            position.y <= bottom
}

internal val IntRect.area: Int
    get() = (width.coerceAtLeast(0)) * (height.coerceAtLeast(0))

internal val FileDropZone.key: String
    get() = if (directoryEntryId == null) {
        "${paneId.name}:__pane"
    } else {
        "${paneId.name}:$directoryEntryId:$targetDirectoryLocation"
    }

internal fun tooltipOffset(
    pointerWindowPosition: IntOffset,
    appWindowOrigin: IntOffset,
    appSize: IntSize,
    tooltipSize: IntSize,
): IntOffset {
    val pointerPosition = IntOffset(
        x = pointerWindowPosition.x - appWindowOrigin.x,
        y = pointerWindowPosition.y - appWindowOrigin.y,
    )
    val width = tooltipSize.width.takeIf { it > 0 } ?: 120
    val height = tooltipSize.height.takeIf { it > 0 } ?: 28
    val rightX = pointerPosition.x + 14
    val leftX = pointerPosition.x - width - 14
    val maxX = (appSize.width - width - 4).coerceAtLeast(4)
    val x = if (rightX <= maxX) rightX else leftX.coerceAtLeast(4)
    val belowY = pointerPosition.y + 18
    val aboveY = pointerPosition.y - height - 12
    val y = if (belowY + height <= appSize.height) belowY else aboveY.coerceAtLeast(4)
    return IntOffset(x, y)
}

internal fun IntRect.centerBottom(): IntOffset {
    return IntOffset(
        x = left + width / 2,
        y = bottom,
    )
}

internal fun TabDropZone.dropIndex(position: IntOffset): Int {
    tabIds.forEachIndexed { index, tabId ->
        val bounds = tabBounds[tabId] ?: return@forEachIndexed
        val centerX = bounds.left + bounds.width / 2
        if (position.x < centerX) {
            return index
        }
    }
    return tabIds.size
}

// ── Sort hint ───────────────────────────────────────────────────────────────

@Composable
internal fun sortHint(
    column: DetailsColumn,
    sort: DetailsSort,
): String? {
    if (sort.column != column) return null
    return when (sort.direction) {
        SortDirection.ASCENDING -> "▲"
        SortDirection.DESCENDING -> "▼"
    }
}

@Composable
internal fun taskStatusLabel(status: BackgroundTaskStatus): String {
    return when (status) {
        BackgroundTaskStatus.QUEUED -> stringResource(Res.string.label_task_status_queued)
        BackgroundTaskStatus.RUNNING -> stringResource(Res.string.label_task_status_running)
        BackgroundTaskStatus.PAUSED -> stringResource(Res.string.label_task_status_paused)
        BackgroundTaskStatus.SUCCEEDED -> stringResource(Res.string.label_task_status_succeeded)
        BackgroundTaskStatus.FAILED -> stringResource(Res.string.label_task_status_failed)
        BackgroundTaskStatus.CANCELLED -> stringResource(Res.string.label_task_status_cancelled)
    }
}

internal fun String?.orEmpty(): String = this ?: ""

/**
 * 判断文件名是否为图片类型。
 * 统一用于 GalleryView 缩略图渲染和其他图片判断场景。
 */
internal fun isImageFile(fileName: String?): Boolean {
    if (fileName == null) return false
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext in setOf("png", "jpg", "jpeg", "gif", "svg", "webp", "bmp", "ico", "tiff", "tif")
}

/**
 * 判断文件名是否为压缩包类型。
 * 用于画廊视图压缩包缩略图渲染。
 */
internal fun isArchiveFile(fileName: String?): Boolean {
    if (fileName == null) return false
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext in setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "lzma", "cab", "cbz", "cbr")
}

/**
 * 根据文件扩展名映射到合适的 Jewel 高清矢量图标 (SVG)。
 * 这是 Onyx 保持 IntelliJ IDEA 视觉风格和高性能滚动的核心手段。
 * 
 * @param fileName 文件名称（包含扩展名）
 * @return 对应的 Jewel IconKey
 */
internal fun fileIconKey(fileName: String?) = when {
    fileName == null -> org.jetbrains.jewel.ui.icons.AllIconsKeys.FileTypes.Any_type
    else -> {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    when (ext) {
        "txt", "md", "log", "csv" -> org.jetbrains.jewel.ui.icons.AllIconsKeys.FileTypes.Text
        "zip", "rar", "7z", "tar", "gz" -> org.jetbrains.jewel.ui.icons.AllIconsKeys.FileTypes.Archive
        "png", "jpg", "jpeg", "gif", "svg", "webp", "bmp" -> org.jetbrains.jewel.ui.icons.AllIconsKeys.FileTypes.Image
        "xml" -> org.jetbrains.jewel.ui.icons.AllIconsKeys.FileTypes.Xml
        "html", "htm" -> org.jetbrains.jewel.ui.icons.AllIconsKeys.FileTypes.Html
        "json" -> org.jetbrains.jewel.ui.icons.AllIconsKeys.FileTypes.Json
        "java", "kt", "class" -> org.jetbrains.jewel.ui.icons.AllIconsKeys.FileTypes.JavaClass
        "js", "ts" -> org.jetbrains.jewel.ui.icons.AllIconsKeys.FileTypes.JavaScript
        "css" -> org.jetbrains.jewel.ui.icons.AllIconsKeys.FileTypes.Css
        "properties", "conf", "ini", "yaml", "yml" -> org.jetbrains.jewel.ui.icons.AllIconsKeys.FileTypes.Config
        else -> org.jetbrains.jewel.ui.icons.AllIconsKeys.FileTypes.Any_type
    }
    }
}

internal fun com.oruke.onyx.app.component.RootState.paneState(paneId: PaneId): com.oruke.onyx.app.component.PaneState {
    return when (paneId) {
        PaneId.PRIMARY -> primaryPane
        PaneId.SECONDARY -> secondaryPane
    }
}
