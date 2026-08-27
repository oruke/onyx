package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.SearchPanelState
import com.oruke.onyx.app.component.SearchStatus
import com.oruke.onyx.app.component.hasExecutableSearchCriteria
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_cancel_task
import onyx.composeapp.generated.resources.action_clear_search_history
import onyx.composeapp.generated.resources.action_search
import onyx.composeapp.generated.resources.quick_open_hint
import onyx.composeapp.generated.resources.search_history_label
import onyx.composeapp.generated.resources.search_placeholder
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** 搜索历史区域最多展示的查询数量。 */
private const val SEARCH_HISTORY_DISPLAY_LIMIT = 8

/**
 * 渲染搜索关键词输入、执行按钮及取消入口。
 *
 * @param state 组件层提供的当前搜索状态。
 * @param selectedIndex 当前键盘导航选中的结果索引。
 * @param onSelectIndex 变更键盘导航选中项。
 * @param focusRequester 搜索输入框焦点请求器。
 * @param actions 面板发往组件层的用户动作。
 */
@Composable
internal fun SearchPanelInputRow(
    state: SearchPanelState,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    focusRequester: FocusRequester,
    actions: SearchPanelActions,
) {
    val canSearch = state.hasExecutableSearchCriteria() && state.status != SearchStatus.RUNNING
    Row(
        modifier = Modifier.fillMaxWidth().height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchQueryTextField(
            state = state,
            selectedIndex = selectedIndex,
            onSelectIndex = onSelectIndex,
            focusRequester = focusRequester,
            actions = actions,
        )
        SearchExecuteButton(enabled = canSearch, onSearch = actions.onSearch)
        SearchQuickOpenHint()
        if (state.status == SearchStatus.RUNNING) {
            SearchToolbarButton(
                enabled = true,
                onClick = actions.onCancel,
                label = Res.string.action_cancel_task,
                icon = AllIconsKeys.Actions.Close,
            )
        }
    }
}

/**
 * 渲染搜索关键词输入框及键盘导航逻辑。
 *
 * @param state 组件层提供的当前搜索状态。
 * @param selectedIndex 当前键盘导航选中的结果索引。
 * @param onSelectIndex 变更键盘导航选中项。
 * @param focusRequester 搜索输入框焦点请求器。
 * @param actions 面板发往组件层的用户动作。
 * @receiver 输入行所在的 RowScope。
 */
@Composable
private fun RowScope.SearchQueryTextField(
    state: SearchPanelState,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    focusRequester: FocusRequester,
    actions: SearchPanelActions,
) {
    val palette = LocalOnyxPalette.current
    val canSearch = state.hasExecutableSearchCriteria() && state.status != SearchStatus.RUNNING
    BasicTextField(
        value = state.query,
        onValueChange = actions.onQueryChange,
        modifier = Modifier
            .weight(1f)
            .height(32.dp)
            .focusRequester(focusRequester)
            .background(palette.inputBackground, RoundedCornerShape(4.dp))
            .border(1.dp, palette.outlineVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .onPreviewKeyEvent { event ->
                handleSearchQueryKeyEvent(
                    event = event,
                    state = state,
                    selectedIndex = selectedIndex,
                    onSelectIndex = onSelectIndex,
                    canSearch = canSearch,
                    actions = actions,
                )
            },
        enabled = state.status != SearchStatus.RUNNING,
        textStyle = TextStyle(fontSize = 12.sp, color = palette.foreground),
        singleLine = true,
        cursorBrush = SolidColor(palette.accent),
        decorationBox = { innerTextField ->
            SearchQueryTextFieldDecoration(
                query = state.query,
                innerTextField = innerTextField,
            )
        },
    )
}

/**
 * 处理搜索输入框中的键盘导航、打开与取消命令。
 *
 * @param event 当前键盘事件。
 * @param state 组件层提供的当前搜索状态。
 * @param selectedIndex 当前键盘导航选中的结果索引。
 * @param onSelectIndex 变更键盘导航选中项。
 * @param canSearch 当前是否允许执行新的搜索。
 * @param actions 面板发往组件层的用户动作。
 * @return 本次事件是否已由搜索输入框处理。
 */
private fun handleSearchQueryKeyEvent(
    event: androidx.compose.ui.input.key.KeyEvent,
    state: SearchPanelState,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    canSearch: Boolean,
    actions: SearchPanelActions,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.DirectionDown -> {
            if (state.results.isNotEmpty()) {
                onSelectIndex((selectedIndex + 1).coerceAtMost(state.results.size - 1))
            }
            true
        }

        Key.DirectionUp -> {
            if (state.results.isNotEmpty()) {
                onSelectIndex((selectedIndex - 1).coerceAtLeast(0))
            }
            true
        }

        Key.Enter -> {
            val selected = state.results.getOrNull(selectedIndex)
            when {
                selected == null && canSearch -> actions.onSearch()
                selected != null && (event.isCtrlPressed || event.isMetaPressed) -> {
                    actions.onOpenResultInFolder(selected)
                }
                selected != null -> actions.onOpenResult(selected)
            }
            true
        }

        Key.Escape -> {
            if (state.status == SearchStatus.RUNNING) actions.onCancel() else actions.onClose()
            true
        }

        else -> false
    }
}

/**
 * 渲染搜索输入框的图标、占位文本与内部文本内容。
 *
 * @param query 当前输入的搜索关键词。
 * @param innerTextField Compose 提供的内部文本输入内容。
 */
@Composable
private fun SearchQueryTextFieldDecoration(
    query: String,
    innerTextField: @Composable () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            key = AllIconsKeys.Actions.Find,
            contentDescription = null,
            modifier = Modifier.padding(end = 6.dp).size(14.dp),
            tint = palette.mutedForeground,
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(Res.string.search_placeholder),
                    fontSize = 11.sp,
                    color = palette.disabledForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            innerTextField()
        }
    }
}

/**
 * 渲染用于执行搜索的主操作按钮。
 *
 * @param enabled 当前是否允许执行搜索。
 * @param onSearch 执行搜索的用户动作。
 */
@Composable
private fun SearchExecuteButton(enabled: Boolean, onSearch: () -> Unit) {
    val palette = LocalOnyxPalette.current
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Brush.linearGradient(colors = listOf(palette.accent, palette.accentVariant)))
            .clickable(enabled = enabled, onClick = onSearch)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.action_search),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 渲染 Quick Open 快捷键提示。 */
@Composable
private fun SearchQuickOpenHint() {
    val palette = LocalOnyxPalette.current
    Text(
        text = stringResource(Res.string.quick_open_hint),
        fontSize = 10.sp,
        color = palette.disabledForeground,
        modifier = Modifier
            .background(palette.surfaceVariant, RoundedCornerShape(3.dp))
            .border(1.dp, palette.outlineVariant, RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/**
 * 渲染用户可复用的搜索历史。
 *
 * @param history 可展示的历史查询。
 * @param actions 面板发往组件层的用户动作。
 */
@Composable
internal fun SearchHistoryView(history: List<String>, actions: SearchPanelActions) {
    val palette = LocalOnyxPalette.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(Res.string.search_history_label),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.mutedForeground,
            )
            Text(
                text = stringResource(Res.string.action_clear_search_history),
                fontSize = 11.sp,
                color = palette.accent,
                modifier = Modifier.clickable(onClick = actions.onClearHistory),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            history.take(SEARCH_HISTORY_DISPLAY_LIMIT).forEach { item ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(palette.surfaceVariant)
                        .border(1.dp, palette.outlineVariant, RoundedCornerShape(4.dp))
                        .clickable {
                            actions.onQueryChange(item)
                            actions.onSearch()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(text = item, fontSize = 11.sp, color = palette.foreground)
                }
            }
        }
    }
}

/**
 * 渲染带悬浮提示的工具栏图标按钮。
 *
 * @param enabled 当前按钮是否可用。
 * @param onClick 点击按钮后的处理。
 * @param label 按钮悬浮提示的国际化资源。
 * @param icon 按钮展示的 Jewel 图标。
 */
@Composable
internal fun SearchToolbarButton(
    enabled: Boolean,
    onClick: () -> Unit,
    label: org.jetbrains.compose.resources.StringResource,
    icon: org.jetbrains.jewel.ui.icon.IconKey,
) {
    val text = stringResource(label)
    ToolbarIconButton(enabled = enabled, onClick = onClick, tooltip = text) {
        Icon(key = icon, contentDescription = text, modifier = Modifier.size(13.dp))
    }
}
