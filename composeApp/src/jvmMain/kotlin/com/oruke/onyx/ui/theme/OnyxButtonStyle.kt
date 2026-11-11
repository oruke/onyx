package com.oruke.onyx.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.styling.ButtonColors
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

/**
 * 基于当前 [palette] 构建主题化的主强调按钮样式，使所有窗口的主按钮与选中胶囊跟随预设与自定义强调色。
 *
 * @param palette 当前生效的语义调色板。
 * @param isDark 当前是否为深色主题。
 * @param baseStyle Jewel 基础主按钮样式。
 * @return 同步调色板强调色与可读对比度前景的主按钮样式。
 */
@Composable
internal fun rememberOnyxDefaultButtonStyle(
    palette: OnyxPalette,
    isDark: Boolean,
    baseStyle: ButtonStyle,
): ButtonStyle = remember(palette, isDark, baseStyle) {
    val background = if (isDark) {
        mixButtonColors(palette.accent, palette.surface, DARK_ACCENT_SURFACE_MIX)
    } else {
        palette.accent
    }
    val hovered = mixButtonColors(background, Color.White, ACCENT_HOVER_LIGHTEN)
    val pressed = palette.accentVariant
    val content = if (background.luminance() > BRIGHT_ACCENT_LUMINANCE_THRESHOLD) {
        DarkContrastTextColor
    } else {
        LightContrastTextColor
    }
    val focusBorder = if (isDark) LightFocusRingColor else DarkContrastTextColor
    ButtonStyle(
        colors = ButtonColors(
            background = SolidColor(background),
            backgroundDisabled = SolidColor(palette.surfaceVariant.copy(alpha = DISABLED_BG_ALPHA)),
            backgroundFocused = SolidColor(background),
            backgroundPressed = SolidColor(pressed),
            backgroundHovered = SolidColor(hovered),
            content = content,
            contentDisabled = palette.disabledForeground,
            contentFocused = content,
            contentPressed = content,
            contentHovered = content,
            border = SolidColor(background),
            borderDisabled = SolidColor(palette.outlineVariant),
            borderFocused = SolidColor(focusBorder),
            borderPressed = SolidColor(pressed),
            borderHovered = SolidColor(hovered),
        ),
        metrics = baseStyle.metrics,
        focusOutlineAlignment = baseStyle.focusOutlineAlignment,
    )
}

/**
 * 基于当前 [palette] 构建主题化的描边次按钮样式，使所有窗口的次要按钮边框与悬停反馈跟随当前主题。
 *
 * @param palette 当前生效的语义调色板。
 * @param isDark 当前是否为深色主题。
 * @param baseStyle Jewel 基础描边按钮样式。
 * @return 同步调色板表面、文字与描边色的次按钮样式。
 */
@Composable
internal fun rememberOnyxOutlinedButtonStyle(
    palette: OnyxPalette,
    isDark: Boolean,
    baseStyle: ButtonStyle,
): ButtonStyle = remember(palette, isDark, baseStyle) {
    val background = if (isDark) Color.Transparent else palette.surface
    val hoveredBg = palette.rowHoverBackground.copy(
        alpha = if (isDark) DARK_OUTLINED_HOVER_ALPHA else LIGHT_OUTLINED_HOVER_ALPHA,
    )
    val hoveredBorder = mixButtonColors(palette.outline, palette.accent, OUTLINED_HOVER_BORDER_MIX)
    ButtonStyle(
        colors = ButtonColors(
            background = SolidColor(background),
            backgroundDisabled = SolidColor(Color.Transparent),
            backgroundFocused = SolidColor(background),
            backgroundPressed = SolidColor(palette.surfaceVariant),
            backgroundHovered = SolidColor(hoveredBg),
            content = palette.foreground,
            contentDisabled = palette.disabledForeground,
            contentFocused = palette.foreground,
            contentPressed = palette.foreground,
            contentHovered = palette.foreground,
            border = SolidColor(palette.outline),
            borderDisabled = SolidColor(palette.outlineVariant),
            borderFocused = SolidColor(palette.accent),
            borderPressed = SolidColor(hoveredBorder),
            borderHovered = SolidColor(hoveredBorder),
        ),
        metrics = baseStyle.metrics,
        focusOutlineAlignment = baseStyle.focusOutlineAlignment,
    )
}

private fun mixButtonColors(from: Color, to: Color, fraction: Float): Color {
    val t = fraction.coerceIn(0f, 1f)
    val inv = 1f - t
    return Color(
        red = from.red * inv + to.red * t,
        green = from.green * inv + to.green * t,
        blue = from.blue * inv + to.blue * t,
        alpha = 1f,
    )
}

private val COMPACT_BUTTON_MIN_WIDTH = 36.dp
private val COMPACT_BUTTON_MIN_HEIGHT = 22.dp
private val CompactButtonPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)
private const val DARK_ACCENT_SURFACE_MIX = 0.14f
private const val ACCENT_HOVER_LIGHTEN = 0.10f
private const val BRIGHT_ACCENT_LUMINANCE_THRESHOLD = 0.36f
private const val DISABLED_BG_ALPHA = 0.5f
private const val DARK_OUTLINED_HOVER_ALPHA = 0.45f
private const val LIGHT_OUTLINED_HOVER_ALPHA = 0.55f
private const val OUTLINED_HOVER_BORDER_MIX = 0.45f
private val DarkContrastTextColor = Color(0xFF11131A)
private val LightContrastTextColor = Color(0xFFFFFFFF)
private val LightFocusRingColor = Color(0xFFF3F4F6)
