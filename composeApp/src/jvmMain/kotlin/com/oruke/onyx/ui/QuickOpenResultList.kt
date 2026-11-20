package com.oruke.onyx.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.testTag
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
import com.oruke.onyx.core.model.decodeSfmDisplayText
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.fileIconKey
import kotlin.math.roundToInt
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_quick_open_recent_locations
import onyx.composeapp.generated.resources.quick_open_placeholder
import onyx.composeapp.generated.resources.search_empty_result
import onyx.composeapp.generated.resources.search_scanned_count
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** 不参与文件名高亮匹配的结构化过滤前缀。 */
private val QUICK_OPEN_STRUCTURED_FILTER_PREFIXES = setOf(
    "type:",
    "kind:",
    "size:",
    "modified:",
    "mtime:",
    "content:",
    "contains:",
)

/**
 * 在键盘选择或结果集刷新时，确保当前选中项仍位于列表可视范围内并在重置首项时滚动归顶。
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
    KeepQuickOpenIndexVisible(
        selectedIndex = selectedIndex,
        itemCount = results.size,
        firstItemKey = results.firstOrNull()?.id,
        listState = listState,
    )
}

/**
 * 在列表选中索引变化或列表首项内容刷新时保持选中项可见，选中首项时自动滚动归顶。
 *
 * @param selectedIndex 当前选中下标。
 * @param itemCount 当前列表条目数。
 * @param firstItemKey 列表首项稳定标识，用于感知结果集替换。
 * @param listState 列表滚动状态。
 */
@Composable
internal fun KeepQuickOpenIndexVisible(
    selectedIndex: Int,
    itemCount: Int,
    firstItemKey: Any?,
    listState: LazyListState,
) {
    LaunchedEffect(selectedIndex, itemCount, firstItemKey) {
        if (itemCount > 0 && selectedIndex in 0 until itemCount) {
            if (
                selectedIndex == QuickOpenPanelDefaults.INITIAL_SELECTED_INDEX &&
                (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
            ) {
                listState.scrollToItem(QuickOpenPanelDefaults.INITIAL_SELECTED_INDEX)
                return@LaunchedEffect
            }
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            if (visibleItems.isNotEmpty()) {
                val firstVisibleIndex = visibleItems.first().index
                val lastVisibleIndex = visibleItems.last().index
                when {
                    // 选中项移到可视区上方时，使其成为视口首行。
                    selectedIndex < firstVisibleIndex -> listState.scrollToItem(selectedIndex)
                    // 选中项移到可视区下方时，使其刚好出现在视口底部。
                    selectedIndex > lastVisibleIndex -> {
                        listState.scrollToItem((selectedIndex - (lastVisibleIndex - firstVisibleIndex)).coerceAtLeast(0))
                    }

                    else -> Unit
                }
            } else if (selectedIndex > 0) {
                listState.scrollToItem(selectedIndex)
            }
        }
    }
}

/**
 * 渲染结果区域，并在查询为空且有历史记录时展示最近打开位置，其余空态展示搜索状态。
 *
 * @param state 当前搜索面板状态。
 * @param listState 结果列表的滚动状态。
 * @param selectedIndex 当前选中结果下标。
 * @param locationLabel 将文件位置转换为可展示文本的函数。
 * @param actions 快速打开面板可发出的组件层动作。
 * @param onShowContextMenu 展示指定结果右键菜单的回调。
 * @param recentLocations 查询为空时展示的最近位置列表。
 * @param recentListState 最近位置列表滚动状态。
 * @param recentSelectedIndex 最近位置列表当前键盘选中下标。
 * @param onRecentSelectedIndexChange 更新最近位置列表选中下标的回调。
 */
@Composable
internal fun QuickOpenResultsView(
    state: SearchPanelState,
    listState: LazyListState,
    selectedIndex: Int,
    locationLabel: (String) -> String,
    actions: QuickOpenPanelActions,
    onShowContextMenu: (Int, VFile, IntOffset) -> Unit,
    recentLocations: List<String> = emptyList(),
    recentListState: LazyListState = rememberLazyListState(),
    recentSelectedIndex: Int = QuickOpenPanelDefaults.INITIAL_SELECTED_INDEX,
    onRecentSelectedIndexChange: (Int) -> Unit = {},
) {
    val showRecentLocations = state.results.isEmpty() &&
        state.query.isBlank() &&
        state.status != SearchStatus.RUNNING &&
        recentLocations.isNotEmpty()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp),
    ) {
        when {
            showRecentLocations -> {
                QuickOpenRecentLocationsList(
                    recentLocations = recentLocations,
                    listState = recentListState,
                    selectedIndex = recentSelectedIndex,
                    locationLabel = locationLabel,
                    onSelectIndex = onRecentSelectedIndexChange,
                    onOpenLocation = actions.onOpenLocation,
                )
            }

            state.results.isEmpty() -> {
                QuickOpenEmptyResultsMessage(state)
            }

            else -> {
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
}

/**
 * 在 Quick Open 初始空输入状态下展示最近访问位置，替代重复的输入框占位提示并支持键盘/点击直达。
 *
 * @param recentLocations 去重后的最近位置列表。
 * @param listState 最近位置列表滚动状态。
 * @param selectedIndex 当前键盘选中的下标。
 * @param locationLabel 将位置转换为展示标签的函数。
 * @param onSelectIndex 更新选中下标回调。
 * @param onOpenLocation 打开指定位置的回调。
 */
@Composable
private fun QuickOpenRecentLocationsList(
    recentLocations: List<String>,
    listState: LazyListState,
    selectedIndex: Int,
    locationLabel: (String) -> String,
    onSelectIndex: (Int) -> Unit,
    onOpenLocation: (String) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.surfaceVariant.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 5.dp)
                .testTag("quick-open-recent-locations-header"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.label_quick_open_recent_locations),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = palette.mutedForeground,
            )
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("quick-open-recent-locations-list"),
            ) {
                itemsIndexed(recentLocations, key = { _, loc -> loc }) { index, location ->
                    val displayPath = desktopLocationLabel(locationLabel(location))
                    val displayName = quickOpenLocationLeafName(displayPath)
                    val isSelected = index == selectedIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp)
                            .background(if (isSelected) palette.surfaceVariant else Color.Transparent)
                            .clickable {
                                onSelectIndex(index)
                                onOpenLocation(location)
                            }
                            .testTag("quick-open-recent-item-$index"),
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
                                key = AllIconsKeys.Nodes.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = palette.accent,
                            )
                            Text(
                                text = displayName,
                                color = palette.foreground,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(QuickOpenPanelDefaults.RESULT_TEXT_COLUMN_WEIGHT),
                            )
                            Text(
                                text = displayPath,
                                color = palette.mutedForeground,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(QuickOpenPanelDefaults.RESULT_TEXT_COLUMN_WEIGHT),
                            )
                        }
                    }
                }
            }
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
 * 从路径展示标签中提取末级目录名，若为根路径则直接返回完整标签。
 */
private fun quickOpenLocationLeafName(displayPath: String): String {
    val trimmed = displayPath.trimEnd('/', '\\')
    if (trimmed.isEmpty()) return displayPath
    val lastSep = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
    return if (lastSep in 0 until trimmed.lastIndex) trimmed.substring(lastSep + 1) else trimmed
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
            OnyxFileIcon(
                entry = entry,
                size = 16.dp,
            )
            Text(
                text = highlightMatchText(
                    text = entry.name.decodeSfmDisplayText(),
                    query = query,
                    normalColor = palette.foreground,
                    highlightColor = palette.accent,
                    highlightBackground = palette.accent.copy(alpha = 0.14f),
                ),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(QuickOpenPanelDefaults.RESULT_TEXT_COLUMN_WEIGHT),
            )
            Text(
                text = desktopLocationLabel(locationLabel(parent)),
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
 * 将结果名称中所有匹配查询词元（支持多词、引号短语、通配符拆段与扩展名，自动忽略结构化筛选前缀）的片段渲染为强调样式。
 *
 * @param text 要展示的完整文件名。
 * @param query 当前搜索关键字。
 * @param normalColor 非匹配文本的颜色。
 * @param highlightColor 匹配文本的强调颜色。
 * @param highlightBackground 匹配文本的可选底纹背景色。
 * @return 带有合并高亮片段样式的展示文本。
 */
internal fun highlightMatchText(
    text: String,
    query: String,
    normalColor: Color,
    highlightColor: Color,
    highlightBackground: Color = Color.Unspecified,
): AnnotatedString {
    val tokens = extractQuickOpenHighlightTokens(query)
    if (tokens.isEmpty() || text.isEmpty()) {
        return AnnotatedString(text, SpanStyle(color = normalColor))
    }
    val rawRanges = mutableListOf<IntRange>()
    for (token in tokens) {
        var searchFrom = 0
        while (searchFrom < text.length) {
            val found = text.indexOf(token, startIndex = searchFrom, ignoreCase = true)
            if (found == QuickOpenPanelDefaults.NO_MATCH_INDEX) break
            rawRanges += found until (found + token.length)
            searchFrom = found + token.length
        }
    }
    if (rawRanges.isEmpty()) {
        return AnnotatedString(text, SpanStyle(color = normalColor))
    }
    val mergedRanges = mergeOverlappingIntRanges(rawRanges)
    val normalStyle = SpanStyle(color = normalColor)
    val highlightStyle = SpanStyle(
        color = highlightColor,
        background = highlightBackground,
        fontWeight = FontWeight.Bold,
    )
    return buildAnnotatedString {
        var cursor = 0
        for (range in mergedRanges) {
            if (range.first > cursor) {
                withStyle(normalStyle) {
                    append(text.substring(cursor, range.first))
                }
            }
            val endExclusive = (range.last + 1).coerceAtMost(text.length)
            withStyle(highlightStyle) {
                append(text.substring(range.first, endExclusive))
            }
            cursor = endExclusive
        }
        if (cursor < text.length) {
            withStyle(normalStyle) {
                append(text.substring(cursor))
            }
        }
    }
}

/**
 * 从搜索查询中解析参与文件名高亮的词元列表，跳过结构化前缀过滤并拆分通配符片段。
 */
internal fun extractQuickOpenHighlightTokens(query: String): List<String> {
    val rawTokens = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    for (ch in query.trim()) {
        when {
            ch == '"' -> {
                if (inQuotes) {
                    if (current.isNotEmpty()) {
                        rawTokens += current.toString()
                        current.clear()
                    }
                    inQuotes = false
                } else {
                    // 若引号紧跟在结构化前缀（如 content:"hello world"、ext:"tar.gz"）冒号后，保持在同一词元内，
                    // 防止结构化筛选的值短语被拆出并误当作文件名高亮词。
                    val attachedToPrefix = current.isNotEmpty() && current.endsWith(':')
                    if (current.isNotEmpty() && !attachedToPrefix) {
                        rawTokens += current.toString()
                        current.clear()
                    }
                    inQuotes = true
                }
            }

            ch.isWhitespace() && !inQuotes -> {
                if (current.isNotEmpty()) {
                    rawTokens += current.toString()
                    current.clear()
                }
            }

            else -> current.append(ch)
        }
    }
    if (current.isNotEmpty()) rawTokens += current.toString()

    val result = LinkedHashSet<String>()
    for (raw in rawTokens) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) continue
        val lower = trimmed.lowercase()
        if (QUICK_OPEN_STRUCTURED_FILTER_PREFIXES.any { prefix -> lower.startsWith(prefix) }) {
            continue
        }
        val effective = if (lower.startsWith("ext:")) {
            trimmed.substring(4).trim().trim('"')
        } else {
            trimmed
        }
        if (effective.isEmpty()) continue
        // 将通配符（如 *.pdf、report*2026.md）拆分为具体匹配片段进行高亮。
        effective.split('*', '?')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { segment -> result += segment }
    }
    return result.toList()
}

/**
 * 将多个可能重叠或相邻的闭区间按起点排序并合并为互不重叠的递增区间序列。
 */
private fun mergeOverlappingIntRanges(ranges: List<IntRange>): List<IntRange> {
    if (ranges.size <= 1) return ranges
    val sorted = ranges.sortedWith(compareBy<IntRange> { it.first }.thenBy { it.last })
    val merged = ArrayList<IntRange>(sorted.size)
    var currentStart = sorted.first().first
    var currentEnd = sorted.first().last
    for (i in 1 until sorted.size) {
        val next = sorted[i]
        if (next.first <= currentEnd + 1) {
            if (next.last > currentEnd) currentEnd = next.last
        } else {
            merged += currentStart..currentEnd
            currentStart = next.first
            currentEnd = next.last
        }
    }
    merged += currentStart..currentEnd
    return merged
}
