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
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.SortDirection
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_task_status_cancelled
import onyx.composeapp.generated.resources.label_task_status_failed
import onyx.composeapp.generated.resources.label_task_status_queued
import onyx.composeapp.generated.resources.label_task_status_running
import onyx.composeapp.generated.resources.label_task_status_succeeded
import org.jetbrains.compose.resources.stringResource
import java.awt.Cursor
import java.nio.file.Path
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
    return stringResource(key, *args.toTypedArray())
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

// ── Breadcrumb ──────────────────────────────────────────────────────────────

internal data class Breadcrumb(
    val label: String,
    val location: String,
)

internal fun buildBreadcrumbs(location: String): List<Breadcrumb> {
    val path = Path.of(location).normalize().toAbsolutePath()
    val breadcrumbs = mutableListOf<Breadcrumb>()
    var current = path.root ?: path
    breadcrumbs += Breadcrumb(
        label = current.toString().ifBlank { "/" },
        location = current.toString().ifBlank { "/" },
    )
    path.iterator().forEach { segment ->
        current = current.resolve(segment)
        breadcrumbs += Breadcrumb(
            label = segment.toString(),
            location = current.toString(),
        )
    }
    return breadcrumbs.distinctBy { it.location }
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

internal fun visibleDetailsColumns(columns: List<DetailsColumn>): List<DetailsColumn> {
    return columns
}

internal fun detailsColumnWeight(
    weights: Map<DetailsColumn, Float>,
    column: DetailsColumn,
): Float {
    return (weights[column] ?: defaultDetailsColumnWeight(column)).coerceAtLeast(0.01f)
}

internal fun defaultDetailsColumnWeight(column: DetailsColumn): Float {
    return when (column) {
        DetailsColumn.NAME -> 0.58f
        DetailsColumn.SIZE -> 0.16f
        DetailsColumn.MODIFIED -> 0.26f
        DetailsColumn.TYPE -> 0.08f
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
        BackgroundTaskStatus.SUCCEEDED -> stringResource(Res.string.label_task_status_succeeded)
        BackgroundTaskStatus.FAILED -> stringResource(Res.string.label_task_status_failed)
        BackgroundTaskStatus.CANCELLED -> stringResource(Res.string.label_task_status_cancelled)
    }
}

internal fun String?.orEmpty(): String = this ?: ""

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
