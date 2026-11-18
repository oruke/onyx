package com.oruke.onyx.app.component

import com.oruke.onyx.app.component.delegate.TaskOrchestrator
import com.oruke.onyx.app.filesystem.JvmTextClipboardService
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.vfs.api.TextClipboardService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 复制错误详情成功后的状态反馈保留时长。 */
private const val COPIED_FEEDBACK_DURATION_MILLIS = 1_600L

/**
 * 管理应用级任务窗口状态，并通过现有任务编排器与文本剪贴板服务执行任务动作。
 *
 * @param taskOrchestrator 所有文件管理器窗口共享的任务编排器。
 * @param settings 所有窗口共享的即时设置流，用于读取自动弹窗与自动收起偏好。
 * @param onUpdateAutoCloseWhenFinished 更新并持久化完成后自动收起偏好的回调。
 * @param textClipboardService 统一系统文本剪贴板服务。
 * @param componentScope 组件异步动作（如剪贴板复制与短暂反馈清理）使用的协程作用域。
 */
internal class DefaultTaskCenterComponent(
    /** 任务生命周期与执行协程的唯一管理器。 */
    private val taskOrchestrator: TaskOrchestrator,
    /** 应用级偏好设置流。 */
    private val settings: StateFlow<OnyxSettings> = MutableStateFlow(OnyxSettings()),
    /** 修改完成后自动关闭设置时的持久化入口。 */
    private val onUpdateAutoCloseWhenFinished: ((Boolean) -> Unit)? = null,
    /** 复制错误文件名与报告使用的统一剪贴板服务。 */
    private val textClipboardService: TextClipboardService = JvmTextClipboardService(),
    /** 剪贴板写入与短暂状态复位使用的协程作用域。 */
    private val componentScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : TaskCenterComponent {
    /** 任务窗口的可变展示状态，不参与后台任务生命周期。 */
    private val mutableState = MutableStateFlow(TaskCenterState())

    /** 后台新任务触发的无焦点抢占窗口置前信号。 */
    private val mutableAutoRevealRequest = MutableStateFlow(0L)

    /** 最近一次复制反馈的延时复位任务。 */
    private var feedbackResetJob: Job? = null

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
     * @param intent 窗口、筛选、错误详情或任务操作意图。
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
            is TaskCenterIntent.ToggleTaskErrors -> mutableState.update { current ->
                val nextExpanded = if (intent.id in current.expandedErrorTaskIds) {
                    current.expandedErrorTaskIds - intent.id
                } else {
                    current.expandedErrorTaskIds + intent.id
                }
                current.copy(expandedErrorTaskIds = nextExpanded, openedAutomatically = false)
            }
            is TaskCenterIntent.CopyErrorText -> copyErrorTextWithFeedback(intent.feedbackKey, intent.text)
            is TaskCenterIntent.Dismiss,
            is TaskCenterIntent.Retry,
            TaskCenterIntent.ClearFinished -> {
                taskOrchestrator.dispatchTaskCenterAction(intent)
                pruneExpandedTasks()
            }
            else -> taskOrchestrator.dispatchTaskCenterAction(intent)
        }
    }

    private fun pruneExpandedTasks() {
        val remainingIds = taskOrchestrator.tasks.value.mapTo(mutableSetOf()) { task -> task.id }
        mutableState.update { current ->
            val pruned = current.expandedErrorTaskIds.intersect(remainingIds)
            if (pruned.size == current.expandedErrorTaskIds.size) {
                current
            } else {
                current.copy(expandedErrorTaskIds = pruned)
            }
        }
    }

    private fun copyErrorTextWithFeedback(feedbackKey: String, text: String) {
        if (text.isBlank()) return
        mutableState.update { current -> current.copy(openedAutomatically = false) }
        feedbackResetJob?.cancel()
        feedbackResetJob = componentScope.launch {
            if (textClipboardService.copyText(text).isSuccess) {
                mutableState.update { current -> current.copy(copiedFeedbackKey = feedbackKey) }
                delay(COPIED_FEEDBACK_DURATION_MILLIS)
                mutableState.update { current ->
                    if (current.copiedFeedbackKey == feedbackKey) {
                        current.copy(copiedFeedbackKey = null)
                    } else {
                        current
                    }
                }
            }
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
