package com.oruke.onyx

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.window.application
import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.cache.OnyxStartupPreloader
import com.oruke.onyx.app.cache.PlatformMenuCacheMaintenanceService
import com.oruke.onyx.app.component.RootWindowKind
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

/** 进程主入口启动时间戳（纳秒），用于度量启动各阶段耗时。 */
internal val processStartNanos: Long = System.nanoTime()

/** 纳秒转毫秒换算常量。 */
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * 计算自 `main()` 启动以来经过的毫秒数。
 *
 * @return 启动经过毫秒数。
 */
internal fun startupElapsedMillis(): Long = (System.nanoTime() - processStartNanos) / NANOS_PER_MILLI

/**
 * 启动 Onyx 桌面应用，并在创建窗口前初始化持久化日志与后台设置预加载。
 *
 * @return 无返回值。
 */
@Suppress("TooGenericExceptionCaught")
fun main() {
    OnyxLogger.initialize()
    OnyxStartupPreloader.start()
    OnyxLogger.info("Startup", "进入 main()，已启动后台设置与会话预加载")
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

            TaskCenterWindow(
                taskCenter = applicationRuntime.taskCenter,
                settings = applicationRuntime.settings,
                resourceLocale = applicationRuntime.resourceLocale,
                onWindowSizeChanged = { width, height, maximized ->
                    applicationRuntime.updateWindowSize(
                        RootWindowKind.TASK_CENTER,
                        width,
                        height,
                        maximized,
                    )
                },
            )

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
