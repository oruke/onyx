package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

@Serializable
/** 文件面板的内容展示模式。 */
enum class ViewMode {
    DETAILS,
    GALLERY,
}
