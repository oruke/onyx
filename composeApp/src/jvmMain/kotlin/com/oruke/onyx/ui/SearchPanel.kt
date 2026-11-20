package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.component.SearchNavigationFeedback
import com.oruke.onyx.app.component.SearchNavigationKind
import com.oruke.onyx.app.component.SearchPanelState
import com.oruke.onyx.app.component.SearchStatus
import com.oruke.onyx.app.component.shouldShowSearchHistory
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.rememberOnyxButtonStyle
import com.oruke.onyx.ui.theme.resolve
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_close_window
import onyx.composeapp.generated.resources.action_search_focus_main_window
import onyx.composeapp.generated.resources.action_search_pin_results
import onyx.composeapp.generated.resources.label_search_background_hint
import onyx.composeapp.generated.resources.label_search_cancelled
import onyx.composeapp.generated.resources.label_search_completed_limited_summary
import onyx.composeapp.generated.resources.label_search_completed_summary
import onyx.composeapp.generated.resources.label_search_failed
import onyx.composeapp.generated.resources.label_search_idle
import onyx.composeapp.generated.resources.label_search_running_summary
import onyx.composeapp.generated.resources.msg_search_located_in_pane
import onyx.composeapp.generated.resources.msg_search_opened_directory_in_pane
import onyx.composeapp.generated.resources.msg_search_pinned_collection_in_pane
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalTextStyle
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.outlinedButtonStyle

/** 窗口内一次展开的结构化筛选器类型。 */
internal enum class SearchFilterMenu {
    /** 没有展开的筛选器。 */
    NONE,
    /** 文件与目录类型。 */
    TYPE,
    /** 最近修改时间。 */
    MODIFIED,
    /** 文件大小。 */
    SIZE,
    /** 内容检索开关，不单独占用展开区域。 */
    CONTENT,
}

/** 搜索结果右键菜单的目标信息。 */
internal data class SearchResultContextMenuTarget(
    /** 触发菜单的搜索结果。 */
    val file: VFile,
    /** 菜单在窗口中的锚点坐标。 */
    val offset: IntOffset,
)

/**
 * 渲染完整搜索窗口的内容，查询与选择等持久交互均通过组件状态流转。
 * 顶部设置可滚动，结果独立虚拟化滚动，底部保留固定结果和关闭操作。
 *
 * @param state 当前完整搜索会话状态。
 * @param presentation 搜索历史与路径显示配置。
 * @param actions 发送到所属根组件的语义动作。
 * @param listState 隐藏窗口期间保留的结果滚动状态。
 * @param focusRequestNonce 来自独立搜索窗口层的重新聚焦请求计数（如窗口级 Ctrl+F/Cmd+F）。
 * @param modifier 搜索内容的布局修饰。
 * @return 无返回值。
 */
@Composable
internal fun SearchPanel(
    state: SearchPanelState,
    presentation: SearchPanelPresentation,
    actions: SearchPanelActions,
    listState: LazyListState,
    focusRequestNonce: Long = 0L,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    var contextMenuTarget by remember { mutableStateOf<SearchResultContextMenuTarget?>(null) }
    var activeFilterMenu by remember { mutableStateOf(SearchFilterMenu.NONE) }
    LaunchedEffect(state.activationRequest, focusRequestNonce) { focusRequester.requestFocus() }
    Box(
        modifier
            .fillMaxSize()
            .background(LocalOnyxPalette.current.appBackground)
            .onPreviewKeyEvent { event ->
                if (isSearchFocusInputShortcut(event)) {
                    focusRequester.requestFocus()
                    true
                } else {
                    false
                }
            }
            .testTag("search-window-content"),
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchWindowHeader(state, presentation, actions)
                SearchPanelInputRow(state, state.selectedIndex, actions.onSelectIndex, focusRequester, actions)
                if (state.advancedFiltersExpanded) {
                    SearchAdvancedFilters(
                        filters = state.filters,
                        activeFilterMenu = activeFilterMenu,
                        onToggleFilterMenu = { menu ->
                            activeFilterMenu = if (activeFilterMenu == menu) SearchFilterMenu.NONE else menu
                        },
                        onUpdateFilters = actions.onUpdateFilters,
                    )
                    SearchFilterExpandablePanel(activeFilterMenu, state.filters, actions.onUpdateFilters)
                }
            }
            Divider(Orientation.Horizontal)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.shouldShowSearchHistory() && presentation.searchHistory.isNotEmpty()) {
                    SearchHistoryView(presentation.searchHistory, actions)
                } else {
                    SearchResultsView(
                        state = state,
                        listState = listState,
                        selectedIndex = state.selectedIndex,
                        presentation = presentation,
                        actions = actions,
                        listActions = SearchResultListActions(
                            onSelectIndex = actions.onSelectIndex,
                            onShowContextMenu = { file, offset ->
                                contextMenuTarget = SearchResultContextMenuTarget(file, offset)
                            },
                        ),
                    )
                }
            }
            Divider(Orientation.Horizontal)
            SearchWindowFooter(state, actions)
        }
        SearchResultContextMenu(contextMenuTarget, actions) { contextMenuTarget = null }
    }
}

/**
 * 显示完整搜索的可观察状态、主窗口跳转反馈及固定结果、关闭动作。
 *
 * @param state 当前完整搜索状态。
 * @param actions 发送到所属根组件的语义动作。
 * @return 无返回值。
 */
@Composable
private fun SearchWindowFooter(state: SearchPanelState, actions: SearchPanelActions) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    val buttonStyle = rememberOnyxButtonStyle(JewelTheme.outlinedButtonStyle)
    val status = when (state.status) {
        SearchStatus.IDLE -> stringResource(Res.string.label_search_idle)
        SearchStatus.RUNNING -> stringResource(
            Res.string.label_search_running_summary, state.scannedEntryCount, state.results.size,
        )
        SearchStatus.COMPLETED -> stringResource(
            if (state.limitReached) Res.string.label_search_completed_limited_summary
            else Res.string.label_search_completed_summary,
            state.results.size, state.scannedEntryCount,
        )
        SearchStatus.CANCELLED -> stringResource(Res.string.label_search_cancelled, state.results.size)
        SearchStatus.FAILED -> stringResource(Res.string.label_search_failed, state.error?.resolve().orEmpty())
    }
    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.lastNavigationFeedback?.let { feedback ->
            SearchNavigationFeedbackBanner(
                feedback = feedback,
                onFocusMainWindow = actions.onFocusMainWindow,
            )
        }
        OnyxTooltip(status) {
            Text(
                text = status,
                color = if (state.status == SearchStatus.FAILED) palette.error else palette.mutedForeground,
                fontSize = appearance.headerFontSize,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("search-status"),
            )
        }
        Text(
            text = stringResource(Res.string.label_search_background_hint),
            color = palette.mutedForeground,
            fontSize = appearance.headerFontSize,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = actions.onOpenResultsAsCollection,
                enabled = state.results.isNotEmpty(),
                style = buttonStyle,
                modifier = Modifier.testTag("search-pin-results"),
            ) { Text(stringResource(Res.string.action_search_pin_results), style = LocalTextStyle.current) }
            OutlinedButton(
                onClick = actions.onClose,
                style = buttonStyle,
                modifier = Modifier.testTag("search-window-close"),
            ) { Text(stringResource(Res.string.action_close_window), style = LocalTextStyle.current) }
        }
    }
}

/**
 * 在“打开后保持窗口”模式下展示主窗口目录跳转或定位成功反馈，并提供一键切回主窗口入口。
 */
@Composable
private fun SearchNavigationFeedbackBanner(
    feedback: SearchNavigationFeedback,
    onFocusMainWindow: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    val message = when (feedback.kind) {
        SearchNavigationKind.OPENED_DIRECTORY -> stringResource(
            Res.string.msg_search_opened_directory_in_pane, feedback.targetLabel,
        )
        SearchNavigationKind.LOCATED_IN_DIRECTORY -> stringResource(
            Res.string.msg_search_located_in_pane, feedback.targetLabel,
        )
        SearchNavigationKind.PINNED_COLLECTION -> stringResource(
            Res.string.msg_search_pinned_collection_in_pane, feedback.targetLabel,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(palette.accent.copy(alpha = 0.12f))
            .border(1.dp, palette.accent.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("search-navigation-feedback"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            key = AllIconsKeys.Nodes.Folder,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = palette.accent,
        )
        Text(
            text = message,
            color = palette.foreground,
            fontSize = appearance.headerFontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(Res.string.action_search_focus_main_window),
            color = palette.accent,
            fontSize = appearance.headerFontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onFocusMainWindow)
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .testTag("search-focus-main-window"),
        )
    }
}

/**
 * 判断键盘事件是否为“重新聚焦搜索输入框”快捷键（Ctrl+F / Cmd+F，且未同时按下 Alt 或 Shift）。
 *
 * @param event 待检测的键盘事件。
 * @return 若匹配聚焦快捷键则返回 true。
 */
internal fun isSearchFocusInputShortcut(event: KeyEvent): Boolean {
    return event.type == KeyEventType.KeyDown &&
        event.key == Key.F &&
        (event.isCtrlPressed || event.isMetaPressed) &&
        !event.isAltPressed &&
        !event.isShiftPressed
}
