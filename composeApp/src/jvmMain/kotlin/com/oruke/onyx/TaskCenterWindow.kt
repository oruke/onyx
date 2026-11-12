package com.oruke.onyx

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import com.oruke.onyx.app.component.RootWindowKind
import com.oruke.onyx.app.component.TaskCenterComponent
import com.oruke.onyx.app.component.TaskCenterIntent
import com.oruke.onyx.app.component.TaskCenterState
import com.oruke.onyx.app.component.shouldAutoCloseTaskCenter
import com.oruke.onyx.app.platform.ObservePersistableWindowState
import com.oruke.onyx.app.platform.SyncHiddenWindowState
import com.oruke.onyx.app.platform.activateFromUserRequest
import com.oruke.onyx.app.platform.revealFromBackgroundTask
import com.oruke.onyx.core.model.AppLocale
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.ui.OnyxTooltipHost
import com.oruke.onyx.ui.TaskCenterContent
import com.oruke.onyx.ui.theme.OnyxDecoratedWindowTheme
import com.oruke.onyx.ui.theme.OnyxLocaleEnvironment
import com.oruke.onyx.ui.theme.OnyxWindowTheme
import com.oruke.onyx.ui.theme.rememberOnyxWindowIconPainter
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.awt.Window
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_task_center
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar

/** 任务窗口的最小逻辑宽度，保证分类与任务动作可操作。 */
private const val TASK_WINDOW_MIN_WIDTH = 560

/** 任务窗口的最小逻辑高度，为工具栏之外保留任务列表空间。 */
private const val TASK_WINDOW_MIN_HEIGHT = 360

/** UI 缩放设置的百分比基数。 */
private const val UI_SCALE_BASE = 100.0

/** 自动弹出的任务窗口在全部任务成功完成后停留展示的默认毫秒数（早于 5 秒任务自动归档）。 */
internal const val TASK_WINDOW_AUTO_CLOSE_DELAY_MILLIS = 2_400L

/**
 * 在应用级创建唯一的非模态任务窗口，窗口隐藏不影响任务编排器的生命周期。
 *
 * @param taskCenter 所有文件窗口共享的任务中心组件。
 * @param settings 应用外观设置流，窗口显示时实时响应外观变化。
 * @param resourceLocale 已切换完成的应用级资源语言信号。
 * @param onWindowSizeChanged 窗口尺寸或最大化状态变化回调。
 * @param autoCloseDelayMillis 自动打开的任务窗口在全部成功完成后的停留毫秒数。
 * @return 无返回值。
 */
@Composable
internal fun TaskCenterWindow(
    taskCenter: TaskCenterComponent,
    settings: StateFlow<OnyxSettings>,
    resourceLocale: StateFlow<AppLocale>,
    onWindowSizeChanged: ((width: Int, height: Int, maximized: Boolean?) -> Unit)? = null,
    autoCloseDelayMillis: Long = TASK_WINDOW_AUTO_CLOSE_DELAY_MILLIS,
) {
    val state by taskCenter.state.collectAsState()
    val autoRevealRequest by taskCenter.autoRevealRequest.collectAsState()
    val preferences by settings.collectAsState()
    val windowState = rememberSyncedTaskWindowState(preferences, state.visible, onWindowSizeChanged)
    val listState = rememberLazyListState()
    var hasOpenedOnce by remember { mutableStateOf(false) }
    var previousFocusedWindow by remember { mutableStateOf<Window?>(null) }
    if (state.visible && !hasOpenedOnce) {
        hasOpenedOnce = true
        previousFocusedWindow = capturePreviousFocusedWindow(state.openedAutomatically)
    }
    LaunchedEffect(state.filter) { listState.scrollToItem(0) }
    if (!hasOpenedOnce) return

    var pointerHovered by remember { mutableStateOf(false) }
    LaunchedEffect(state.visible) {
        if (!state.visible) pointerHovered = false
    }

    val tasks by taskCenter.tasks.collectAsState()
    val shouldAutoClose = shouldAutoCloseTaskCenter(state, tasks, preferences.autoCloseTaskCenterWhenFinished)
    val completionKey = remember(tasks) { tasks.firstOrNull()?.let { "${it.id}:${it.status}" }.orEmpty() }
    LaunchedEffect(shouldAutoClose, pointerHovered, completionKey, autoCloseDelayMillis) {
        if (!shouldAutoClose || pointerHovered) return@LaunchedEffect
        delay(autoCloseDelayMillis)
        taskCenter.dispatch(TaskCenterIntent.AutoCloseWhenIdle)
    }

    OnyxDecoratedWindowTheme(preferences) {
        OnyxLocaleEnvironment(resourceLocale) {
            DecoratedWindow(
                onCloseRequest = { taskCenter.dispatch(TaskCenterIntent.Close) },
                visible = state.visible,
                title = stringResource(Res.string.label_task_center),
                icon = rememberOnyxWindowIconPainter(),
                state = windowState,
                onPreviewKeyEvent = { event ->
                    if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                        taskCenter.dispatch(TaskCenterIntent.Close)
                        true
                    } else {
                        false
                    }
                },
            ) {
                BindNativeTaskWindowActivation(
                    window = window,
                    windowState = windowState,
                    state = state,
                    autoRevealRequest = autoRevealRequest,
                    uiScale = preferences.uiScale,
                    previousFocusedWindow = previousFocusedWindow,
                )
                TitleBar { _ ->
                    Text(
                        stringResource(Res.string.label_task_center),
                        modifier = Modifier.align(Alignment.Start).padding(start = 12.dp),
                    )
                }
                if (state.visible) {
                    OnyxWindowTheme(preferences) {
                        OnyxTooltipHost {
                            TaskCenterContent(
                                state = state,
                                tasks = tasks,
                                listState = listState,
                                dispatch = taskCenter::dispatch,
                                autoCloseWhenFinished = preferences.autoCloseTaskCenterWhenFinished,
                                autoClosingSoon = shouldAutoClose,
                                autoClosePausedByHover = pointerHovered,
                                modifier = Modifier.trackTaskWindowHover { pointerHovered = it },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberSyncedTaskWindowState(
    preferences: OnyxSettings,
    visible: Boolean,
    onWindowSizeChanged: ((width: Int, height: Int, maximized: Boolean?) -> Unit)?,
): WindowState {
    val windowState = rememberWindowState(
        placement = if (preferences.taskCenterWindowMaximized) {
            WindowPlacement.Maximized
        } else {
            WindowPlacement.Floating
        },
        width = preferences.taskCenterWindowWidth.dp,
        height = preferences.taskCenterWindowHeight.dp,
    )
    SyncHiddenWindowState(
        windowState = windowState,
        visible = visible,
        width = preferences.taskCenterWindowWidth,
        height = preferences.taskCenterWindowHeight,
        maximized = preferences.taskCenterWindowMaximized,
    )
    if (onWindowSizeChanged != null) {
        ObservePersistableWindowState(
            windowState = windowState,
            windowKind = RootWindowKind.TASK_CENTER,
            currentWidth = preferences.taskCenterWindowWidth,
            currentHeight = preferences.taskCenterWindowHeight,
            currentMaximized = preferences.taskCenterWindowMaximized,
            enabled = visible,
            onWindowSizeChanged = { _, width, height, maximized ->
                onWindowSizeChanged(width, height, maximized)
            },
        )
    }
    return windowState
}

private fun capturePreviousFocusedWindow(openedAutomatically: Boolean): Window? {
    if (!openedAutomatically) return null
    return KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
        ?.takeIf { candidate -> candidate.name != "onyx-task-center" }
}

@Composable
private fun BindNativeTaskWindowActivation(
    window: ComposeWindow,
    windowState: WindowState,
    state: TaskCenterState,
    autoRevealRequest: Long,
    uiScale: Int,
    previousFocusedWindow: Window?,
) {
    var handledActivationRequest by remember { mutableStateOf(0L) }
    var handledAutoRevealRequest by remember { mutableStateOf(0L) }
    SideEffect {
        window.name = "onyx-task-center"
        if (!state.visible) {
            window.isAutoRequestFocus = false
        }
        val scale = uiScale / UI_SCALE_BASE
        window.minimumSize = Dimension(
            (TASK_WINDOW_MIN_WIDTH * scale).toInt(),
            (TASK_WINDOW_MIN_HEIGHT * scale).toInt(),
        )
    }
    LaunchedEffect(state.activationRequest, state.visible) {
        if (state.visible && state.activationRequest != handledActivationRequest) {
            handledActivationRequest = state.activationRequest
            window.activateFromUserRequest(windowState)
        }
    }
    LaunchedEffect(autoRevealRequest, state.visible) {
        if (state.visible && autoRevealRequest != handledAutoRevealRequest) {
            handledAutoRevealRequest = autoRevealRequest
            if (state.openedAutomatically) {
                val ownerFocus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
                    ?.takeIf { candidate -> candidate !== window }
                    ?: previousFocusedWindow
                window.revealFromBackgroundTask(windowState, ownerFocus)
            }
        }
    }
}

private fun Modifier.trackTaskWindowHover(onHoverChanged: (Boolean) -> Unit): Modifier {
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                when (event.type) {
                    PointerEventType.Move,
                    PointerEventType.Press -> onHoverChanged(true)
                    PointerEventType.Exit -> onHoverChanged(false)
                    else -> Unit
                }
            }
        }
    }
}
