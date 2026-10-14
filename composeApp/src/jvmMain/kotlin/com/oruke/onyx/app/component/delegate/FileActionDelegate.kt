package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.component.withBatchRenamePreview
import com.oruke.onyx.shared.usecase.BatchRenameUseCase
import com.oruke.onyx.shared.usecase.DeleteEntriesUseCase
import com.oruke.onyx.shared.usecase.TaskProgress
import com.oruke.onyx.shared.usecase.buildTaskDetail
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.TrashMoveRecord
import com.oruke.onyx.vfs.api.TrashRestorationStatus
import com.oruke.onyx.vfs.api.TrashService
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.DirectoryChange
import com.oruke.onyx.app.component.PaneRemovalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID
import com.oruke.onyx.core.model.BatchRenameDraft

/**
 * 文件操作委托回调集合，负责把操作结果同步回根组件和面板。
 *
 */
internal data class FileActionDelegateCallbacks(
    /** 发布已确认的文件变化。 */
    val onDirectoryChange: (DirectoryChange) -> Unit,
    /** 校验部分失败或不确定的受影响目录。 */
    val onRefreshDirectories: (Set<String>) -> Unit,
    /** 读取指定面板状态。 */
    val getPaneState: (PaneId) -> PaneState,
    /** 成功的批量重命名记录。 */
    val onBatchRenameSucceeded: (List<Pair<VFile, String>>) -> Unit = {},
    /** 可恢复的删除记录。 */
    val onTrashDeleteSucceeded: (List<TrashMoveRecord>) -> Unit = {},
    /** 捕获一次移除命令的用户上下文。 */
    val onRemovalStarted: (List<VFile>) -> PaneRemovalContext? = { null },
    /** 最终结果到达后续接一次选择。 */
    val onRemovalFinished: (PaneRemovalContext?, Set<String>, Boolean) -> Unit = { _, _, _ -> },
)

/**
 * 文件操作委托 — 负责删除、创建目录、批量重命名等文件管理操作。
 *
 * 从 DefaultRootComponent 剥离的纯业务逻辑。
 *
 * @param fileCommandService 统一文件命令服务。
 * @param trashService 平台回收站服务。
 * @param taskOrchestrator 后台任务编排器。
 * @param dialogState 根组件对话框状态。
 * @param callbacks 文件操作结果回调集合。
 */
internal class FileActionDelegate(
    private val fileCommandService: FileCommandService,
    private val trashService: TrashService,
    private val taskOrchestrator: TaskOrchestrator,
    private val dialogState: MutableStateFlow<RootDialogState?>,
    private val callbacks: FileActionDelegateCallbacks,
) {
    var pendingDeleteRequest: PendingDeleteRequest? = null
        private set
    private val deleteEntriesUseCase = DeleteEntriesUseCase(fileCommandService, trashService)
    private val batchRenameUseCase = BatchRenameUseCase(fileCommandService)

    /**
     * 执行删除请求（确认对话框后调用）。
     */
    @Suppress("TooGenericExceptionCaught") // 文件任务边界统一将 Provider 异常写入任务中心。
    fun executeDeleteRequest(request: PendingDeleteRequest) {
        val selectedEntries = request.entries
        if (selectedEntries.isEmpty()) {
            return
        }
        val trashRecords = mutableListOf<TrashMoveRecord>()
        val context = callbacks.onRemovalStarted(selectedEntries)
        val removedIds = mutableSetOf<String>()
        var verify = false

        val taskId = UUID.randomUUID().toString()
        taskOrchestrator.appendTask(
            BackgroundTask(
                id = taskId,
                kind = BackgroundTaskKind.DELETE,
                title = I18nMessage(MessageKey.MSG_DELETE_ITEMS, selectedEntries.size),
                status = BackgroundTaskStatus.QUEUED,
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, callbacks.getPaneState(request.paneId).location),
                progress = 0f,
                totalCount = selectedEntries.size,
                startTimeMillis = System.currentTimeMillis(),
            )
        )

        taskOrchestrator.launchQueuedTask(taskId) {
            try {
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, buildTaskDetail(selectedEntries)),
                    progress = 0f,
                )

                deleteEntriesUseCase.execute(
                    DeleteEntriesUseCase.DeleteEntriesRequest(
                        entries = selectedEntries,
                        moveToTrash = request.moveToTrash,
                    )
                ).collect { progress ->
                    trashRecords += progress.trashRecords
                    progress.directoryChange?.let { change ->
                        removedIds += change.removed.map { it.id }
                        callbacks.onDirectoryChange(change)
                    }
                    applyTaskProgress(taskId, progress)
                }

                taskOrchestrator.unregisterJob(taskId)
                val unavailableUndoCount = trashRecords.count { record ->
                    record.restorationStatus == TrashRestorationStatus.METADATA_UNAVAILABLE
                }
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = if (unavailableUndoCount > 0) {
                        I18nMessage(
                            MessageKey.MSG_DELETED_ITEMS_WITHOUT_UNDO,
                            selectedEntries.size,
                            unavailableUndoCount,
                        )
                    } else {
                        I18nMessage(MessageKey.MSG_DELETED_ITEMS, selectedEntries.size)
                    },
                    progress = 1f,
                    processedCount = selectedEntries.size,
                )
                callbacks.onTrashDeleteSucceeded(trashRecords)
                taskOrchestrator.scheduleAutoCleanup(taskId)
            } catch (failure: CancellationException) {
                callbacks.onTrashDeleteSucceeded(trashRecords)
                verify = true
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(MessageKey.MSG_CANCELLED),
                    progress = null,
                )
                callbacks.onRefreshDirectories(selectedEntries.mapNotNull { it.parentLocation }.toSet())
                throw failure
            } catch (failure: Exception) {
                verify = true
                callbacks.onTrashDeleteSucceeded(trashRecords)
                OnyxLogger.error("FileActionDelegate", "删除失败", failure)
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = failure.toI18nMessage(MessageKey.MSG_DELETE_FAILED),
                    progress = null,
                )
                callbacks.onRefreshDirectories(selectedEntries.mapNotNull { it.parentLocation }.toSet())
            } finally {
                callbacks.onRemovalFinished(context, removedIds, verify)
            }
        }
    }

    /**
     * 设置待处理的删除请求（弹出确认对话框前）。
     */
    fun setPendingDeleteRequest(request: PendingDeleteRequest) {
        pendingDeleteRequest = request
    }

    /**
     * 清除待处理的删除请求。
     */
    fun clearPending() {
        pendingDeleteRequest = null
    }

    /**
     * 创建多个目录并按真实结果同步内容，只有嵌套创建需要额外校验中间父目录。
     * @param parentLocation 命令发起时的父目录。
     * @param paths 已解析并校验的相对目录路径。
     */
    @Suppress("TooGenericExceptionCaught") // 文件任务边界统一将 Provider 异常写入任务中心。
    fun executeCreateDirectories(
        parentLocation: String,
        paths: List<String>,
    ) {
        val taskId = UUID.randomUUID().toString()
        taskOrchestrator.appendTask(
            BackgroundTask(
                id = taskId,
                kind = BackgroundTaskKind.COPY,
                title = I18nMessage(MessageKey.MSG_CREATE_FOLDERS, paths.size),
                status = BackgroundTaskStatus.QUEUED,
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, parentLocation),
                progress = 0f,
                totalCount = paths.size,
                startTimeMillis = System.currentTimeMillis(),
            )
        )

        taskOrchestrator.launchQueuedTask(taskId) {
            try {
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(
                        MessageKey.MSG_STRING_LITERAL,
                        paths.joinToString(limit = 3, truncated = " ...")
                    ),
                    progress = 0f,
                )
                var needsParentVerification = false
                paths.forEachIndexed { index, path ->
                    ensureActive()
                    val created = fileCommandService.createDirectory(
                        parentLocation = parentLocation,
                        name = path,
                    ).getOrThrow()
                    if (created.parentLocation != parentLocation) needsParentVerification = true
                    callbacks.onDirectoryChange(DirectoryChange(upserted = listOf(created)))
                    taskOrchestrator.updateTask(
                        taskId = taskId,
                        status = BackgroundTaskStatus.RUNNING,
                        detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, path),
                        progress = (index + 1).toFloat() / paths.size,
                    )
                }
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = I18nMessage(MessageKey.MSG_CREATED_FOLDERS, paths.size),
                    progress = 1f,
                    processedCount = paths.size,
                )
                if (needsParentVerification) callbacks.onRefreshDirectories(setOf(parentLocation))
                taskOrchestrator.scheduleAutoCleanup(taskId)
            } catch (failure: CancellationException) {
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(MessageKey.MSG_CANCELLED),
                    progress = null,
                )
                callbacks.onRefreshDirectories(setOf(parentLocation))
                throw failure
            } catch (failure: Exception) {
                OnyxLogger.error("FileActionDelegate", "创建目录失败", failure)
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = failure.toI18nMessage(MessageKey.MSG_CREATE_FOLDER_FAILED),
                    progress = null,
                )
                callbacks.onRefreshDirectories(setOf(parentLocation))
            }
        }
    }

    /**
     * 执行批量重命名。
     */
    @Suppress("TooGenericExceptionCaught") // 批量任务边界统一将逐项异常写入任务与对话框状态。
    fun executeBatchRename(paneId: PaneId, renameMap: List<Pair<VFile, String>>) {
        if (renameMap.isEmpty()) return

        // 更新对话框状态为执行中
        val currentDialog = dialogState.value as? RootDialogState.BatchRename ?: return
        dialogState.value = currentDialog.copy(executing = true, progress = 0f, processedCount = 0, currentDetail = "")

        val taskId = UUID.randomUUID().toString()
        taskOrchestrator.appendTask(
            BackgroundTask(
                id = taskId,
                kind = BackgroundTaskKind.RENAME,
                title = I18nMessage(MessageKey.ACTION_BATCH_RENAME),
                status = BackgroundTaskStatus.QUEUED,
                detail = I18nMessage(MessageKey.MSG_BATCH_RENAME_FILES, renameMap.size),
                progress = 0f,
                totalCount = renameMap.size,
                startTimeMillis = System.currentTimeMillis(),
            )
        )

        taskOrchestrator.launchQueuedTask(taskId) {
            try {
                batchRenameUseCase.execute(
                    BatchRenameUseCase.BatchRenameRequest(renameMap = renameMap)
                ).collect { progress ->
                    progress.directoryChange?.let(callbacks.onDirectoryChange)
                    applyTaskProgress(taskId, progress)
                    // 同步更新对话框进度
                    (dialogState.value as? RootDialogState.BatchRename)?.let { ds ->
                        dialogState.value = ds.copy(
                            progress = progress.progress ?: ds.progress,
                            processedCount = progress.processedCount ?: ds.processedCount,
                            currentDetail = progress.detailText(),
                        )
                    }
                }

                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = I18nMessage(MessageKey.MSG_BATCH_RENAMED_FILES, renameMap.size),
                    progress = 1f,
                    processedCount = renameMap.size,
                )
                // 标记对话框完成
                (dialogState.value as? RootDialogState.BatchRename)?.let { ds ->
                    dialogState.value = ds.copy(
                        executing = false,
                        completed = true,
                        progress = 1f,
                        processedCount = renameMap.size,
                    )
                }
                callbacks.onBatchRenameSucceeded(renameMap)
                taskOrchestrator.scheduleAutoCleanup(taskId)
                // 短暂展示完成状态后自动重置为编辑模式
                delay(BATCH_RENAME_COMPLETION_RESET_DELAY_MS)
                resetBatchRenameForContinue(paneId)
            } catch (failure: CancellationException) {
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(MessageKey.MSG_CANCELLED),
                )
                (dialogState.value as? RootDialogState.BatchRename)?.let { ds ->
                    dialogState.value = ds.copy(
                        executing = false,
                        errorMessage = I18nMessage(MessageKey.MSG_CANCELLED),
                    )
                }
                callbacks.onRefreshDirectories(renameMap.mapNotNull { it.first.parentLocation }.toSet())
                throw failure
            } catch (e: Exception) {
                OnyxLogger.error("FileActionDelegate", "批量重命名失败", e)
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = e.toI18nMessage(),
                )
                (dialogState.value as? RootDialogState.BatchRename)?.let { ds ->
                    dialogState.value = ds.copy(
                        executing = false,
                        errorMessage = e.toI18nMessage(),
                    )
                }
                callbacks.onRefreshDirectories(renameMap.mapNotNull { it.first.parentLocation }.toSet())
            }
        }
    }

    /**
     * 重置批量重命名为新一轮输入模式。
     */
    fun resetBatchRenameForContinue(paneId: PaneId) {
        val paneState = callbacks.getPaneState(paneId)
        val allEntries = (paneState.entriesState as? PaneEntriesState.Ready)?.entries.orEmpty()
        if (allEntries.isEmpty()) {
            dialogState.value = null
            return
        }
        val previous = dialogState.value as? RootDialogState.BatchRename
        dialogState.value = RootDialogState.BatchRename(
            paneId = paneId,
            entries = allEntries,
            draft = previous?.draft ?: BatchRenameDraft(),
            selectedPresetId = previous?.selectedPresetId,
            presetName = previous?.presetName.orEmpty(),
        ).withBatchRenamePreview()
    }

    /**
     * 待确认的删除请求。
     *
     * @property paneId 发起删除的面板标识。
     * @property entries 待删除条目。
     * @property moveToTrash 是否移动到系统回收站。
     */
    data class PendingDeleteRequest(
        val paneId: PaneId,
        val entries: List<VFile>,
        val moveToTrash: Boolean,
    )

    private companion object {
        /** 批量重命名完成状态保留时长。 */
        const val BATCH_RENAME_COMPLETION_RESET_DELAY_MS = 600L
    }

    private fun applyTaskProgress(
        taskId: String,
        progress: TaskProgress,
    ) {
        taskOrchestrator.updateTask(
            taskId = taskId,
            status = progress.status,
            detail = progress.detail,
            progress = progress.progress,
            processedCount = progress.processedCount,
            processedBytes = progress.processedBytes,
            totalBytes = progress.totalBytes,
            bytesPerSecond = progress.bytesPerSecond,
            estimatedRemainingSeconds = progress.estimatedRemainingSeconds,
        )
        progress.currentFileName?.let { fileName ->
            taskOrchestrator.updateTaskFields(taskId) { task ->
                task.copy(currentFileName = fileName)
            }
        }
    }

    private fun TaskProgress.detailText(): String {
        return detail.args.firstOrNull()?.toString().orEmpty()
    }
}
