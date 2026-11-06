package com.oruke.onyx.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.oruke.onyx.app.component.MIN_PERSISTED_WINDOW_HEIGHT
import com.oruke.onyx.app.component.MIN_PERSISTED_WINDOW_WIDTH
import com.oruke.onyx.app.component.RootWindowKind
import com.oruke.onyx.core.model.OnyxSettings
import kotlin.math.roundToInt

/** 主窗口与图片查看器窗口的 Compose Desktop 状态集合。 */
internal data class DesktopWindowStates(
    /** 主窗口状态。 */
    val main: WindowState,
    /** 图片查看器窗口状态。 */
    val imageViewer: WindowState,
)

/** 窗口几何与摆放状态的不可变采样快照。 */
private data class WindowGeometrySnapshot(
    /** 当前窗口逻辑尺寸。 */
    val size: DpSize,
    /** 当前窗口摆放状态（浮动、最大化、全屏）。 */
    val placement: WindowPlacement,
    /** 当前窗口是否处于最小化状态。 */
    val isMinimized: Boolean,
)

/**
 * 创建并绑定可持久化的桌面窗口状态。
 *
 * 窗口实例始终保留在应用组合范围内，因此临时关闭图片查看器不会丢失位置、尺寸或最大化状态。
 * 调用方必须在设置恢复完成后再创建本状态，确保原生窗口第一次显示时就使用持久化尺寸。
 *
 * @param settings 已完成恢复的应用设置。
 * @param persistMainWindowSize 是否允许当前窗口更新主窗口持久化尺寸。
 * @param mainWindowVisible 主窗口当前是否可见。
 * @param imageViewerVisible 图片查看器当前是否可见。
 * @param onWindowSizeChanged 只更新指定窗口尺寸与最大化状态的组件事件入口。
 * @return 主窗口与图片查看器窗口状态。
 */
@Composable
internal fun rememberDesktopWindowStates(
    settings: OnyxSettings,
    persistMainWindowSize: Boolean,
    mainWindowVisible: Boolean = true,
    imageViewerVisible: Boolean = false,
    onWindowSizeChanged: (RootWindowKind, Int, Int, Boolean?) -> Unit,
): DesktopWindowStates {
    val mainWindowState = remember {
        WindowState(
            placement = if (settings.mainWindowMaximized) WindowPlacement.Maximized else WindowPlacement.Floating,
            size = DpSize(settings.mainWindowWidth.dp, settings.mainWindowHeight.dp),
            position = WindowPosition.PlatformDefault,
        )
    }
    val imageViewerWindowState = remember {
        WindowState(
            placement = if (settings.imageViewerWindowMaximized) {
                WindowPlacement.Maximized
            } else {
                WindowPlacement.Floating
            },
            size = DpSize(settings.imageViewerWindowWidth.dp, settings.imageViewerWindowHeight.dp),
            position = WindowPosition.PlatformDefault,
        )
    }

    SyncHiddenWindowState(
        windowState = mainWindowState,
        visible = mainWindowVisible,
        width = settings.mainWindowWidth,
        height = settings.mainWindowHeight,
        maximized = settings.mainWindowMaximized,
    )

    SyncHiddenWindowState(
        windowState = imageViewerWindowState,
        visible = imageViewerVisible,
        width = settings.imageViewerWindowWidth,
        height = settings.imageViewerWindowHeight,
        maximized = settings.imageViewerWindowMaximized,
    )

    ObservePersistableWindowState(
        windowState = mainWindowState,
        windowKind = RootWindowKind.MAIN,
        currentWidth = settings.mainWindowWidth,
        currentHeight = settings.mainWindowHeight,
        currentMaximized = settings.mainWindowMaximized,
        enabled = persistMainWindowSize && mainWindowVisible,
        onWindowSizeChanged = onWindowSizeChanged,
    )

    ObservePersistableWindowState(
        windowState = imageViewerWindowState,
        windowKind = RootWindowKind.IMAGE_VIEWER,
        currentWidth = settings.imageViewerWindowWidth,
        currentHeight = settings.imageViewerWindowHeight,
        currentMaximized = settings.imageViewerWindowMaximized,
        enabled = imageViewerVisible,
        onWindowSizeChanged = onWindowSizeChanged,
    )

    return DesktopWindowStates(
        main = mainWindowState,
        imageViewer = imageViewerWindowState,
    )
}

/**
 * 在窗口隐藏期间将持久化设置中的尺寸与最大化状态同步到窗口状态。
 *
 * @param windowState 目标窗口状态。
 * @param visible 窗口当前是否可见。
 * @param width 设置中的目标浮动宽度（dp）。
 * @param height 设置中的目标浮动高度（dp）。
 * @param maximized 设置中的目标最大化状态。
 */
@Composable
internal fun SyncHiddenWindowState(
    windowState: WindowState,
    visible: Boolean,
    width: Int,
    height: Int,
    maximized: Boolean,
) {
    val latestVisible by rememberUpdatedState(visible)
    LaunchedEffect(width, height, maximized) {
        if (latestVisible) return@LaunchedEffect
        val targetSize = DpSize(width.dp, height.dp)
        val targetPlacement = if (maximized) WindowPlacement.Maximized else WindowPlacement.Floating
        if (windowState.size != targetSize) {
            windowState.size = targetSize
        }
        if (windowState.placement != targetPlacement) {
            windowState.placement = targetPlacement
        }
    }
}

/**
 * 观察窗口尺寸与最大化状态变化，并在有效时通知持久化回调。
 *
 * 最小化状态忽略；最大化或全屏状态仅更新最大化标记，不覆盖浮动窗口宽高；
 * 浮动状态下仅当宽高满足最小持久化阈值时才写回。
 *
 * @param windowState 待观察的桌面窗口状态。
 * @param windowKind 窗口类型标识。
 * @param currentWidth 当前已保存的浮动宽度。
 * @param currentHeight 当前已保存的浮动高度。
 * @param currentMaximized 当前已保存的最大化状态。
 * @param enabled 是否启用持久化监听。
 * @param onWindowSizeChanged 窗口尺寸或最大化状态变化回调。
 */
@Composable
internal fun ObservePersistableWindowState(
    windowState: WindowState,
    windowKind: RootWindowKind,
    currentWidth: Int,
    currentHeight: Int,
    currentMaximized: Boolean,
    enabled: Boolean = true,
    onWindowSizeChanged: (RootWindowKind, Int, Int, Boolean?) -> Unit,
) {
    val latestWidth by rememberUpdatedState(currentWidth)
    val latestHeight by rememberUpdatedState(currentHeight)
    val latestMaximized by rememberUpdatedState(currentMaximized)
    val latestOnWindowSizeChanged by rememberUpdatedState(onWindowSizeChanged)

    LaunchedEffect(windowState, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            WindowGeometrySnapshot(
                size = windowState.size,
                placement = windowState.placement,
                isMinimized = windowState.isMinimized,
            )
        }.collect { snapshot ->
            if (snapshot.isMinimized) return@collect
            val maximized = snapshot.placement == WindowPlacement.Maximized ||
                snapshot.placement == WindowPlacement.Fullscreen
            if (maximized) {
                if (!latestMaximized) {
                    latestOnWindowSizeChanged(windowKind, latestWidth, latestHeight, true)
                }
                return@collect
            }
            val width = snapshot.size.width
            val height = snapshot.size.height
            if (width == Dp.Unspecified || height == Dp.Unspecified) return@collect
            val widthDp = width.value.roundToInt()
            val heightDp = height.value.roundToInt()
            if (widthDp < MIN_PERSISTED_WINDOW_WIDTH || heightDp < MIN_PERSISTED_WINDOW_HEIGHT) return@collect
            if (latestWidth == widthDp && latestHeight == heightDp && !latestMaximized) return@collect
            latestOnWindowSizeChanged(windowKind, widthDp, heightDp, false)
        }
    }
}
