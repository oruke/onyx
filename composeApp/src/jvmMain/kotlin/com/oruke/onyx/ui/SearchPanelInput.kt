package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.component.SearchPanelState
import com.oruke.onyx.app.component.SearchStatus
import com.oruke.onyx.app.component.hasExecutableSearchCriteria
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.rememberOnyxButtonStyle
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_clear_search_history
import onyx.composeapp.generated.resources.action_search
import onyx.composeapp.generated.resources.action_stop_search
import onyx.composeapp.generated.resources.search_history_label
import onyx.composeapp.generated.resources.search_placeholder
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalTextStyle
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.defaultButtonStyle
import org.jetbrains.jewel.ui.theme.outlinedButtonStyle

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
 * @return 无返回值。
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
        modifier = Modifier.fillMaxWidth().heightIn(min = LocalOnyxAppearance.current.headerHeight + 8.dp),
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
        if (state.status == SearchStatus.RUNNING) {
            OutlinedButton(
                onClick = actions.onCancel,
                modifier = Modifier.testTag("search-cancel"),
                style = rememberOnyxButtonStyle(JewelTheme.outlinedButtonStyle),
            ) { Text(stringResource(Res.string.action_stop_search), style = LocalTextStyle.current) }
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
 * @return 无返回值。
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
    val appearance = LocalOnyxAppearance.current
    val canSearch = state.hasExecutableSearchCriteria() && state.status != SearchStatus.RUNNING
    BasicTextField(
        value = state.query,
        onValueChange = actions.onQueryChange,
        modifier = Modifier
            .weight(1f)
            .heightIn(min = appearance.headerHeight + 8.dp)
            .testTag("search-query")
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
        textStyle = TextStyle(fontSize = appearance.listFontSize, color = palette.foreground),
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
 * 处理输入框和结果列表中的键盘导航、打开与隐藏命令，选中顺序与目录分组展示一致。
 *
 * @param event 当前键盘事件。
 * @param state 组件层提供的当前搜索状态。
 * @param selectedIndex 当前键盘导航选中的结果索引。
 * @param onSelectIndex 变更键盘导航选中项。
 * @param canSearch 当前是否允许执行新的搜索。
 * @param actions 面板发往组件层的用户动作。
 * @return 本次事件是否已由搜索输入框或结果列表处理。
 */
internal fun handleSearchQueryKeyEvent(
    event: androidx.compose.ui.input.key.KeyEvent,
    state: SearchPanelState,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    canSearch: Boolean,
    actions: SearchPanelActions,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.DirectionDown, Key.DirectionUp -> {
            if (state.results.isNotEmpty()) {
                // 并行搜索结果可能交错到达；键盘必须沿当前可见分组顺序移动，而非按到达顺序跳行。
                val displayIndices = state.results.withIndex()
                    .groupBy { (_, entry) -> entry.parentLocation ?: entry.location }
                    .values.flatten().map { it.index }
                val position = displayIndices.indexOf(selectedIndex)
                val delta = if (event.key == Key.DirectionDown) 1 else -1
                val nextPosition = if (position < 0) 0 else (position + delta).coerceIn(0, displayIndices.lastIndex)
                onSelectIndex(displayIndices[nextPosition])
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
            actions.onClose()
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
 * @return 无返回值。
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
                    fontSize = LocalOnyxAppearance.current.listFontSize,
                    color = palette.mutedForeground,
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
 * @return 无返回值。
 */
@Composable
private fun SearchExecuteButton(enabled: Boolean, onSearch: () -> Unit) {
    DefaultButton(
        onClick = onSearch,
        enabled = enabled,
        modifier = Modifier.testTag("search-execute"),
        style = rememberOnyxButtonStyle(JewelTheme.defaultButtonStyle),
    ) { Text(stringResource(Res.string.action_search), style = LocalTextStyle.current) }
}

/**
 * 渲染用户可复用的搜索历史。
 *
 * @param history 可展示的历史查询。
 * @param actions 面板发往组件层的用户动作。
 * @return 无返回值。
 */
@Composable
internal fun SearchHistoryView(history: List<String>, actions: SearchPanelActions) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(Res.string.search_history_label),
                fontSize = appearance.headerFontSize,
                fontWeight = FontWeight.SemiBold,
                color = palette.mutedForeground,
            )
            Text(
                text = stringResource(Res.string.action_clear_search_history),
                fontSize = appearance.headerFontSize,
                color = palette.accent,
                modifier = Modifier.clickable(onClick = actions.onClearHistory),
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
                    Text(
                        text = item,
                        fontSize = appearance.listFontSize,
                        color = palette.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
