package com.oruke.onyx

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import com.oruke.onyx.app.component.TaskCenterComponent
import com.oruke.onyx.app.component.TaskCenterIntent
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.ui.OnyxTooltipHost
import com.oruke.onyx.ui.TaskCenterContent
import com.oruke.onyx.ui.theme.OnyxTheme
import com.oruke.onyx.ui.theme.rememberOnyxAppearance
import java.awt.Dimension
import kotlinx.coroutines.flow.StateFlow
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_task_center
import onyx.composeapp.generated.resources.onyx_logo
import org.jetbrains.compose.resources.painterResource
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

/**
 * 在应用级创建唯一的非模态任务窗口，窗口隐藏不影响任务编排器的生命周期。
 *
 * @param taskCenter 所有文件窗口共享的任务中心组件。
 * @param settings 应用外观设置流，窗口显示时实时响应外观变化。
 * @return 无返回值。
 */
@Composable
internal fun TaskCenterWindow(taskCenter: TaskCenterComponent, settings: StateFlow<OnyxSettings>) {
    val state by taskCenter.state.collectAsState()
    val windowState = rememberWindowState(width = 760.dp, height = 540.dp)
    val listState = rememberLazyListState()
    // 状态保留在可见条件之外，关闭再打开不会丢失尺寸、位置、筛选与滚动位置。
    LaunchedEffect(state.filter) { listState.scrollToItem(0) }
    if (!state.visible) return

    val tasks by taskCenter.tasks.collectAsState()
    val preferences by settings.collectAsState()
    DecoratedWindow(
        onCloseRequest = { taskCenter.dispatch(TaskCenterIntent.Close) },
        title = stringResource(Res.string.label_task_center),
        icon = painterResource(Res.drawable.onyx_logo),
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
        SideEffect {
            window.name = "onyx-task-center"
            // 同步提高最小尺寸，避免高缩放下工具栏挤掉整个任务列表。
            val scale = preferences.uiScale / UI_SCALE_BASE
            window.minimumSize = Dimension(
                (TASK_WINDOW_MIN_WIDTH * scale).toInt(),
                (TASK_WINDOW_MIN_HEIGHT * scale).toInt(),
            )
        }
        LaunchedEffect(state.activationRequest) {
            // 只有显式打开才激活窗口；后台进度刷新不得抢走文件面板焦点。
            windowState.isMinimized = false
            window.toFront()
            window.requestFocus()
        }
        TitleBar { _ ->
            Text(
                stringResource(Res.string.label_task_center),
                modifier = Modifier.align(Alignment.Start).padding(start = 12.dp),
            )
        }
        val appearance = rememberOnyxAppearance(
            listRowHeightDp = preferences.listRowHeightDp,
            listFontSizeSp = preferences.listFontSizeSp,
            zebraStripeEnabled = preferences.zebraStripeEnabled,
        )
        OnyxTheme(uiScale = preferences.uiScale, appearance = appearance) {
            OnyxTooltipHost {
                TaskCenterContent(state, tasks, listState, taskCenter::dispatch)
            }
        }
    }
}
