package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.SearchFilterFileType
import com.oruke.onyx.app.component.SearchFilters
import com.oruke.onyx.core.model.SearchScope
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import java.time.Instant
import java.time.temporal.ChronoUnit
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_close_search_panel
import onyx.composeapp.generated.resources.action_reset
import onyx.composeapp.generated.resources.search_filter_content
import onyx.composeapp.generated.resources.search_filter_directory
import onyx.composeapp.generated.resources.search_filter_file
import onyx.composeapp.generated.resources.search_filter_modified
import onyx.composeapp.generated.resources.search_filter_month
import onyx.composeapp.generated.resources.search_filter_size
import onyx.composeapp.generated.resources.search_filter_size_1_to_100_mb
import onyx.composeapp.generated.resources.search_filter_size_over_100_mb
import onyx.composeapp.generated.resources.search_filter_size_under_1_mb
import onyx.composeapp.generated.resources.search_filter_today
import onyx.composeapp.generated.resources.search_filter_type
import onyx.composeapp.generated.resources.search_filter_week
import onyx.composeapp.generated.resources.search_scope_all_roots
import onyx.composeapp.generated.resources.search_scope_current_directory
import onyx.composeapp.generated.resources.search_scope_favorites
import onyx.composeapp.generated.resources.search_scope_label
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * 渲染搜索范围下拉、筛选器入口和关闭按钮。
 *
 * @param scope 当前选择的搜索范围。
 * @param filters 当前生效的结构化筛选条件。
 * @param activeFilterMenu 当前展开的筛选器菜单。
 * @param onToggleFilterMenu 切换指定筛选器菜单的展开状态。
 * @param onUpdateScope 更新搜索范围。
 * @param onUpdateFilters 更新结构化筛选条件。
 * @param onClose 关闭搜索面板。
 */
@Composable
internal fun SearchPanelHeaderRow(
    scope: SearchScope,
    filters: SearchFilters,
    activeFilterMenu: SearchFilterMenu,
    onToggleFilterMenu: (SearchFilterMenu) -> Unit,
    onUpdateScope: (SearchScope) -> Unit,
    onUpdateFilters: (SearchFilters) -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().height(26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(Res.string.search_scope_label),
                fontSize = 11.sp,
                color = palette.mutedForeground,
            )
            Dropdown(
                menuContent = {
                    selectableItem(
                        selected = scope == SearchScope.CURRENT_DIRECTORY,
                        onClick = { onUpdateScope(SearchScope.CURRENT_DIRECTORY) },
                    ) {
                        Text(
                            text = stringResource(Res.string.search_scope_current_directory),
                            fontSize = 11.sp,
                        )
                    }
                    selectableItem(
                        selected = scope == SearchScope.FAVORITES,
                        onClick = { onUpdateScope(SearchScope.FAVORITES) },
                    ) {
                        Text(
                            text = stringResource(Res.string.search_scope_favorites),
                            fontSize = 11.sp,
                        )
                    }
                    selectableItem(
                        selected = scope == SearchScope.ALL_ROOTS,
                        onClick = { onUpdateScope(SearchScope.ALL_ROOTS) },
                    ) {
                        Text(
                            text = stringResource(Res.string.search_scope_all_roots),
                            fontSize = 11.sp,
                        )
                    }
                },
            ) {
                val label = when (scope) {
                    SearchScope.CURRENT_DIRECTORY -> {
                        stringResource(Res.string.search_scope_current_directory)
                    }
                    SearchScope.FAVORITES -> stringResource(Res.string.search_scope_favorites)
                    SearchScope.ALL_ROOTS -> stringResource(Res.string.search_scope_all_roots)
                }
                Text(text = label, fontSize = 11.sp, color = palette.foreground)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchFilterPill(
                label = stringResource(Res.string.search_filter_type),
                isActive = filters.types.isNotEmpty(),
                isExpanded = activeFilterMenu == SearchFilterMenu.TYPE,
                onClick = { onToggleFilterMenu(SearchFilterMenu.TYPE) },
            )
            SearchFilterPill(
                label = stringResource(Res.string.search_filter_modified),
                isActive = filters.modifiedSinceEpochMillis != null,
                isExpanded = activeFilterMenu == SearchFilterMenu.MODIFIED,
                onClick = { onToggleFilterMenu(SearchFilterMenu.MODIFIED) },
            )
            SearchFilterPill(
                label = stringResource(Res.string.search_filter_size),
                isActive = filters.minSizeBytes != null || filters.maxSizeBytes != null,
                isExpanded = activeFilterMenu == SearchFilterMenu.SIZE,
                onClick = { onToggleFilterMenu(SearchFilterMenu.SIZE) },
            )
            SearchFilterPill(
                label = stringResource(Res.string.search_filter_content),
                isActive = filters.searchInContent,
                isExpanded = activeFilterMenu == SearchFilterMenu.CONTENT,
                onClick = {
                    onUpdateFilters(filters.copy(searchInContent = !filters.searchInContent))
                },
            )
        }

        SearchToolbarButton(
            enabled = true,
            onClick = onClose,
            label = Res.string.action_close_search_panel,
            icon = AllIconsKeys.Actions.Close,
        )
    }
}

/**
 * 渲染单个筛选器入口按钮。
 *
 * @param label 按钮展示名称。
 * @param isActive 筛选器是否已生效。
 * @param isExpanded 筛选器菜单是否已展开。
 * @param onClick 点击筛选器入口后的处理。
 */
@Composable
private fun SearchFilterPill(
    label: String,
    isActive: Boolean,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val borderColor = when {
        isExpanded -> palette.accent
        isActive -> palette.accent.copy(alpha = 0.5f)
        else -> palette.outlineVariant
    }
    val backgroundColor = when {
        isExpanded -> palette.surfaceVariant
        isActive -> palette.accent.copy(alpha = 0.12f)
        else -> palette.inputBackground
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (isActive || isExpanded) palette.accent else palette.foreground,
            fontWeight = if (isActive || isExpanded) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/**
 * 渲染已展开的结构化筛选条件选项。
 *
 * @param activeMenu 当前展开的筛选器菜单。
 * @param filters 当前生效的结构化筛选条件。
 * @param onUpdateFilters 更新结构化筛选条件。
 */
@Composable
internal fun SearchFilterExpandablePanel(
    activeMenu: SearchFilterMenu,
    filters: SearchFilters,
    onUpdateFilters: (SearchFilters) -> Unit,
) {
    if (activeMenu == SearchFilterMenu.NONE || activeMenu == SearchFilterMenu.CONTENT) return
    val palette = LocalOnyxPalette.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surfaceVariant, RoundedCornerShape(6.dp))
            .border(1.dp, palette.outlineVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (activeMenu) {
            SearchFilterMenu.TYPE -> SearchTypeFilterOptions(filters, onUpdateFilters)
            SearchFilterMenu.MODIFIED -> SearchModifiedFilterOptions(filters, onUpdateFilters)
            SearchFilterMenu.SIZE -> SearchSizeFilterOptions(filters, onUpdateFilters)

            SearchFilterMenu.NONE,
            SearchFilterMenu.CONTENT -> Unit
        }
    }
}

/**
 * 渲染文件类型筛选条件。
 *
 * @param filters 当前生效的结构化筛选条件。
 * @param onUpdateFilters 更新结构化筛选条件。
 */
@Composable
private fun SearchTypeFilterOptions(
    filters: SearchFilters,
    onUpdateFilters: (SearchFilters) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Text(
        text = stringResource(Res.string.search_filter_type) + ":",
        fontSize = 11.sp,
        color = palette.mutedForeground,
    )
    SearchChipOption(
        label = stringResource(Res.string.search_filter_file),
        selected = SearchFilterFileType.FILE in filters.types,
        onClick = {
            val next = if (SearchFilterFileType.FILE in filters.types) {
                filters.types - SearchFilterFileType.FILE
            } else {
                filters.types + SearchFilterFileType.FILE
            }
            onUpdateFilters(filters.copy(types = next))
        },
    )
    SearchChipOption(
        label = stringResource(Res.string.search_filter_directory),
        selected = SearchFilterFileType.DIRECTORY in filters.types,
        onClick = {
            val next = if (SearchFilterFileType.DIRECTORY in filters.types) {
                filters.types - SearchFilterFileType.DIRECTORY
            } else {
                filters.types + SearchFilterFileType.DIRECTORY
            }
            onUpdateFilters(filters.copy(types = next))
        },
    )
    if (filters.types.isNotEmpty()) {
        SearchChipOption(
            label = stringResource(Res.string.action_reset),
            selected = false,
            onClick = { onUpdateFilters(filters.copy(types = emptySet())) },
        )
    }
}

/**
 * 渲染按最近修改时间筛选的条件。
 *
 * @param filters 当前生效的结构化筛选条件。
 * @param onUpdateFilters 更新结构化筛选条件。
 */
@Composable
private fun SearchModifiedFilterOptions(
    filters: SearchFilters,
    onUpdateFilters: (SearchFilters) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val now = Instant.now()
    val todayStart = now.truncatedTo(ChronoUnit.DAYS).toEpochMilli()
    val weekStart = now.minus(7, ChronoUnit.DAYS).toEpochMilli()
    val monthStart = now.minus(30, ChronoUnit.DAYS).toEpochMilli()
    Text(
        text = stringResource(Res.string.search_filter_modified) + ":",
        fontSize = 11.sp,
        color = palette.mutedForeground,
    )
    SearchChipOption(
        label = stringResource(Res.string.search_filter_today),
        selected = filters.modifiedSinceEpochMillis == todayStart,
        onClick = { onUpdateFilters(filters.copy(modifiedSinceEpochMillis = todayStart)) },
    )
    SearchChipOption(
        label = stringResource(Res.string.search_filter_week),
        selected = filters.modifiedSinceEpochMillis == weekStart,
        onClick = { onUpdateFilters(filters.copy(modifiedSinceEpochMillis = weekStart)) },
    )
    SearchChipOption(
        label = stringResource(Res.string.search_filter_month),
        selected = filters.modifiedSinceEpochMillis == monthStart,
        onClick = { onUpdateFilters(filters.copy(modifiedSinceEpochMillis = monthStart)) },
    )
    if (filters.modifiedSinceEpochMillis != null) {
        SearchChipOption(
            label = stringResource(Res.string.action_reset),
            selected = false,
            onClick = { onUpdateFilters(filters.copy(modifiedSinceEpochMillis = null)) },
        )
    }
}

/**
 * 渲染按文件大小筛选的条件。
 *
 * @param filters 当前生效的结构化筛选条件。
 * @param onUpdateFilters 更新结构化筛选条件。
 */
@Composable
private fun SearchSizeFilterOptions(
    filters: SearchFilters,
    onUpdateFilters: (SearchFilters) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val size1MB = 1_048_576L
    val size100MB = 104_857_600L
    Text(
        text = stringResource(Res.string.search_filter_size) + ":",
        fontSize = 11.sp,
        color = palette.mutedForeground,
    )
    SearchChipOption(
        label = stringResource(Res.string.search_filter_size_under_1_mb),
        selected = filters.maxSizeBytes == size1MB,
        onClick = { onUpdateFilters(filters.copy(minSizeBytes = null, maxSizeBytes = size1MB)) },
    )
    SearchChipOption(
        label = stringResource(Res.string.search_filter_size_1_to_100_mb),
        selected = filters.minSizeBytes == size1MB && filters.maxSizeBytes == size100MB,
        onClick = {
            onUpdateFilters(filters.copy(minSizeBytes = size1MB, maxSizeBytes = size100MB))
        },
    )
    SearchChipOption(
        label = stringResource(Res.string.search_filter_size_over_100_mb),
        selected = filters.minSizeBytes == size100MB,
        onClick = { onUpdateFilters(filters.copy(minSizeBytes = size100MB, maxSizeBytes = null)) },
    )
    if (filters.minSizeBytes != null || filters.maxSizeBytes != null) {
        SearchChipOption(
            label = stringResource(Res.string.action_reset),
            selected = false,
            onClick = { onUpdateFilters(filters.copy(minSizeBytes = null, maxSizeBytes = null)) },
        )
    }
}

/**
 * 渲染筛选器面板中的单个可选条件。
 *
 * @param label 条件展示名称。
 * @param selected 条件是否已选中。
 * @param onClick 点击条件后的处理。
 */
@Composable
private fun SearchChipOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) palette.accent else palette.inputBackground)
            .border(1.dp, if (selected) palette.accent else palette.outlineVariant, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = if (selected) Color.White else palette.foreground,
        )
    }
}
