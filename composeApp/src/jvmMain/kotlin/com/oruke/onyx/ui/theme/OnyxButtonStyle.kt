package com.oruke.onyx.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.styling.ButtonMetrics
import org.jetbrains.jewel.ui.component.styling.ButtonStyle

/**
 * 按 Jewel 当前文字行高扩展按钮最小高度，避免高缩放下中文行框被固定内容高度裁切。
 *
 * @param style 当前主题的按钮样式。
 * @return 保留主题颜色、边框与内边距的按钮样式。
 */
@Composable
internal fun rememberOnyxButtonStyle(style: ButtonStyle): ButtonStyle {
    val density = LocalDensity.current
    val lineHeight = with(density) { JewelTheme.defaultTextStyle.lineHeight.toDp() }
    val metrics = style.metrics
    // dp 随 UI 缩放，sp 还包含系统字体缩放；以实际文字行高计算剩余内容空间。
    val minHeight = maxOf(
        metrics.minSize.height,
        lineHeight + metrics.padding.calculateTopPadding() + metrics.padding.calculateBottomPadding(),
    )
    return remember(style, minHeight) {
        ButtonStyle(
            colors = style.colors,
            metrics = ButtonMetrics(
                cornerSize = metrics.cornerSize,
                padding = metrics.padding,
                minSize = DpSize(metrics.minSize.width, minHeight),
                borderWidth = metrics.borderWidth,
                focusOutlineExpand = metrics.focusOutlineExpand,
            ),
            focusOutlineAlignment = style.focusOutlineAlignment,
        )
    }
}
