package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

@Serializable
/** 详情视图支持的标准列。 */
enum class DetailsColumn {
    NAME,
    TYPE,
    SIZE,
    MODIFIED,
}
