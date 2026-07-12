package com.oruke.onyx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.InlineExpandedEntry
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.ui.theme.FileDropZone
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.detailsColumnWidth
import com.oruke.onyx.ui.theme.fileIconKey
import com.oruke.onyx.ui.theme.formatFileSize
import com.oruke.onyx.ui.theme.formatModifiedTime
import com.oruke.onyx.ui.theme.visibleDetailsColumns
import com.oruke.onyx.ui.theme.windowBounds
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_directory_badge
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** 树形明细每一级缩进宽度。 */
private const val INLINE_TREE_DEPTH_INDENT_DP = 20

/**
 * 渲染内联新建行中尚无文件元数据的占位单元格。
 *
 * @param width 单元格宽度。
 */
@Composable
internal fun InlineEditPlaceholderCell(width: Dp) {
    Text(
        text = "-",
        modifier = Modifier.width(width),
        fontSize = 12.sp,
        color = LocalOnyxPalette.current.mutedForeground,
        textAlign = TextAlign.Start,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 扁平化后的树形文件条目。
 */
internal data class FlattenedEntry(
    /** 文件条目。 */
    val entry: VFile,
    /** 树形深度。 */
    val depth: Int,
    /** 当前目录是否展开。 */
    val isExpanded: Boolean,
    /** 当前条目是否允许展开。 */
    val isExpandable: Boolean,
    /** 当前子项是否正在加载。 */
    val isLoading: Boolean,
)

/**
 * 计算详情行的放置目标、选中或斑马纹背景。
 *
 * @param state 详情行显示状态。
 * @return 当前应展示的背景色。
 */
@Composable
internal fun entryRowBackground(state: EntryRowState): Color {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    return when {
        state.fileDropTarget?.directoryEntryId == state.entry.id -> palette.rowHoverBackground
        state.selected && state.paneActive -> palette.selectionBackground
        state.selected -> palette.inactiveSelectionBackground
        state.zebra && appearance.zebraStripeEnabled -> appearance.zebraStripeColor ?: palette.surfaceVariant
        else -> Color.Transparent
    }
}

/**
 * 按当前列顺序显示详情行全部单元格。
 *
 * @param state 详情行显示状态。
 * @param actions 详情行交互回调。
 * @param onNameCoordinates 上报名称区域坐标。
 * @param onExpandCoordinates 上报展开箭头坐标。
 */
@Composable
internal fun EntryRowCells(
    state: EntryRowState,
    actions: EntryRowActions,
    onNameCoordinates: (LayoutCoordinates) -> Unit,
    onExpandCoordinates: (LayoutCoordinates) -> Unit,
) {
    visibleDetailsColumns(state.columns).forEach { column ->
        val width = detailsColumnWidth(state.columnWidths, column).dp
        if (column == DetailsColumn.NAME) {
            EntryNameCell(state, actions, width, onNameCoordinates, onExpandCoordinates)
        } else {
            EntryMetadataCell(column, state.entry, width)
        }
    }
}

/**
 * 显示树形缩进、展开状态、图标和文件名。
 *
 * @param state 详情行显示状态。
 * @param actions 详情行交互回调。
 * @param width 名称列宽度。
 * @param onNameCoordinates 上报名称区域坐标。
 * @param onExpandCoordinates 上报展开箭头坐标。
 */
@Composable
internal fun EntryNameCell(
    state: EntryRowState,
    actions: EntryRowActions,
    width: Dp,
    onNameCoordinates: (LayoutCoordinates) -> Unit,
    onExpandCoordinates: (LayoutCoordinates) -> Unit,
) {
    Row(modifier = Modifier.width(width), verticalAlignment = Alignment.CenterVertically) {
        if (state.depth > 0) Spacer(Modifier.width((state.depth * INLINE_TREE_DEPTH_INDENT_DP).dp))
        Box(
            modifier = Modifier.size(16.dp).onGloballyPositioned(onExpandCoordinates),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.isExpandLoading -> Text(
                    "…",
                    fontSize = 10.sp,
                    color = LocalOnyxPalette.current.disabledForeground,
                )
                state.isExpandable -> Icon(
                    key = if (state.isExpanded) AllIconsKeys.General.ArrowDown else AllIconsKeys.General.ArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
        Spacer(Modifier.width(2.dp))
        EntryNameContent(state, actions, onNameCoordinates, Modifier.weight(1f, fill = false))
    }
}

/**
 * 显示名称列图标与文本，并注册目录放置区域。
 *
 * @param state 详情行显示状态。
 * @param actions 详情行交互回调。
 * @param onNameCoordinates 上报名称区域坐标。
 * @param modifier 名称内容布局修饰符。
 */
@Composable
private fun EntryNameContent(
    state: EntryRowState,
    actions: EntryRowActions,
    onNameCoordinates: (LayoutCoordinates) -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.onGloballyPositioned { coordinates ->
            onNameCoordinates(coordinates)
            if (state.entry.kind == VFileKind.DIRECTORY) {
                actions.onFileDropZoneChange(
                    FileDropZone(
                        state.paneId,
                        state.entry.location,
                        coordinates.windowBounds(),
                        state.entry.id,
                    ),
                )
            }
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            key = if (state.entry.kind == VFileKind.DIRECTORY) {
                AllIconsKeys.Nodes.Folder
            } else {
                fileIconKey(state.entry.name)
            },
            contentDescription = null,
        )
        Text(
            text = state.entry.name,
            fontWeight = if (state.entry.kind == VFileKind.DIRECTORY) FontWeight.Medium else FontWeight.Normal,
            fontSize = LocalOnyxAppearance.current.listFontSize,
            color = LocalOnyxPalette.current.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 显示大小、修改时间或类型元数据单元格。
 *
 * @param column 当前元数据列。
 * @param entry 文件条目。
 * @param width 列宽度。
 */
@Composable
internal fun EntryMetadataCell(column: DetailsColumn, entry: VFile, width: Dp) {
    val text = when (column) {
        DetailsColumn.SIZE -> formatFileSize(entry.sizeBytes)
        DetailsColumn.MODIFIED -> formatModifiedTime(entry.modifiedAtEpochMillis)
        DetailsColumn.TYPE -> if (entry.kind == VFileKind.DIRECTORY) {
            stringResource(Res.string.label_directory_badge)
        } else {
            entry.name.substringAfterLast('.', "").ifEmpty { "-" }
        }
        DetailsColumn.NAME -> return
    }
    Text(
        text = text,
        modifier = Modifier.width(width),
        fontSize = LocalOnyxAppearance.current.listFontSize,
        color = LocalOnyxPalette.current.mutedForeground,
        textAlign = TextAlign.Start,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 将文件条目与已加载展开子项递归扁平化。
 *
 * @param entries 当前层文件条目。
 * @param expandedLocations 已展开目录位置。
 * @param expandedEntries 已加载展开子项。
 * @param depth 当前树形深度。
 * @return 按展示顺序排列的扁平树节点。
 */
internal fun flattenEntries(
    entries: List<VFile>,
    expandedLocations: Set<String>,
    expandedEntries: Map<String, InlineExpandedEntry>,
    depth: Int,
): List<FlattenedEntry> = buildList {
    entries.forEach { entry ->
        val isExpanded = entry.location in expandedLocations
        val expandData = expandedEntries[entry.location]
        add(
            FlattenedEntry(
                entry = entry,
                depth = depth,
                isExpanded = isExpanded,
                isExpandable = entry.kind == VFileKind.DIRECTORY,
                isLoading = isExpanded && expandData?.entries == null,
            ),
        )
        if (isExpanded && expandData?.entries != null) {
            addAll(flattenEntries(expandData.entries, expandedLocations, expandedEntries, depth + 1))
        }
    }
}
