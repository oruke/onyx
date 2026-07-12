package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.vfs.api.TaskPersistenceRepository
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

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

    /** 后台任务并发许可，保证 QUEUED 状态不是只用于展示。 */
    private val taskConcurrency = Semaphore(MAX_CONCURRENT_RUNNING_TASKS)

    private val persistenceMutex = Mutex()

    init {
        restorePersistedTasks()
    }

    /**
     * 将新任务插入任务列表顶部。
     *
     * @param task 新后台任务。
     */
    fun appendTask(task: BackgroundTask) {
        replaceTasks(listOf(task) + _tasks.value)
    }

    /**
     * 更新后台任务的执行状态与进度字段。
     *
     * @param taskId 任务 id。
     * @param status 新任务状态。
     * @param detail 新状态详情。
     * @param progress 可选进度比例。
     * @param processedCount 可选已处理条目数。
     * @param processedBytes 可选已处理字节数。
     * @param totalBytes 可选总字节数。
     */
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

    /**
     * 使用转换函数更新指定任务。
     *
     * @param taskId 任务 id。
     * @param transform 任务状态转换函数。
     */
    fun updateTaskFields(taskId: String, transform: (BackgroundTask) -> BackgroundTask) {
        replaceTasks(_tasks.value.map { task ->
            if (task.id == taskId) transform(task) else task
        })
    }

    /**
     * 绑定任务与协程 Job。
     *
     * @param taskId 任务 id。
     * @param job 任务协程。
     */
    fun registerJob(taskId: String, job: Job) {
        taskJobs[taskId] = job
    }

    /**
     * 启动受并发队列约束的后台任务。
     *
     * @param taskId 后台任务 id。
     * @param block 真正的任务执行逻辑；只有拿到并发许可后才会进入。
     * @return 已注册的 coroutine Job。
     */
    fun launchQueuedTask(
        taskId: String,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        var enteredExecution = false
        val job = scope.launch {
            try {
                taskConcurrency.withPermit {
                    enteredExecution = true
                    block()
                }
            } finally {
                if (!enteredExecution) {
                    unregisterJob(taskId)
                    updateTask(
                        taskId = taskId,
                        status = BackgroundTaskStatus.CANCELLED,
                        detail = I18nMessage(MessageKey.MSG_CANCELLED),
                        progress = null,
                    )
                }
            }
        }
        registerJob(taskId, job)
        return job
    }

    /**
     * 解除任务 Job 和暂停标志注册。
     *
     * @param taskId 任务 id。
     */
    fun unregisterJob(taskId: String) {
        taskJobs.remove(taskId)
        taskPauseFlags.remove(taskId)
    }

    /**
     * 注册任务重试动作。
     *
     * @param taskId 任务 id。
     * @param retry 重试动作。
     */
    fun registerRetryHandler(taskId: String, retry: () -> Unit) {
        taskRetryHandlers[taskId] = retry
    }

    /**
     * 获取或创建任务暂停标志。
     *
     * @param taskId 任务 id。
     * @return 可观察暂停标志。
     */
    fun getOrCreatePauseFlag(taskId: String): MutableStateFlow<Boolean> {
        return taskPauseFlags.getOrPut(taskId) { MutableStateFlow(false) }
    }

    /**
     * 从活动列表移除任务并归档其最终状态。
     *
     * @param taskId 任务 id。
     */
    fun dismissTask(taskId: String) {
        val removedTask = _tasks.value.firstOrNull { task -> task.id == taskId }?.withArchivedStatus()
        taskJobs.remove(taskId)?.cancel()
        taskPauseFlags.remove(taskId)
        taskRetryHandlers.remove(taskId)
        archiveTasks(listOfNotNull(removedTask))
        replaceTasks(_tasks.value.filterNot { task -> task.id == taskId })
    }

    /**
     * 取消指定任务协程。
     *
     * @param taskId 任务 id。
     */
    fun cancelTask(taskId: String) {
        taskJobs[taskId]?.cancel()
    }

    /**
     * 将任务标记为暂停。
     *
     * @param taskId 任务 id。
     */
    fun pauseTask(taskId: String) {
        taskPauseFlags[taskId]?.value = true
        updateTaskFields(taskId) { task ->
            task.copy(status = BackgroundTaskStatus.PAUSED)
        }
    }

    /**
     * 恢复暂停任务。
     *
     * @param taskId 任务 id。
     */
    fun resumeTask(taskId: String) {
        taskPauseFlags[taskId]?.value = false
        updateTaskFields(taskId) { task ->
            task.copy(status = BackgroundTaskStatus.RUNNING)
        }
    }

    /**
     * 移除失败任务并执行已注册的重试动作。
     *
     * @param taskId 任务 id。
     */
    fun retryTask(taskId: String) {
        val retry = taskRetryHandlers[taskId] ?: return
        dismissTask(taskId)
        retry()
    }

    /**
     * 取消、归档并清空全部活动任务。
     */
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
            delay(TASK_RETRY_HANDLER_RETENTION_MS)
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
        /** 持久化任务历史的最大条数。 */
        const val MAX_PERSISTED_TASKS = 200
        /** 同时运行的后台任务上限。 */
        const val MAX_CONCURRENT_RUNNING_TASKS = 2
        /** 完成任务后继续保留重试处理器的时长。 */
        const val TASK_RETRY_HANDLER_RETENTION_MS = 5_000L
    }
}
