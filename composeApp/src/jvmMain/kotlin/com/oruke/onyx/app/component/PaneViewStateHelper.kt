package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.SortDirection
import com.oruke.onyx.core.model.ViewMode

internal fun PaneTabState.withViewModeState(mode: ViewMode): PaneTabState {
    return withTabState { current -> current.copy(viewMode = mode) }
}

internal fun PaneTabState.withFilterQueryState(query: String): PaneTabState {
    return withTabState { current -> current.copy(filterQuery = query.trim()) }
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
        current.copy(galleryItemSizeDp = sizeDp.coerceIn(80, 320))
    }
}

internal fun PaneTabState.withResizedDetailsColumnState(
    column: DetailsColumn,
    deltaWeight: Float,
): PaneTabState {
    val currentWidth = detailsColumnWeights[column] ?: defaultDetailsColumnWidth(column)
    val newWidth = (currentWidth + deltaWeight).coerceAtLeast(MIN_DETAILS_COLUMN_WIDTH)
    return withTabState { current ->
        current.copy(
            detailsColumnWeights = current.detailsColumnWeights + mapOf(column to newWidth),
        )
    }
}
