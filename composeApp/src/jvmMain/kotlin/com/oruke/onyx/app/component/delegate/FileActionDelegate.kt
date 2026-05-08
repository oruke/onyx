package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.filesystem.FileCommandService
import com.oruke.onyx.app.filesystem.TrashService
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_batch_rename
import onyx.composeapp.generated.resources.msg_cancelled
import onyx.composeapp.generated.resources.msg_create_folder_failed
import onyx.composeapp.generated.resources.msg_create_folders
import onyx.composeapp.generated.resources.msg_created_folders
import onyx.composeapp.generated.resources.msg_delete_failed
import onyx.composeapp.generated.resources.msg_delete_items
import onyx.composeapp.generated.resources.msg_deleted_items
import onyx.composeapp.generated.resources.msg_string_literal
import java.util.*

/**
 * 文件操作委托 — 负责删除、创建目录、批量重命名等文件管理操作。
 *
 * 从 DefaultRootComponent 剥离的纯业务逻辑。
 */
class FileActionDelegate(
    private val scope: CoroutineScope,
    private val fileCommandService: FileCommandService,
    private val trashService: TrashService,
    private val taskOrchestrator: TaskOrchestrator,
    private val dialogState: MutableStateFlow<RootDialogState?>,
    private val onRefreshAllPanes: () -> Unit,
    private val onRefreshPane: (PaneId) -> Unit,
    private val getPaneState: (PaneId) -> PaneState,
) {
    var pendingDeleteRequest: PendingDeleteRequest? = null
        private set

    /**
     * 执行删除请求（确认对话框后调用）。
     */
    fun executeDeleteRequest(request: PendingDeleteRequest) {
        val selectedEntries = request.entries
        if (selectedEntries.isEmpty()) {
            return
        }

        val taskId = UUID.randomUUID().toString()
        taskOrchestrator.appendTask(
            BackgroundTask(
                id = taskId,
                kind = BackgroundTaskKind.DELETE,
                title = I18nMessage(Res.string.msg_delete_items, selectedEntries.size),
                status = BackgroundTaskStatus.QUEUED,
                detail = I18nMessage(Res.string.msg_string_literal, getPaneState(request.paneId).location),
                progress = 0f,
                totalCount = selectedEntries.size,
                startTimeMillis = System.currentTimeMillis(),
            )
        )

        val job = scope.launch {
            try {
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(Res.string.msg_string_literal, buildTaskDetail(selectedEntries)),
                    progress = 0f,
                )

                selectedEntries.forEachIndexed { index, entry ->
                    ensureActive()
                    if (request.moveToTrash) {
                        trashService.moveToTrash(listOf(entry)).getOrThrow()
                    } else {
                        fileCommandService.delete(listOf(entry)).getOrThrow()
                    }
                    taskOrchestrator.updateTask(
                        taskId = taskId,
                        status = BackgroundTaskStatus.RUNNING,
                        detail = I18nMessage(Res.string.msg_string_literal, entry.name),
                        progress = (index + 1).toFloat() / selectedEntries.size,
                    )
                }

                taskOrchestrator.dismissTask(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = I18nMessage(Res.string.msg_deleted_items, selectedEntries.size),
                    progress = 1f,
                    processedCount = selectedEntries.size,
                )
                onRefreshAllPanes()
                taskOrchestrator.scheduleAutoCleanup(taskId)
            } catch (_: CancellationException) {
                taskOrchestrator.dismissTask(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(Res.string.msg_cancelled),
                    progress = null,
                )
                onRefreshAllPanes()
            } catch (failure: Throwable) {
                OnyxLogger.error("FileActionDelegate", "删除失败", failure)
                taskOrchestrator.dismissTask(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = failure.message?.let { I18nMessage(Res.string.msg_string_literal, it) }
                        ?: I18nMessage(Res.string.msg_delete_failed),
                    progress = null,
                )
                onRefreshAllPanes()
            }
        }
        taskOrchestrator.registerJob(taskId, job)
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
     * 执行创建目录。
     */
    fun executeCreateDirectories(
        paneId: PaneId,
        parentLocation: String,
        paths: List<String>,
    ) {
        val taskId = UUID.randomUUID().toString()
        taskOrchestrator.appendTask(
            BackgroundTask(
                id = taskId,
                kind = BackgroundTaskKind.COPY,
                title = I18nMessage(Res.string.msg_create_folders, paths.size),
                status = BackgroundTaskStatus.QUEUED,
                detail = I18nMessage(Res.string.msg_string_literal, parentLocation),
                progress = 0f,
                totalCount = paths.size,
                startTimeMillis = System.currentTimeMillis(),
            )
        )

        val job = scope.launch {
            try {
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(
                        Res.string.msg_string_literal,
                        paths.joinToString(limit = 3, truncated = " ...")
                    ),
                    progress = 0f,
                )
                paths.forEachIndexed { index, path ->
                    ensureActive()
                    fileCommandService.createDirectory(
                        parentLocation = parentLocation,
                        name = path,
                    ).getOrThrow()
                    taskOrchestrator.updateTask(
                        taskId = taskId,
                        status = BackgroundTaskStatus.RUNNING,
                        detail = I18nMessage(Res.string.msg_string_literal, path),
                        progress = (index + 1).toFloat() / paths.size,
                    )
                }
                taskOrchestrator.dismissTask(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = I18nMessage(Res.string.msg_created_folders, paths.size),
                    progress = 1f,
                    processedCount = paths.size,
                )
                onRefreshPane(paneId)
                taskOrchestrator.scheduleAutoCleanup(taskId)
            } catch (_: CancellationException) {
                taskOrchestrator.dismissTask(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(Res.string.msg_cancelled),
                    progress = null,
                )
                onRefreshPane(paneId)
            } catch (failure: Throwable) {
                OnyxLogger.error("FileActionDelegate", "创建目录失败", failure)
                taskOrchestrator.dismissTask(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = failure.message?.let { I18nMessage(Res.string.msg_string_literal, it) }
                        ?: I18nMessage(Res.string.msg_create_folder_failed),
                    progress = null,
                )
                onRefreshPane(paneId)
            }
        }
        taskOrchestrator.registerJob(taskId, job)
    }

    /**
     * 执行批量重命名。
     */
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
                title = I18nMessage(Res.string.action_batch_rename),
                status = BackgroundTaskStatus.QUEUED,
                detail = I18nMessage(Res.string.msg_string_literal, "${renameMap.size} files"),
                progress = 0f,
                totalCount = renameMap.size,
                startTimeMillis = System.currentTimeMillis(),
            )
        )

        val job = scope.launch {
            try {
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(Res.string.msg_string_literal, "Starting..."),
                    progress = 0f,
                )
                renameMap.forEachIndexed { index, (entry, newName) ->
                    ensureActive()
                    val detailText = "${entry.name} → $newName"
                    val prog = (index + 1).toFloat() / renameMap.size
                    taskOrchestrator.updateTask(
                        taskId = taskId,
                        status = BackgroundTaskStatus.RUNNING,
                        detail = I18nMessage(Res.string.msg_string_literal, detailText),
                        progress = prog,
                    )
                    // 同步更新对话框进度
                    (dialogState.value as? RootDialogState.BatchRename)?.let { ds ->
                        dialogState.value = ds.copy(
                            progress = prog,
                            processedCount = index + 1,
                            currentDetail = detailText,
                        )
                    }
                    fileCommandService.rename(entry, newName).getOrThrow()
                }

                taskOrchestrator.dismissTask(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = I18nMessage(Res.string.msg_string_literal, "${renameMap.size} files renamed"),
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
                onRefreshAllPanes()
                taskOrchestrator.scheduleAutoCleanup(taskId)
                // 短暂展示完成状态后自动重置为编辑模式
                delay(600)
                resetBatchRenameForContinue(paneId)
            } catch (_: CancellationException) {
                taskOrchestrator.dismissTask(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(Res.string.msg_cancelled),
                )
                (dialogState.value as? RootDialogState.BatchRename)?.let { ds ->
                    dialogState.value = ds.copy(executing = false, errorMessage = "Cancelled")
                }
            } catch (e: Throwable) {
                OnyxLogger.error("FileActionDelegate", "批量重命名失败", e)
                taskOrchestrator.dismissTask(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = I18nMessage(Res.string.msg_string_literal, e.message ?: "Unknown error"),
                )
                (dialogState.value as? RootDialogState.BatchRename)?.let { ds ->
                    dialogState.value = ds.copy(executing = false, errorMessage = e.message ?: "Unknown error")
                }
                onRefreshAllPanes()
            }
        }
        taskOrchestrator.registerJob(taskId, job)
    }

    /**
     * 重置批量重命名为新一轮输入模式。
     */
    fun resetBatchRenameForContinue(paneId: PaneId) {
        val paneState = getPaneState(paneId)
        val allEntries = (paneState.entriesState as? PaneEntriesState.Ready)?.entries.orEmpty()
        if (allEntries.isEmpty()) {
            dialogState.value = null
            return
        }
        dialogState.value = RootDialogState.BatchRename(
            paneId = paneId,
            entries = allEntries,
        )
    }

    data class PendingDeleteRequest(
        val paneId: PaneId,
        val entries: List<VFile>,
        val moveToTrash: Boolean,
    )
}
