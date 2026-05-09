package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.filesystem.FileCommandService
import com.oruke.onyx.app.filesystem.FileRepository
import com.oruke.onyx.app.filesystem.TransferConflictStrategy
import com.oruke.onyx.app.filesystem.VfsPathService
import com.oruke.onyx.app.filesystem.VfsProviderRegistry
import com.oruke.onyx.app.usecase.FileTransferUseCase
import com.oruke.onyx.app.usecase.TaskProgress
import com.oruke.onyx.app.usecase.buildTransferTaskDetail
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.*

/**
 * 文件传输委托 — 负责复制 / 移动 / 冲突检测 / 进度追踪。
 *
 * 从 DefaultRootComponent 剥离的纯业务逻辑，不直接持有 UI 组件引用。
 * 通过回调 lambda 与宿主组件交互（对话框、面板刷新等）。
 */
class FileTransferDelegate(
    private val scope: CoroutineScope,
    private val fileCommandService: FileCommandService,
    private val fileRepository: FileRepository,
    private val taskOrchestrator: TaskOrchestrator,
    private val clipboardManager: ClipboardManager,
    private val dialogState: MutableStateFlow<RootDialogState?>,
    private val pathService: VfsPathService,
    private val providerRegistry: VfsProviderRegistry,
    private val onRefreshAllPanes: () -> Unit,
) {
    var pendingTransferRequest: PendingTransferRequest? = null
        private set
    private val transferUseCase = FileTransferUseCase(fileCommandService, providerRegistry)

    /**
     * 请求文件传输 — 检测冲突后决定直接执行或弹出冲突对话框。
     */
    fun requestTransferEntriesToDirectory(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        operation: FileTransferOperation,
        clearClipboardOnSuccess: Boolean,
    ) {
        if (operation == FileTransferOperation.MOVE && entries.all { it.parentLocation == targetDirectoryLocation }) {
            return
        }
        if (entries.any { entry -> pathService.isSameOrChildOf(targetDirectoryLocation, entry.location) }) {
            return
        }
        scope.launch {
            val conflictingEntries = try {
                detectConflictingEntries(
                    entries = entries,
                    targetDirectoryLocation = targetDirectoryLocation,
                )
            } catch (failure: Throwable) {
                OnyxLogger.error("FileTransferDelegate", "冲突检测失败 (${operation.name})", failure)
                appendFailedTransferTask(
                    entries = entries,
                    targetDirectoryLocation = targetDirectoryLocation,
                    operation = operation,
                    failure = failure,
                )
                return@launch
            }
            if (conflictingEntries.isNotEmpty()) {
                pendingTransferRequest = PendingTransferRequest(
                    entries = entries,
                    targetDirectoryLocation = targetDirectoryLocation,
                    operation = operation,
                    clearClipboardOnSuccess = clearClipboardOnSuccess,
                    conflictingEntries = conflictingEntries,
                    resolvedStrategies = emptyMap(),
                    nextConflictIndex = 0,
                )
                val firstConflict = conflictingEntries.first()
                dialogState.value = RootDialogState.ConflictResolution(
                    sourceName = firstConflict.name,
                    targetLocation = targetDirectoryLocation,
                    operation = operation,
                    currentIndex = 1,
                    total = conflictingEntries.size,
                )
                return@launch
            }
            launchTransferTask(
                entries = entries,
                targetDirectoryLocation = targetDirectoryLocation,
                operation = operation,
                clearClipboardOnSuccess = clearClipboardOnSuccess,
                conflictStrategies = emptyMap(),
            )
        }
    }

    /**
     * 解决单个冲突条目，或应用到所有后续冲突。
     */
    fun resolveConflict(
        strategy: TransferConflictStrategy,
        applyToAll: Boolean,
    ) {
        val pendingRequest = pendingTransferRequest ?: return
        val currentConflict = pendingRequest.conflictingEntries.getOrNull(pendingRequest.nextConflictIndex) ?: run {
            pendingTransferRequest = null
            dialogState.value = null
            return
        }
        val nextResolvedStrategies = pendingRequest.resolvedStrategies.toMutableMap().apply {
            put(currentConflict.id, strategy)
        }
        if (applyToAll) {
            pendingRequest.conflictingEntries
                .drop(pendingRequest.nextConflictIndex + 1)
                .forEach { entry ->
                    nextResolvedStrategies[entry.id] = strategy
                }
            pendingTransferRequest = null
            dialogState.value = null
            launchTransferTask(
                entries = pendingRequest.entries,
                targetDirectoryLocation = pendingRequest.targetDirectoryLocation,
                operation = pendingRequest.operation,
                clearClipboardOnSuccess = pendingRequest.clearClipboardOnSuccess,
                conflictStrategies = nextResolvedStrategies,
            )
            return
        }

        val nextConflictIndex = pendingRequest.nextConflictIndex + 1
        if (nextConflictIndex >= pendingRequest.conflictingEntries.size) {
            pendingTransferRequest = null
            dialogState.value = null
            launchTransferTask(
                entries = pendingRequest.entries,
                targetDirectoryLocation = pendingRequest.targetDirectoryLocation,
                operation = pendingRequest.operation,
                clearClipboardOnSuccess = pendingRequest.clearClipboardOnSuccess,
                conflictStrategies = nextResolvedStrategies,
            )
            return
        }

        val nextConflict = pendingRequest.conflictingEntries[nextConflictIndex]
        pendingTransferRequest = pendingRequest.copy(
            resolvedStrategies = nextResolvedStrategies,
            nextConflictIndex = nextConflictIndex,
        )
        dialogState.value = RootDialogState.ConflictResolution(
            sourceName = nextConflict.name,
            targetLocation = pendingRequest.targetDirectoryLocation,
            operation = pendingRequest.operation,
            currentIndex = nextConflictIndex + 1,
            total = pendingRequest.conflictingEntries.size,
        )
    }

    /**
     * 清除待处理的传输请求（用于对话框取消时）。
     */
    fun clearPending() {
        pendingTransferRequest = null
    }

    /**
     * 启动文件传输后台任务。
     */
    private fun launchTransferTask(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        operation: FileTransferOperation,
        clearClipboardOnSuccess: Boolean,
        conflictStrategies: Map<String, TransferConflictStrategy>,
    ) {
        if (entries.isEmpty()) return

        val taskId = UUID.randomUUID().toString()
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
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, targetDirectoryLocation),
                progress = 0f,
                totalCount = entries.size,
                totalBytes = initialTotalBytes,
                startTimeMillis = startTime,
            )
        )

        val job = scope.launch {
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
                            delay(200)
                        }
                    },
                ).collect { progress ->
                    applyTaskProgress(taskId, progress)
                }

                taskOrchestrator.unregisterJob(taskId)
                val completedBytes = taskOrchestrator.tasks.value
                    .firstOrNull { task -> task.id == taskId }
                    ?.totalBytes
                    ?: entries.sumOf { entry -> entry.sizeBytes ?: 0L }
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = when (operation) {
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
                if (clearClipboardOnSuccess) {
                    clipboardManager.clear()
                }
                onRefreshAllPanes()
                taskOrchestrator.scheduleAutoCleanup(taskId)
            } catch (_: CancellationException) {
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(MessageKey.MSG_CANCELLED),
                    progress = null,
                )
                onRefreshAllPanes()
            } catch (failure: Throwable) {
                OnyxLogger.error("FileTransferDelegate", "文件传输失败 (${operation.name})", failure)
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = failure.message?.let { I18nMessage(MessageKey.MSG_STRING_LITERAL, it) }
                        ?: when (operation) {
                            FileTransferOperation.COPY -> I18nMessage(MessageKey.MSG_COPY_FAILED)
                            FileTransferOperation.MOVE -> I18nMessage(MessageKey.MSG_MOVE_FAILED)
                            FileTransferOperation.EXTRACT -> I18nMessage(MessageKey.MSG_EXTRACT_FAILED)
                        },
                    progress = null,
                )
                onRefreshAllPanes()
            }
        }
        taskOrchestrator.registerJob(taskId, job)
    }

    /**
     * 检测传输目标目录中已存在的同名条目。
     */
    private suspend fun detectConflictingEntries(
        entries: List<VFile>,
        targetDirectoryLocation: String,
    ): List<VFile> {
        val existingNames = fileRepository.list(targetDirectoryLocation)
            .getOrThrow()
            .mapTo(mutableSetOf()) { entry -> entry.name }
        return entries.filter { entry -> existingNames.contains(entry.name) }
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
        )
        progress.currentFileName?.let { fileName ->
            taskOrchestrator.updateTaskFields(taskId) { task ->
                task.copy(currentFileName = fileName)
            }
        }
    }

    private fun appendFailedTransferTask(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        operation: FileTransferOperation,
        failure: Throwable,
    ) {
        val taskId = UUID.randomUUID().toString()
        taskOrchestrator.appendTask(
            BackgroundTask(
                id = taskId,
                kind = taskKindFor(operation),
                title = taskTitleFor(operation, entries.size),
                status = BackgroundTaskStatus.FAILED,
                detail = failure.message?.let { I18nMessage(MessageKey.MSG_STRING_LITERAL, it) }
                    ?: failureMessageFor(operation),
                progress = null,
                totalCount = entries.size,
                startTimeMillis = System.currentTimeMillis(),
            )
        )
        taskOrchestrator.scheduleAutoCleanup(taskId)
    }

    private fun taskKindFor(operation: FileTransferOperation): BackgroundTaskKind {
        return when (operation) {
            FileTransferOperation.COPY -> BackgroundTaskKind.COPY
            FileTransferOperation.MOVE -> BackgroundTaskKind.MOVE
            FileTransferOperation.EXTRACT -> BackgroundTaskKind.EXTRACT
        }
    }

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

    private fun failureMessageFor(operation: FileTransferOperation): I18nMessage {
        return when (operation) {
            FileTransferOperation.COPY -> I18nMessage(MessageKey.MSG_COPY_FAILED)
            FileTransferOperation.MOVE -> I18nMessage(MessageKey.MSG_MOVE_FAILED)
            FileTransferOperation.EXTRACT -> I18nMessage(MessageKey.MSG_EXTRACT_FAILED)
        }
    }

    data class PendingTransferRequest(
        val entries: List<VFile>,
        val targetDirectoryLocation: String,
        val operation: FileTransferOperation,
        val clearClipboardOnSuccess: Boolean,
        val conflictingEntries: List<VFile>,
        val resolvedStrategies: Map<String, TransferConflictStrategy>,
        val nextConflictIndex: Int,
    )
}
