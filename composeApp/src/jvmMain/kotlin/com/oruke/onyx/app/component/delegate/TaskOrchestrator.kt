package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.filesystem.TaskPersistenceRepository
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 后台任务生命周期管理器。
 *
 * 持有所有后台任务的状态列表，以及每个任务对应的 Job 和暂停标志。
 * 提供 append / update / dismiss / cancel / pause / resume / clearAll 等操作。
 */
class TaskOrchestrator(
    private val scope: CoroutineScope,
    private val taskRepository: TaskPersistenceRepository? = null,
) {
    private val _tasks = MutableStateFlow<List<BackgroundTask>>(emptyList())
    val tasks: StateFlow<List<BackgroundTask>> = _tasks.asStateFlow()

    /** 每个任务对应的 coroutine Job — 取消 Job 即取消任务执行。 */
    private val taskJobs = mutableMapOf<String, Job>()

    /** 每个任务的暂停标志 — 任务协程应周期性检查此标志来实现暂停功能。 */
    private val taskPauseFlags = mutableMapOf<String, MutableStateFlow<Boolean>>()

    /** 失败任务的重试入口。重试会清理旧任务并重新发起同一业务请求。 */
    private val taskRetryHandlers = mutableMapOf<String, () -> Unit>()

    private val persistenceMutex = Mutex()

    init {
        restorePersistedTasks()
    }

    fun appendTask(task: BackgroundTask) {
        replaceTasks(listOf(task) + _tasks.value)
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
        replaceTasks(_tasks.value.map { task ->
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
        })
    }

    fun updateTaskFields(taskId: String, transform: (BackgroundTask) -> BackgroundTask) {
        replaceTasks(_tasks.value.map { task ->
            if (task.id == taskId) transform(task) else task
        })
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
        val removedTask = _tasks.value.firstOrNull { task -> task.id == taskId }?.withArchivedStatus()
        taskJobs.remove(taskId)?.cancel()
        taskPauseFlags.remove(taskId)
        taskRetryHandlers.remove(taskId)
        archiveTasks(listOfNotNull(removedTask))
        replaceTasks(_tasks.value.filterNot { task -> task.id == taskId })
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
        val removedTasks = _tasks.value.map { task -> task.withArchivedStatus() }
        taskJobs.values.forEach { job -> job.cancel() }
        taskJobs.clear()
        taskPauseFlags.clear()
        taskRetryHandlers.clear()
        archiveTasks(removedTasks)
        replaceTasks(emptyList())
    }

    /**
     * 任务完成 5 秒后自动从列表中移除。
     */
    fun scheduleAutoCleanup(taskId: String) {
        scope.launch {
            delay(5000)
            taskRetryHandlers.remove(taskId)
            val task = _tasks.value.firstOrNull { current -> current.id == taskId }
            if (task != null) {
                archiveTasks(listOf(task.withArchivedStatus()))
                replaceTasks(_tasks.value.filterNot { current -> current.id == taskId })
            }
        }
    }

    private fun replaceTasks(tasks: List<BackgroundTask>) {
        _tasks.value = tasks
        persistTasks()
    }

    private fun restorePersistedTasks() {
        val repository = taskRepository ?: return
        scope.launch {
            repository.loadTasks().fold(
                onSuccess = { persistedTasks ->
                    if (persistedTasks.isEmpty()) return@fold
                    val currentIds = _tasks.value.map { task -> task.id }.toSet()
                    val restoredTasks = persistedTasks
                        .map { task -> task.withRestoredStatus() }
                        .filterNot { task -> task.id in currentIds }
                    if (restoredTasks.isNotEmpty()) {
                        replaceTasks((restoredTasks + _tasks.value).take(MAX_PERSISTED_TASKS))
                    }
                },
                onFailure = { failure ->
                    OnyxLogger.warn("TaskOrchestrator", "任务历史恢复失败", failure)
                },
            )
        }
    }

    private fun persistTasks() {
        val repository = taskRepository ?: return
        val snapshot = _tasks.value.take(MAX_PERSISTED_TASKS)
        scope.launch {
            persistenceMutex.withLock {
                repository.saveTasks(snapshot).onFailure { failure ->
                    OnyxLogger.warn("TaskOrchestrator", "任务历史保存失败", failure)
                }
            }
        }
    }

    private fun archiveTasks(tasks: List<BackgroundTask>) {
        val repository = taskRepository ?: return
        val terminalTasks = tasks.filter { task -> task.status.isTerminal() }
        if (terminalTasks.isEmpty()) return
        scope.launch {
            persistenceMutex.withLock {
                repository.archiveTasks(terminalTasks).onFailure { failure ->
                    OnyxLogger.warn("TaskOrchestrator", "任务历史归档失败", failure)
                }
            }
        }
    }

    private fun BackgroundTask.withRestoredStatus(): BackgroundTask {
        return when (status) {
            BackgroundTaskStatus.QUEUED,
            BackgroundTaskStatus.RUNNING,
            BackgroundTaskStatus.PAUSED -> copy(
                status = BackgroundTaskStatus.CANCELLED,
                detail = I18nMessage(MessageKey.MSG_CANCELLED),
                progress = null,
            )

            else -> this
        }
    }

    private fun BackgroundTask.withArchivedStatus(): BackgroundTask {
        return if (status.isActive()) {
            copy(
                status = BackgroundTaskStatus.CANCELLED,
                detail = I18nMessage(MessageKey.MSG_CANCELLED),
                progress = null,
            )
        } else {
            this
        }
    }

    private fun BackgroundTaskStatus.isActive(): Boolean {
        return this == BackgroundTaskStatus.QUEUED ||
            this == BackgroundTaskStatus.RUNNING ||
            this == BackgroundTaskStatus.PAUSED
    }

    private fun BackgroundTaskStatus.isTerminal(): Boolean {
        return !isActive()
    }

    private companion object {
        const val MAX_PERSISTED_TASKS = 200
    }
}
