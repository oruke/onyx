package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

/** 文件视图独立颜色设置，空值表示随系统主题，颜色按 ARGB 持久化。 */
@Serializable
data class FileListColors(
    /** 文件区域背景。 */
    val backgroundArgb: Long? = null,
    /** 默认文件名颜色。 */
    val nameArgb: Long? = null,
    /** 活动面板选中背景。 */
    val selectionArgb: Long? = null,
    /** 非活动面板选中背景。 */
    val inactiveSelectionArgb: Long? = null,
    /** 斑马纹背景。 */
    val zebraArgb: Long? = null,
)

/** 组件计算后的条目着色结果；先配置的规则优先，选择背景始终优先于规则。 */
data class FileLabelStyle(
    /** 规则文件名颜色。 */
    val textColorArgb: Long? = null,
    /** 规则行背景。 */
    val backgroundColorArgb: Long? = null,
)
