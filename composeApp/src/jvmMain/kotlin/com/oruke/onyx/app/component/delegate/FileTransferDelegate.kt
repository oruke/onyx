package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.component.FileTransferOperation
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.filesystem.FileCommandService
import com.oruke.onyx.app.filesystem.FileRepository
import com.oruke.onyx.app.filesystem.TransferConflictStrategy
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.msg_cancelled
import onyx.composeapp.generated.resources.msg_copied_items
import onyx.composeapp.generated.resources.msg_copy_failed
import onyx.composeapp.generated.resources.msg_copy_items
import onyx.composeapp.generated.resources.msg_extract_failed
import onyx.composeapp.generated.resources.msg_extract_items
import onyx.composeapp.generated.resources.msg_extracted_items
import onyx.composeapp.generated.resources.msg_move_failed
import onyx.composeapp.generated.resources.msg_move_items
import onyx.composeapp.generated.resources.msg_moved_items
import onyx.composeapp.generated.resources.msg_string_literal
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
    private val onRefreshAllPanes: () -> Unit,
) {
    var pendingTransferRequest: PendingTransferRequest? = null
        private set

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
        if (entries.any { entry -> targetDirectoryLocation.isSameOrChildOf(entry.location) }) {
            return
        }
        scope.launch {
            val conflictingEntries = detectConflictingEntries(
                entries = entries,
                targetDirectoryLocation = targetDirectoryLocation,
            )
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
        val taskId = UUID.randomUUID().toString()
        val taskKind = when (operation) {
            FileTransferOperation.COPY -> BackgroundTaskKind.COPY
            FileTransferOperation.MOVE -> BackgroundTaskKind.MOVE
            FileTransferOperation.EXTRACT -> BackgroundTaskKind.EXTRACT
        }
        val pauseFlag = taskOrchestrator.getOrCreatePauseFlag(taskId)
        val startTime = System.currentTimeMillis()
        // 预计算总字节（目录递归统计由 sizeBytes 提供，若为 0 则回退到文件计数进度）
        val totalBytes = entries.sumOf { it.sizeBytes ?: 0L }

        taskOrchestrator.appendTask(
            BackgroundTask(
                id = taskId,
                kind = taskKind,
                title = when (operation) {
                    FileTransferOperation.COPY -> I18nMessage(Res.string.msg_copy_items, entries.size)
                    FileTransferOperation.MOVE -> I18nMessage(Res.string.msg_move_items, entries.size)
                    FileTransferOperation.EXTRACT -> I18nMessage(Res.string.msg_extract_items, entries.size)
                },
                status = BackgroundTaskStatus.QUEUED,
                detail = I18nMessage(Res.string.msg_string_literal, targetDirectoryLocation),
                progress = 0f,
                totalCount = entries.size,
                totalBytes = totalBytes,
                startTimeMillis = startTime,
            )
        )

        val job = scope.launch {
            try {
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(
                        Res.string.msg_string_literal,
                        buildTransferTaskDetail(
                            entries = entries,
                            targetLocation = targetDirectoryLocation,
                        )
                    ),
                    progress = 0f,
                )

                var accumulatedBytes = 0L
                entries.forEachIndexed { index, entry ->
                    ensureActive()
                    // 文件级暂停检测
                    while (pauseFlag.value) {
                        ensureActive()
                        delay(200)
                    }
                    taskOrchestrator.updateTaskFields(taskId) { task ->
                        task.copy(currentFileName = entry.name)
                    }
                    val conflictStrategy = conflictStrategies[entry.id] ?: TransferConflictStrategy.KEEP_BOTH
                    val result = when (operation) {
                        FileTransferOperation.COPY, FileTransferOperation.EXTRACT -> {
                            fileCommandService.copy(
                                entries = listOf(entry),
                                targetDirectoryLocation = targetDirectoryLocation,
                                conflictStrategy = conflictStrategy,
                            )
                        }

                        FileTransferOperation.MOVE -> {
                            fileCommandService.move(
                                entries = listOf(entry),
                                targetDirectoryLocation = targetDirectoryLocation,
                                conflictStrategy = conflictStrategy,
                            )
                        }
                    }
                    result.getOrThrow()
                    accumulatedBytes += entry.sizeBytes ?: 0L
                    val byteProgress = if (totalBytes > 0L) {
                        accumulatedBytes.toFloat() / totalBytes
                    } else {
                        (index + 1).toFloat() / entries.size
                    }
                    taskOrchestrator.updateTask(
                        taskId = taskId,
                        status = BackgroundTaskStatus.RUNNING,
                        detail = I18nMessage(
                            Res.string.msg_string_literal,
                            "${entry.name} → $targetDirectoryLocation"
                        ),
                        progress = byteProgress,
                        processedCount = index + 1,
                        processedBytes = accumulatedBytes,
                    )
                }

                taskOrchestrator.dismissTask(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = when (operation) {
                        FileTransferOperation.COPY ->
                            I18nMessage(Res.string.msg_copied_items, entries.size, targetDirectoryLocation)

                        FileTransferOperation.MOVE ->
                            I18nMessage(Res.string.msg_moved_items, entries.size, targetDirectoryLocation)

                        FileTransferOperation.EXTRACT ->
                            I18nMessage(Res.string.msg_extracted_items, entries.size, targetDirectoryLocation)
                    },
                    progress = 1f,
                    processedCount = entries.size,
                    processedBytes = totalBytes,
                )
                if (clearClipboardOnSuccess) {
                    clipboardManager.clear()
                }
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
                OnyxLogger.error("FileTransferDelegate", "文件传输失败 (${operation.name})", failure)
                taskOrchestrator.dismissTask(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = failure.message?.let { I18nMessage(Res.string.msg_string_literal, it) }
                        ?: when (operation) {
                            FileTransferOperation.COPY -> I18nMessage(Res.string.msg_copy_failed)
                            FileTransferOperation.MOVE -> I18nMessage(Res.string.msg_move_failed)
                            FileTransferOperation.EXTRACT -> I18nMessage(Res.string.msg_extract_failed)
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
            .getOrDefault(emptyList())
            .mapTo(mutableSetOf()) { entry -> entry.name }
        return entries.filter { entry -> existingNames.contains(entry.name) }
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

internal fun buildTransferTaskDetail(
    entries: List<VFile>,
    targetLocation: String,
): String {
    return "${buildTaskDetail(entries)} -> $targetLocation"
}

internal fun buildTaskDetail(entries: List<VFile>): String {
    val preview = entries.take(3).joinToString { it.name }
    return if (entries.size <= 3) preview else "$preview ..."
}

private fun String.isSameOrChildOf(parentLocation: String): Boolean {
    val target = java.nio.file.Path.of(this).normalize().toAbsolutePath()
    val parent = java.nio.file.Path.of(parentLocation).normalize().toAbsolutePath()
    return target == parent || target.startsWith(parent)
}
