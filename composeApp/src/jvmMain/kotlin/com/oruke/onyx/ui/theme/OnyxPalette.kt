package com.oruke.onyx.ui.theme

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.oruke.onyx.core.model.OnyxThemeColors
import com.oruke.onyx.core.model.OnyxThemeMode
import com.oruke.onyx.core.model.OnyxThemePreset
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme

/** 内置精选强调色预设列表（ARGB）。 */
internal val ONYX_ACCENT_SWATCHES: List<Long> = listOf(
    0xFF2F6FEBL,
    0xFF6366F1L,
    0xFF8B5CF6L,
    0xFFEC4899L,
    0xFFEF4444L,
    0xFFF59E0BL,
    0xFF10B981L,
    0xFF06B6D4L,
)

/**
 * Onyx 运行时语义调色板。
 */
data class OnyxPalette(
    /** 应用主背景色。 */
    val appBackground: Color,

    /** 标题栏背景色。 */
    val titleBarBackground: Color,

    /** 基础表面色。 */
    val surface: Color,

    /** 次级表面色。 */
    val surfaceVariant: Color,

    /** 浮层表面色。 */
    val floatingSurface: Color,

    /** 输入控件背景色。 */
    val inputBackground: Color,

    /** 主边框色。 */
    val outline: Color,

    /** 次级边框色。 */
    val outlineVariant: Color,

    /** 主前景色。 */
    val foreground: Color,

    /** 弱化前景色。 */
    val mutedForeground: Color,

    /** 禁用状态前景色。 */
    val disabledForeground: Color,

    /** 错误语义色。 */
    val error: Color,

    /** 错误正文与状态文字色，在基础表面和默认选择背景上保持可读对比度。 */
    val errorForeground: Color,

    /** 操作成功语义色。 */
    val success: Color,

    /** 收藏与重点标记语义色。 */
    val favorite: Color,

    /** 主强调色。 */
    val accent: Color,

    /** 次级强调色。 */
    val accentVariant: Color,

    /** 激活选择背景色。 */
    val selectionBackground: Color,

    /** 选择前景色。 */
    val selectionForeground: Color,

    /** 表头背景色。 */
    val headerBackground: Color,

    /** 行悬停背景色。 */
    val rowHoverBackground: Color,

    /** 状态栏背景色。 */
    val statusBarBackground: Color,

    /** 标题栏边框色。 */
    val titleBarBorder: Color,

    /** 标题栏激活背景色。 */
    val titleBarActiveBackground: Color,

    /** 标题栏悬停背景色。 */
    val titleBarHoverBackground: Color,

    /** 标题栏按下背景色。 */
    val titleBarPressedBackground: Color,

    /** 非激活面板选择背景色。 */
    val inactiveSelectionBackground: Color,
)

val LocalOnyxPalette = compositionLocalOf<OnyxPalette> {
    error("No OnyxPalette provided. Wrap your content in OnyxTheme.")
}

/**
 * 根据明暗模式配置与系统当前状态解析最终是否使用深色主题。
 */
internal fun resolveEffectiveDarkTheme(themeMode: OnyxThemeMode, systemDark: Boolean): Boolean = when (themeMode) {
    OnyxThemeMode.SYSTEM -> systemDark
    OnyxThemeMode.LIGHT -> false
    OnyxThemeMode.DARK -> true
}

/**
 * 根据主题模式、预设与自定义颜色覆盖创建并记忆 Onyx 调色板。
 */
@Composable
fun rememberOnyxPalette(
    themeMode: OnyxThemeMode = OnyxThemeMode.SYSTEM,
    themePreset: OnyxThemePreset = OnyxThemePreset.DEFAULT,
    themeColors: OnyxThemeColors = OnyxThemeColors(),
): OnyxPalette {
    val systemDark = isSystemInDarkTheme()
    val dark = resolveEffectiveDarkTheme(themeMode, systemDark)
    return remember(dark, themePreset, themeColors) {
        resolveOnyxPalette(themePreset = themePreset, dark = dark, overrides = themeColors)
    }
}

/**
 * 纯函数构建指定预设、明暗模式与覆盖项对应的 [OnyxPalette]。
 */
@Suppress("MagicNumber")
internal fun resolveOnyxPalette(
    themePreset: OnyxThemePreset,
    dark: Boolean,
    overrides: OnyxThemeColors = OnyxThemeColors(),
): OnyxPalette {
    val base = when (themePreset) {
        OnyxThemePreset.DEFAULT -> if (dark) defaultDarkPalette() else defaultLightPalette()
        OnyxThemePreset.MIDNIGHT_OLED -> if (dark) midnightOledDarkPalette() else midnightOledLightPalette()
        OnyxThemePreset.NORDIC_FROST -> if (dark) nordicFrostDarkPalette() else nordicFrostLightPalette()
        OnyxThemePreset.DRACULA_PURPLE -> if (dark) draculaPurpleDarkPalette() else draculaPurpleLightPalette()
        OnyxThemePreset.SOLARIZED_WARM -> if (dark) solarizedWarmDarkPalette() else solarizedWarmLightPalette()
        OnyxThemePreset.FOREST_EMERALD -> if (dark) forestEmeraldDarkPalette() else forestEmeraldLightPalette()
    }
    return applyThemeColorOverrides(base, overrides, dark)
}

@Suppress("MagicNumber")
private fun applyThemeColorOverrides(
    base: OnyxPalette,
    overrides: OnyxThemeColors,
    dark: Boolean,
): OnyxPalette {
    val withSurface = applySurfaceAndTextOverrides(base, overrides, dark)
    return applyAccentAndBorderOverrides(withSurface, overrides, dark)
}

@Suppress("MagicNumber")
private fun applySurfaceAndTextOverrides(
    base: OnyxPalette,
    overrides: OnyxThemeColors,
    dark: Boolean,
): OnyxPalette {
    val surface = overrides.surfaceArgb?.let(::Color) ?: base.surface
    val appBg = overrides.appBackgroundArgb?.let(::Color) ?: base.appBackground
    val fg = overrides.foregroundArgb?.let(::Color) ?: base.foreground
    val mutedFg = overrides.mutedForegroundArgb?.let(::Color) ?: base.mutedForeground
    val surfaceOverridden = overrides.surfaceArgb != null
    val surfaceVariant = if (surfaceOverridden) {
        blendColors(surface, fg, if (dark) 0.06f else 0.03f)
    } else {
        base.surfaceVariant
    }
    val floatingSurface = if (surfaceOverridden) {
        blendColors(surface, fg, if (dark) 0.09f else 0.02f)
    } else {
        base.floatingSurface
    }
    val headerBg = overrides.headerBackgroundArgb?.let(::Color)
        ?: if (surfaceOverridden) surfaceVariant else base.headerBackground

    return base.copy(
        appBackground = appBg,
        titleBarBackground = if (surfaceOverridden) surface else base.titleBarBackground,
        surface = surface,
        surfaceVariant = surfaceVariant,
        floatingSurface = floatingSurface,
        inputBackground = if (overrides.appBackgroundArgb != null) appBg else base.inputBackground,
        foreground = fg,
        mutedForeground = mutedFg,
        selectionForeground = fg,
        headerBackground = headerBg,
        statusBarBackground = if (surfaceOverridden) surfaceVariant else base.statusBarBackground,
    )
}

@Suppress("MagicNumber")
private fun applyAccentAndBorderOverrides(
    base: OnyxPalette,
    overrides: OnyxThemeColors,
    dark: Boolean,
): OnyxPalette {
    val outline = overrides.outlineArgb?.let(::Color) ?: base.outline
    val accent = overrides.accentArgb?.let(::Color) ?: base.accent
    val surfaceOverridden = overrides.surfaceArgb != null
    val outlineOverridden = overrides.outlineArgb != null
    val accentOverridden = overrides.accentArgb != null
    val outlineVariant = if (outlineOverridden) {
        blendColors(base.surface, outline, 0.65f)
    } else {
        base.outlineVariant
    }
    val accentVariant = if (accentOverridden) {
        blendColors(accent, if (dark) Color.Black else Color.White, 0.22f)
    } else {
        base.accentVariant
    }
    val selectionBg = if (accentOverridden || surfaceOverridden) {
        blendColors(base.surface, accent, if (dark) 0.36f else 0.24f)
    } else {
        base.selectionBackground
    }
    val rowHoverBg = if (accentOverridden || surfaceOverridden) {
        blendColors(base.surface, accent, if (dark) 0.22f else 0.14f)
    } else {
        base.rowHoverBackground
    }
    return base.copy(
        outline = outline,
        outlineVariant = outlineVariant,
        accent = accent,
        accentVariant = accentVariant,
        selectionBackground = selectionBg,
        rowHoverBackground = rowHoverBg,
        titleBarBorder = if (outlineOverridden) outlineVariant else base.titleBarBorder,
    )
}

private fun blendColors(from: Color, to: Color, fraction: Float): Color {
    val t = fraction.coerceIn(0f, 1f)
    val inv = 1f - t
    return Color(
        red = from.red * inv + to.red * t,
        green = from.green * inv + to.green * t,
        blue = from.blue * inv + to.blue * t,
        alpha = 1f,
    )
}

@Suppress("MagicNumber")
private fun defaultDarkPalette() = OnyxPalette(
    appBackground = Color(0xFF1E1F22),
    titleBarBackground = Color(0xFF2B2D30),
    surface = Color(0xFF2B2D30),
    surfaceVariant = Color(0xFF313336),
    floatingSurface = Color(0xFF3C3F41),
    inputBackground = Color(0xFF1E1F22),
    outline = Color(0xFF4E5157),
    outlineVariant = Color(0xFF3C3F41),
    foreground = Color(0xFFD4D4D8),
    mutedForeground = Color(0xFF8C8C94),
    disabledForeground = Color(0xFF6E6E6E),
    error = onyxErrorColor,
    errorForeground = Color(0xFFFFB4B4),
    success = Color(0xFF4DAA57),
    favorite = Color(0xFFFFC94D),
    accent = Color(0xFF4D8DFF),
    accentVariant = Color(0xFF2A5FAD),
    selectionBackground = Color(0xFF2B579A),
    selectionForeground = Color(0xFFD4D4D8),
    headerBackground = Color(0xFF353739),
    rowHoverBackground = Color(0xFF38475E),
    statusBarBackground = Color(0xFF26282C),
    titleBarBorder = Color(0xFF43454A),
    titleBarActiveBackground = Color(0xFF3A3D41),
    titleBarHoverBackground = Color(0xFF35373A),
    titleBarPressedBackground = Color(0xFF3E4145),
    inactiveSelectionBackground = Color(0xFF43454A),
)

@Suppress("MagicNumber")
private fun defaultLightPalette() = OnyxPalette(
    appBackground = Color(0xFFF3F5F8),
    titleBarBackground = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF4F6F9),
    floatingSurface = Color(0xFFF8FAFD),
    inputBackground = Color(0xFFFFFFFF),
    outline = Color(0xFFD0D7E2),
    outlineVariant = Color(0xFFE2E7EF),
    foreground = Color(0xFF1D2733),
    mutedForeground = Color(0xFF5D6B7C),
    disabledForeground = Color(0xFF94A0AE),
    error = onyxErrorColor,
    errorForeground = Color(0xFFA83232),
    success = Color(0xFF318343),
    favorite = Color(0xFFC78600),
    accent = Color(0xFF2F6FEB),
    accentVariant = Color(0xFF5B9BF0),
    selectionBackground = Color(0xFFBDD4FF),
    selectionForeground = Color(0xFF1D2733),
    headerBackground = Color(0xFFF4F6F9),
    rowHoverBackground = Color(0xFFDCE8FC),
    statusBarBackground = Color(0xFFF4F6F9),
    titleBarBorder = Color(0xFFEBECF0),
    titleBarActiveBackground = Color(0xFFEBECF0),
    titleBarHoverBackground = Color(0xFFF0F2F5),
    titleBarPressedBackground = Color(0xFFE4E6EB),
    inactiveSelectionBackground = Color(0xFFE2E7EF),
)

@Suppress("MagicNumber")
private fun midnightOledDarkPalette() = defaultDarkPalette().copy(
    appBackground = Color(0xFF090A0F),
    titleBarBackground = Color(0xFF11131A),
    surface = Color(0xFF12141C),
    surfaceVariant = Color(0xFF181B24),
    floatingSurface = Color(0xFF1E222D),
    inputBackground = Color(0xFF090A0F),
    outline = Color(0xFF2E3446),
    outlineVariant = Color(0xFF222736),
    foreground = Color(0xFFEFF2F8),
    mutedForeground = Color(0xFF94A3B8),
    accent = Color(0xFF38BDF8),
    accentVariant = Color(0xFF0284C7),
    selectionBackground = Color(0xFF194B70),
    selectionForeground = Color(0xFFEFF2F8),
    headerBackground = Color(0xFF161922),
    rowHoverBackground = Color(0xFF1F364D),
    statusBarBackground = Color(0xFF0D0F15),
    titleBarBorder = Color(0xFF222736),
    inactiveSelectionBackground = Color(0xFF222736),
)

@Suppress("MagicNumber")
private fun midnightOledLightPalette() = defaultLightPalette().copy(
    appBackground = Color(0xFFF8FAFC),
    titleBarBackground = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F5F9),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    foreground = Color(0xFF0F172A),
    mutedForeground = Color(0xFF475569),
    accent = Color(0xFF0284C7),
    accentVariant = Color(0xFF38BDF8),
    selectionBackground = Color(0xFFBAE6FD),
    rowHoverBackground = Color(0xFFD8EEFE),
)

@Suppress("MagicNumber")
private fun nordicFrostDarkPalette() = defaultDarkPalette().copy(
    appBackground = Color(0xFF242933),
    titleBarBackground = Color(0xFF2E3440),
    surface = Color(0xFF2E3440),
    surfaceVariant = Color(0xFF3B4252),
    floatingSurface = Color(0xFF434C5E),
    inputBackground = Color(0xFF242933),
    outline = Color(0xFF4C566A),
    outlineVariant = Color(0xFF3B4252),
    foreground = Color(0xFFECEFF4),
    mutedForeground = Color(0xFFD8DEE9),
    accent = Color(0xFF88C0D0),
    accentVariant = Color(0xFF5E81AC),
    selectionBackground = Color(0xFF4C6A8A),
    selectionForeground = Color(0xFFECEFF4),
    headerBackground = Color(0xFF353C4A),
    rowHoverBackground = Color(0xFF43536B),
    statusBarBackground = Color(0xFF292E39),
    titleBarBorder = Color(0xFF3B4252),
    inactiveSelectionBackground = Color(0xFF434C5E),
)

@Suppress("MagicNumber")
private fun nordicFrostLightPalette() = defaultLightPalette().copy(
    appBackground = Color(0xFFECEFF4),
    titleBarBackground = Color(0xFFE5E9F0),
    surface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFFECEFF4),
    floatingSurface = Color(0xFFFFFFFF),
    inputBackground = Color(0xFFFFFFFF),
    outline = Color(0xFFD8DEE9),
    outlineVariant = Color(0xFFE5E9F0),
    foreground = Color(0xFF2E3440),
    mutedForeground = Color(0xFF4C566A),
    accent = Color(0xFF5E81AC),
    accentVariant = Color(0xFF81A1C1),
    selectionBackground = Color(0xFFBDD2EC),
    selectionForeground = Color(0xFF2E3440),
    headerBackground = Color(0xFFE5E9F0),
    rowHoverBackground = Color(0xFFD4E2F4),
    statusBarBackground = Color(0xFFE5E9F0),
)

@Suppress("MagicNumber")
private fun draculaPurpleDarkPalette() = defaultDarkPalette().copy(
    appBackground = Color(0xFF1E1F29),
    titleBarBackground = Color(0xFF282A36),
    surface = Color(0xFF282A36),
    surfaceVariant = Color(0xFF313442),
    floatingSurface = Color(0xFF383A4A),
    inputBackground = Color(0xFF1E1F29),
    outline = Color(0xFF565970),
    outlineVariant = Color(0xFF44475A),
    foreground = Color(0xFFF8F8F2),
    mutedForeground = Color(0xFF9EA4C4),
    accent = Color(0xFFBD93F9),
    accentVariant = Color(0xFF9567E0),
    selectionBackground = Color(0xFF515575),
    selectionForeground = Color(0xFFF8F8F2),
    headerBackground = Color(0xFF2F3140),
    rowHoverBackground = Color(0xFF3E4259),
    statusBarBackground = Color(0xFF21222C),
    titleBarBorder = Color(0xFF44475A),
    inactiveSelectionBackground = Color(0xFF3B3E52),
)

@Suppress("MagicNumber")
private fun draculaPurpleLightPalette() = defaultLightPalette().copy(
    appBackground = Color(0xFFF5F3FF),
    titleBarBackground = Color(0xFFEDE9FE),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF5F3FF),
    floatingSurface = Color(0xFFFFFFFF),
    outline = Color(0xFFDDD6FE),
    outlineVariant = Color(0xFFEDE9FE),
    foreground = Color(0xFF2E1065),
    mutedForeground = Color(0xFF6B5B95),
    accent = Color(0xFF7C3AED),
    accentVariant = Color(0xFFA78BFA),
    selectionBackground = Color(0xFFD0BFFF),
    selectionForeground = Color(0xFF2E1065),
    headerBackground = Color(0xFFF5F3FF),
    rowHoverBackground = Color(0xFFE4D9FF),
    statusBarBackground = Color(0xFFEDE9FE),
)

@Suppress("MagicNumber")
private fun solarizedWarmDarkPalette() = defaultDarkPalette().copy(
    appBackground = Color(0xFF00212B),
    titleBarBackground = Color(0xFF002B36),
    surface = Color(0xFF002B36),
    surfaceVariant = Color(0xFF073642),
    floatingSurface = Color(0xFF0B414F),
    inputBackground = Color(0xFF00212B),
    outline = Color(0xFF2E5B66),
    outlineVariant = Color(0xFF164652),
    foreground = Color(0xFFEEE8D5),
    mutedForeground = Color(0xFF93A1A1),
    accent = Color(0xFF2AA198),
    accentVariant = Color(0xFF268BD2),
    selectionBackground = Color(0xFF166473),
    selectionForeground = Color(0xFFFDF6E3),
    headerBackground = Color(0xFF073642),
    rowHoverBackground = Color(0xFF0F4C59),
    statusBarBackground = Color(0xFF00252E),
    titleBarBorder = Color(0xFF164652),
    inactiveSelectionBackground = Color(0xFF073642),
)

@Suppress("MagicNumber")
private fun solarizedWarmLightPalette() = defaultLightPalette().copy(
    appBackground = Color(0xFFEEE8D5),
    titleBarBackground = Color(0xFFF5EFDC),
    surface = Color(0xFFFDF6E3),
    surfaceVariant = Color(0xFFEEE8D5),
    floatingSurface = Color(0xFFFDF6E3),
    inputBackground = Color(0xFFFDF6E3),
    outline = Color(0xFFD5CBB4),
    outlineVariant = Color(0xFFE4DEC8),
    foreground = Color(0xFF073642),
    mutedForeground = Color(0xFF586E75),
    accent = Color(0xFFB58900),
    accentVariant = Color(0xFF268BD2),
    selectionBackground = Color(0xFFD3C7A4),
    selectionForeground = Color(0xFF073642),
    headerBackground = Color(0xFFEEE8D5),
    rowHoverBackground = Color(0xFFE2D9BE),
    statusBarBackground = Color(0xFFEEE8D5),
    titleBarBorder = Color(0xFFE4DEC8),
    inactiveSelectionBackground = Color(0xFFE4DEC8),
)

@Suppress("MagicNumber")
private fun forestEmeraldDarkPalette() = defaultDarkPalette().copy(
    appBackground = Color(0xFF111915),
    titleBarBackground = Color(0xFF17221D),
    surface = Color(0xFF17221D),
    surfaceVariant = Color(0xFF1E2C26),
    floatingSurface = Color(0xFF24352E),
    inputBackground = Color(0xFF111915),
    outline = Color(0xFF344C41),
    outlineVariant = Color(0xFF263830),
    foreground = Color(0xFFE2F1EA),
    mutedForeground = Color(0xFF8FB0A1),
    accent = Color(0xFF10B981),
    accentVariant = Color(0xFF059669),
    selectionBackground = Color(0xFF1F6349),
    selectionForeground = Color(0xFFE2F1EA),
    headerBackground = Color(0xFF1C2923),
    rowHoverBackground = Color(0xFF26493B),
    statusBarBackground = Color(0xFF141E19),
    titleBarBorder = Color(0xFF263830),
    inactiveSelectionBackground = Color(0xFF263830),
)

@Suppress("MagicNumber")
private fun forestEmeraldLightPalette() = defaultLightPalette().copy(
    appBackground = Color(0xFFF0F7F4),
    titleBarBackground = Color(0xFFE8F3EE),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0F7F4),
    floatingSurface = Color(0xFFFFFFFF),
    outline = Color(0xFFC6DFD3),
    outlineVariant = Color(0xFFDCECE4),
    foreground = Color(0xFF112A20),
    mutedForeground = Color(0xFF496B5D),
    accent = Color(0xFF059669),
    accentVariant = Color(0xFF10B981),
    selectionBackground = Color(0xFFA7E9C9),
    selectionForeground = Color(0xFF064E3B),
    headerBackground = Color(0xFFF0F7F4),
    rowHoverBackground = Color(0xFFD1F2E1),
    statusBarBackground = Color(0xFFE8F3EE),
)

/** 应用级错误语义色。 */
private val onyxErrorColor = Color(0xFFD74E4E)

/**
 * Onyx 主题入口
 *
 * @param uiScale UI 缩放百分比（75~200），通过修改 LocalDensity 实现全局缩放，
 *                所有子组件的 dp/sp 值自动按比例缩放，无需修改下游代码。
 * @param appearance 动态外观参数，由 OnyxSettings 构建。
 * @param themeMode 明暗主题模式。
 * @param themePreset 主题配色预设。
 * @param themeColors 全局主题颜色覆盖。
 * @param content 需要统一主题与缩放的界面内容。
 * @return 无返回值。
 */
@Composable
fun OnyxTheme(
    uiScale: Int = 100,
    appearance: OnyxAppearance = OnyxAppearance(),
    themeMode: OnyxThemeMode = OnyxThemeMode.SYSTEM,
    themePreset: OnyxThemePreset = OnyxThemePreset.DEFAULT,
    themeColors: OnyxThemeColors = OnyxThemeColors(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = resolveEffectiveDarkTheme(themeMode, systemDark)
    val palette = rememberOnyxPalette(themeMode, themePreset, themeColors)
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(uiScale, baseDensity) {
        val scaleFactor = uiScale.coerceIn(75, 200) / 100f
        Density(
            density = baseDensity.density * scaleFactor,
            // sp 已通过 density 随 UI 缩放；再次乘 fontScale 会平方放大文字并挤出 Jewel 控件。
            fontScale = baseDensity.fontScale,
        )
    }
    val scrollbarStyle = remember(dark) {
        ScrollbarStyle(
            minimalHeight = 24.dp,
            thickness = 8.dp,
            shape = RoundedCornerShape(4.dp),
            hoverDurationMillis = 200,
            unhoverColor = if (dark) Color.White.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.20f),
            hoverColor = if (dark) Color.White.copy(alpha = 0.48f) else Color.Black.copy(alpha = 0.38f),
        )
    }
    IntUiTheme(isDark = dark) {
        CompositionLocalProvider(
            LocalOnyxPalette provides palette,
            LocalOnyxAppearance provides appearance,
            LocalDensity provides scaledDensity,
            LocalScrollbarStyle provides scrollbarStyle,
        ) {
            content()
        }
    }
}
