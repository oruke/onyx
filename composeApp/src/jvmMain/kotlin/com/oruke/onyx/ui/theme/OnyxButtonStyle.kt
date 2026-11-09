package com.oruke.onyx.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.styling.ButtonMetrics
import org.jetbrains.jewel.ui.component.styling.ButtonStyle

/**
 * 按 Jewel 当前文字行高扩展按钮最小高度，避免高缩放下中文行框被固定内容高度裁切；
 * 当 [compact] 为 true 时使用扁平紧凑的内边距与最小宽度，适配设置行内与紧凑选项按钮。
 *
 * @param style 当前主题的按钮样式。
 * @param compact 是否使用扁平紧凑尺寸。
 * @return 保留主题颜色、边框与内边距的按钮样式。
 */
@Composable
internal fun rememberOnyxButtonStyle(
    style: ButtonStyle,
    compact: Boolean = false,
): ButtonStyle {
    val density = LocalDensity.current
    val lineHeight = with(density) { JewelTheme.defaultTextStyle.lineHeight.toDp() }
    val metrics = style.metrics
    val targetPadding = if (compact) CompactButtonPadding else metrics.padding
    val baseMinWidth = if (compact) COMPACT_BUTTON_MIN_WIDTH else metrics.minSize.width
    val baseMinHeight = if (compact) COMPACT_BUTTON_MIN_HEIGHT else metrics.minSize.height
    // dp 随 UI 缩放，sp 还包含系统字体缩放；以实际文字行高计算剩余内容空间。
    val minHeight = maxOf(
        baseMinHeight,
        lineHeight + targetPadding.calculateTopPadding() + targetPadding.calculateBottomPadding(),
    )
    return remember(style, compact, minHeight) {
        ButtonStyle(
            colors = style.colors,
            metrics = ButtonMetrics(
                cornerSize = metrics.cornerSize,
                padding = targetPadding,
                minSize = DpSize(baseMinWidth, minHeight),
                borderWidth = metrics.borderWidth,
                focusOutlineExpand = metrics.focusOutlineExpand,
            ),
            focusOutlineAlignment = style.focusOutlineAlignment,
        )
    }
}

private val COMPACT_BUTTON_MIN_WIDTH = 36.dp
private val COMPACT_BUTTON_MIN_HEIGHT = 22.dp
private val CompactButtonPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)
