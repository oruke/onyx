package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.SortDirection
import com.oruke.onyx.core.model.ViewMode

/** 画廊项目允许的最小边长。 */
private const val MIN_GALLERY_ITEM_SIZE_DP = 80

/** 画廊项目允许的最大边长。 */
private const val MAX_GALLERY_ITEM_SIZE_DP = 320

/**
 * 更新当前位置所属的视图偏好，设备概览不能覆盖普通文件夹的视图。
 * @param mode 用户请求的视图模式。
 * @return 保留其他位置偏好的标签状态。
 */
internal fun PaneTabState.withViewModeState(mode: ViewMode): PaneTabState {
    return withTabState { current ->
        if (current.location == com.oruke.onyx.core.model.COMPUTER_LOCATION) {
            current.copy(computerViewMode = mode)
        } else current.copy(viewMode = mode)
    }
}

/**
 * 更新快速筛选文本，保留用户正在输入的中间/尾随空格（匹配阶段再按需忽略首尾空白）。
 *
 * @param query 用户输入的最新筛选文本。
 * @return 更新过滤词后的标签状态。
 */
internal fun PaneTabState.withFilterQueryState(query: String): PaneTabState {
    return withTabState { current -> current.copy(filterQuery = query) }
}

internal fun PaneTabState.withToggledSortState(column: DetailsColumn): PaneTabState {
    val nextSort = if (detailsSort.column == column) {
        detailsSort.copy(
            direction = when (detailsSort.direction) {
                SortDirection.ASCENDING -> SortDirection.DESCENDING
                SortDirection.DESCENDING -> SortDirection.ASCENDING
            }
        )
    } else {
        DetailsSort(
            column = column,
            direction = SortDirection.ASCENDING,
        )
    }
    return withTabState { current ->
        current.copy(detailsSort = nextSort)
    }
}

internal fun PaneTabState.withToggledHiddenItemsState(): PaneTabState {
    return withTabState { current ->
        current.copy(showHiddenItems = !current.showHiddenItems)
    }
}

internal fun PaneTabState.withToggledColumnVisibilityState(column: DetailsColumn): PaneTabState {
    if (column == DetailsColumn.NAME) return this
    return withTabState { current ->
        val hiddenColumns = current.hiddenColumns.toMutableSet()
        if (column in hiddenColumns) {
            hiddenColumns.remove(column)
        } else {
            hiddenColumns.add(column)
        }
        current.copy(hiddenColumns = hiddenColumns)
    }
}

internal fun PaneTabState.withGalleryItemSizeState(sizeDp: Int): PaneTabState {
    return withTabState { current ->
        current.copy(galleryItemSizeDp = sizeDp.coerceIn(MIN_GALLERY_ITEM_SIZE_DP, MAX_GALLERY_ITEM_SIZE_DP))
    }
}

/**
 * 按拖拽增量调整明细列宽度；当当前标签仍使用默认列宽且调用方提供自适应视觉基准列宽时，
 * 直接以该视觉基准列宽为起点叠加增量，避免双面板窄视口首次拖拽时整表列宽突跳。
 *
 * @param column 待调整列。
 * @param deltaWeight 拖拽宽度增量（dp）。
 * @param baseWidths 可选当前自适应视觉列宽映射。
 * @return 更新列宽后的标签状态。
 */
internal fun PaneTabState.withResizedDetailsColumnState(
    column: DetailsColumn,
    deltaWeight: Float,
    baseWidths: Map<DetailsColumn, Float>? = null,
): PaneTabState {
    val defaults = defaultDetailsColumnWeights()
    val usesDefaultWidths = DetailsColumn.entries.all { col ->
        val actual = detailsColumnWeights[col] ?: defaults.getValue(col)
        kotlin.math.abs(actual - defaults.getValue(col)) < 0.5f
    }
    val effectiveBase = if (baseWidths != null && usesDefaultWidths) {
        baseWidths
    } else {
        detailsColumnWeights
    }
    val currentWidth = effectiveBase[column] ?: defaultDetailsColumnWidth(column)
    val adjustedDelta = deltaWeight.coerceAtLeast(MIN_DETAILS_COLUMN_WIDTH - currentWidth)
    return withTabState { current ->
        current.copy(
            detailsColumnWeights = effectiveBase + (column to currentWidth + adjustedDelta),
        )
    }
}
