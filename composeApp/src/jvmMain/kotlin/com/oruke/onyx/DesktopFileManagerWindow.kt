package com.oruke.onyx

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
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
import com.oruke.onyx.app.platform.DesktopFileManagerWindowRequest
import com.oruke.onyx.app.platform.ExternalFileDragService
import com.oruke.onyx.app.platform.OnyxStartupSplash
import com.oruke.onyx.app.platform.StartupSplashStage
import com.oruke.onyx.app.platform.activateFromUserRequest
import com.oruke.onyx.app.platform.rememberDesktopWindowStates
import com.oruke.onyx.core.model.AppLocale
import com.oruke.onyx.core.model.ImageViewerState
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.ui.ImageViewerContent
import com.oruke.onyx.ui.SearchPanelPresentation
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
import org.jetbrains.jewel.ui.component.Text
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

    OnyxDecoratedWindowTheme(visualSettings) {
        DecoratedWindow(
            onCloseRequest = { onCloseRequest(request.id) },
            visible = request.visible,
            title = "Onyx ${BuildConfig.VERSION}",
            icon = windowIcon,
            state = windowStates.main,
        ) {
            window.minimumSize = Dimension(FILE_MANAGER_WINDOW_MIN_WIDTH, FILE_MANAGER_WINDOW_MIN_HEIGHT)
            DisposableEffect(window, request.id) {
                OnyxLogger.info(
                    "Startup",
                    "主窗口已创建 (id=${request.id}, visible=${request.visible}, " +
                        "size=${window.width}x${window.height}, " +
                        "extendedState=${window.extendedState}, elapsed=${startupElapsedMillis()}ms)",
                )
                onDispose {}
            }
            ObserveResidentWindowLifecycle(
                window = window,
                windowState = windowStates.main,
                request = request,
                rootComponent = rootComponent,
                onConsumePendingOpenLocation = onConsumePendingOpenLocation,
            )
            DisposableEffect(window, externalFileDragService) {
                externalFileDragService.install(window)
                onDispose(externalFileDragService::uninstall)
            }
            OnyxLocaleEnvironment(applicationRuntime.resourceLocale) {
                WindowApp(rootComponent, externalFileDragService)
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
    val viewerTitle = imageViewerState.currentFile?.name ?: stringResource(Res.string.window_image_viewer_title)
    DecoratedWindow(
        onCloseRequest = { rootComponent.dispatch(RootIntent.CloseImageViewer) },
        title = viewerTitle,
        icon = rememberOnyxWindowIconPainter(),
        state = windowState,
    ) {
        window.minimumSize = Dimension(IMAGE_VIEWER_WINDOW_MIN_WIDTH, IMAGE_VIEWER_WINDOW_MIN_HEIGHT)
        TitleBar { _ ->
            Text(
                text = viewerTitle,
                modifier = Modifier.align(Alignment.Start).padding(start = 12.dp),
            )
        }
        OnyxLocaleEnvironment(resourceLocale) {
            OnyxWindowTheme(visualSettings) {
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
                )
            }
        }
    }
}

/**
 * 监听常驻主窗口的显隐与单实例唤醒请求，在首帧就绪后关闭启动动画，在唤醒时置前聚焦并刷新目录，在隐藏时立即落盘设置与会话。
 */
@Composable
private fun ObserveResidentWindowLifecycle(
    window: ComposeWindow,
    windowState: WindowState,
    request: DesktopFileManagerWindowRequest,
    rootComponent: RootComponent,
    onConsumePendingOpenLocation: (Long) -> Unit,
) {
    LaunchedEffect(window, request.id) {
        withFrameNanos { }
        OnyxStartupSplash.dismiss()
    }
    LaunchedEffect(request.activationRequest, request.visible) {
        if (request.visible) {
            if (request.activationRequest > 0L) {
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
                    "热驻留主窗口已秒起唤醒 (activation=${request.activationRequest})",
                )
            }
        } else {
            if (rootComponent.state.value.searchState.visible) {
                rootComponent.dispatch(RootIntent.CloseSearchPanel)
            }
            if (rootComponent.imageViewerState.value.visible) {
                rootComponent.dispatch(RootIntent.CloseImageViewer)
            }
            rootComponent.dispatch(RootIntent.FlushPersistedState)
            OnyxLogger.info("Startup", "主窗口已隐藏并转入后台热驻留")
        }
    }
}
