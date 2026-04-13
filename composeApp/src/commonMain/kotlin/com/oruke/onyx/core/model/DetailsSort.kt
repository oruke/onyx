package com.oruke.onyx.core.model

data class DetailsSort(
    val column: DetailsColumn,
    val direction: SortDirection,
)

enum class SortDirection {
    ASCENDING,
    DESCENDING,
}
