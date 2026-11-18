package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.component.PaneRemovalContext
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.DirectoryChange
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.TaskError
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.shared.usecase.FileTransferUseCase
import com.oruke.onyx.shared.usecase.TaskProgress
import com.oruke.onyx.shared.usecase.TransferEntryFailure
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

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
    onMoveSucceeded: (CompletedTransferHistory) -> Unit,
    /** 成功复制的可逆历史登记入口。 */
    onCopySucceeded: suspend (CompletedTransferHistory) -> Unit,
    /** 冲突或部分完成时移除已不可靠的旧历史。 */
    private val onHistoryInvalidated: () -> Unit,
) {
    /** 按 Provider 实际结果发出逐项传输进度。 */
    private val transferUseCase = FileTransferUseCase(fileCommandService, providerRegistry)

    /** 完成状态显示之前建立的可逆历史记录。 */
    private val historyRecorder = TransferHistoryRecordDelegate(onMoveSucceeded, onCopySucceeded, onHistoryInvalidated)

    /**
     * 启动文件传输后台任务。
     *
     * @param structure 本次结构化传输规划（含待传输条目、根目标目录与相对目录结构映射）。
     * @param operation 复制、移动或解压操作。
     * @param clipboardToClear 与本次传输绑定的剪切载荷，不清理后来复制的内容。
     * @param conflictStrategies 各冲突条目的处理策略。
     */
    @Suppress("TooGenericExceptionCaught") // 传输任务边界统一将 Provider 异常写入任务中心。
    fun launch(
        structure: PlannedTransferStructure,
        operation: FileTransferOperation,
        clipboardToClear: ClipboardManager.ClipboardPayload?,
        conflictStrategies: Map<String, TransferConflictStrategy>,
    ) {
        val entries = structure.entries
        val targetDirectoryLocation = structure.targetDirectoryLocation
        if (entries.isEmpty()) return

        val taskId = UUID.randomUUID().toString()
        val removalContext = if (operation == FileTransferOperation.MOVE) onRemovalStarted(entries) else null
        val removedIds = mutableSetOf<String>()
        val invalidated = mutableSetOf<String>()
        val createdIntermediateDirs = mutableListOf<VFile>()
        val completedEntryIds = ConcurrentHashMap.newKeySet<String>()
        val recordedErrors = mutableListOf<TaskError>()
        var firstEntryFailure: Throwable? = null
        var skippedCount = 0
        val pauseFlag = taskOrchestrator.getOrCreatePauseFlag(taskId)

        enqueueTransferTask(taskId, entries, targetDirectoryLocation, operation)
        registerUnresolvedRetryHandler(
            taskId = taskId,
            structure = structure,
            operation = operation,
            clipboardToClear = clipboardToClear,
            conflictStrategies = conflictStrategies,
            completedEntryIds = completedEntryIds,
        )

        taskOrchestrator.launchQueuedTask(taskId) {
            try {
                transferUseCase.execute(
                    request = FileTransferUseCase.FileTransferRequest(
                        entries = entries,
                        targetDirectoryLocation = targetDirectoryLocation,
                        operation = operation,
                        conflictStrategies = conflictStrategies,
                        entryTargetDirectories = structure.entryTargetDirectories,
                        entryDirectoryChains = structure.entryDirectoryChains,
                    ),
                    awaitReady = {
                        while (pauseFlag.value) {
                            ensureActive()
                            delay(TRANSFER_PAUSE_POLL_INTERVAL_MS)
                        }
                    },
                    isPaused = { pauseFlag.value },
                ).collect { progress ->
                    createdIntermediateDirs += progress.createdDirectories
                    progress.completedEntry?.let { completed -> completedEntryIds += completed.id }
                    progress.directoryChange?.let { change ->
                        removedIds += change.removed.map { it.id }
                        invalidated += change.invalidatedDirectories
                        if (change.removed.isNotEmpty() || change.upserted.isNotEmpty()) {
                            onDirectoryChange(change.copy(invalidatedDirectories = emptySet()))
                        }
                    }
                    skippedCount += progress.skippedCount
                    applyTaskProgress(taskId, progress)
                    progress.entryFailure?.let { entryFailure ->
                        if (firstEntryFailure == null) firstEntryFailure = entryFailure.cause
                        recordSingleEntryFailure(taskId, operation, entryFailure, recordedErrors)
                    }
                }

                if (recordedErrors.isEmpty()) {
                    recordHistorySafelyAfterCommit(
                        taskId = taskId,
                        structure = structure,
                        operation = operation,
                        conflictStrategies = conflictStrategies,
                        clipboardToClear = clipboardToClear,
                        skippedCount = skippedCount,
                        createdIntermediateDirs = createdIntermediateDirs,
                    )
                } else {
                    completeTransferWithEntryFailures(
                        taskId = taskId,
                        structure = structure,
                        operation = operation,
                        completedCount = completedEntryIds.size,
                        skippedCount = skippedCount,
                        recordedErrors = recordedErrors.toList(),
                        firstFailure = firstEntryFailure,
                        createdDirectories = createdIntermediateDirs,
                        invalidated = invalidated,
                    )
                }
            } catch (failure: CancellationException) {
                recordTransferCancellation(taskId, structure, createdIntermediateDirs, invalidated)
                throw failure
            } catch (failure: Exception) {
                recordUnexpectedTransferFailure(
                    taskId = taskId,
                    structure = structure,
                    operation = operation,
                    failure = failure,
                    completedEntryIds = completedEntryIds,
                    skippedCount = skippedCount,
                    recordedErrors = recordedErrors,
                    createdDirectories = createdIntermediateDirs,
                    invalidated = invalidated,
                )
            } finally {
                if (invalidated.isNotEmpty()) onRefreshDirectories(invalidated)
                onRemovalFinished(removalContext, removedIds, invalidated.isNotEmpty())
            }
        }
    }

    /**
     * 注册仅针对未完成（失败或未执行）条目的重试回调，避免重传前面已成功落盘的文件。
     */
    private fun registerUnresolvedRetryHandler(
        taskId: String,
        structure: PlannedTransferStructure,
        operation: FileTransferOperation,
        clipboardToClear: ClipboardManager.ClipboardPayload?,
        conflictStrategies: Map<String, TransferConflictStrategy>,
        completedEntryIds: Set<String>,
    ) {
        taskOrchestrator.registerRetryHandler(taskId) {
            val remainingIds = structure.entries
                .mapNotNullTo(LinkedHashSet()) { entry -> entry.id.takeIf { id -> id !in completedEntryIds } }
            val retryStructure = if (remainingIds.isEmpty() || remainingIds.size == structure.entries.size) {
                structure
            } else {
                structure.retainEntries(remainingIds)
            }
            val retainedIdSet = retryStructure.entries.mapTo(mutableSetOf()) { entry -> entry.id }
            launch(
                structure = retryStructure,
                operation = operation,
                clipboardToClear = clipboardToClear,
                conflictStrategies = conflictStrategies.filterKeys { id -> id in retainedIdSet },
            )
        }
    }

    private fun enqueueTransferTask(
        taskId: String,
        entries: List<VFile>,
        targetDirectoryLocation: String,
        operation: FileTransferOperation,
    ) {
        taskOrchestrator.appendTask(
            BackgroundTask(
                id = taskId,
                kind = taskKindFor(operation),
                title = taskTitleFor(operation, entries.size),
                status = BackgroundTaskStatus.QUEUED,
                canPause = canPauseTransfer(entries, targetDirectoryLocation, providerRegistry),
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, targetDirectoryLocation),
                progress = 0f,
                totalCount = entries.size,
                totalBytes = entries.sumOf { it.sizeBytes ?: 0L },
                startTimeMillis = System.currentTimeMillis(),
            )
        )
    }

    /**
     * 在传输文件已全量提交落盘后安全登记历史记录。
     */
    private suspend fun recordHistorySafelyAfterCommit(
        taskId: String,
        structure: PlannedTransferStructure,
        operation: FileTransferOperation,
        conflictStrategies: Map<String, TransferConflictStrategy>,
        clipboardToClear: ClipboardManager.ClipboardPayload?,
        skippedCount: Int,
        createdIntermediateDirs: List<VFile>,
    ) {
        val entries = structure.entries
        val targetDirectoryLocation = structure.targetDirectoryLocation
        val historyFailure = runCatching {
            historyRecorder.record(
                operation = operation,
                history = CompletedTransferHistory(
                    entries = entries,
                    targetDirectoryLocation = targetDirectoryLocation,
                    entryTargetDirectories = structure.entryTargetDirectories,
                    directorySteps = structure.allDirectorySteps,
                    createdDirectories = createdIntermediateDirs.toList(),
                ),
                strategies = conflictStrategies,
            )
        }.exceptionOrNull()
        if (historyFailure is Error) {
            onHistoryInvalidated()
            throw historyFailure
        }
        if (historyFailure != null) {
            onHistoryInvalidated()
            if (historyFailure !is CancellationException) {
                OnyxLogger.warn("FileTransferDelegate", "传输完成后登记历史快照失败，跳过历史记录", historyFailure)
            }
        }
        withContext(NonCancellable) {
            completeSuccessfulTransfer(
                taskId = taskId,
                entries = entries,
                targetDirectoryLocation = targetDirectoryLocation,
                operation = operation,
                clipboardToClear = clipboardToClear,
                skippedCount = skippedCount,
            )
        }
        if (historyFailure is CancellationException) {
            throw historyFailure
        }
    }

    private fun completeTransferWithEntryFailures(
        taskId: String,
        structure: PlannedTransferStructure,
        operation: FileTransferOperation,
        completedCount: Int,
        skippedCount: Int,
        recordedErrors: List<TaskError>,
        firstFailure: Throwable?,
        createdDirectories: List<VFile>,
        invalidated: MutableSet<String>,
    ) {
        onHistoryInvalidated()
        taskOrchestrator.unregisterJob(taskId)
        val currentTask = taskOrchestrator.tasks.value.firstOrNull { it.id == taskId }
        val committedCount = (completedCount - skippedCount).coerceAtLeast(0)
        val retainedProgress = if (completedCount > 0) currentTask?.progress else null
        taskOrchestrator.updateTask(
            taskId = taskId,
            status = BackgroundTaskStatus.FAILED,
            detail = partialTransferFailureMessageFor(
                operation = operation,
                committedCount = committedCount,
                failedCount = recordedErrors.size,
                targetDirectoryLocation = structure.targetDirectoryLocation,
                firstFailure = firstFailure,
            ),
            progress = retainedProgress,
            processedCount = completedCount,
        )
        taskOrchestrator.updateTaskFields(taskId) { task ->
            task.copy(currentFileName = null, errors = recordedErrors)
        }
        collectTouchedTransferLocations(structure, createdDirectories, invalidated)
    }

    private fun recordTransferCancellation(
        taskId: String,
        structure: PlannedTransferStructure,
        createdDirectories: List<VFile>,
        invalidated: MutableSet<String>,
    ) {
        onHistoryInvalidated()
        taskOrchestrator.unregisterJob(taskId)
        val currentStatus = taskOrchestrator.tasks.value.firstOrNull { it.id == taskId }?.status
        if (currentStatus != BackgroundTaskStatus.SUCCEEDED) {
            taskOrchestrator.updateTask(
                taskId = taskId,
                status = BackgroundTaskStatus.CANCELLED,
                detail = I18nMessage(MessageKey.MSG_CANCELLED),
                progress = null,
            )
        }
        collectTouchedTransferLocations(structure, createdDirectories, invalidated)
    }

    private fun recordUnexpectedTransferFailure(
        taskId: String,
        structure: PlannedTransferStructure,
        operation: FileTransferOperation,
        failure: Exception,
        completedEntryIds: Set<String>,
        skippedCount: Int,
        recordedErrors: List<TaskError>,
        createdDirectories: List<VFile>,
        invalidated: MutableSet<String>,
    ) {
        onHistoryInvalidated()
        OnyxLogger.error("FileTransferDelegate", "文件传输失败 (${operation.name})", failure)
        taskOrchestrator.unregisterJob(taskId)
        val currentTask = taskOrchestrator.tasks.value.firstOrNull { it.id == taskId }
        if (currentTask?.status != BackgroundTaskStatus.SUCCEEDED) {
            val firstUnresolved = structure.entries.firstOrNull { entry -> entry.id !in completedEntryIds }
            val unexpectedErrors = if (firstUnresolved != null) {
                listOf(failure.toTaskError(firstUnresolved, structure.effectiveTargetOf(firstUnresolved)))
            } else {
                failure.toTaskErrors(emptyList(), structure.targetDirectoryLocation)
            }
            val mergedErrors = (recordedErrors + unexpectedErrors).distinctBy { it.fileName to it.message }
            val committedCount = (completedEntryIds.size - skippedCount).coerceAtLeast(0)
            taskOrchestrator.updateTask(
                taskId = taskId,
                status = BackgroundTaskStatus.FAILED,
                detail = partialTransferFailureMessageFor(
                    operation = operation,
                    committedCount = committedCount,
                    failedCount = mergedErrors.size,
                    targetDirectoryLocation = structure.targetDirectoryLocation,
                    firstFailure = failure,
                ),
                progress = if (completedEntryIds.isNotEmpty()) currentTask?.progress else null,
                processedCount = completedEntryIds.size,
            )
            taskOrchestrator.updateTaskFields(taskId) { task ->
                task.copy(currentFileName = null, errors = mergedErrors)
            }
        }
        collectTouchedTransferLocations(structure, createdDirectories, invalidated)
    }

    /**
     * 完成成功任务并按原始载荷条件清理剪贴板，跳过项不被误报为实际传输。
     */
    private suspend fun completeSuccessfulTransfer(
        taskId: String,
        entries: List<VFile>,
        targetDirectoryLocation: String,
        operation: FileTransferOperation,
        clipboardToClear: ClipboardManager.ClipboardPayload?,
        skippedCount: Int,
    ) = withContext(NonCancellable) {
        taskOrchestrator.unregisterJob(taskId)
        val completedBytes = taskOrchestrator.tasks.value
            .firstOrNull { task -> task.id == taskId }
            ?.totalBytes
            ?: entries.sumOf { entry -> entry.sizeBytes ?: 0L }
        taskOrchestrator.updateTask(
            taskId = taskId,
            status = BackgroundTaskStatus.SUCCEEDED,
            detail = completedTransferMessageFor(operation, entries.size, skippedCount, targetDirectoryLocation),
            progress = 1f,
            processedCount = entries.size,
            processedBytes = completedBytes,
        )
        taskOrchestrator.updateTaskFields(taskId) { task ->
            task.copy(currentFileName = null, errors = emptyList())
        }
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

    private fun recordSingleEntryFailure(
        taskId: String,
        operation: FileTransferOperation,
        entryFailure: TransferEntryFailure,
        recordedErrors: MutableList<TaskError>,
    ) {
        OnyxLogger.error(
            "FileTransferDelegate",
            "单条目文件传输失败 (${operation.name}): ${entryFailure.entry.location}",
            entryFailure.cause,
        )
        recordedErrors += entryFailure.cause.toTaskError(
            entry = entryFailure.entry,
            targetDirectoryLocation = entryFailure.targetDirectoryLocation,
        )
        val snapshotErrors = recordedErrors.toList()
        taskOrchestrator.updateTaskFields(taskId) { task ->
            task.copy(errors = snapshotErrors)
        }
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

    /**
     * 记录冲突预检失败，并保留从原始业务入口重试的能力。
     */
    fun recordPreflightFailure(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        operation: FileTransferOperation,
        failure: Throwable,
        retry: () -> Unit,
    ) {
        val taskId = UUID.randomUUID().toString()
        val errorEntries = if (entries.size == 1) entries else emptyList()
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
                errors = failure.toTaskErrors(errorEntries, targetDirectoryLocation),
            )
        )
        taskOrchestrator.registerRetryHandler(taskId, retry)
    }
}
