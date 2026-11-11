package com.oruke.onyx.app.component

import com.oruke.onyx.app.component.delegate.TaskOrchestrator
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.OnyxSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 管理应用级任务窗口状态，并通过现有任务编排器执行任务动作。
 *
 * @param taskOrchestrator 所有文件管理器窗口共享的任务编排器。
 * @param settings 所有窗口共享的即时设置流，用于读取自动弹窗与自动收起偏好。
 * @param onUpdateAutoCloseWhenFinished 更新并持久化完成后自动收起偏好的回调。
 */
internal class DefaultTaskCenterComponent(
    /** 任务生命周期与执行协程的唯一管理器。 */
    private val taskOrchestrator: TaskOrchestrator,
    /** 应用级偏好设置流。 */
    private val settings: StateFlow<OnyxSettings> = MutableStateFlow(OnyxSettings()),
    /** 修改完成后自动关闭设置时的持久化入口。 */
    private val onUpdateAutoCloseWhenFinished: ((Boolean) -> Unit)? = null,
) : TaskCenterComponent {
    /** 任务窗口的可变展示状态，不参与后台任务生命周期。 */
    private val mutableState = MutableStateFlow(TaskCenterState())

    /** 后台新任务触发的无焦点抢占窗口置前信号。 */
    private val mutableAutoRevealRequest = MutableStateFlow(0L)

    /** 对外只读的任务窗口状态。 */
    override val state: StateFlow<TaskCenterState> = mutableState.asStateFlow()

    /** 直接复用任务流，防止关闭窗口或多窗口切换时丢失进度。 */
    override val tasks: StateFlow<List<BackgroundTask>> = taskOrchestrator.tasks

    /** 对外只读的无焦点抢占窗口置前计数。 */
    override val autoRevealRequest: StateFlow<Long> = mutableAutoRevealRequest.asStateFlow()

    init {
        taskOrchestrator.addTaskAppendedListener(::onTaskAppended)
    }

    /**
     * 更新窗口状态，或在任务生命周期允许时转发执行请求。
     *
     * @param intent 窗口、筛选或任务操作意图。
     * @return 无返回值。
     */
    override fun dispatch(intent: TaskCenterIntent) {
        when (intent) {
            TaskCenterIntent.Open -> mutableState.update { current ->
                current.copy(
                    visible = true,
                    openedAutomatically = false,
                    activationRequest = current.activationRequest + 1L,
                )
            }
            TaskCenterIntent.Close -> mutableState.update { current ->
                current.copy(visible = false, openedAutomatically = false)
            }
            TaskCenterIntent.AutoCloseWhenIdle -> mutableState.update { current ->
                if (shouldAutoCloseTaskCenter(
                        state = current,
                        tasks = taskOrchestrator.tasks.value,
                        autoCloseEnabled = settings.value.autoCloseTaskCenterWhenFinished,
                    )
                ) {
                    current.copy(visible = false, openedAutomatically = false)
                } else {
                    current
                }
            }
            is TaskCenterIntent.SetAutoCloseWhenFinished -> {
                onUpdateAutoCloseWhenFinished?.invoke(intent.enabled)
                mutableState.update { current ->
                    current.copy(openedAutomatically = if (current.visible) intent.enabled else false)
                }
            }
            is TaskCenterIntent.SetFilter -> mutableState.update { current ->
                current.copy(filter = intent.filter, openedAutomatically = false)
            }
            else -> taskOrchestrator.dispatchTaskCenterAction(intent)
        }
    }

    /**
     * 在新后台任务提交时按用户设置自动展示任务窗口，并确保当前筛选条件不会隐藏该新任务。
     */
    private fun onTaskAppended(task: BackgroundTask) {
        if (!settings.value.autoShowTaskCenter || !task.shouldAutoRevealTaskCenter()) return
        mutableAutoRevealRequest.update { count -> count + 1L }
        mutableState.update { current ->
            val currentTasks = taskOrchestrator.tasks.value
            val nextFilter = if (currentTasks.forTaskCenterFilter(current.filter).any { item -> item.id == task.id }) {
                current.filter
            } else {
                TaskCenterFilter.ALL
            }
            current.copy(
                visible = true,
                filter = nextFilter,
                openedAutomatically = if (current.visible) current.openedAutomatically else true,
            )
        }
    }
}
