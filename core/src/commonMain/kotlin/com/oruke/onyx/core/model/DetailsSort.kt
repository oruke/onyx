package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

@Serializable
/** 详情视图排序列与方向。 */
data class DetailsSort(
    /** 排序列。 */
    val column: DetailsColumn,
    /** 排序方向。 */
    val direction: SortDirection,
)

@Serializable
/** 列排序方向。 */
enum class SortDirection {
    ASCENDING,
    DESCENDING,
}
