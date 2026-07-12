package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.shared.usecase.ArchiveExtractionUseCase
import com.oruke.onyx.shared.usecase.TaskProgress
import com.oruke.onyx.shared.usecase.buildTaskDetail
import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.vfs.archive.ArchiveProgressSink
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 压缩包操作委托 — 负责解压任务、密码验证等压缩包相关业务。
 *
 * 从 DefaultRootComponent 剥离的纯业务逻辑。
 */
internal class ArchiveActionDelegate(
    private val archiveService: ArchiveService,
    private val taskOrchestrator: TaskOrchestrator,
    private val dialogState: MutableStateFlow<RootDialogState?>,
    private val onRefreshAllPanes: () -> Unit,
) {
    var pendingArchiveExtraction: PendingArchiveExtraction? = null
        private set
    private val archiveExtractionUseCase = ArchiveExtractionUseCase(archiveService)

    /**
     * 将压缩包内的选中条目解压到目标本地目录。
     * 用于从已打开的压缩包面板拖拽文件到本地目录面板。
     */
    @Suppress("TooGenericExceptionCaught") // 后台任务边界负责将任意业务异常转换为任务失败状态。
    fun launchArchiveExtractToDirectory(
        entries: List<VFile>,
        targetDirectoryLocation: String,
    ) {
        val taskId = UUID.randomUUID().toString()
        taskOrchestrator.appendTask(
            BackgroundTask(
                id = taskId,
                kind = BackgroundTaskKind.EXTRACT,
                title = I18nMessage(MessageKey.MSG_EXTRACT_ITEMS, entries.size),
                status = BackgroundTaskStatus.QUEUED,
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, targetDirectoryLocation),
                progress = 0f,
                totalCount = entries.size,
                startTimeMillis = System.currentTimeMillis(),
            )
        )

        taskOrchestrator.launchQueuedTask(taskId) {
            try {
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, targetDirectoryLocation),
                    progress = 0f,
                )
                archiveExtractionUseCase.extractEntriesToDirectory(
                    request = ArchiveExtractionUseCase.ArchiveEntryExtractionRequest(
                        entries = entries,
                        targetDirectoryLocation = targetDirectoryLocation,
                    ),
                    resolvePassword = { request ->
                        requestArchivePassword(
                            archivePath = request.archivePath,
                            archiveName = request.archiveName,
                            entries = entries,
                            targetLocation = targetDirectoryLocation,
                            taskId = taskId,
                            taskTitle = I18nMessage(MessageKey.MSG_EXTRACT_ITEMS, entries.size),
                        )
                    },
                ).collect { progress ->
                    applyTaskProgress(taskId, progress)
                }

                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = I18nMessage(MessageKey.MSG_EXTRACTED_ITEMS, entries.size, targetDirectoryLocation),
                    progress = 1f,
                )
                // 刷新目标目录所在面板
                onRefreshAllPanes()
                taskOrchestrator.scheduleAutoCleanup(taskId)
            } catch (e: CancellationException) {
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(MessageKey.MSG_EXTRACT_FAILED),
                    progress = 0f,
                )
                throw e
            } catch (e: Exception) {
                OnyxLogger.error("ArchiveActionDelegate", "解压失败", e)
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = e.toI18nMessage(MessageKey.MSG_EXTRACT_FAILED),
                    progress = 0f,
                )
            }
        }
    }

    /**
     * 通用压缩包解压任务启动器 — 支持加密压缩包密码输入。
     */
    @Suppress("TooGenericExceptionCaught") // 后台任务边界负责将任意 Provider 异常转换为任务失败状态。
    fun launchArchiveExtraction(
        selectedEntries: List<VFile>,
        currentLocation: String,
        taskTitle: I18nMessage,
        extractAction: suspend (VFile, String, String?, ArchiveProgressSink) -> Result<Unit>,
    ) {
        val archiveEntries = selectedEntries.filter { entry ->
            entry.kind == VFileKind.FILE && ArchiveService.isArchive(entry.name)
        }
        if (archiveEntries.isEmpty()) return

        val taskId = UUID.randomUUID().toString()
        taskOrchestrator.appendTask(
            BackgroundTask(
                id = taskId,
                kind = BackgroundTaskKind.EXTRACT,
                title = taskTitle,
                status = BackgroundTaskStatus.QUEUED,
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, buildTaskDetail(archiveEntries)),
                progress = 0f,
                totalCount = archiveEntries.size,
                startTimeMillis = System.currentTimeMillis(),
            )
        )

        taskOrchestrator.launchQueuedTask(taskId) {
            try {
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, buildTaskDetail(archiveEntries)),
                    progress = 0f,
                )
                archiveExtractionUseCase.extractArchiveFiles(
                    request = ArchiveExtractionUseCase.ArchiveFileExtractionRequest(
                        archiveEntries = archiveEntries,
                        currentLocation = currentLocation,
                        extractAction = extractAction,
                    ),
                    resolvePassword = { request ->
                        requestArchivePassword(
                            archivePath = request.archivePath,
                            archiveName = request.archiveName,
                            entries = archiveEntries,
                            targetLocation = currentLocation,
                            taskId = taskId,
                            taskTitle = taskTitle,
                            extractAction = extractAction,
                        )
                    },
                ).collect { progress ->
                    applyTaskProgress(taskId, progress)
                }

                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, buildTaskDetail(archiveEntries)),
                    progress = 1f,
                    processedCount = archiveEntries.size,
                )
                onRefreshAllPanes()
                taskOrchestrator.scheduleAutoCleanup(taskId)
            } catch (failure: CancellationException) {
                pendingArchiveExtraction = null
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(MessageKey.MSG_CANCELLED),
                )
                throw failure
            } catch (e: Exception) {
                OnyxLogger.error("ArchiveActionDelegate", "拖拽解压失败", e)
                pendingArchiveExtraction = null
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = e.toI18nMessage(MessageKey.MSG_EXTRACT_FAILED),
                )
            }
        }
    }

    /**
     * 提交压缩包密码（从对话框回调）。
     */
    fun submitArchivePassword(password: String) {
        val pending = pendingArchiveExtraction ?: return
        pending.passwordDeferred.complete(password)
    }

    /**
     * 清除待处理状态（用于对话框取消时）。
     */
    fun clearPending() {
        pendingArchiveExtraction?.passwordDeferred?.cancel()
        pendingArchiveExtraction = null
    }

    /**
     * 通用密码请求循环 — 弹出对话框、验证密码，直到正确或取消。
     */
    private suspend fun requestArchivePassword(
        archivePath: String,
        archiveName: String,
        entries: List<VFile>,
        targetLocation: String,
        taskId: String,
        taskTitle: I18nMessage,
        extractAction: suspend (VFile, String, String?, ArchiveProgressSink) -> Result<Unit> =
            { _, _, _, _ -> Result.success(Unit) },
    ): String {
        var errorMsg: I18nMessage? = null
        while (true) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val deferred = CompletableDeferred<String>()
            pendingArchiveExtraction = PendingArchiveExtraction(
                entries = entries,
                currentLocation = targetLocation,
                taskId = taskId,
                taskTitle = taskTitle,
                extractAction = extractAction,
                passwordDeferred = deferred,
            )
            dialogState.value = RootDialogState.ArchivePassword(
                archiveName = archiveName,
                error = errorMsg,
            )
            val candidatePassword = deferred.await()
            val valid = archiveService.verifyPassword(archivePath, candidatePassword)
            if (valid) {
                dialogState.value = null
                pendingArchiveExtraction = null
                return candidatePassword
            } else {
                errorMsg = I18nMessage(MessageKey.MSG_ARCHIVE_PASSWORD_INVALID)
            }
        }
    }

    class PendingArchiveExtraction(
        val entries: List<VFile>,
        val currentLocation: String,
        val taskId: String,
        val taskTitle: I18nMessage,
        val extractAction: suspend (VFile, String, String?, ArchiveProgressSink) -> Result<Unit>,
        val passwordDeferred: CompletableDeferred<String>,
    )

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
}
