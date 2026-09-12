package com.oruke.onyx.app.component

import com.oruke.onyx.app.component.delegate.TaskOrchestrator
import com.oruke.onyx.core.model.BackgroundTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 管理应用级任务窗口状态，并通过现有任务编排器执行任务动作。
 *
 * @param taskOrchestrator 所有文件管理器窗口共享的任务编排器。
 */
internal class DefaultTaskCenterComponent(
    /** 任务生命周期与执行协程的唯一管理器。 */
    private val taskOrchestrator: TaskOrchestrator,
) : TaskCenterComponent {
    /** 任务窗口的可变展示状态，不参与后台任务生命周期。 */
    private val mutableState = MutableStateFlow(TaskCenterState())

    /** 对外只读的任务窗口状态。 */
    override val state: StateFlow<TaskCenterState> = mutableState.asStateFlow()

    /** 直接复用任务流，防止关闭窗口或多窗口切换时丢失进度。 */
    override val tasks: StateFlow<List<BackgroundTask>> = taskOrchestrator.tasks

    /**
     * 更新窗口状态，或在任务生命周期允许时转发执行请求。
     *
     * @param intent 窗口、筛选或任务操作意图。
     * @return 无返回值。
     */
    override fun dispatch(intent: TaskCenterIntent) {
        when (intent) {
            TaskCenterIntent.Open -> mutableState.update { current ->
                current.copy(visible = true, activationRequest = current.activationRequest + 1L)
            }
            TaskCenterIntent.Close -> mutableState.update { current -> current.copy(visible = false) }
            is TaskCenterIntent.SetFilter -> mutableState.update { current -> current.copy(filter = intent.filter) }
            else -> taskOrchestrator.dispatchTaskCenterAction(intent)
        }
    }
}
