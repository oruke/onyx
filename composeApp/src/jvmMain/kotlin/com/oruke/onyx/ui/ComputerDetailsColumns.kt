package com.oruke.onyx.ui

/** 紧凑设备列表的列可见性，表头和内容必须共用同一配置。 */
internal data class ComputerDetailsColumns(
    /** 面板能够容纳总容量列。 */
    val totalVisible: Boolean,
    /** 面板能够容纳占用条列。 */
    val usageVisible: Boolean,
)
