package com.oruke.onyx.ui.theme

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme

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

val LocalOnyxPalette = staticCompositionLocalOf<OnyxPalette> {
    error("No OnyxPalette provided. Wrap your content in OnyxTheme.")
}

/**
 * 根据系统明暗主题创建并记忆 Onyx 调色板。
 *
 * @return 当前系统主题对应的调色板。
 */
@Composable
@Suppress("MagicNumber") // ARGB 字面量是集中式调色板数据，抽成编号常量会降低可读性。
fun rememberOnyxPalette(): OnyxPalette {
    val dark = isSystemInDarkTheme()
    return remember(dark) {
        if (dark) {
            OnyxPalette(
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
                success = Color(0xFF4DAA57),
                favorite = Color(0xFFFFC94D),
                accent = Color(0xFF4D8DFF),
                accentVariant = Color(0xFF2A5FAD),
                selectionBackground = Color(0xFF2D4F80),
                selectionForeground = Color(0xFFD4D4D8),
                headerBackground = Color(0xFF353739),
                rowHoverBackground = Color(0xFF2D4F80),
                statusBarBackground = Color(0xFF26282C),
                titleBarBorder = Color(0xFF43454A),
                titleBarActiveBackground = Color(0xFF3A3D41),
                titleBarHoverBackground = Color(0xFF35373A),
                titleBarPressedBackground = Color(0xFF3E4145),
                inactiveSelectionBackground = Color(0xFF43454A),
            )
        } else {
            OnyxPalette(
                appBackground = Color(0xFFF3F5F8),
                titleBarBackground = Color(0xFFF7F8FA),
                surface = Color(0xFFFFFFFF),
                surfaceVariant = Color(0xFFF7F8FA),
                floatingSurface = Color(0xFFF8FAFD),
                inputBackground = Color(0xFFFFFFFF),
                outline = Color(0xFFD0D7E2),
                outlineVariant = Color(0xFFE2E7EF),
                foreground = Color(0xFF1D2733),
                mutedForeground = Color(0xFF5D6B7C),
                disabledForeground = Color(0xFF94A0AE),
                error = onyxErrorColor,
                success = Color(0xFF318343),
                favorite = Color(0xFFC78600),
                accent = Color(0xFF2F6FEB),
                accentVariant = Color(0xFF5B9BF0),
                selectionBackground = Color(0xFFD0E0FF),
                selectionForeground = Color(0xFF1D2733),
                headerBackground = Color(0xFFF7F8FA),
                rowHoverBackground = Color(0xFFE8F0FE),
                statusBarBackground = Color(0xFFF7F8FA),
                titleBarBorder = Color(0xFFEBECF0),
                titleBarActiveBackground = Color(0xFFEBECF0),
                titleBarHoverBackground = Color(0xFFF0F2F5),
                titleBarPressedBackground = Color(0xFFE4E6EB),
                inactiveSelectionBackground = Color(0xFFE2E7EF),
            )
        }
    }
}

/** 应用级错误语义色。 */
private val onyxErrorColor = Color(0xFFD74E4E)

/**
 * Onyx 主题入口
 *
 * @param uiScale UI 缩放百分比（75~200），通过修改 LocalDensity 实现全局缩放，
 *                所有子组件的 dp/sp 值自动按比例缩放，无需修改下游代码。
 * @param appearance 动态外观参数，由 OnyxSettings 构建。
 */
@Composable
fun OnyxTheme(
    uiScale: Int = 100,
    appearance: OnyxAppearance = OnyxAppearance(),
    content: @Composable () -> Unit,
) {
    val palette = rememberOnyxPalette()
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(uiScale, baseDensity) {
        val scaleFactor = uiScale.coerceIn(75, 200) / 100f
        Density(
            density = baseDensity.density * scaleFactor,
            fontScale = baseDensity.fontScale * scaleFactor,
        )
    }
    val dark = isSystemInDarkTheme()
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
