package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

@Serializable
data class DetailsSort(
    val column: DetailsColumn,
    val direction: SortDirection,
)

@Serializable
enum class SortDirection {
    ASCENDING,
    DESCENDING,
}
