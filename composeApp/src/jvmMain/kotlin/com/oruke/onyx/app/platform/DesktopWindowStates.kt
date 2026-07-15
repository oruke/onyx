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
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.oruke.onyx.core.model.OnyxSettings

/** 主窗口与图片查看器窗口的 Compose Desktop 状态集合。 */
internal data class DesktopWindowStates(
    /** 主窗口状态。 */
    val main: WindowState,
    /** 图片查看器窗口状态。 */
    val imageViewer: WindowState,
)

/**
 * 创建并绑定可持久化的桌面窗口状态。
 *
 * 窗口实例始终保留在应用组合范围内，因此临时关闭图片查看器不会丢失位置、尺寸或最大化状态。
 * 调用方必须在设置恢复完成后再创建本状态，确保原生窗口第一次显示时就使用持久化尺寸。
 *
 * @param settings 已完成恢复的应用设置。
 * @param persistMainWindowSize 是否允许当前窗口更新主窗口持久化尺寸。
 * @param onSettingsChanged 窗口尺寸变化后的设置更新回调。
 * @return 主窗口与图片查看器窗口状态。
 */
@Composable
internal fun rememberDesktopWindowStates(
    settings: OnyxSettings,
    persistMainWindowSize: Boolean,
    onSettingsChanged: (OnyxSettings) -> Unit,
): DesktopWindowStates {
    val latestSettings by rememberUpdatedState(settings)
    val latestOnSettingsChanged by rememberUpdatedState(onSettingsChanged)
    val mainWindowState = remember {
        WindowState(
            size = DpSize(settings.mainWindowWidth.dp, settings.mainWindowHeight.dp),
            position = WindowPosition.PlatformDefault,
        )
    }
    val imageViewerWindowState = remember {
        WindowState(
            size = DpSize(settings.imageViewerWindowWidth.dp, settings.imageViewerWindowHeight.dp),
            position = WindowPosition.PlatformDefault,
        )
    }

    LaunchedEffect(mainWindowState, persistMainWindowSize) {
        if (!persistMainWindowSize) return@LaunchedEffect
        snapshotFlow { mainWindowState.size }.collect { size ->
            val width = size.width
            val height = size.height
            val current = latestSettings
            if (width == Dp.Unspecified || height == Dp.Unspecified) return@collect
            if (current.mainWindowWidth == width.value.toInt() &&
                current.mainWindowHeight == height.value.toInt()
            ) return@collect
            latestOnSettingsChanged(
                current.copy(
                    mainWindowWidth = width.value.toInt(),
                    mainWindowHeight = height.value.toInt(),
                )
            )
        }
    }

    LaunchedEffect(imageViewerWindowState) {
        snapshotFlow { imageViewerWindowState.size }.collect { size ->
            val width = size.width
            val height = size.height
            val current = latestSettings
            if (width == Dp.Unspecified || height == Dp.Unspecified) return@collect
            if (current.imageViewerWindowWidth == width.value.toInt() &&
                current.imageViewerWindowHeight == height.value.toInt()
            ) return@collect
            latestOnSettingsChanged(
                current.copy(
                    imageViewerWindowWidth = width.value.toInt(),
                    imageViewerWindowHeight = height.value.toInt(),
                )
            )
        }
    }

    return DesktopWindowStates(
        main = mainWindowState,
        imageViewer = imageViewerWindowState,
    )
}
