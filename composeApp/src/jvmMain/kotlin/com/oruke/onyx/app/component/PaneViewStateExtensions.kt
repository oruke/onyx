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

internal fun PaneTabState.withResizedDetailsColumnState(
    column: DetailsColumn,
    deltaWeight: Float,
): PaneTabState {
    val currentWidth = detailsColumnWeights[column] ?: defaultDetailsColumnWidth(column)
    val adjustedDelta = deltaWeight.coerceAtLeast(MIN_DETAILS_COLUMN_WIDTH - currentWidth)
    return withTabState { current ->
        current.copy(
            detailsColumnWeights = current.detailsColumnWeights + (column to currentWidth + adjustedDelta),
        )
    }
}
