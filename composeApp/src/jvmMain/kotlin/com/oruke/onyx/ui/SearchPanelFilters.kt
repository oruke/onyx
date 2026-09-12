package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import java.time.Instant
import java.time.temporal.ChronoUnit
import onyx.composeapp.generated.resources.Res
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
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/**
 * 渲染高级条件的紧凑入口，收起整个高级区域时仍保留组件中的筛选值。
 *
 * @param filters 当前生效的结构化筛选条件。
 * @param activeFilterMenu 当前展开的筛选器。
 * @param onToggleFilterMenu 展开或收起筛选器。
 * @param onUpdateFilters 更新组件中的结构化条件。
 * @return 无返回值。
 */
@Composable
internal fun SearchAdvancedFilters(
    filters: SearchFilters,
    activeFilterMenu: SearchFilterMenu,
    onToggleFilterMenu: (SearchFilterMenu) -> Unit,
    onUpdateFilters: (SearchFilters) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SearchFilterPill(stringResource(Res.string.search_filter_type), filters.types.isNotEmpty(),
            activeFilterMenu == SearchFilterMenu.TYPE) { onToggleFilterMenu(SearchFilterMenu.TYPE) }
        SearchFilterPill(stringResource(Res.string.search_filter_modified), filters.modifiedSinceEpochMillis != null,
            activeFilterMenu == SearchFilterMenu.MODIFIED) { onToggleFilterMenu(SearchFilterMenu.MODIFIED) }
        SearchFilterPill(stringResource(Res.string.search_filter_size),
            filters.minSizeBytes != null || filters.maxSizeBytes != null,
            activeFilterMenu == SearchFilterMenu.SIZE) { onToggleFilterMenu(SearchFilterMenu.SIZE) }
        SearchFilterPill(stringResource(Res.string.search_filter_content), filters.searchInContent, false) {
            onUpdateFilters(filters.copy(searchInContent = !filters.searchInContent))
        }
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
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
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

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surfaceVariant, RoundedCornerShape(6.dp))
            .border(1.dp, palette.outlineVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
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
