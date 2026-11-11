package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.component.PaneRemovalContext
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.TRASH_LOCATION
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.shared.usecase.buildTaskDetail
import com.oruke.onyx.vfs.api.TrashService
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 回收站动作委托回调集合，负责同步目录缓存、操作历史与面板焦点续接。
 *
 * @property onRefreshDirectories 失效并刷新受影响目录及回收站容器。
 * @property onHistoryInvalidated 清空过期的文件撤销/重做历史栈。
 * @property onRemovalStarted 在条目移除前捕获面板选择续接上下文。
 * @property onRemovalFinished 在条目移除完成后续接面板选择焦点。
 * @property onOperationFailure 将操作失败原因展示到发起面板。
 */
internal data class TrashActionDelegateCallbacks(
    val onRefreshDirectories: (Set<String>) -> Unit,
    val onHistoryInvalidated: () -> Unit = {},
    val onRemovalStarted: (List<VFile>) -> PaneRemovalContext? = { null },
    val onRemovalFinished: (PaneRemovalContext?, Set<String>, Boolean) -> Unit = { _, _, _ -> },
    val onOperationFailure: (PaneId, Throwable) -> Unit = { _, _ -> },
)

/**
 * 回收站专属业务委托，编排还原、永久删除与清空回收站后台任务。
 *
 * @param trashService 跨平台回收站服务。
 * @param taskOrchestrator 后台任务编排器。
 * @param callbacks 状态同步与反馈回调。
 */
internal class TrashActionDelegate(
    private val trashService: TrashService,
    private val taskOrchestrator: TaskOrchestrator,
    private val callbacks: TrashActionDelegateCallbacks,
) {
    /**
     * 将选中的回收站条目异步还原回原始位置。
     *
     * @param paneId 发起操作的面板标识。
     * @param entries 当前回收站已就绪的条目列表。
     * @param entryIds 待还原的回收站条目 ID 集合。
     */
    @Suppress("TooGenericExceptionCaught")
    fun restoreItems(
        paneId: PaneId,
        entries: List<VFile>,
        entryIds: Set<String>,
    ) {
        if (entryIds.isEmpty()) return
        val requestedEntries = entries.filter { entry -> entry.id in entryIds }
        val restorableEntries = requestedEntries.filter { entry -> entry.parentLocation != null }
        if (restorableEntries.isEmpty()) {
            if (requestedEntries.isNotEmpty()) {
                callbacks.onOperationFailure(
                    paneId,
                    LocalizedException(I18nMessage(MessageKey.MSG_TRASH_ORIGINAL_PATH_UNKNOWN)),
                )
            }
            return
        }

        val targetIds = restorableEntries.mapTo(linkedSetOf()) { entry -> entry.id }
        val context = callbacks.onRemovalStarted(restorableEntries)
        val removedIds = mutableSetOf<String>()
        var verify = false
        val taskId = UUID.randomUUID().toString()

        taskOrchestrator.appendTask(
            BackgroundTask(
                id = taskId,
                kind = BackgroundTaskKind.MOVE,
                title = I18nMessage(MessageKey.MSG_TRASH_RESTORE_ITEMS, restorableEntries.size),
                status = BackgroundTaskStatus.QUEUED,
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, TRASH_LOCATION),
                progress = 0f,
                totalCount = restorableEntries.size,
                startTimeMillis = System.currentTimeMillis(),
            ),
        )

        taskOrchestrator.launchQueuedTask(taskId) {
            val fallbackRefresh = restorableEntries.mapNotNull { it.parentLocation }.toSet() + TRASH_LOCATION
            try {
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, buildTaskDetail(restorableEntries)),
                    progress = 0f,
                )
                val outcome = withContext(Dispatchers.IO) {
                    trashService.restoreItems(targetIds)
                }.getOrThrow()

                removedIds += outcome.restoredItems.map { item -> item.id }
                callbacks.onHistoryInvalidated()
                callbacks.onRefreshDirectories(outcome.affectedParentLocations + TRASH_LOCATION)
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = I18nMessage(MessageKey.MSG_TRASH_RESTORED_ITEMS, outcome.restoredItems.size),
                    progress = 1f,
                    processedCount = outcome.restoredItems.size,
                )
                taskOrchestrator.scheduleAutoCleanup(taskId)
            } catch (failure: CancellationException) {
                verify = true
                callbacks.onHistoryInvalidated()
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(MessageKey.MSG_CANCELLED),
                    progress = null,
                )
                callbacks.onRefreshDirectories(fallbackRefresh)
                throw failure
            } catch (failure: Exception) {
                verify = true
                callbacks.onHistoryInvalidated()
                OnyxLogger.error("TrashActionDelegate", "还原回收站项目失败", failure)
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = failure.toI18nMessage(MessageKey.MSG_TRASH_RESTORE_FAILED),
                    progress = null,
                )
                callbacks.onRefreshDirectories(fallbackRefresh)
                callbacks.onOperationFailure(paneId, failure)
            } finally {
                callbacks.onRemovalFinished(context, removedIds, verify)
            }
        }
    }

    /**
     * 从回收站永久删除指定条目（已在根组件层完成强制二次确认后调用）。
     *
     * @param paneId 发起操作的面板标识。
     * @param entries 当前回收站已就绪的条目列表。
     * @param entryIds 待永久删除的回收站条目 ID 集合。
     */
    @Suppress("TooGenericExceptionCaught")
    fun deletePermanently(
        paneId: PaneId,
        entries: List<VFile>,
        entryIds: Set<String>,
    ) {
        if (entryIds.isEmpty()) return
        val targetEntries = entries.filter { entry -> entry.id in entryIds }
        val context = if (targetEntries.isNotEmpty()) callbacks.onRemovalStarted(targetEntries) else null
        val removedIds = mutableSetOf<String>()
        var verify = false
        val itemCount = targetEntries.size.takeIf { it > 0 } ?: entryIds.size
        val taskId = UUID.randomUUID().toString()

        taskOrchestrator.appendTask(
            BackgroundTask(
                id = taskId,
                kind = BackgroundTaskKind.DELETE,
                title = I18nMessage(MessageKey.MSG_DELETE_ITEMS, itemCount),
                status = BackgroundTaskStatus.QUEUED,
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, TRASH_LOCATION),
                progress = 0f,
                totalCount = itemCount,
                startTimeMillis = System.currentTimeMillis(),
            ),
        )

        taskOrchestrator.launchQueuedTask(taskId) {
            try {
                val runningDetail = if (targetEntries.isNotEmpty()) {
                    buildTaskDetail(targetEntries)
                } else {
                    TRASH_LOCATION
                }
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, runningDetail),
                    progress = 0f,
                )
                val deletedCount = withContext(Dispatchers.IO) {
                    trashService.deletePermanently(entryIds)
                }.getOrThrow()

                removedIds += entryIds
                callbacks.onHistoryInvalidated()
                callbacks.onRefreshDirectories(setOf(TRASH_LOCATION))
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = I18nMessage(MessageKey.MSG_DELETED_ITEMS, deletedCount),
                    progress = 1f,
                    processedCount = deletedCount,
                )
                taskOrchestrator.scheduleAutoCleanup(taskId)
            } catch (failure: CancellationException) {
                verify = true
                callbacks.onHistoryInvalidated()
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(MessageKey.MSG_CANCELLED),
                    progress = null,
                )
                callbacks.onRefreshDirectories(setOf(TRASH_LOCATION))
                throw failure
            } catch (failure: Exception) {
                verify = true
                callbacks.onHistoryInvalidated()
                OnyxLogger.error("TrashActionDelegate", "永久删除回收站项目失败", failure)
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = failure.toI18nMessage(MessageKey.MSG_DELETE_FAILED),
                    progress = null,
                )
                callbacks.onRefreshDirectories(setOf(TRASH_LOCATION))
                callbacks.onOperationFailure(paneId, failure)
            } finally {
                callbacks.onRemovalFinished(context, removedIds, verify)
            }
        }
    }

    /**
     * 清空当前用户回收站中的所有条目（已在根组件层完成强制二次确认后调用）。
     *
     * @param paneId 发起操作的面板标识。
     * @param currentEntries 发起清空时回收站面板内的可见条目快照。
     */
    @Suppress("TooGenericExceptionCaught")
    fun emptyTrash(
        paneId: PaneId,
        currentEntries: List<VFile>,
    ) {
        val context = if (currentEntries.isNotEmpty()) callbacks.onRemovalStarted(currentEntries) else null
        val removedIds = mutableSetOf<String>()
        var verify = false
        val totalCount = currentEntries.size.coerceAtLeast(1)
        val taskId = UUID.randomUUID().toString()

        taskOrchestrator.appendTask(
            BackgroundTask(
                id = taskId,
                kind = BackgroundTaskKind.DELETE,
                title = I18nMessage(MessageKey.MSG_TRASH_EMPTY_RUNNING),
                status = BackgroundTaskStatus.QUEUED,
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, TRASH_LOCATION),
                progress = 0f,
                totalCount = totalCount,
                startTimeMillis = System.currentTimeMillis(),
            ),
        )

        taskOrchestrator.launchQueuedTask(taskId) {
            try {
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(MessageKey.MSG_TRASH_EMPTY_RUNNING),
                    progress = 0f,
                )
                val deletedCount = withContext(Dispatchers.IO) {
                    trashService.emptyTrash()
                }.getOrThrow()

                removedIds += currentEntries.map { entry -> entry.id }
                callbacks.onHistoryInvalidated()
                callbacks.onRefreshDirectories(setOf(TRASH_LOCATION))
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = I18nMessage(MessageKey.MSG_TRASH_EMPTIED, deletedCount),
                    progress = 1f,
                    processedCount = deletedCount,
                )
                taskOrchestrator.scheduleAutoCleanup(taskId)
            } catch (failure: CancellationException) {
                verify = true
                callbacks.onHistoryInvalidated()
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(MessageKey.MSG_CANCELLED),
                    progress = null,
                )
                callbacks.onRefreshDirectories(setOf(TRASH_LOCATION))
                throw failure
            } catch (failure: Exception) {
                verify = true
                callbacks.onHistoryInvalidated()
                OnyxLogger.error("TrashActionDelegate", "清空回收站失败", failure)
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = failure.toI18nMessage(MessageKey.MSG_TRASH_EMPTY_FAILED),
                    progress = null,
                )
                callbacks.onRefreshDirectories(setOf(TRASH_LOCATION))
                callbacks.onOperationFailure(paneId, failure)
            } finally {
                callbacks.onRemovalFinished(context, removedIds, verify)
            }
        }
    }
}
