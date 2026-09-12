package com.oruke.onyx.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.component.PaneCommand
import com.oruke.onyx.app.component.PaneContextMenuCommand
import com.oruke.onyx.app.component.PaneContextMenuIcon
import com.oruke.onyx.app.component.PaneContextMenuModel
import com.oruke.onyx.app.component.PaneContextMenuNode
import com.oruke.onyx.app.component.PaneContextMenuText
import com.oruke.onyx.app.component.SearchPanelState
import com.oruke.onyx.app.component.SearchStatus
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.fileIconKey
import com.oruke.onyx.ui.theme.formatFileSize
import com.oruke.onyx.ui.theme.formatModifiedTime
import com.oruke.onyx.ui.theme.resolve
import kotlin.math.roundToInt
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_open_in_folder
import onyx.composeapp.generated.resources.search_empty_result
import onyx.composeapp.generated.resources.search_failed
import onyx.composeapp.generated.resources.search_scanned_count
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * 渲染搜索结果的右键菜单并将菜单命令映射为搜索动作。
 *
 * @param target 当前菜单锚点及其对应的搜索结果；为空时不渲染菜单。
 * @param actions 面板发往组件层的用户动作。
 * @param onDismiss 关闭菜单并清理内部状态。
 */
@Composable
internal fun BoxScope.SearchResultContextMenu(
    target: SearchResultContextMenuTarget?,
    actions: SearchPanelActions,
    onDismiss: () -> Unit,
) {
    target ?: return
    val openInFolderText = stringResource(Res.string.action_open_in_folder)
    val menuModel = remember(target.file, openInFolderText) {
        PaneContextMenuModel(
            nodes = listOf(
                PaneContextMenuNode.Item(
                    id = "open",
                    text = PaneContextMenuText.OPEN,
                    icon = PaneContextMenuIcon.OPEN,
                    command = PaneContextMenuCommand.Pane(PaneCommand.OPEN_SELECTION),
                ),
                PaneContextMenuNode.Item(
                    id = "open_in_folder",
                    displayName = openInFolderText,
                    icon = PaneContextMenuIcon.FOLDER,
                    command = PaneContextMenuCommand.CopyPath,
                ),
            ),
        )
    }
    PaneContextMenu(
        anchorOffset = target.offset,
        model = menuModel,
        onCommand = { command ->
            when (command) {
                is PaneContextMenuCommand.Pane -> actions.onOpenResult(target.file)
                PaneContextMenuCommand.CopyPath -> actions.onOpenResultInFolder(target.file)
                else -> Unit
            }
            onDismiss()
        },
        onClose = onDismiss,
    )
}

/**
 * 根据当前状态展示搜索空态或按目录分组的结果列表。
 *
 * @param state 组件层提供的当前搜索状态。
 * @return 无返回值。
 * @param listState 搜索结果列表滚动状态。
 * @param selectedIndex 当前键盘导航选中的结果索引。
 * @param presentation 面板展示配置。
 * @param actions 面板发往组件层的用户动作。
 * @param listActions 结果列表的局部交互回调。
 */
@Composable
internal fun SearchResultsView(
    state: SearchPanelState,
    listState: LazyListState,
    selectedIndex: Int,
    presentation: SearchPanelPresentation,
    actions: SearchPanelActions,
    listActions: SearchResultListActions,
) {
    if (state.results.isEmpty()) {
        SearchResultsEmptyState(state = state)
    } else {
        SearchResultsList(
            state = state,
            listState = listState,
            selectedIndex = selectedIndex,
            presentation = presentation,
            actions = actions,
            listActions = listActions,
        )
    }
}

/**
 * 渲染搜索进行中、失败或无结果时的空态提示。
 *
 * @param state 组件层提供的当前搜索状态。
 */
@Composable
private fun SearchResultsEmptyState(state: SearchPanelState) {
    val palette = LocalOnyxPalette.current
    val message = when (state.status) {
        SearchStatus.IDLE -> ""
        SearchStatus.RUNNING -> stringResource(Res.string.search_scanned_count, state.scannedEntryCount)
        SearchStatus.FAILED -> stringResource(
            Res.string.search_failed,
            state.error?.resolve() ?: "",
        )
        else -> stringResource(Res.string.search_empty_result)
    }
    Box(
        modifier = Modifier.fillMaxSize().background(palette.appBackground).testTag("search-results"),
        contentAlignment = Alignment.Center,
    ) {
        if (message.isNotEmpty()) {
            Text(
                text = message,
                color = if (state.status == SearchStatus.FAILED) {
                    palette.error
                } else {
                    palette.disabledForeground
                },
                fontSize = LocalOnyxAppearance.current.listFontSize,
            )
        }
    }
}

/**
 * 按父目录分组渲染搜索结果并保证键盘选中项可见。
 *
 * @param state 组件层提供的当前搜索状态。
 * @param listState 搜索结果列表滚动状态。
 * @param selectedIndex 当前键盘导航选中的结果索引。
 * @param presentation 面板展示配置。
 * @param actions 面板发往组件层的用户动作。
 * @param listActions 结果列表的局部交互回调。
 */
@Composable
private fun SearchResultsList(
    state: SearchPanelState,
    listState: LazyListState,
    selectedIndex: Int,
    presentation: SearchPanelPresentation,
    actions: SearchPanelActions,
    listActions: SearchResultListActions,
) {
    val palette = LocalOnyxPalette.current
    val focusRequester = remember { FocusRequester() }
    val focusedListActions = listActions.copy(onSelectIndex = { index ->
        listActions.onSelectIndex(index)
        focusRequester.requestFocus()
    })
    val groupedResults = remember(state.results) {
        state.results.groupBy { it.parentLocation ?: it.location }
    }
    val lazyColumnIndex = remember(state.results, selectedIndex, groupedResults) {
        searchResultLazyColumnIndex(
            results = state.results,
            groupedResults = groupedResults,
            selectedIndex = selectedIndex,
        )
    }

    // 只在选中条目改变时定位，追加结果或隐藏重开不能将用户手动滚动的位置拉回选中行。
    LaunchedEffect(state.results.getOrNull(selectedIndex)?.id) {
        listState.revealSearchResult(lazyColumnIndex)
    }

    Box(modifier = Modifier.fillMaxSize().background(palette.appBackground)) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 10.dp)
                .testTag("search-results")
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    // 焦点始终由列表承接，方向键切换后 Enter 必须打开组件当前选中项。
                    handleSearchQueryKeyEvent(event, state, selectedIndex, listActions.onSelectIndex, false, actions)
                }
                .focusable(),
        ) {
            groupedResults.forEach { (parentPath, entries) ->
                searchResultGroup(
                    parentPath = parentPath,
                    entries = entries,
                    results = state.results,
                    query = state.query,
                    selectedIndex = selectedIndex,
                    presentation = presentation,
                    actions = actions,
                    listActions = focusedListActions,
                )
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/**
 * 计算选中搜索结果在分组 LazyColumn 中对应的行索引。
 *
 * @param results 搜索结果原始顺序。
 * @param groupedResults 按父目录分组后的搜索结果。
 * @param selectedIndex 当前键盘导航选中的结果索引。
 * @return LazyColumn 中的行索引；没有选中结果时返回负数。
 */
private fun searchResultLazyColumnIndex(
    results: List<VFile>,
    groupedResults: Map<String, List<VFile>>,
    selectedIndex: Int,
): Int {
    val target = results.getOrNull(selectedIndex) ?: return -1
    var lazyIndex = 0
    var resolvedIndex = -1
    for (entries in groupedResults.values) {
        lazyIndex += 1
        val indexInGroup = entries.indexOfFirst { it.id == target.id }
        if (indexInGroup >= 0) {
            resolvedIndex = lazyIndex + indexInGroup
            break
        }
        lazyIndex += entries.size
    }
    return resolvedIndex
}

/**
 * 将指定的搜索结果行滚动到当前可视区域内。
 *
 * @param targetIndex 目标结果在 LazyColumn 中的行索引。
 * @return 无返回值。
 */
private suspend fun LazyListState.revealSearchResult(targetIndex: Int) {
    if (targetIndex < 0) return
    val layout = layoutInfo
    val visibleItem = layout.visibleItemsInfo.firstOrNull { it.index == targetIndex }
    if (visibleItem == null) {
        scrollToItem(targetIndex)
    } else {
        // 最后一行即使只有少量像素露出也算 visible，需把整行移入视口才能看清键盘选中项。
        val overflow = when {
            visibleItem.offset < layout.viewportStartOffset -> visibleItem.offset - layout.viewportStartOffset
            visibleItem.offset + visibleItem.size > layout.viewportEndOffset ->
                visibleItem.offset + visibleItem.size - layout.viewportEndOffset
            else -> 0
        }
        if (overflow != 0) scrollBy(overflow.toFloat())
    }
}

/**
 * 向搜索结果 LazyColumn 写入一个父目录分组及其结果行。
 *
 * @param parentPath 当前分组的父目录位置。
 * @param entries 当前分组内的搜索结果。
 * @param results 搜索结果原始顺序。
 * @param query 当前搜索关键词。
 * @param selectedIndex 当前键盘导航选中的结果索引。
 * @param presentation 面板展示配置。
 * @param actions 面板发往组件层的用户动作。
 * @param listActions 结果列表的局部交互回调。
 */
private fun LazyListScope.searchResultGroup(
    parentPath: String,
    entries: List<VFile>,
    results: List<VFile>,
    query: String,
    selectedIndex: Int,
    presentation: SearchPanelPresentation,
    actions: SearchPanelActions,
    listActions: SearchResultListActions,
) {
    item(key = "header_$parentPath") {
        SearchResultGroupHeader(
            parentPath = parentPath,
            entryCount = entries.size,
            locationLabel = presentation.locationLabel,
        )
    }
    items(entries, key = { entry -> entry.id }) { entry ->
        val globalIndex = results.indexOfFirst { it.id == entry.id }
        SearchResultRow(
            entry = entry,
            query = query,
            isSelected = globalIndex == selectedIndex,
            onSelect = { listActions.onSelectIndex(globalIndex) },
            onOpen = {
                listActions.onSelectIndex(globalIndex)
                actions.onOpenResult(entry)
            },
            onContextMenu = { offset ->
                listActions.onSelectIndex(globalIndex)
                listActions.onShowContextMenu(entry, offset)
            },
        )
    }
}

/**
 * 渲染搜索结果父目录分组的表头。
 *
 * @param parentPath 当前分组的父目录位置。
 * @param entryCount 当前分组内的搜索结果数量。
 * @param locationLabel 将底层位置标识转换为可显示目录名称的函数。
 * @return 无返回值。
 */
@Composable
private fun SearchResultGroupHeader(
    parentPath: String,
    entryCount: Int,
    locationLabel: (String) -> String,
) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(appearance.headerHeight)
            .background(palette.headerBackground)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            key = AllIconsKeys.Nodes.Folder,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = palette.accent,
        )
        Text(
            text = locationLabel(parentPath),
            color = palette.foreground,
            fontSize = appearance.headerFontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = entryCount.toString(),
            fontSize = appearance.headerFontSize,
            color = palette.mutedForeground,
            modifier = Modifier
                .background(palette.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/**
 * 渲染单条搜索结果；单击仅选中，双击打开，右键保留选中后显示菜单。
 *
 * @param entry 当前搜索结果。
 * @param query 当前搜索关键词，用于高亮名称命中片段。
 * @param isSelected 当前结果是否被键盘导航选中。
 * @param onSelect 将当前结果设为组件选中项。
 * @param onOpen 打开当前搜索结果。
 * @param onContextMenu 在指定窗口坐标显示右键菜单。
 * @return 无返回值。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SearchResultRow(
    entry: VFile,
    query: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onContextMenu: (IntOffset) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    var isHovered by remember { mutableStateOf(false) }
    var rowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val backgroundColor = when {
        isSelected -> palette.selectionBackground
        isHovered -> palette.rowHoverBackground
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(appearance.listRowHeight)
            .testTag("search-result-${entry.id}")
            .semantics { selected = isSelected }
            .onGloballyPositioned { rowCoordinates = it }
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false }
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.buttons.isSecondaryPressed) {
                    val pointer = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                    val windowPos = rowCoordinates?.localToWindow(pointer) ?: pointer
                    onContextMenu(IntOffset(windowPos.x.roundToInt(), windowPos.y.roundToInt()))
                }
            }
            .background(backgroundColor)
            .combinedClickable(onClick = onSelect, onDoubleClick = onOpen)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val iconKey = if (entry.kind == VFileKind.DIRECTORY) {
            AllIconsKeys.Nodes.Folder
        } else {
            fileIconKey(entry.name)
        }
        Icon(
            key = iconKey,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = highlightMatchText(
                text = entry.name,
                query = query,
                normalColor = if (isSelected) palette.selectionForeground else palette.foreground,
                highlightColor = if (isSelected) palette.selectionForeground else palette.foreground,
            ),
            fontSize = appearance.listFontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatFileSize(entry.sizeBytes),
            color = palette.mutedForeground,
            fontSize = appearance.headerFontSize,
            maxLines = 1,
        )
        Text(
            text = formatModifiedTime(entry.modifiedAtEpochMillis),
            color = palette.mutedForeground,
            fontSize = appearance.headerFontSize,
            maxLines = 1,
        )
    }
}
