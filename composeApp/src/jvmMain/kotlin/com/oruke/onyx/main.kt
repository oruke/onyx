package com.oruke.onyx

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.window.application
import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.cache.OnyxStartupPreloader
import com.oruke.onyx.app.cache.PlatformMenuCacheMaintenanceService
import com.oruke.onyx.app.component.RootWindowKind
import com.oruke.onyx.app.component.rememberRootApplicationRuntime
import com.oruke.onyx.app.platform.DesktopWindowManager
import com.oruke.onyx.app.platform.OnyxSingleInstanceManager
import com.oruke.onyx.app.platform.OnyxSingleInstanceRelay
import com.oruke.onyx.app.platform.OnyxStartupSplash
import com.oruke.onyx.app.platform.OnyxSystemTrayController
import com.oruke.onyx.app.platform.StartupSplashStage
import com.oruke.onyx.ui.theme.OnyxDecoratedWindowTheme
import org.koin.compose.KoinIsolatedContext
import org.koin.compose.getKoin
import kotlin.system.exitProcess

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
 * 启动 Onyx 桌面应用；若本机已有同版本常驻热实例则直接通过本地回环 IPC 毫秒级秒起唤醒并退出当前进程。
 *
 * @param args 命令行启动参数。
 * @return 无返回值。
 */
fun main(args: Array<String>) {
    if (OnyxSingleInstanceManager.tryForwardToRunningInstance(args)) {
        exitProcess(0)
    }
    OnyxDesktopRuntime.start(args)
}

/**
 * 承载实际 Compose Desktop 与 Koin 运行时初始化，与 `MainKt` 分离以避免二次秒起转发进程触发重型类验证。
 */
private object OnyxDesktopRuntime {
    /**
     * 初始化日志、启动动画、早期单实例锁与后台预加载器并启动桌面应用窗口。
     *
     * @param args 命令行启动参数。
     */
    @Suppress("TooGenericExceptionCaught")
    fun start(args: Array<String>) {
        OnyxLogger.initialize()
        OnyxStartupSplash.showIfInteractive(args)
        val singleInstanceRelay = OnyxSingleInstanceRelay()
        OnyxStartupPreloader.start()
        OnyxLogger.info("Startup", "进入 main()，已启动启动动画、早期单实例锁与后台并行预加载")
        try {
            launchOnyxApplication(args, singleInstanceRelay)
        } catch (failure: Throwable) {
            OnyxStartupSplash.dismiss()
            singleInstanceRelay.close()
            OnyxLogger.error("Startup", "应用启动失败", failure)
            throw failure
        }
    }

    /**
     * 创建 Compose 应用生命周期并装配预热依赖、主题、单实例热驻留服务与多窗口管理器。
     *
     * @param args 命令行启动参数。
     * @param singleInstanceRelay 早期单实例 IPC 中继器。
     * @return 无返回值。
     */
    private fun launchOnyxApplication(
        args: Array<String>,
        singleInstanceRelay: OnyxSingleInstanceRelay,
    ) = application {
        OnyxStartupSplash.updateStage(StartupSplashStage.INITIALIZING_UI)
        val koinApplication = remember { OnyxStartupPreloader.obtainKoinApplication() }
        DisposableEffect(koinApplication) {
            onDispose {
                koinApplication.close()
            }
        }
        KoinIsolatedContext(context = koinApplication) {
            val koin = getKoin()
            val applicationRuntime = rememberRootApplicationRuntime()
            val appSettings by applicationRuntime.settings.collectAsState()

            OnyxDecoratedWindowTheme(appSettings) {
                val windowManager = remember {
                    DesktopWindowManager(
                        keepPrimaryWindowResident = appSettings.closeToTray,
                        primaryWindowInitiallyVisible = !OnyxSingleInstanceManager.isBackgroundLaunch(args),
                        initialPrimaryLocation = OnyxSingleInstanceManager.extractInitialLocation(args),
                    )
                }
                SideEffect {
                    windowManager.keepPrimaryWindowResident = appSettings.closeToTray
                }
                DisposableEffect(windowManager) {
                    singleInstanceRelay.bindWindowCallbacks(
                        onActivate = { location ->
                            windowManager.activatePrimaryWindow(location)
                        },
                        onShutdown = ::exitApplication,
                    )
                    val trayHandle = OnyxSystemTrayController.install(
                        onOpenWindow = { windowManager.activatePrimaryWindow() },
                        onExitApplication = ::exitApplication,
                    )
                    onDispose {
                        trayHandle.close()
                        singleInstanceRelay.close()
                    }
                }
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
                val consumePendingOpenLocation = remember(windowManager) {
                    { windowId: Long ->
                        windowManager.consumePendingOpenLocation(windowId)
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
                            onConsumePendingOpenLocation = consumePendingOpenLocation,
                        )
                    }
                }
            }
        }
    }
}
