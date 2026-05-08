package com.oruke.onyx

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.oruke.onyx.app.component.rememberRootComponent
import com.oruke.onyx.app.platform.ExternalDragHelper
import com.oruke.onyx.di.fileModule
import com.oruke.onyx.ui.ImageViewerContent
import com.oruke.onyx.ui.theme.OnyxTheme
import com.oruke.onyx.ui.theme.rememberOnyxAppearance
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.onyx_logo
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.KoinApplication
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
    KoinApplication(application = { modules(fileModule) }) {
        val isDark = isSystemInDarkTheme()
        val theme = if (isDark) JewelTheme.darkThemeDefinition() else JewelTheme.lightThemeDefinition()
        val styling = ComponentStyling.default().decoratedWindow(
            titleBarStyle = if (isDark) TitleBarStyle.dark() else TitleBarStyle.lightWithLightHeader(),
        )

        IntUiTheme(
            theme = theme,
            styling = styling,
        ) {
            // rootComponent 在 application 级别创建，供主窗口和图片查看器共享
            val rootComponent = rememberRootComponent()
            val state by rootComponent.state.collectAsState()

            // ── 主窗口（记忆大小，最小 800×600）──────────────────────────
            val mainWindowState = remember {
                WindowState(
                    size = DpSize(
                        state.settings.mainWindowWidth.dp,
                        state.settings.mainWindowHeight.dp,
                    ),
                    position = WindowPosition.PlatformDefault,
                )
            }

            LaunchedEffect(mainWindowState) {
                snapshotFlow { mainWindowState.size }
                    .collect { size ->
                        val w = size.width; val h = size.height
                        if (w != Dp.Unspecified && h != Dp.Unspecified) {
                            rootComponent.updateSettings(
                                state.settings.copy(
                                    mainWindowWidth = w.value.toInt(),
                                    mainWindowHeight = h.value.toInt(),
                                ),
                            )
                        }
                    }
            }

            DecoratedWindow(
                onCloseRequest = {
                    ExternalDragHelper.uninstall()
                    exitApplication()
                },
                title = "Onyx ${BuildConfig.VERSION}",
                icon = painterResource(Res.drawable.onyx_logo),
                state = mainWindowState,
            ) {
                window.minimumSize = java.awt.Dimension(800, 600)
                // 安装外部拖放支持
                LaunchedEffect(window) {
                    ExternalDragHelper.install(window)
                }
                WindowApp(rootComponent)
            }

            // ── 图片查看器窗口 ──────────────────────────────────────────
            // imageViewerState 独立收集，不触发主窗口 RootState 重组
            val imageViewerState by rootComponent.imageViewerState.collectAsState()
            if (imageViewerState.visible) {
                // WindowState 必须 remember，避免每次重组重置窗口位置和大小
                val viewerWindowState = remember {
                    WindowState(
                        size = DpSize(1200.dp, 800.dp),
                        position = WindowPosition.PlatformDefault,
                    )
                }
                Window(
                    onCloseRequest = rootComponent::closeImageViewer,
                    title = imageViewerState.currentFile?.name ?: "Onyx Viewer",
                    icon = painterResource(Res.drawable.onyx_logo),
                    state = viewerWindowState,
                ) {
                    val appearance = rememberOnyxAppearance(
                        listRowHeightDp = state.settings.listRowHeightDp,
                        listFontSizeSp = state.settings.listFontSizeSp,
                        zebraStripeEnabled = state.settings.zebraStripeEnabled,
                    )
                    OnyxTheme(
                        uiScale = state.settings.uiScale,
                        appearance = appearance,
                    ) {
                        ImageViewerContent(
                            state = imageViewerState,
                            onClose = rootComponent::closeImageViewer,
                            onNext = rootComponent::imageViewerNext,
                            onPrevious = rootComponent::imageViewerPrevious,
                            onSetZoom = rootComponent::imageViewerSetZoom,
                            onSetFitMode = rootComponent::imageViewerSetFitMode,
                            onRotate = rootComponent::imageViewerRotate,
                            loadThumbnail = rootComponent::loadThumbnail,
                            readImageSize = rootComponent::readImageSize,
                        )
                    }
                }
            }
        }
    }
}
