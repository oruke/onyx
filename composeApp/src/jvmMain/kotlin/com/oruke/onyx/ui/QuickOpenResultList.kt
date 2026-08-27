package com.oruke.onyx.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.SearchPanelState
import com.oruke.onyx.app.component.SearchStatus
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.fileIconKey
import kotlin.math.roundToInt
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.quick_open_placeholder
import onyx.composeapp.generated.resources.search_empty_result
import onyx.composeapp.generated.resources.search_scanned_count
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * 在键盘选择变化时，确保当前结果仍位于列表可视范围内。
 *
 * @param selectedIndex 当前选中结果的下标。
 * @param results 当前搜索结果。
 * @param listState 结果列表的滚动状态。
 */
@Composable
internal fun KeepQuickOpenSelectionVisible(
    selectedIndex: Int,
    results: List<VFile>,
    listState: LazyListState,
) {
    LaunchedEffect(selectedIndex) {
        if (results.isNotEmpty() && selectedIndex in results.indices) {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            if (visibleItems.isNotEmpty()) {
                val firstVisibleIndex = visibleItems.first().index
                val lastVisibleIndex = visibleItems.last().index
                when {
                    // 选中项移到可视区上方时，使其成为视口首行。
                    selectedIndex < firstVisibleIndex -> listState.scrollToItem(selectedIndex)
                    // 选中项移到可视区下方时，使其刚好出现在视口底部。
                    selectedIndex > lastVisibleIndex -> {
                        listState.scrollToItem(selectedIndex - (lastVisibleIndex - firstVisibleIndex))
                    }

                    else -> Unit
                }
            }
        }
    }
}

/**
 * 渲染结果区域，并在无结果时展示搜索状态。
 *
 * @param state 当前搜索面板状态。
 * @param listState 结果列表的滚动状态。
 * @param selectedIndex 当前选中结果下标。
 * @param locationLabel 将文件位置转换为可展示文本的函数。
 * @param actions 快速打开面板可发出的组件层动作。
 * @param onShowContextMenu 展示指定结果右键菜单的回调。
 */
@Composable
internal fun QuickOpenResultsView(
    state: SearchPanelState,
    listState: LazyListState,
    selectedIndex: Int,
    locationLabel: (String) -> String,
    actions: QuickOpenPanelActions,
    onShowContextMenu: (Int, VFile, IntOffset) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp),
    ) {
        if (state.results.isEmpty()) {
            QuickOpenEmptyResultsMessage(state)
        } else {
            QuickOpenResultList(
                results = state.results,
                query = state.query,
                listState = listState,
                selectedIndex = selectedIndex,
                locationLabel = locationLabel,
                actions = actions,
                onShowContextMenu = onShowContextMenu,
            )
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
            )
        }
    }
}

/**
 * 渲染快速打开没有结果时的空态、扫描进度或输入提示。
 *
 * @param state 当前搜索面板状态。
 */
@Composable
private fun QuickOpenEmptyResultsMessage(state: SearchPanelState) {
    val message = when {
        state.status == SearchStatus.RUNNING -> {
            stringResource(Res.string.search_scanned_count, state.scannedEntryCount)
        }

        state.query.isNotBlank() -> stringResource(Res.string.search_empty_result)
        else -> stringResource(Res.string.quick_open_placeholder)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = LocalOnyxPalette.current.disabledForeground,
            fontSize = 12.sp,
        )
    }
}

/**
 * 使用虚拟化列表渲染快速打开结果。
 *
 * @param results 当前搜索结果。
 * @param query 当前搜索关键字。
 * @param listState 结果列表的滚动状态。
 * @param selectedIndex 当前选中结果下标。
 * @param locationLabel 将文件位置转换为可展示文本的函数。
 * @param actions 快速打开面板可发出的组件层动作。
 * @param onShowContextMenu 展示指定结果右键菜单的回调。
 */
@Composable
private fun QuickOpenResultList(
    results: List<VFile>,
    query: String,
    listState: LazyListState,
    selectedIndex: Int,
    locationLabel: (String) -> String,
    actions: QuickOpenPanelActions,
    onShowContextMenu: (Int, VFile, IntOffset) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(results, key = { _, entry -> entry.id }) { index, entry ->
            QuickOpenResultRow(
                entry = entry,
                query = query,
                isSelected = index == selectedIndex,
                locationLabel = locationLabel,
                onClick = { actions.onOpenResult(entry) },
                onContextMenu = { file, offset -> onShowContextMenu(index, file, offset) },
            )
        }
    }
}

/**
 * 在后台搜索仍在运行且已有结果时展示已扫描条目数。
 *
 * @param state 当前搜索面板状态。
 */
@Composable
internal fun QuickOpenSearchProgress(state: SearchPanelState) {
    val palette = LocalOnyxPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.search_scanned_count, state.scannedEntryCount),
            fontSize = 11.sp,
            color = palette.mutedForeground,
        )
    }
}

/**
 * 渲染单条快速打开结果，并处理左键打开和右键菜单定位。
 *
 * @param entry 要展示的文件结果。
 * @param query 当前搜索关键字。
 * @param isSelected 当前结果是否被键盘选中。
 * @param locationLabel 将文件位置转换为可展示文本的函数。
 * @param onClick 左键打开结果的回调。
 * @param onContextMenu 右键请求菜单的回调，携带文件和窗口坐标。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun QuickOpenResultRow(
    entry: VFile,
    query: String,
    isSelected: Boolean,
    locationLabel: (String) -> String,
    onClick: () -> Unit,
    onContextMenu: (VFile, IntOffset) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val parent = entry.parentLocation ?: entry.location
    var rowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .onGloballyPositioned { rowCoordinates = it }
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.buttons.isSecondaryPressed) {
                    val pointer = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                    val windowPosition = rowCoordinates?.localToWindow(pointer) ?: pointer
                    onContextMenu(
                        entry,
                        IntOffset(windowPosition.x.roundToInt(), windowPosition.y.roundToInt()),
                    )
                }
            }
            .background(if (isSelected) palette.surfaceVariant else Color.Transparent)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(QuickOpenPanelDefaults.SELECTED_INDICATOR_WIDTH)
                .fillMaxHeight()
                .background(if (isSelected) palette.accent else Color.Transparent),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                key = if (entry.kind == VFileKind.DIRECTORY) {
                    AllIconsKeys.Nodes.Folder
                } else {
                    fileIconKey(entry.name)
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = highlightMatchText(
                    text = entry.name,
                    query = query,
                    normalColor = palette.foreground,
                    highlightColor = palette.accent,
                ),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(QuickOpenPanelDefaults.RESULT_TEXT_COLUMN_WEIGHT),
            )
            Text(
                text = locationLabel(parent),
                color = palette.mutedForeground,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(QuickOpenPanelDefaults.RESULT_TEXT_COLUMN_WEIGHT),
            )
        }
    }
}

/**
 * 将结果名称中首个不区分大小写的关键字匹配片段渲染为强调样式。
 *
 * @param text 要展示的完整文件名。
 * @param query 当前搜索关键字。
 * @param normalColor 非匹配文本的颜色。
 * @param highlightColor 匹配文本的强调颜色。
 * @return 带有匹配片段样式的展示文本。
 */
internal fun highlightMatchText(
    text: String,
    query: String,
    normalColor: Color,
    highlightColor: Color,
): AnnotatedString {
    val trimmedQuery = query.trim()
    val matchIndex = text.lowercase().indexOf(trimmedQuery.lowercase())
    val matchEndIndex = matchIndex + trimmedQuery.length
    return if (
        trimmedQuery.isBlank() ||
        matchIndex == QuickOpenPanelDefaults.NO_MATCH_INDEX
    ) {
        AnnotatedString(text, SpanStyle(color = normalColor))
    } else {
        buildAnnotatedString {
            if (matchIndex > QuickOpenPanelDefaults.INITIAL_SELECTED_INDEX) {
                withStyle(SpanStyle(color = normalColor)) {
                    append(text.substring(QuickOpenPanelDefaults.INITIAL_SELECTED_INDEX, matchIndex))
                }
            }
            withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold)) {
                append(text.substring(matchIndex, matchEndIndex))
            }
            if (matchEndIndex < text.length) {
                withStyle(SpanStyle(color = normalColor)) {
                    append(text.substring(matchEndIndex))
                }
            }
        }
    }
}
