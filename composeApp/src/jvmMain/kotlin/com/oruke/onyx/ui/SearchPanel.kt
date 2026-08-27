package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.SearchPanelState
import com.oruke.onyx.app.component.SearchStatus
import com.oruke.onyx.app.component.shouldShowSearchHistory
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.resolve
import com.oruke.onyx.ui.theme.verticalResizePointerIcon
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.search_failed
import onyx.composeapp.generated.resources.search_result_count
import onyx.composeapp.generated.resources.search_scanned_count
import onyx.composeapp.generated.resources.search_truncated_to
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/** 拖拽手柄高度（dp）。 */
private const val DRAG_HANDLE_HEIGHT_DP = 16

/** 搜索抽屉允许的最小高度比例。 */
private const val SEARCH_DRAWER_MIN_HEIGHT_FRACTION = 0.2f

/** 搜索抽屉允许的最大高度比例。 */
private const val SEARCH_DRAWER_MAX_HEIGHT_FRACTION = 0.8f

/** 面板过滤下拉类型。 */
internal enum class SearchFilterMenu {
    NONE,
    TYPE,
    MODIFIED,
    SIZE,
    CONTENT,
}

/**
 * 搜索结果右键菜单的目标信息。
 */
internal data class SearchResultContextMenuTarget(
    /** 触发菜单的搜索结果。 */
    val file: VFile,
    /** 菜单在窗口中的锚点坐标。 */
    val offset: IntOffset,
)

/**
 * 搜索面板内部的瞬时交互状态。
 *
 * 该状态仅描述当前 Composable 生命周期内的菜单、焦点导航和右键菜单位置，不替代组件层的可持久化搜索状态。
 */
private class SearchPanelInteractionState {
    /** 当前展开的筛选器菜单。 */
    var activeFilterMenu by mutableStateOf(SearchFilterMenu.NONE)

    /** 搜索面板父容器的像素高度，用于换算拖拽增量。 */
    var parentHeightPx by mutableStateOf(1f)

    /** 当前键盘导航选中的搜索结果索引。 */
    var selectedIndex by mutableIntStateOf(0)

    /** 当前待展示右键菜单的搜索结果与窗口坐标。 */
    var contextMenuTarget by mutableStateOf<SearchResultContextMenuTarget?>(null)

    /**
     * 记录需要展示右键菜单的结果位置。
     *
     * @param file 触发菜单的搜索结果。
     * @param offset 菜单在窗口中的锚点坐标。
     */
    fun showResultContextMenu(file: VFile, offset: IntOffset) {
        contextMenuTarget = SearchResultContextMenuTarget(file = file, offset = offset)
    }

    /** 清理右键菜单目标并关闭菜单。 */
    fun clearContextMenu() {
        contextMenuTarget = null
    }
}

/**
 * 渲染搜索面板并连接展示配置与用户动作。
 *
 * @param state 组件层提供的当前搜索状态。
 * @param presentation 面板展示配置。
 * @param actions 面板发往组件层的用户动作。
 * @param modifier 应用于面板外层容器的修饰符。
 */
@Composable
internal fun SearchPanel(
    state: SearchPanelState,
    presentation: SearchPanelPresentation,
    actions: SearchPanelActions,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val interaction = remember { SearchPanelInteractionState() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    LaunchedEffect(state.results.size) {
        val lastIndex = state.results.lastIndex.coerceAtLeast(0)
        interaction.selectedIndex = interaction.selectedIndex.coerceIn(0, lastIndex)
    }

    Box(modifier = modifier.fillMaxWidth()) {
        SearchPanelContent(
            state = state,
            presentation = presentation,
            actions = actions,
            interaction = interaction,
            focusRequester = focusRequester,
            listState = listState,
        )
        SearchResultContextMenu(
            target = interaction.contextMenuTarget,
            actions = actions,
            onDismiss = interaction::clearContextMenu,
        )
    }
}

/**
 * 渲染搜索面板的主体结构。
 *
 * @param state 组件层提供的当前搜索状态。
 * @param presentation 面板展示配置。
 * @param actions 面板发往组件层的用户动作。
 * @param interaction 面板内部瞬时交互状态。
 * @param focusRequester 搜索输入框焦点请求器。
 * @param listState 搜索结果列表滚动状态。
 */
@Composable
private fun SearchPanelContent(
    state: SearchPanelState,
    presentation: SearchPanelPresentation,
    actions: SearchPanelActions,
    interaction: SearchPanelInteractionState,
    focusRequester: FocusRequester,
    listState: LazyListState,
) {
    val palette = LocalOnyxPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(
                presentation.drawerHeightFraction.coerceIn(
                    SEARCH_DRAWER_MIN_HEIGHT_FRACTION,
                    SEARCH_DRAWER_MAX_HEIGHT_FRACTION,
                ),
            )
            .onGloballyPositioned { coordinates ->
                interaction.parentHeightPx = (
                    coordinates.parentCoordinates?.size?.height?.toFloat() ?: 1f
                ).coerceAtLeast(1f)
            }
            .background(palette.surface.copy(alpha = 0.80f))
            .border(1.dp, palette.outlineVariant)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SearchPanelResizeHandle(
            drawerHeightFraction = presentation.drawerHeightFraction,
            parentHeightPx = interaction.parentHeightPx,
            onSetDrawerHeight = actions.onSetDrawerHeight,
        )
        SearchPanelHeaderRow(
            scope = state.scope,
            filters = state.filters,
            activeFilterMenu = interaction.activeFilterMenu,
            onToggleFilterMenu = { menu ->
                interaction.activeFilterMenu = if (interaction.activeFilterMenu == menu) {
                    SearchFilterMenu.NONE
                } else {
                    menu
                }
            },
            onUpdateScope = actions.onUpdateScope,
            onUpdateFilters = actions.onUpdateFilters,
            onClose = actions.onClose,
        )
        SearchFilterExpandablePanel(
            activeMenu = interaction.activeFilterMenu,
            filters = state.filters,
            onUpdateFilters = actions.onUpdateFilters,
        )
        SearchPanelInputRow(
            state = state,
            selectedIndex = interaction.selectedIndex,
            onSelectIndex = { interaction.selectedIndex = it },
            focusRequester = focusRequester,
            actions = actions,
        )
        SearchPanelBody(
            state = state,
            presentation = presentation,
            actions = actions,
            listState = listState,
            selectedIndex = interaction.selectedIndex,
            onSelectIndex = { interaction.selectedIndex = it },
            onShowContextMenu = interaction::showResultContextMenu,
        )
        SearchPanelStatusBar(state = state)
    }
}

/**
 * 渲染用于调整搜索抽屉高度的拖拽手柄。
 *
 * @param drawerHeightFraction 当前抽屉高度比例。
 * @param parentHeightPx 父容器的像素高度。
 * @param onSetDrawerHeight 保存新的抽屉高度比例。
 */
@Composable
private fun SearchPanelResizeHandle(
    drawerHeightFraction: Float,
    parentHeightPx: Float,
    onSetDrawerHeight: (Float) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DRAG_HANDLE_HEIGHT_DP.dp)
            .pointerHoverIcon(verticalResizePointerIcon())
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    val deltaFraction = dragAmount / parentHeightPx
                    val newFraction = (drawerHeightFraction - deltaFraction).coerceIn(
                        SEARCH_DRAWER_MIN_HEIGHT_FRACTION,
                        SEARCH_DRAWER_MAX_HEIGHT_FRACTION,
                    )
                    onSetDrawerHeight(newFraction)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(palette.outlineVariant),
        )
    }
}

/**
 * 根据查询状态在搜索历史和搜索结果之间切换。
 *
 * @param state 组件层提供的当前搜索状态。
 * @param presentation 面板展示配置。
 * @param actions 面板发往组件层的用户动作。
 * @param listState 搜索结果列表滚动状态。
 * @param selectedIndex 当前键盘导航选中的结果索引。
 * @param onSelectIndex 变更键盘导航选中项。
 * @param onShowContextMenu 显示搜索结果右键菜单。
 * @receiver 面板主体所在的 ColumnScope。
 */
@Composable
private fun ColumnScope.SearchPanelBody(
    state: SearchPanelState,
    presentation: SearchPanelPresentation,
    actions: SearchPanelActions,
    listState: LazyListState,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    onShowContextMenu: (VFile, IntOffset) -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
    ) {
        if (state.shouldShowSearchHistory() && presentation.searchHistory.isNotEmpty()) {
            SearchHistoryView(history = presentation.searchHistory, actions = actions)
        } else {
            SearchResultsView(
                state = state,
                listState = listState,
                selectedIndex = selectedIndex,
                presentation = presentation,
                actions = actions,
                listActions = SearchResultListActions(
                    onSelectIndex = onSelectIndex,
                    onShowContextMenu = onShowContextMenu,
                ),
            )
        }
    }
}

/**
 * 渲染搜索扫描进度、结果数量及错误提示。
 *
 * @param state 组件层提供的当前搜索状态。
 */
@Composable
private fun SearchPanelStatusBar(state: SearchPanelState) {
    val palette = LocalOnyxPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(Res.string.search_scanned_count, state.scannedEntryCount),
                color = palette.mutedForeground,
                fontSize = 10.sp,
            )
            if (state.status == SearchStatus.RUNNING) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(palette.accent),
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.status == SearchStatus.FAILED) {
                val errorMessage = state.error?.resolve().orEmpty()
                Text(
                    text = stringResource(Res.string.search_failed) + ": " + errorMessage,
                    color = palette.error,
                    fontSize = 10.sp,
                )
            } else {
                Text(
                    text = stringResource(Res.string.search_result_count, state.results.size),
                    color = palette.mutedForeground,
                    fontSize = 10.sp,
                )
                if (state.limitReached) {
                    Text(
                        text = stringResource(Res.string.search_truncated_to, state.results.size),
                        color = palette.favorite,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}
