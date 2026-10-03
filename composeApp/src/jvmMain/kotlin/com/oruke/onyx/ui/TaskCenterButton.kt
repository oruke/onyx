package com.oruke.onyx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.component.TaskCenterComponent
import com.oruke.onyx.app.component.TaskCenterIntent
import com.oruke.onyx.app.component.isActiveTask
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_task_entry_summary
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * 在文件窗口标题栏展示任务列表图标与活动数量，并用错误色提示待处理的失败任务。
 *
 * @param component 全应用共享任务中心；重复点击只请求激活同一个窗口。
 * @return 无返回值。
 */
@Composable
internal fun TaskCenterButton(component: TaskCenterComponent) {
    val state by component.state.collectAsState()
    val tasks by component.tasks.collectAsState()
    val activeCount = tasks.count { it.status.isActiveTask() }
    val failedCount = tasks.count { it.status == BackgroundTaskStatus.FAILED }
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    val tooltip = stringResource(Res.string.label_task_entry_summary, activeCount, failedCount)
    LayoutIconButton(
        selected = state.visible,
        onClick = { component.dispatch(TaskCenterIntent.Open) },
        tooltip = tooltip,
        modifier = Modifier.testTag("task-center-open").semantics { contentDescription = tooltip },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(
                key = AllIconsKeys.Toolwindows.ToolWindowTodo,
                contentDescription = null,
                tint = if (failedCount > 0) palette.error else Color.Unspecified,
            )
            if (activeCount > 0) {
                Text(activeCount.toString(), fontSize = appearance.headerFontSize, color = palette.accent)
            }
        }
    }
}
