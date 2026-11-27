package com.oruke.onyx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.component.RootApplicationRuntime
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.RootIntent
import com.oruke.onyx.app.component.RootLaunchConfiguration
import com.oruke.onyx.app.component.RootWindowKind
import com.oruke.onyx.app.component.SessionRestoreState
import com.oruke.onyx.app.component.effectiveSettings
import com.oruke.onyx.app.component.rememberRootComponent
import com.oruke.onyx.app.platform.BindWindowsTaskbarIdentity
import com.oruke.onyx.app.platform.DesktopFileManagerWindowRequest
import com.oruke.onyx.app.platform.ExternalFileDragService
import com.oruke.onyx.app.platform.OnyxStartupSplash
import com.oruke.onyx.app.platform.StartupSplashStage
import com.oruke.onyx.app.platform.activateFromUserRequest
import com.oruke.onyx.app.platform.rememberDesktopWindowStates
import com.oruke.onyx.core.model.AppLocale
import com.oruke.onyx.core.model.ImageViewerState
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.decodeSfmDisplayText
import com.oruke.onyx.ui.ImageViewerContent
import com.oruke.onyx.ui.OnyxTooltipHost
import com.oruke.onyx.ui.SearchPanelPresentation
import com.oruke.onyx.ui.TitleBarBrandTitle
import com.oruke.onyx.ui.WindowKeyboardFocusRegistry
import com.oruke.onyx.ui.handleWindowGlobalKeyEvent
import com.oruke.onyx.ui.searchWindowActions
import com.oruke.onyx.ui.theme.OnyxDecoratedWindowTheme
import com.oruke.onyx.ui.theme.OnyxLocaleEnvironment
import com.oruke.onyx.ui.theme.OnyxWindowTheme
import com.oruke.onyx.ui.theme.rememberOnyxWindowIconPainter
import java.awt.Dimension
import kotlinx.coroutines.flow.StateFlow
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.window_image_viewer_title
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar
import org.koin.compose.getKoin

/** 文件管理器窗口允许缩放到的最小宽度，单位为物理像素。 */
private const val FILE_MANAGER_WINDOW_MIN_WIDTH = 800

/** 文件管理器窗口允许缩放到的最小高度，单位为物理像素。 */
private const val FILE_MANAGER_WINDOW_MIN_HEIGHT = 600

/** 图片查看器窗口允许缩放到的最小宽度，单位为物理像素。 */
private const val IMAGE_VIEWER_WINDOW_MIN_WIDTH = 480

/** 图片查看器窗口允许缩放到的最小高度，单位为物理像素。 */
private const val IMAGE_VIEWER_WINDOW_MIN_HEIGHT = 360

/**
 * 创建一个具有独立根组件和图片查看器的文件管理器窗口。
 *
 * @param request 当前窗口创建请求。
 * @param applicationRuntime 所有窗口共享的应用运行时。
 * @param onOpenDirectoryInNewWindow 创建目录窗口的应用级动作。
 * @param onCloseRequest 关闭当前窗口的应用级动作。
 * @param onConsumePendingOpenLocation 消费外部唤醒传入的待打开目录动作。
 * @return 无返回值。
 */
@Composable
internal fun DesktopFileManagerWindow(
    request: DesktopFileManagerWindowRequest,
    applicationRuntime: RootApplicationRuntime,
    onOpenDirectoryInNewWindow: (String) -> Unit,
    onCloseRequest: (Long) -> Unit,
    onConsumePendingOpenLocation: (Long) -> Unit = {},
) {
    OnyxStartupSplash.updateStage(StartupSplashStage.RESTORING_WORKSPACE)
    val koin = getKoin()
    val externalFileDragService = remember(request.id) { koin.get<ExternalFileDragService>() }
    val launchConfiguration = remember(request.id, onOpenDirectoryInNewWindow) {
        RootLaunchConfiguration(
            initialLocation = request.initialLocation,
            persistsMainSession = request.persistsMainSession,
            onOpenDirectoryInNewWindow = onOpenDirectoryInNewWindow,
        )
    }
    val rootComponent = rememberRootComponent(
        applicationRuntime = applicationRuntime,
        externalFileDragService = externalFileDragService,
        launchConfiguration = launchConfiguration,
    )
    DisposableEffect(externalFileDragService) {
        onDispose(externalFileDragService::dispose)
    }
    val state by rootComponent.state.collectAsState()
    val imageViewerState by rootComponent.imageViewerState.collectAsState()
    // 必须先恢复持久化尺寸再创建原生窗口，否则 Windows 会先绘制默认尺寸并产生可见缩放。
    if (state.sessionRestoreState is SessionRestoreState.Loading) return

    val visualSettings = state.effectiveSettings
    val windowStates = rememberDesktopWindowStates(
        settings = state.settings,
        persistMainWindowSize = request.persistsMainSession,
        mainWindowVisible = request.visible,
        imageViewerVisible = imageViewerState.visible,
        onWindowSizeChanged = { kind, width, height, maximized ->
            rootComponent.dispatch(RootIntent.UpdateWindowSize(kind, width, height, maximized))
        },
    )
    val windowIcon = rememberOnyxWindowIconPainter()
    val keyboardFocusRegistry = remember(request.id) { WindowKeyboardFocusRegistry() }

    // 窗口隐藏入托盘（或 --background 静默启动）时同样需要关闭启动动画、收起浮层并落盘状态。
    ObserveResidentWindowVisibility(
        request = request,
        rootComponent = rootComponent,
    )

    OnyxDecoratedWindowTheme(visualSettings) {
        // 隐藏到系统托盘时彻底从组合树卸载 DecoratedWindow，释放 Skiko D3D12 渲染上下文、字体图集与整棵 UI 树，
        // 同时保留上层轻量 RootComponent 状态以维持标签、滚动位置、撤销历史，唤醒后免重扫慢 NAS。
        if (request.visible) {
            DecoratedWindow(
                onCloseRequest = { onCloseRequest(request.id) },
                visible = true,
                title = "Onyx ${BuildConfig.VERSION}",
                icon = windowIcon,
                state = windowStates.main,
                onPreviewKeyEvent = { event ->
                    handleWindowGlobalKeyEvent(
                        event = event,
                        state = rootComponent.state.value,
                        rootComponent = rootComponent,
                        focusRegistry = keyboardFocusRegistry,
                    )
                },
            ) {
                BindWindowsTaskbarIdentity(window)
                window.minimumSize = Dimension(FILE_MANAGER_WINDOW_MIN_WIDTH, FILE_MANAGER_WINDOW_MIN_HEIGHT)
                DisposableEffect(window, request.id) {
                    OnyxLogger.info(
                        "Startup",
                        "主窗口已创建 (id=${request.id}, visible=true, " +
                            "size=${window.width}x${window.height}, " +
                            "extendedState=${window.extendedState}, elapsed=${startupElapsedMillis()}ms)",
                    )
                    onDispose {
                        OnyxLogger.info("Startup", "主窗口 UI 与 Skiko 渲染上下文已卸载释放 (id=${request.id})")
                    }
                }
                ObserveActiveWindowFocusAndWake(
                    window = window,
                    windowState = windowStates.main,
                    request = request,
                    ownerWindowActivationRequest = state.windowActivationRequest,
                    rootComponent = rootComponent,
                    onConsumePendingOpenLocation = onConsumePendingOpenLocation,
                )
                DisposableEffect(window, externalFileDragService) {
                    externalFileDragService.install(window)
                    onDispose(externalFileDragService::uninstall)
                }
                OnyxLocaleEnvironment(applicationRuntime.resourceLocale) {
                    WindowApp(rootComponent, externalFileDragService, keyboardFocusRegistry)
                }
            }
        }

        SearchWindow(
            state = state.searchState,
            presentation = SearchPanelPresentation(visualSettings.searchHistory, rootComponent::locationLabel),
            actions = rootComponent.searchWindowActions(),
            settings = visualSettings,
            resourceLocale = applicationRuntime.resourceLocale,
            onWindowSizeChanged = { width, height, maximized ->
                rootComponent.dispatch(RootIntent.UpdateWindowSize(RootWindowKind.SEARCH, width, height, maximized))
            },
        )

        if (imageViewerState.visible) {
            ImageViewerWindow(
                imageViewerState = imageViewerState,
                rootComponent = rootComponent,
                visualSettings = visualSettings,
                resourceLocale = applicationRuntime.resourceLocale,
                windowState = windowStates.imageViewer,
            )
        }
    }
}

/**
 * 渲染与主窗口共享沉浸式标题栏与主题管线的独立图片查看器窗口。
 */
@Composable
private fun ImageViewerWindow(
    imageViewerState: ImageViewerState,
    rootComponent: RootComponent,
    visualSettings: OnyxSettings,
    resourceLocale: StateFlow<AppLocale>,
    windowState: WindowState,
) {
    val viewerTitle = imageViewerState.currentFile?.name?.decodeSfmDisplayText()
        ?: stringResource(Res.string.window_image_viewer_title)
    DecoratedWindow(
        onCloseRequest = { rootComponent.dispatch(RootIntent.CloseImageViewer) },
        title = viewerTitle,
        icon = rememberOnyxWindowIconPainter(),
        state = windowState,
    ) {
        BindWindowsTaskbarIdentity(window)
        window.minimumSize = Dimension(IMAGE_VIEWER_WINDOW_MIN_WIDTH, IMAGE_VIEWER_WINDOW_MIN_HEIGHT)
        TitleBar { _ ->
            TitleBarBrandTitle(
                title = viewerTitle,
                startPadding = 12.dp,
            )
        }
        OnyxLocaleEnvironment(resourceLocale) {
            OnyxWindowTheme(visualSettings) {
                OnyxTooltipHost {
                    var placementBeforeFullscreen by remember { mutableStateOf(WindowPlacement.Floating) }
                    ImageViewerContent(
                        state = imageViewerState,
                        onClose = { rootComponent.dispatch(RootIntent.CloseImageViewer) },
                        onNext = { rootComponent.dispatch(RootIntent.ImageViewerNext) },
                        onPrevious = { rootComponent.dispatch(RootIntent.ImageViewerPrevious) },
                        onSetZoom = { factor -> rootComponent.dispatch(RootIntent.ImageViewerSetZoom(factor)) },
                        onSetFitMode = { mode -> rootComponent.dispatch(RootIntent.ImageViewerSetFitMode(mode)) },
                        onRotate = { clockwise -> rootComponent.dispatch(RootIntent.ImageViewerRotate(clockwise)) },
                        loadThumbnail = rootComponent::loadThumbnail,
                        readImageSize = rootComponent::readImageSize,
                        loadViewerImage = rootComponent::loadViewerImage,
                        onOpenExternally = { entry -> rootComponent.dispatch(RootIntent.OpenExternalFile(entry)) },
                        onToggleFullscreen = {
                            if (windowState.placement == WindowPlacement.Fullscreen) {
                                windowState.placement = placementBeforeFullscreen
                            } else {
                                placementBeforeFullscreen = windowState.placement
                                windowState.placement = WindowPlacement.Fullscreen
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 监听主窗口隐藏转入后台托盘驻留时的收尾动作：收起模态弹窗、搜索窗与看图器，并立即刷盘保存设置与会话。
 *
 * 位于 `DecoratedWindow` 之外，确保彻底卸载窗口节点释放 Skiko 上下文后仍能稳定触发落盘。
 *
 * @param request 当前窗口请求状态。
 * @param rootComponent 窗口绑定的根业务组件。
 */
@Composable
private fun ObserveResidentWindowVisibility(
    request: DesktopFileManagerWindowRequest,
    rootComponent: RootComponent,
) {
    LaunchedEffect(request.visible) {
        if (!request.visible) {
            OnyxStartupSplash.dismiss()
            if (rootComponent.state.value.dialogState != null) {
                rootComponent.dispatch(RootIntent.DismissDialog)
            }
            if (rootComponent.state.value.searchState.visible) {
                rootComponent.dispatch(RootIntent.CloseSearchPanel)
            }
            if (rootComponent.imageViewerState.value.visible) {
                rootComponent.dispatch(RootIntent.CloseImageViewer)
            }
            rootComponent.dispatch(RootIntent.FlushPersistedState)
            OnyxLogger.info("Startup", "主窗口已隐藏并转入后台轻量驻留（已卸载 Skiko 窗口上下文）")
        }
    }
}

/**
 * 监听活动主窗口的首帧就绪与单实例唤醒请求，在首帧绘制后关闭启动动画，并在唤醒时置前聚焦与刷新目录。
 *
 * @param window 当前活跃原生窗口实例。
 * @param windowState 主窗口尺寸与摆放状态。
 * @param request 当前窗口请求。
 * @param ownerWindowActivationRequest 子窗口发起的主窗口置前信号。
 * @param rootComponent 窗口绑定的根业务组件。
 * @param onConsumePendingOpenLocation 消费外部传入待打开目录的回调。
 */
@Composable
private fun ObserveActiveWindowFocusAndWake(
    window: ComposeWindow,
    windowState: WindowState,
    request: DesktopFileManagerWindowRequest,
    ownerWindowActivationRequest: Long,
    rootComponent: RootComponent,
    onConsumePendingOpenLocation: (Long) -> Unit,
) {
    LaunchedEffect(window, request.id) {
        withFrameNanos { }
        OnyxStartupSplash.dismiss()
    }
    LaunchedEffect(ownerWindowActivationRequest, window) {
        if (ownerWindowActivationRequest > 0L) {
            window.activateFromUserRequest(windowState)
        }
    }
    LaunchedEffect(window, request.activationRequest) {
        if (request.activationRequest > 0L) {
            // 窗口刚从托盘重建挂载时等待首帧布局完成再置前，避免 AWT 焦点竞争。
            withFrameNanos { }
            window.activateFromUserRequest(windowState)
            val targetLocation = request.pendingOpenLocation
            if (!targetLocation.isNullOrBlank()) {
                rootComponent.dispatch(RootIntent.OpenLocationInActivePane(targetLocation))
                onConsumePendingOpenLocation(request.id)
            } else {
                rootComponent.dispatch(RootIntent.RefreshActivePane)
            }
            OnyxLogger.info(
                "Startup",
                "轻量驻留主窗口已重建并唤醒 (activation=${request.activationRequest})",
            )
        }
    }
}
