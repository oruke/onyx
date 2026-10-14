package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.component.PaneRemovalContext
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.DirectoryChange
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.shared.usecase.FileTransferUseCase
import com.oruke.onyx.shared.usecase.TaskProgress
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** 暂停期间检查恢复状态的间隔，不阻塞主线程。 */
private const val TRANSFER_PAUSE_POLL_INTERVAL_MS = 200L

/** 编排文件传输后台任务、真实目录结果、选择续接与可逆历史登记。 */
internal class FileTransferTaskDelegate(
    /** 统一文件命令能力。 */
    fileCommandService: FileCommandService,
    /** 应用级任务队列与进度状态。 */
    private val taskOrchestrator: TaskOrchestrator,
    /** 与原始剪切载荷核对后才允许清理的剪贴板。 */
    private val clipboardManager: ClipboardManager,
    /** 用于判断传输能力与暂停边界的 Provider 注册表。 */
    private val providerRegistry: VfsProviderRegistry,
    /** 已确认变化的共享目录入口。 */
    private val onDirectoryChange: (DirectoryChange) -> Unit,
    /** 不确定或部分失败目录的校验入口。 */
    private val onRefreshDirectories: (Set<String>) -> Unit,
    /** 捕获匹配当前访问的删除上下文。 */
    private val onRemovalStarted: (List<VFile>) -> PaneRemovalContext?,
    /** 真实移除结果的最终选择续接入口。 */
    private val onRemovalFinished: (PaneRemovalContext?, Set<String>, Boolean) -> Unit,
    /** 成功移动的可逆历史登记入口。 */
    onMoveSucceeded: (List<VFile>, String) -> Unit,
    /** 成功复制的可逆历史登记入口。 */
    onCopySucceeded: suspend (List<VFile>, String) -> Unit,
    /** 冲突或部分完成时移除已不可靠的旧历史。 */
    private val onHistoryInvalidated: () -> Unit,
) {
    /** 按 Provider 实际结果发出逐项传输进度。 */
    private val transferUseCase = FileTransferUseCase(fileCommandService, providerRegistry)
    /** 完成状态显示之前建立的可逆历史记录。 */
    private val historyRecorder = TransferHistoryRecordDelegate(onMoveSucceeded, onCopySucceeded, onHistoryInvalidated)

    /**
     * 启动文件传输后台任务。
     * @param entries 待传输条目。
     * @param targetDirectoryLocation 当前请求的目标目录。
     * @param operation 复制、移动或解压操作。
     * @param clipboardToClear 与本次传输绑定的剪切载荷，不清理后来复制的内容。
     * @param conflictStrategies 各冲突条目的处理策略。
     * @return 无返回值，最终状态写入任务中心。
     */
    @Suppress("TooGenericExceptionCaught") // 传输任务边界统一将 Provider 异常写入任务中心。
    fun launch(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        operation: FileTransferOperation,
        clipboardToClear: ClipboardManager.ClipboardPayload?,
        conflictStrategies: Map<String, TransferConflictStrategy>,
    ) {
        if (entries.isEmpty()) return

        val taskId = UUID.randomUUID().toString()
        val removalContext = if (operation == FileTransferOperation.MOVE) onRemovalStarted(entries) else null
        val removedIds = mutableSetOf<String>()
        val invalidated = mutableSetOf<String>()
        var skippedCount = 0
        val taskKind = taskKindFor(operation)
        val pauseFlag = taskOrchestrator.getOrCreatePauseFlag(taskId)
        val startTime = System.currentTimeMillis()
        val initialTotalBytes = entries.sumOf { it.sizeBytes ?: 0L }

        taskOrchestrator.appendTask(
            BackgroundTask(
                id = taskId,
                kind = taskKind,
                title = taskTitleFor(operation, entries.size),
                status = BackgroundTaskStatus.QUEUED,
                canPause = canPauseTransfer(entries, targetDirectoryLocation),
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, targetDirectoryLocation),
                progress = 0f,
                totalCount = entries.size,
                totalBytes = initialTotalBytes,
                startTimeMillis = startTime,
            )
        )
        taskOrchestrator.registerRetryHandler(taskId) {
            launch(
                entries = entries,
                targetDirectoryLocation = targetDirectoryLocation,
                operation = operation,
                clipboardToClear = clipboardToClear,
                conflictStrategies = conflictStrategies,
            )
        }

        taskOrchestrator.launchQueuedTask(taskId) {
            try {
                transferUseCase.execute(
                    request = FileTransferUseCase.FileTransferRequest(
                        entries = entries,
                        targetDirectoryLocation = targetDirectoryLocation,
                        operation = operation,
                        conflictStrategies = conflictStrategies,
                    ),
                    awaitReady = {
                        while (pauseFlag.value) {
                            ensureActive()
                            delay(TRANSFER_PAUSE_POLL_INTERVAL_MS)
                        }
                    },
                    isPaused = { pauseFlag.value },
                ).collect { progress ->
                    progress.directoryChange?.let { change ->
                        removedIds += change.removed.map { it.id }
                        invalidated += change.invalidatedDirectories
                        if (change.removed.isNotEmpty() || change.upserted.isNotEmpty()) {
                            onDirectoryChange(change.copy(invalidatedDirectories = emptySet()))
                        }
                    }
                    skippedCount += progress.skippedCount
                    applyTaskProgress(taskId, progress)
                }

                // 在任务显示完成前建立撤销记录，避免用户立即撤销时误操作上一条历史。
                historyRecorder.record(operation, entries, targetDirectoryLocation, conflictStrategies)
                completeSuccessfulTransfer(taskId, entries, targetDirectoryLocation,
                    operation, clipboardToClear, skippedCount)
            } catch (failure: CancellationException) {
                onHistoryInvalidated()
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(MessageKey.MSG_CANCELLED),
                    progress = null,
                )
                invalidated += entries.mapNotNull { it.parentLocation }
                invalidated += targetDirectoryLocation
                throw failure
            } catch (failure: Exception) {
                onHistoryInvalidated()
                OnyxLogger.error("FileTransferDelegate", "文件传输失败 (${operation.name})", failure)
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = failure.toTransferFailureMessage(operation),
                    progress = null,
                )
                taskOrchestrator.updateTaskFields(taskId) { task ->
                    task.copy(errors = failure.toTaskErrors(entries, targetDirectoryLocation))
                }
                invalidated += entries.mapNotNull { it.parentLocation }
                invalidated += targetDirectoryLocation
            } finally {
                if (invalidated.isNotEmpty()) onRefreshDirectories(invalidated)
                onRemovalFinished(removalContext, removedIds, invalidated.isNotEmpty())
            }
        }
    }

    /**
     * 完成成功任务并按原始载荷条件清理剪贴板，跳过项不被误报为实际传输。
     * @param taskId 本次任务身份。
     * @param entries 原始顶层请求条目。
     * @param targetDirectoryLocation 实际请求的目标目录。
     * @param operation 原始业务操作。
     * @param clipboardToClear 本次捕获的剪切载荷。
     * @param skippedCount 明确跳过的顶层条目数量。
     */
    private suspend fun completeSuccessfulTransfer(
        taskId: String,
        entries: List<VFile>,
        targetDirectoryLocation: String,
        operation: FileTransferOperation,
        clipboardToClear: ClipboardManager.ClipboardPayload?,
        skippedCount: Int,
    ) {
        taskOrchestrator.unregisterJob(taskId)
        val completedBytes = taskOrchestrator.tasks.value
            .firstOrNull { task -> task.id == taskId }
            ?.totalBytes
            ?: entries.sumOf { entry -> entry.sizeBytes ?: 0L }
        taskOrchestrator.updateTask(
            taskId = taskId,
            status = BackgroundTaskStatus.SUCCEEDED,
            detail = if (skippedCount > 0) I18nMessage(MessageKey.MSG_TRANSFER_SKIPPED_ITEMS,
                entries.size - skippedCount, skippedCount) else when (operation) {
                FileTransferOperation.COPY ->
                    I18nMessage(MessageKey.MSG_COPIED_ITEMS, entries.size, targetDirectoryLocation)

                FileTransferOperation.MOVE ->
                    I18nMessage(MessageKey.MSG_MOVED_ITEMS, entries.size, targetDirectoryLocation)

                FileTransferOperation.EXTRACT ->
                    I18nMessage(MessageKey.MSG_EXTRACTED_ITEMS, entries.size, targetDirectoryLocation)
            },
            progress = 1f,
            processedCount = entries.size,
            processedBytes = completedBytes,
        )
        taskOrchestrator.updateTaskFields(taskId) { task -> task.copy(errors = emptyList()) }
        if (clipboardToClear != null && skippedCount == 0) {
            clipboardManager.clearIfUnchanged(clipboardToClear).onFailure { failure ->
                OnyxLogger.warn("FileTransferDelegate", "传输完成后清理剪贴板失败", failure)
                taskOrchestrator.updateTaskFields(taskId) { task ->
                    task.copy(detail = I18nMessage(MessageKey.MSG_TRANSFER_CLIPBOARD_CLEAR_FAILED))
                }
            }
        }
        taskOrchestrator.scheduleAutoCleanup(taskId)
    }

    /**
     * 将用例进度同步到任务中心，文件变化由单独回调更新目录。
     * @param taskId 本次任务身份。
     * @param progress 用例发出的实际进度。
     */
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

    /**
     * 记录冲突预检失败，并保留从原始业务入口重试的能力。
     * @param entries 原始请求条目。
     * @param targetDirectoryLocation 原始目标目录。
     * @param operation 原始操作类型。
     * @param failure 预检真实失败原因。
     * @param retry 重新执行预检的命令。
     */
    fun recordPreflightFailure(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        operation: FileTransferOperation,
        failure: Throwable,
        retry: () -> Unit,
    ) {
        val taskId = UUID.randomUUID().toString()
        taskOrchestrator.appendTask(
            BackgroundTask(
                id = taskId,
                kind = taskKindFor(operation),
                title = taskTitleFor(operation, entries.size),
                status = BackgroundTaskStatus.FAILED,
                detail = failure.toTransferFailureMessage(operation),
                progress = null,
                totalCount = entries.size,
                startTimeMillis = System.currentTimeMillis(),
                errors = failure.toTaskErrors(entries, targetDirectoryLocation),
            )
        )
        taskOrchestrator.registerRetryHandler(taskId, retry)
    }

    /**
     * 将传输操作映射为任务中心分类。
     * @param operation 文件操作。
     * @return 对应任务类型。
     */
    private fun taskKindFor(operation: FileTransferOperation): BackgroundTaskKind {
        return when (operation) {
            FileTransferOperation.COPY -> BackgroundTaskKind.COPY
            FileTransferOperation.MOVE -> BackgroundTaskKind.MOVE
            FileTransferOperation.EXTRACT -> BackgroundTaskKind.EXTRACT
        }
    }

    /**
     * 判断当前传输是否能在内容块或顶层条目边界可靠暂停。
     *
     * 同一远端 Provider 的单项服务端复制没有字节回调，因此不暴露无效暂停按钮。
     *
     * @param entries 待传输条目。
     * @param targetDirectoryLocation 目标目录位置。
     * @return 能可靠暂停时返回 true。
     */
    private fun canPauseTransfer(
        entries: List<VFile>,
        targetDirectoryLocation: String,
    ): Boolean {
        if (entries.size > 1) return true
        val sourceProtocol = entries.firstOrNull()
            ?.let { entry -> providerRegistry.providerFor(entry.location).getOrNull()?.protocol }
            ?: return false
        val targetProtocol = providerRegistry.providerFor(targetDirectoryLocation).getOrNull()?.protocol
            ?: return false
        return sourceProtocol == VfsProtocol.LOCAL || sourceProtocol != targetProtocol
    }

    /**
     * 构造可随语言变化解析的传输任务标题。
     * @param operation 文件操作。
     * @param entryCount 顶层请求条目数量。
     * @return 本地化任务标题。
     */
    private fun taskTitleFor(
        operation: FileTransferOperation,
        entryCount: Int,
    ): I18nMessage {
        return when (operation) {
            FileTransferOperation.COPY -> I18nMessage(MessageKey.MSG_COPY_ITEMS, entryCount)
            FileTransferOperation.MOVE -> I18nMessage(MessageKey.MSG_MOVE_ITEMS, entryCount)
            FileTransferOperation.EXTRACT -> I18nMessage(MessageKey.MSG_EXTRACT_ITEMS, entryCount)
        }
    }

    /**
     * 提供传输错误映射使用的操作级消息。
     * @param operation 文件操作。
     * @return 本地化通用失败消息。
     */
    private fun failureMessageFor(operation: FileTransferOperation): I18nMessage {
        return when (operation) {
            FileTransferOperation.COPY -> I18nMessage(MessageKey.MSG_COPY_FAILED)
            FileTransferOperation.MOVE -> I18nMessage(MessageKey.MSG_MOVE_FAILED)
            FileTransferOperation.EXTRACT -> I18nMessage(MessageKey.MSG_EXTRACT_FAILED)
        }
    }

    /**
     * 通过统一错误映射保留传输失败原因及本地化参数。
     *
     * @param operation 当前传输操作。
     * @return 可在当前语言下解析的失败消息。
     */
    private fun Throwable.toTransferFailureMessage(operation: FileTransferOperation): I18nMessage {
        return toI18nMessage(failureMessageFor(operation).key)
    }

}
