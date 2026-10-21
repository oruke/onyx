package com.oruke.onyx.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.core.model.FileListColors

/**
 * 动态外观参数系统
 *
 * 集中管理所有运行时可调的 UI 尺寸与视觉参数，
 * 通过 CompositionLocal 向全组件树提供统一访问入口。
 *
 * 所有核心组件必须通过此对象读取行高、字号等参数，
 * 不允许在 Composable 中硬编码尺寸常量。
 */
data class OnyxAppearance(
    /** 文件列表行高 */
    val listRowHeight: Dp = 22.dp,
    /** 文件列表字号 */
    val listFontSize: TextUnit = 12.sp,
    /** 表头高度 */
    val headerHeight: Dp = 24.dp,
    /** 表头字号 */
    val headerFontSize: TextUnit = 11.sp,
    /** 斑马纹开关 */
    val zebraStripeEnabled: Boolean = true,
    /** 自定义斑马纹颜色（null 时使用 palette.surfaceVariant） */
    val zebraStripeColor: Color? = null,
    /** 标签栏高度 */
    val tabBarHeight: Dp = 26.dp,
    /** 文件区域背景，未覆盖时随系统主题。 */
    val fileListBackground: Color? = null,
    /** 默认文件名颜色。 */
    val fileNameColor: Color? = null,
    /** 活动面板选中背景。 */
    val selectionBackground: Color? = null,
    /** 非活动面板选中背景。 */
    val inactiveSelectionBackground: Color? = null,
    /** 面板反馈条的水平内边距。 */
    val feedbackHorizontalPadding: Dp = 8.dp,
    /** 面板反馈条的垂直内边距。 */
    val feedbackVerticalPadding: Dp = 6.dp,
    /** 面板反馈文字与操作之间的间距。 */
    val feedbackItemSpacing: Dp = 8.dp,
    /** 面板反馈条的文字字号。 */
    val feedbackFontSize: TextUnit = 11.sp,
    /** 面板反馈背景叠加主题错误色的透明度。 */
    val feedbackBackgroundAlpha: Float = 0.2f,
    /** 文件与设备入口使用的标准小图标边长。 */
    val entryIconSize: Dp = 16.dp,
    /** 密集列表条目内容的水平内边距。 */
    val entryHorizontalPadding: Dp = 8.dp,
    /** 密集列表图标、文字和附加信息之间的间距。 */
    val entryContentSpacing: Dp = 6.dp,
    /** 设备概览在窄面板中将容量移到名称下方的统一宽度阈值。 */
    val storageCompactWidth: Dp = 420.dp,
    /** 此电脑的统一尺寸与容量提示参数。 */
    val computer: ComputerAppearance = ComputerAppearance(),
)

val LocalOnyxAppearance = compositionLocalOf<OnyxAppearance> {
    error("No OnyxAppearance provided. Wrap your content in OnyxTheme.")
}

/**
 * 从持久化设置构建 OnyxAppearance 实例。
 *
 * @param listRowHeightDp 行高（dp 整数值）
 * @param listFontSizeSp 字号（sp 整数值）
 * @param zebraStripeEnabled 斑马纹开关
 * @param fileListColors 文件列表持久化颜色覆盖。
 * @return 随设置变化重建的统一外观。
 */
@Composable
fun rememberOnyxAppearance(
    listRowHeightDp: Int = 22,
    listFontSizeSp: Int = 12,
    zebraStripeEnabled: Boolean = true,
    fileListColors: FileListColors = FileListColors(),
): OnyxAppearance {
    return remember(listRowHeightDp, listFontSizeSp, zebraStripeEnabled, fileListColors) {
        OnyxAppearance(
            listRowHeight = listRowHeightDp.dp,
            listFontSize = listFontSizeSp.sp,
            zebraStripeEnabled = zebraStripeEnabled,
            zebraStripeColor = fileListColors.zebraArgb?.let(::Color),
            fileListBackground = fileListColors.backgroundArgb?.let(::Color),
            fileNameColor = fileListColors.nameArgb?.let(::Color),
            selectionBackground = fileListColors.selectionArgb?.let(::Color),
            inactiveSelectionBackground = fileListColors.inactiveSelectionArgb?.let(::Color),
        )
    }
}
