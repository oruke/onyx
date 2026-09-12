package com.oruke.onyx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.component.SearchPanelState
import com.oruke.onyx.core.model.SearchScope
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.rememberOnyxButtonStyle
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_search_use_current_directory
import onyx.composeapp.generated.resources.label_search_advanced_filters
import onyx.composeapp.generated.resources.label_search_include_remote
import onyx.composeapp.generated.resources.label_search_root
import onyx.composeapp.generated.resources.search_scope_all_roots
import onyx.composeapp.generated.resources.search_scope_current_directory
import onyx.composeapp.generated.resources.search_scope_favorites
import onyx.composeapp.generated.resources.search_scope_label
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalTextStyle
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.outlinedButtonStyle

/**
 * 显示明确的搜索范围、网络位置选择和高级条件入口，窄窗口内自动换行。
 *
 * @param state 完整搜索会话状态。
 * @param presentation 目录名称显示能力。
 * @param actions 发往组件层的搜索动作。
 * @return 无返回值。
 */
@Composable
internal fun SearchWindowHeader(
    state: SearchPanelState,
    presentation: SearchPanelPresentation,
    actions: SearchPanelActions,
) {
    val appearance = LocalOnyxAppearance.current
    val palette = LocalOnyxPalette.current
    val buttonStyle = rememberOnyxButtonStyle(JewelTheme.outlinedButtonStyle)
    val filterCount = listOf(
        state.filters.types.isNotEmpty(),
        state.filters.modifiedSinceEpochMillis != null,
        state.filters.minSizeBytes != null || state.filters.maxSizeBytes != null,
        state.filters.searchInContent,
    ).count { it }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            SearchScopeSelector(state.scope, actions.onUpdateScope)
            CheckboxRow(
                text = stringResource(Res.string.label_search_include_remote),
                checked = state.includeRemoteLocations,
                onCheckedChange = actions.onSetIncludeRemoteLocations,
                modifier = Modifier.testTag("search-include-remote"),
            )
            OutlinedButton(
                onClick = { actions.onSetAdvancedFiltersExpanded(!state.advancedFiltersExpanded) },
                style = buttonStyle,
                modifier = Modifier.testTag("search-advanced")
                    .semantics { selected = state.advancedFiltersExpanded },
            ) {
                Text(
                    stringResource(Res.string.label_search_advanced_filters, filterCount),
                    style = LocalTextStyle.current,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f)) {
                val rootLabel = stringResource(
                    Res.string.label_search_root, presentation.locationLabel(state.rootLocation),
                )
                OnyxTooltip(rootLabel) {
                    Text(
                        rootLabel,
                        fontSize = appearance.headerFontSize,
                        color = palette.mutedForeground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("search-root"),
                    )
                }
            }
            OutlinedButton(
                onClick = actions.onUseCurrentDirectory,
                style = buttonStyle,
                modifier = Modifier.testTag("search-use-current-directory"),
            ) { Text(stringResource(Res.string.action_search_use_current_directory), style = LocalTextStyle.current) }
        }
    }
}

/**
 * 选择完整搜索范围；目录与网络位置是否实际扫描由组件统一解析。
 *
 * @param scope 当前选择的范围。
 * @param onUpdateScope 切换搜索范围的组件动作。
 * @return 无返回值。
 */
@Composable
private fun SearchScopeSelector(scope: SearchScope, onUpdateScope: (SearchScope) -> Unit) {
    val appearance = LocalOnyxAppearance.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(Res.string.search_scope_label), fontSize = appearance.headerFontSize)
        Dropdown(
            modifier = Modifier.heightIn(min = 29.dp).testTag("search-scope"),
            menuContent = {
                SearchScope.entries.forEach { candidate ->
                    selectableItem(selected = scope == candidate, onClick = { onUpdateScope(candidate) }) {
                        Text(searchScopeLabel(candidate))
                    }
                }
            },
        ) { Text(searchScopeLabel(scope), fontSize = appearance.headerFontSize) }
    }
}

/**
 * 返回搜索范围的本地化名称。
 *
 * @param scope 要展示的搜索范围。
 * @return 当前语言中的范围名称。
 */
@Composable
private fun searchScopeLabel(scope: SearchScope): String = stringResource(
    when (scope) {
        SearchScope.CURRENT_DIRECTORY -> Res.string.search_scope_current_directory
        SearchScope.FAVORITES -> Res.string.search_scope_favorites
        SearchScope.ALL_ROOTS -> Res.string.search_scope_all_roots
    },
)
