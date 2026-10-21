package com.oruke.onyx.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 此电脑概览与紧凑列表共用的尺寸，由全局外观提供并响应 UI 缩放。 */
data class ComputerAppearance(
    /** 一列磁盘概览的最小可读宽度。 */
    val deviceMinWidth: Dp = 255.dp,
    /** 标准文件夹快捷入口的最小可读宽度，兼顾英文目录名。 */
    val placeMinWidth: Dp = 104.dp,
    /** 磁盘概览使用的图标尺寸。 */
    val deviceIconSize: Dp = 24.dp,
    /** 同组入口的水平与垂直间隔。 */
    val itemSpacing: Dp = 8.dp,
    /** 分组之间的额外间距。 */
    val sectionSpacing: Dp = 12.dp,
    /** 容量条高度，不添加装饰性外框。 */
    val meterHeight: Dp = 4.dp,
    /** 紧凑列表的可用容量列宽。 */
    val availableColumnWidth: Dp = 96.dp,
    /** 紧凑列表的总容量列宽。 */
    val totalColumnWidth: Dp = 80.dp,
    /** 紧凑列表的占用条列宽。 */
    val usageColumnWidth: Dp = 112.dp,
    /** 容量不足提示的剩余空间比例阈值。 */
    val lowSpaceFraction: Float = 0.1f,
)
