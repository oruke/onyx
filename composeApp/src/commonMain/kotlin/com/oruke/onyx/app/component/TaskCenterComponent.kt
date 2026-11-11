package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 所有文件管理器窗口共用的任务中心状态与任务操作入口。 */
internal interface TaskCenterComponent {
    /** 独立任务窗口的可见性、激活请求与筛选状态。 */
    val state: StateFlow<TaskCenterState>

    /** 应用级任务编排器的实时任务列表，窗口关闭后继续更新。 */
    val tasks: StateFlow<List<BackgroundTask>>

    /** 后台新任务触发的无焦点抢占窗口置前信号。 */
    val autoRevealRequest: StateFlow<Long>
        get() = EMPTY_AUTO_REVEAL_REQUEST

    /**
     * 处理窗口与任务意图，任务操作必须遵守当前生命周期状态。
     *
     * @param intent 待处理的任务中心意图。
     * @return 无返回值。
     */
    fun dispatch(intent: TaskCenterIntent)
}

private val EMPTY_AUTO_REVEAL_REQUEST: StateFlow<Long> = MutableStateFlow(0L)

/** 独立任务窗口的共享展示状态，关闭窗口时保留筛选条件。 */
internal data class TaskCenterState(
    /** 是否显示全应用唯一的任务窗口。 */
    val visible: Boolean = false,
    /** 每次打开都递增，用于唤起已经打开或最小化的窗口。 */
    val activationRequest: Long = 0L,
    /** 当前任务筛选条件。 */
    val filter: TaskCenterFilter = TaskCenterFilter.ALL,
    /** 当前可见会话是否由后台新任务自动唤起（用于判断全部完成后是否允许自动收起）。 */
    val openedAutomatically: Boolean = false,
)

/** 任务中心可选的生命周期筛选条件。 */
internal enum class TaskCenterFilter {
    /** 全部任务，优先显示活动任务与失败任务。 */
    ALL,
    /** 排队、运行或暂停的任务。 */
    ACTIVE,
    /** 执行失败的任务。 */
    FAILED,
    /** 已成功或已取消的任务。 */
    FINISHED,
}

/** 任务中心窗口与后台任务的统一交互意图。 */
internal sealed interface TaskCenterIntent {
    /** 显示并激活任务窗口。 */
    data object Open : TaskCenterIntent

    /** 隐藏任务窗口，不改变任何后台任务。 */
    data object Close : TaskCenterIntent

    /** 当窗口由后台任务自动唤起且当前已无活动或失败任务时自动收起窗口。 */
    data object AutoCloseWhenIdle : TaskCenterIntent

    /** 切换任务全部成功完成后是否自动收起窗口的偏好。 */
    data class SetAutoCloseWhenFinished(
        /** 是否在任务全部完成后自动关闭自动弹出的任务窗口。 */
        val enabled: Boolean,
    ) : TaskCenterIntent

    /** 切换当前任务列表的筛选条件。 */
    data class SetFilter(
        /** 要展示的任务类别。 */
        val filter: TaskCenterFilter,
    ) : TaskCenterIntent

    /** 暂停支持暂停的运行任务。 */
    data class Pause(
        /** 待暂停任务的唯一标识。 */
        val id: String,
    ) : TaskCenterIntent

    /** 恢复支持暂停的已暂停任务。 */
    data class Resume(
        /** 待恢复任务的唯一标识。 */
        val id: String,
    ) : TaskCenterIntent

    /** 使用已登记的业务请求重试失败任务。 */
    data class Retry(
        /** 待重试任务的唯一标识。 */
        val id: String,
    ) : TaskCenterIntent

    /** 取消仍处于活动生命周期的任务。 */
    data class Cancel(
        /** 待取消任务的唯一标识。 */
        val id: String,
    ) : TaskCenterIntent

    /** 从列表移除已进入终态的单个任务。 */
    data class Dismiss(
        /** 待移除任务的唯一标识。 */
        val id: String,
    ) : TaskCenterIntent

    /** 清理已成功或已取消的任务，保留活动任务与需要处理的失败任务。 */
    data object ClearFinished : TaskCenterIntent
}

/**
 * 按任务中心筛选条件提取任务，保留各状态分组内的原始顺序。
 *
 * @param filter 当前筛选条件；全部视图按活动、失败、其他终态排序。
 * @return 符合条件的任务列表。
 */
internal fun List<BackgroundTask>.forTaskCenterFilter(filter: TaskCenterFilter): List<BackgroundTask> {
    return when (filter) {
        TaskCenterFilter.ALL -> sortedBy { task ->
            when {
                task.status.isActiveTask() -> 0
                task.status == BackgroundTaskStatus.FAILED -> 1
                else -> 2
            }
        }
        TaskCenterFilter.ACTIVE -> filter { task -> task.status.isActiveTask() }
        TaskCenterFilter.FAILED -> filter { task -> task.status == BackgroundTaskStatus.FAILED }
        TaskCenterFilter.FINISHED -> filter { task ->
            task.status == BackgroundTaskStatus.SUCCEEDED || task.status == BackgroundTaskStatus.CANCELLED
        }
    }
}

/**
 * 判断任务是否仍可执行，避免展示层清理操作误取消后台任务。
 *
 * @return 排队、运行或暂停时返回 true。
 */
internal fun BackgroundTaskStatus.isActiveTask(): Boolean {
    return this == BackgroundTaskStatus.QUEUED ||
        this == BackgroundTaskStatus.RUNNING ||
        this == BackgroundTaskStatus.PAUSED
}

/**
 * 判断新提交的后台任务是否需要自动弹出任务窗口。
 *
 * 批量重命名已在专属对话框中内嵌实时进度与错误反馈，不重复弹出任务中心遮挡对话框。
 *
 * @return 需要自动显示任务窗口时返回 true。
 */
internal fun BackgroundTask.shouldAutoRevealTaskCenter(): Boolean {
    return kind != BackgroundTaskKind.RENAME
}

/**
 * 判断自动唤起的任务窗口当前是否满足自动收起条件。
 *
 * 仅当窗口可见、由任务自动唤起、开启了自动关闭偏好，且不存在任何活动任务与未处理的失败任务时返回 true。
 *
 * @param state 当前任务中心展示状态。
 * @param tasks 当前后台任务快照。
 * @param autoCloseEnabled 用户设置中的完成后自动关闭开关。
 * @return 允许进入自动收起倒计时时返回 true。
 */
internal fun shouldAutoCloseTaskCenter(
    state: TaskCenterState,
    tasks: List<BackgroundTask>,
    autoCloseEnabled: Boolean,
): Boolean {
    if (!state.visible || !state.openedAutomatically || !autoCloseEnabled) return false
    val hasActive = tasks.any { task -> task.status.isActiveTask() }
    val hasFailed = tasks.any { task -> task.status == BackgroundTaskStatus.FAILED }
    return !hasActive && !hasFailed
}
