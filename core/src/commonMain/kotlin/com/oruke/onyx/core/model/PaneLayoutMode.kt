package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

@Serializable
/** 主窗口支持的面板布局模式。 */
enum class PaneLayoutMode {
    SINGLE,
    DUAL_VERTICAL,
    DUAL_HORIZONTAL,
}
