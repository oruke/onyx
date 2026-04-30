package com.oruke.onyx.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
)

val LocalOnyxAppearance = staticCompositionLocalOf<OnyxAppearance> {
    error("No OnyxAppearance provided. Wrap your content in OnyxTheme.")
}

/**
 * 从持久化设置构建 OnyxAppearance 实例。
 *
 * @param listRowHeightDp 行高（dp 整数值）
 * @param listFontSizeSp 字号（sp 整数值）
 * @param zebraStripeEnabled 斑马纹开关
 */
@Composable
fun rememberOnyxAppearance(
    listRowHeightDp: Int = 22,
    listFontSizeSp: Int = 12,
    zebraStripeEnabled: Boolean = true,
): OnyxAppearance {
    return remember(listRowHeightDp, listFontSizeSp, zebraStripeEnabled) {
        OnyxAppearance(
            listRowHeight = listRowHeightDp.dp,
            listFontSize = listFontSizeSp.sp,
            zebraStripeEnabled = zebraStripeEnabled,
        )
    }
}
