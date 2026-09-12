package com.oruke.onyx.app.component

import com.oruke.onyx.app.component.delegate.TaskOrchestrator
import com.oruke.onyx.core.model.BackgroundTaskStatus

/**
 * 按真实任务生命周期执行任务中心动作，过期或不允许的操作不改变任务状态。
 *
 * @param intent 组件转发的任务动作；窗口展示意图由组件自行处理。
 * @return 无返回值。
 */
internal fun TaskOrchestrator.dispatchTaskCenterAction(intent: TaskCenterIntent) {
    when (intent) {
        is TaskCenterIntent.Pause -> tasks.value.find { task -> task.id == intent.id }
            ?.takeIf { task -> task.canPause && task.status == BackgroundTaskStatus.RUNNING }
            ?.let { task -> pauseTask(task.id) }
        is TaskCenterIntent.Resume -> tasks.value.find { task -> task.id == intent.id }
            ?.takeIf { task -> task.canPause && task.status == BackgroundTaskStatus.PAUSED }
            ?.let { task -> resumeTask(task.id) }
        is TaskCenterIntent.Retry -> tasks.value.find { task -> task.id == intent.id }
            ?.takeIf { task -> task.status == BackgroundTaskStatus.FAILED }
            ?.let { task -> retryTask(task.id) }
        is TaskCenterIntent.Cancel -> tasks.value.find { task -> task.id == intent.id }
            ?.takeIf { task -> task.status.isActiveTask() }
            ?.let { task -> cancelTask(task.id) }
        is TaskCenterIntent.Dismiss -> tasks.value.find { task -> task.id == intent.id }
            ?.takeUnless { task -> task.status.isActiveTask() }
            ?.let { task -> dismissTask(task.id) }
        TaskCenterIntent.ClearFinished -> {
            // 与“已结束”分类保持一致；失败任务必须保留，供用户检查错误或重试。
            tasks.value.forTaskCenterFilter(TaskCenterFilter.FINISHED)
                .forEach { task -> dismissTask(task.id) }
        }
        TaskCenterIntent.Open, TaskCenterIntent.Close, is TaskCenterIntent.SetFilter -> Unit
    }
}
