package com.oruke.onyx

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.window.application
import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.cache.PlatformMenuCacheMaintenanceService
import com.oruke.onyx.app.component.rememberRootApplicationRuntime
import com.oruke.onyx.app.platform.DesktopWindowManager
import com.oruke.onyx.di.fileModule
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
import org.jetbrains.jewel.window.styling.TitleBarStyle
import org.koin.compose.getKoin

/**
 * 启动 Onyx 桌面应用，并在创建窗口前初始化持久化日志。
 *
 * @return 无返回值。
 */
@Suppress("TooGenericExceptionCaught")
fun main() {
    OnyxLogger.initialize()
    try {
        launchOnyxApplication()
    } catch (failure: Throwable) {
        OnyxLogger.error("Startup", "应用启动失败", failure)
        throw failure
    }
}

/**
 * 创建 Compose 应用生命周期并装配依赖、主题和多窗口管理器。
 *
 * @return 无返回值。
 */
private fun launchOnyxApplication() = application {
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
            val koin = getKoin()
            val applicationRuntime = rememberRootApplicationRuntime()
            val windowManager = remember { DesktopWindowManager() }
            val openDirectoryInNewWindow = remember(windowManager) {
                { location: String ->
                    windowManager.openDirectory(location)
                    Unit
                }
            }
            val closeWindow = remember(windowManager) {
                { windowId: Long ->
                    if (windowManager.closeWindow(windowId)) exitApplication()
                }
            }
            val platformMenuCacheMaintenanceService = remember {
                koin.get<PlatformMenuCacheMaintenanceService>()
            }

            LaunchedEffect(platformMenuCacheMaintenanceService) {
                platformMenuCacheMaintenanceService.runUntilCancelled()
            }

            windowManager.windows.forEach { request ->
                key(request.id) {
                    DesktopFileManagerWindow(
                        request = request,
                        applicationRuntime = applicationRuntime,
                        onOpenDirectoryInNewWindow = openDirectoryInNewWindow,
                        onCloseRequest = closeWindow,
                    )
                }
            }
        }
    }
}
