package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.vfs.api.TaskPersistenceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 协调后台任务缓存的恢复、防抖保存和终态归档。
 */
internal class TaskPersistenceCoordinator(
    /** 任务缓存协程作用域。 */
    private val scope: CoroutineScope,
    /** 后台任务持久化仓储。 */
    private val repository: TaskPersistenceRepository,
) {
    /** 串行化任务快照保存与归档，避免两个文件写入交错。 */
    private val persistenceMutex = Mutex()

    /** 当前等待防抖窗口结束的保存任务。 */
    private var persistenceJob: Job? = null

    /**
     * 异步加载缓存任务。
     *
     * @param onRestored 加载成功后的任务合并回调。
     */
    fun restore(onRestored: (List<BackgroundTask>) -> Unit) {
        scope.launch {
            repository.loadTasks().fold(
                onSuccess = onRestored,
                onFailure = { failure ->
                    OnyxLogger.warn(LOG_TAG, "任务历史恢复失败", failure)
                },
            )
        }
    }

    /**
     * 防抖保存最新任务快照，合并高频字节进度更新。
     *
     * @param tasks 待保存的最新任务快照。
     */
    fun schedule(tasks: List<BackgroundTask>) {
        persistenceJob?.cancel()
        val snapshot = tasks.toList()
        persistenceJob = scope.launch {
            delay(PERSISTENCE_DEBOUNCE_MILLIS)
            persistenceMutex.withLock {
                repository.saveTasks(snapshot).onFailure { failure ->
                    OnyxLogger.warn(LOG_TAG, "任务历史保存失败", failure)
                }
            }
        }
    }

    /**
     * 异步归档终态任务。
     *
     * @param tasks 待归档任务；调用方保证任务已经收敛为终态。
     */
    fun archive(tasks: List<BackgroundTask>) {
        if (tasks.isEmpty()) return
        val snapshot = tasks.toList()
        scope.launch {
            persistenceMutex.withLock {
                repository.archiveTasks(snapshot).onFailure { failure ->
                    OnyxLogger.warn(LOG_TAG, "任务历史归档失败", failure)
                }
            }
        }
    }

    private companion object {
        /** 日志标签。 */
        const val LOG_TAG = "TaskPersistenceCoordinator"
        /** 高频任务状态写入缓存前的防抖时长。 */
        const val PERSISTENCE_DEBOUNCE_MILLIS = 500L
    }
}
