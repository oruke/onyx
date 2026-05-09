package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 后台任务生命周期管理器。
 *
 * 持有所有后台任务的状态列表，以及每个任务对应的 Job 和暂停标志。
 * 提供 append / update / dismiss / cancel / pause / resume / clearAll 等操作。
 */
class TaskOrchestrator(
    private val scope: CoroutineScope,
) {
    private val _tasks = MutableStateFlow<List<BackgroundTask>>(emptyList())
    val tasks: StateFlow<List<BackgroundTask>> = _tasks.asStateFlow()

    /** 每个任务对应的 coroutine Job — 取消 Job 即取消任务执行。 */
    private val taskJobs = mutableMapOf<String, Job>()

    /** 每个任务的暂停标志 — 任务协程应周期性检查此标志来实现暂停功能。 */
    private val taskPauseFlags = mutableMapOf<String, MutableStateFlow<Boolean>>()

    /** 失败任务的重试入口。重试会清理旧任务并重新发起同一业务请求。 */
    private val taskRetryHandlers = mutableMapOf<String, () -> Unit>()

    fun appendTask(task: BackgroundTask) {
        _tasks.value = listOf(task) + _tasks.value
    }

    fun updateTask(
        taskId: String,
        status: BackgroundTaskStatus,
        detail: I18nMessage,
        progress: Float? = null,
        processedCount: Int? = null,
        processedBytes: Long? = null,
        totalBytes: Long? = null,
    ) {
        _tasks.value = _tasks.value.map { task ->
            if (task.id == taskId) {
                task.copy(
                    status = status,
                    detail = detail,
                    progress = progress,
                    processedCount = processedCount ?: task.processedCount,
                    processedBytes = processedBytes ?: task.processedBytes,
                    totalBytes = totalBytes ?: task.totalBytes,
                )
            } else {
                task
            }
        }
    }

    fun updateTaskFields(taskId: String, transform: (BackgroundTask) -> BackgroundTask) {
        _tasks.value = _tasks.value.map { task ->
            if (task.id == taskId) transform(task) else task
        }
    }

    fun registerJob(taskId: String, job: Job) {
        taskJobs[taskId] = job
    }

    fun unregisterJob(taskId: String) {
        taskJobs.remove(taskId)
        taskPauseFlags.remove(taskId)
    }

    fun registerRetryHandler(taskId: String, retry: () -> Unit) {
        taskRetryHandlers[taskId] = retry
    }

    fun getOrCreatePauseFlag(taskId: String): MutableStateFlow<Boolean> {
        return taskPauseFlags.getOrPut(taskId) { MutableStateFlow(false) }
    }

    fun dismissTask(taskId: String) {
        taskJobs.remove(taskId)?.cancel()
        taskPauseFlags.remove(taskId)
        taskRetryHandlers.remove(taskId)
        _tasks.value = _tasks.value.filterNot { task -> task.id == taskId }
    }

    fun cancelTask(taskId: String) {
        taskJobs[taskId]?.cancel()
    }

    fun pauseTask(taskId: String) {
        taskPauseFlags[taskId]?.value = true
        updateTaskFields(taskId) { task ->
            task.copy(status = BackgroundTaskStatus.PAUSED)
        }
    }

    fun resumeTask(taskId: String) {
        taskPauseFlags[taskId]?.value = false
        updateTaskFields(taskId) { task ->
            task.copy(status = BackgroundTaskStatus.RUNNING)
        }
    }

    fun retryTask(taskId: String) {
        val retry = taskRetryHandlers[taskId] ?: return
        dismissTask(taskId)
        retry()
    }

    fun clearAllTasks() {
        taskJobs.values.forEach { job -> job.cancel() }
        taskJobs.clear()
        taskPauseFlags.clear()
        taskRetryHandlers.clear()
        _tasks.value = emptyList()
    }

    /**
     * 任务完成 5 秒后自动从列表中移除。
     */
    fun scheduleAutoCleanup(taskId: String) {
        scope.launch {
            delay(5000)
            taskRetryHandlers.remove(taskId)
            _tasks.value = _tasks.value.filterNot { task -> task.id == taskId }
        }
    }
}
