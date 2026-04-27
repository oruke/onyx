package com.oruke.onyx

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.window.application
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
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.styling.TitleBarStyle

fun main() = application {
    val isDark = isSystemInDarkTheme()
    val theme = if (isDark) JewelTheme.darkThemeDefinition() else JewelTheme.lightThemeDefinition()
    val styling = ComponentStyling.default().decoratedWindow(
        titleBarStyle = if (isDark) TitleBarStyle.dark() else TitleBarStyle.lightWithLightHeader(),
    )

    IntUiTheme(
        theme = theme,
        styling = styling,
    ) {
        DecoratedWindow(
            onCloseRequest = ::exitApplication,
            title = "Onyx",
            icon = painterResource(Res.drawable.onyx_logo),
        ) {
            WindowApp()
        }
    }
}
