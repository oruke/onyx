package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.app.filesystem.VfsPathService
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.shared.usecase.FileTransferUseCase
import com.oruke.onyx.shared.usecase.TaskProgress
import com.oruke.onyx.shared.usecase.buildTransferTaskDetail
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.TaskError
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** 文件任务暂停期间检查恢复状态的轮询间隔。 */
private const val TRANSFER_PAUSE_POLL_INTERVAL_MS = 200L

/**
 * 文件传输委托 — 负责复制 / 移动 / 冲突检测 / 进度追踪。
 *
 * 从 DefaultRootComponent 剥离的纯业务逻辑，不直接持有 UI 组件引用。
 * 通过回调 lambda 与宿主组件交互（对话框、面板刷新等）。
 */
internal class FileTransferDelegate(
    private val scope: CoroutineScope,
    private val fileCommandService: FileCommandService,
    private val fileRepository: FileRepository,
    private val taskOrchestrator: TaskOrchestrator,
    private val clipboardManager: ClipboardManager,
    private val dialogState: MutableStateFlow<RootDialogState?>,
    private val pathService: VfsPathService,
    private val providerRegistry: VfsProviderRegistry,
    private val onRefreshAllPanes: () -> Unit,
    private val onMoveSucceeded: (entries: List<VFile>, targetDirectoryLocation: String) -> Unit = { _, _ -> },
) {
    var pendingTransferRequest: PendingTransferRequest? = null
        private set
    private val transferUseCase = FileTransferUseCase(fileCommandService, providerRegistry)

    /**
     * 请求文件传输 — 检测冲突后决定直接执行或弹出冲突对话框。
     */
    @Suppress("TooGenericExceptionCaught") // 冲突预检边界统一将 Provider 异常转换为失败任务。
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
            } catch (failure: Exception) {
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
        val currentConflict = pendingRequest.conflictingEntries.getOrNull(pendingRequest.nextConflictIndex)
        if (currentConflict == null) {
            pendingTransferRequest = null
            dialogState.value = null
        } else {
            val nextResolvedStrategies = pendingRequest.resolveConflictStrategies(
                currentConflict = currentConflict,
                strategy = strategy,
                applyToAll = applyToAll,
            )
            val nextConflictIndex = if (applyToAll) {
                pendingRequest.conflictingEntries.size
            } else {
                pendingRequest.nextConflictIndex + 1
            }
            if (nextConflictIndex >= pendingRequest.conflictingEntries.size) {
                finishPendingTransfer(pendingRequest, nextResolvedStrategies)
            } else {
                showNextConflict(pendingRequest, nextResolvedStrategies, nextConflictIndex)
            }
        }
    }

    /**
     * 合并当前选择，并按需应用到尚未处理的全部冲突。
     *
     * @param currentConflict 当前正在处理的冲突条目。
     * @param strategy 用户选择的冲突策略。
     * @param applyToAll 是否将策略应用到剩余冲突。
     * @return 合并后的条目策略表。
     */
    private fun PendingTransferRequest.resolveConflictStrategies(
        currentConflict: VFile,
        strategy: TransferConflictStrategy,
        applyToAll: Boolean,
    ): Map<String, TransferConflictStrategy> {
        val nextStrategies = resolvedStrategies.toMutableMap()
        nextStrategies[currentConflict.id] = strategy
        if (applyToAll) {
            conflictingEntries.drop(nextConflictIndex + 1).forEach { entry ->
                nextStrategies[entry.id] = strategy
            }
        }
        return nextStrategies
    }

    /**
     * 关闭冲突对话框并使用已选策略启动传输。
     *
     * @param request 原始待处理传输请求。
     * @param strategies 每个冲突条目最终采用的处理策略。
     */
    private fun finishPendingTransfer(
        request: PendingTransferRequest,
        strategies: Map<String, TransferConflictStrategy>,
    ) {
        pendingTransferRequest = null
        dialogState.value = null
        launchTransferTask(
            entries = request.entries,
            targetDirectoryLocation = request.targetDirectoryLocation,
            operation = request.operation,
            clearClipboardOnSuccess = request.clearClipboardOnSuccess,
            conflictStrategies = strategies,
        )
    }

    /**
     * 保存当前冲突进度并展示下一条冲突。
     *
     * @param request 原始待处理传输请求。
     * @param strategies 当前已经确定的条目策略。
     * @param nextConflictIndex 下一条冲突在列表中的索引。
     */
    private fun showNextConflict(
        request: PendingTransferRequest,
        strategies: Map<String, TransferConflictStrategy>,
        nextConflictIndex: Int,
    ) {
        val nextConflict = request.conflictingEntries[nextConflictIndex]
        pendingTransferRequest = request.copy(
            resolvedStrategies = strategies,
            nextConflictIndex = nextConflictIndex,
        )
        dialogState.value = RootDialogState.ConflictResolution(
            sourceName = nextConflict.name,
            targetLocation = request.targetDirectoryLocation,
            operation = request.operation,
            currentIndex = nextConflictIndex + 1,
            total = request.conflictingEntries.size,
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
    @Suppress("TooGenericExceptionCaught") // 传输任务边界统一将 Provider 异常写入任务中心。
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
                canPause = canPauseTransfer(entries, targetDirectoryLocation),
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, targetDirectoryLocation),
                progress = 0f,
                totalCount = entries.size,
                totalBytes = initialTotalBytes,
                startTimeMillis = startTime,
            )
        )
        taskOrchestrator.registerRetryHandler(taskId) {
            launchTransferTask(
                entries = entries,
                targetDirectoryLocation = targetDirectoryLocation,
                operation = operation,
                clearClipboardOnSuccess = clearClipboardOnSuccess,
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
                taskOrchestrator.updateTaskFields(taskId) { task -> task.copy(errors = emptyList()) }
                if (clearClipboardOnSuccess) {
                    clipboardManager.clear()
                }
                if (operation == FileTransferOperation.MOVE && conflictStrategies.isEmpty()) {
                    onMoveSucceeded(entries, targetDirectoryLocation)
                }
                onRefreshAllPanes()
                taskOrchestrator.scheduleAutoCleanup(taskId)
            } catch (failure: CancellationException) {
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(MessageKey.MSG_CANCELLED),
                    progress = null,
                )
                onRefreshAllPanes()
                throw failure
            } catch (failure: Exception) {
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
                onRefreshAllPanes()
            }
        }
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
            bytesPerSecond = progress.bytesPerSecond,
            estimatedRemainingSeconds = progress.estimatedRemainingSeconds,
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
                detail = failure.toTransferFailureMessage(operation),
                progress = null,
                totalCount = entries.size,
                startTimeMillis = System.currentTimeMillis(),
                errors = failure.toTaskErrors(entries, targetDirectoryLocation),
            )
        )
        taskOrchestrator.registerRetryHandler(taskId) {
            requestTransferEntriesToDirectory(
                entries = entries,
                targetDirectoryLocation = targetDirectoryLocation,
                operation = operation,
                clearClipboardOnSuccess = false,
            )
        }
    }

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

    private fun Throwable.toTransferFailureMessage(operation: FileTransferOperation): I18nMessage {
        val providerError = (this as? VfsProviderException)?.error
        return when (providerError) {
            is VfsProviderError.CrossProviderTransferUnsupported -> providerError.toI18nMessage()
            null -> message?.takeIf { it.isNotBlank() }?.let { detail ->
                I18nMessage(MessageKey.MSG_STRING_LITERAL, detail)
            } ?: failureMessageFor(operation)
            else -> providerError.toI18nMessage()
        }
    }

    private fun Throwable.toTaskErrors(
        entries: List<VFile>,
        targetDirectoryLocation: String,
    ): List<TaskError> {
        val message = message?.takeIf { it.isNotBlank() } ?: this::class.simpleName.orEmpty()
        if (entries.isEmpty()) {
            return listOf(TaskError(fileName = targetDirectoryLocation, message = message))
        }
        return entries.map { entry ->
            TaskError(
                fileName = entry.name,
                message = message,
            )
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
