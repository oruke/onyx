package com.oruke.onyx.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.oruke.onyx.app.component.TaskCenterComponent
import com.oruke.onyx.app.component.TaskCenterFilter
import com.oruke.onyx.app.component.TaskCenterIntent
import com.oruke.onyx.app.component.isActiveTask
import com.oruke.onyx.core.model.BackgroundTaskStatus
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_task_entry_summary
import org.jetbrains.compose.resources.stringResource

/**
 * 在主窗口底部状态栏展示后台任务实时进度、异常提醒或最近完成反馈；点击可直接唤起任务窗口。
 *
 * @param component 应用级共享任务中心组件。
 */
@Composable
internal fun StatusBarTaskIndicator(component: TaskCenterComponent) {
    val tasks by component.tasks.collectAsState()
    if (tasks.isEmpty()) return

    val activeTasks = remember(tasks) { tasks.filter { it.status.isActiveTask() } }
    val failedTasks = remember(tasks) { tasks.filter { it.status == BackgroundTaskStatus.FAILED } }
    val latestSucceeded = remember(tasks) {
        tasks.firstOrNull { it.status == BackgroundTaskStatus.SUCCEEDED }
    }
    if (activeTasks.isEmpty() && failedTasks.isEmpty() && latestSucceeded == null) return

    val tooltip = stringResource(Res.string.label_task_entry_summary, activeTasks.size, failedTasks.size)
    OnyxTooltip(text = tooltip) {
        when {
            activeTasks.isNotEmpty() -> ActiveTasksPill(
                tasks = tasks,
                activeTasks = activeTasks,
                onClick = { component.dispatch(TaskCenterIntent.Open) },
            )
            failedTasks.isNotEmpty() -> FailedTasksPill(
                failedCount = failedTasks.size,
                onClick = {
                    component.dispatch(TaskCenterIntent.SetFilter(TaskCenterFilter.FAILED))
                    component.dispatch(TaskCenterIntent.Open)
                },
            )
            latestSucceeded != null -> SucceededTaskPill(
                task = latestSucceeded,
                onClick = { component.dispatch(TaskCenterIntent.Open) },
            )
        }
    }
}
