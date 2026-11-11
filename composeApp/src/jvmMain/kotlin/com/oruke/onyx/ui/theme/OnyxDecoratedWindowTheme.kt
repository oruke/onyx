package com.oruke.onyx.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.oruke.onyx.app.cache.OnyxStartupPreloader
import com.oruke.onyx.core.model.OnyxSettings
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.onyx_logo
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.dark
import org.jetbrains.jewel.intui.window.styling.light
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.window.styling.DecoratedWindowColors
import org.jetbrains.jewel.window.styling.DecoratedWindowStyle
import org.jetbrains.jewel.window.styling.TitleBarColors
import org.jetbrains.jewel.window.styling.TitleBarStyle

/**
 * 向子窗口与模态对话框传递当前宿主窗口生效的 [OnyxSettings]，保证弹出窗口默认继承主窗口主题与缩放。
 */
internal val LocalOnyxSettings = compositionLocalOf { OnyxSettings() }

/**
 * 按给定的 [settings] 装配 Jewel 装饰窗口主题与沉浸式标题栏样式，使标题栏背景、文字、分割线与窗口外框
 * 实时同步明暗模式、6 套内置主题预设以及用户自定义颜色覆盖。
 *
 * @param settings 当前窗口生效的外观设置。
 * @param content 窗口树内容。
 */
@Composable
internal fun OnyxDecoratedWindowTheme(
    settings: OnyxSettings,
    content: @Composable () -> Unit,
) {
    val isDark = resolveEffectiveDarkTheme(settings.themeMode, isSystemInDarkTheme())
    val palette = rememberOnyxPalette(
        themeMode = settings.themeMode,
        themePreset = settings.themePreset,
        themeColors = settings.themeColors,
    )
    val windowTheme = if (isDark) {
        JewelTheme.darkThemeDefinition()
    } else {
        JewelTheme.lightThemeDefinition()
    }
    val titleBarStyle = if (isDark) {
        TitleBarStyle.dark(
            colors = TitleBarColors.dark(
                backgroundColor = palette.titleBarBackground,
                inactiveBackground = palette.surfaceVariant,
                contentColor = palette.foreground,
                borderColor = palette.titleBarBorder,
                iconButtonHoveredBackground = palette.titleBarHoverBackground,
                iconButtonPressedBackground = palette.titleBarPressedBackground,
            ),
        )
    } else {
        TitleBarStyle.lightWithLightHeader(
            colors = TitleBarColors.lightWithLightHeader(
                backgroundColor = palette.titleBarBackground,
                inactiveBackground = palette.surfaceVariant,
                contentColor = palette.foreground,
                borderColor = palette.titleBarBorder,
                iconButtonHoveredBackground = palette.titleBarHoverBackground,
                iconButtonPressedBackground = palette.titleBarPressedBackground,
            ),
        )
    }
    val decoratedWindowStyle = if (isDark) {
        DecoratedWindowStyle.dark(
            colors = DecoratedWindowColors.dark(
                borderColor = palette.outline,
                inactiveBorderColor = palette.outlineVariant,
            ),
        )
    } else {
        DecoratedWindowStyle.light(
            colors = DecoratedWindowColors.light(
                borderColor = palette.outline,
                inactiveBorderColor = palette.outlineVariant,
            ),
        )
    }
    val windowStyling = ComponentStyling.default().decoratedWindow(
        windowStyle = decoratedWindowStyle,
        titleBarStyle = titleBarStyle,
    )
    IntUiTheme(theme = windowTheme, styling = windowStyling) {
        CompositionLocalProvider(LocalOnyxSettings provides settings, content = content)
    }
}

/**
 * 优先复用启动预加载缓存的 Onyx 窗口图标 [Painter]，保证主窗口、独立工具窗口与模态对话框图标一致且零闪烁。
 *
 * @return 应用窗口图标绘制器。
 */
@Composable
internal fun rememberOnyxWindowIconPainter(): Painter {
    val preloadedPainter = remember {
        runCatching {
            OnyxStartupPreloader.peekLogoImage()?.toComposeImageBitmap()?.let(::BitmapPainter)
        }.getOrNull()
    }
    return preloadedPainter ?: painterResource(Res.drawable.onyx_logo)
}
