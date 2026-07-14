package com.oruke.onyx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import com.oruke.onyx.app.component.RootApplicationRuntime
import com.oruke.onyx.app.component.RootIntent
import com.oruke.onyx.app.component.RootLaunchConfiguration
import com.oruke.onyx.app.component.SessionRestoreState
import com.oruke.onyx.app.component.rememberRootComponent
import com.oruke.onyx.app.platform.DesktopFileManagerWindowRequest
import com.oruke.onyx.app.platform.ExternalFileDragService
import com.oruke.onyx.app.platform.rememberDesktopWindowStates
import com.oruke.onyx.ui.ImageViewerContent
import com.oruke.onyx.ui.theme.OnyxTheme
import com.oruke.onyx.ui.theme.rememberOnyxAppearance
import java.awt.Dimension
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.onyx_logo
import onyx.composeapp.generated.resources.window_image_viewer_title
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.window.DecoratedWindow
import org.koin.compose.getKoin

/** 文件管理器窗口允许缩放到的最小宽度，单位为物理像素。 */
private const val FILE_MANAGER_WINDOW_MIN_WIDTH = 800

/** 文件管理器窗口允许缩放到的最小高度，单位为物理像素。 */
private const val FILE_MANAGER_WINDOW_MIN_HEIGHT = 600

/**
 * 创建一个具有独立根组件和图片查看器的文件管理器窗口。
 *
 * @param request 当前窗口创建请求。
 * @param applicationRuntime 所有窗口共享的应用运行时。
 * @param onOpenDirectoryInNewWindow 创建目录窗口的应用级动作。
 * @param onCloseRequest 关闭当前窗口的应用级动作。
 * @return 无返回值。
 */
@Composable
internal fun DesktopFileManagerWindow(
    request: DesktopFileManagerWindowRequest,
    applicationRuntime: RootApplicationRuntime,
    onOpenDirectoryInNewWindow: (String) -> Unit,
    onCloseRequest: (Long) -> Unit,
) {
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
    val windowStates = rememberDesktopWindowStates(
        settings = state.settings,
        restorationCompleted = state.sessionRestoreState !is SessionRestoreState.Loading,
        onSettingsChanged = { settings -> rootComponent.dispatch(RootIntent.UpdateSettings(settings)) },
    )

    DecoratedWindow(
        onCloseRequest = { onCloseRequest(request.id) },
        title = "Onyx ${BuildConfig.VERSION}",
        icon = painterResource(Res.drawable.onyx_logo),
        state = windowStates.main,
    ) {
        window.minimumSize = Dimension(FILE_MANAGER_WINDOW_MIN_WIDTH, FILE_MANAGER_WINDOW_MIN_HEIGHT)
        DisposableEffect(window, externalFileDragService) {
            externalFileDragService.install(window)
            onDispose(externalFileDragService::uninstall)
        }
        WindowApp(rootComponent, externalFileDragService)
    }

    val imageViewerState by rootComponent.imageViewerState.collectAsState()
    if (imageViewerState.visible) {
        Window(
            onCloseRequest = { rootComponent.dispatch(RootIntent.CloseImageViewer) },
            title = imageViewerState.currentFile?.name ?: stringResource(Res.string.window_image_viewer_title),
            icon = painterResource(Res.drawable.onyx_logo),
            state = windowStates.imageViewer,
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
