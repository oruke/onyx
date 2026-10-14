package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.component.PaneRemovalContext
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.filesystem.VfsPathService
import com.oruke.onyx.core.model.DirectoryChange
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** 处理传输请求预检和冲突决策，通过独立任务委托执行已确定的文件操作。 */
internal class FileTransferDelegate(
    /** 窗口请求预检作用域。 */
    private val scope: CoroutineScope,
    /** 执行真实文件操作的统一命令能力。 */
    fileCommandService: FileCommandService,
    /** 冲突预检使用的统一目录仓储。 */
    private val fileRepository: FileRepository,
    /** 应用级后台任务队列。 */
    taskOrchestrator: TaskOrchestrator,
    /** 捕获原始载荷及完成后条件清理的剪贴板。 */
    private val clipboardManager: ClipboardManager,
    /** 窗口冲突对话框状态。 */
    private val dialogState: MutableStateFlow<RootDialogState?>,
    /** 传输路径关系判断能力。 */
    private val pathService: VfsPathService,
    /** 协议能力注册表。 */
    providerRegistry: VfsProviderRegistry,
    /** 已确认的目录增删结果。 */
    onDirectoryChange: (DirectoryChange) -> Unit,
    /** 不确定或部分失败目录的校验入口。 */
    onRefreshDirectories: (Set<String>) -> Unit = {},
    /** 只捕获本窗口匹配的发起访问。 */
    onRemovalStarted: (List<VFile>) -> PaneRemovalContext? = { null },
    /** 命令最终完成后的选择续接。 */
    onRemovalFinished: (PaneRemovalContext?, Set<String>, Boolean) -> Unit = { _, _, _ -> },
    /** 无冲突移动的历史登记入口。 */
    onMoveSucceeded: (List<VFile>, String) -> Unit = { _, _ -> },
    /** 无冲突复制完成后同步登记历史快照。 */
    onCopySucceeded: suspend (List<VFile>, String) -> Unit = { _, _ -> },
    /** 冲突或部分完成导致无法可靠反向执行时，停止暴露旧历史。 */
    onHistoryInvalidated: () -> Unit = {},
) {
    /** 冲突待决期间的原始请求；不会被当前新选择或剪贴板覆盖。 */
    var pendingTransferRequest: PendingTransferRequest? = null
        private set
    /** 文件任务、真实结果及完成状态的独立编排入口。 */
    private val transferTasks = FileTransferTaskDelegate(
        fileCommandService, taskOrchestrator, clipboardManager, providerRegistry, onDirectoryChange,
        onRefreshDirectories, onRemovalStarted, onRemovalFinished, onMoveSucceeded, onCopySucceeded,
        onHistoryInvalidated,
    )

    /**
     * 请求文件传输 — 检测冲突后决定直接执行或弹出冲突对话框。
     * @param entries 本次明确请求传输的条目。
     * @param targetDirectoryLocation 用户发起操作时的目标目录。
     * @param operation 复制、移动或解压操作。
     * @param clearClipboardOnSuccess 是否在成功后清理本次读取的剪切载荷。
     * @return 无返回值，冲突与失败通过对话框或任务反馈。
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
        val clipboardToClear = clipboardManager.clipboard.value.takeIf { clearClipboardOnSuccess }
        scope.launch {
            val conflictingEntries = try {
                detectConflictingEntries(
                    entries = entries,
                    targetDirectoryLocation = targetDirectoryLocation,
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                OnyxLogger.error("FileTransferDelegate", "冲突检测失败 (${operation.name})", failure)
                transferTasks.recordPreflightFailure(
                    entries = entries,
                    targetDirectoryLocation = targetDirectoryLocation,
                    operation = operation,
                    failure = failure,
                    retry = { requestTransferEntriesToDirectory(entries, targetDirectoryLocation, operation, false) },
                )
                return@launch
            }
            if (conflictingEntries.isNotEmpty()) {
                pendingTransferRequest = PendingTransferRequest(
                    entries = entries,
                    targetDirectoryLocation = targetDirectoryLocation,
                    operation = operation,
                    clipboardToClear = clipboardToClear,
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
            transferTasks.launch(
                entries = entries,
                targetDirectoryLocation = targetDirectoryLocation,
                operation = operation,
                clipboardToClear = clipboardToClear,
                conflictStrategies = emptyMap(),
            )
        }
    }

    /**
     * 解决单个冲突条目，或应用到所有后续冲突。
     * @param strategy 当前选择的冲突策略。
     * @param applyToAll 是否应用到后续全部冲突。
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
        transferTasks.launch(
            entries = request.entries,
            targetDirectoryLocation = request.targetDirectoryLocation,
            operation = request.operation,
            clipboardToClear = request.clipboardToClear,
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
     * 检测传输目标目录中已存在的同名条目。
     * @param entries 原始传输条目。
     * @param targetDirectoryLocation 目标目录。
     * @return 目标目录已包含同名内容的源条目。
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

    /** 冲突待决期间保留的原始传输请求，剪贴板载荷不得随新选择改变。 */
    data class PendingTransferRequest(
        /** 本次需要处理的条目。 */
        val entries: List<VFile>,
        /** 原始目标目录。 */
        val targetDirectoryLocation: String,
        /** 原始业务操作。 */
        val operation: FileTransferOperation,
        /** 完成后允许清理的原始载荷，新复制的内容不受影响。 */
        val clipboardToClear: ClipboardManager.ClipboardPayload?,
        /** 需要用户决策的条目。 */
        val conflictingEntries: List<VFile>,
        /** 用户已经确定的冲突策略。 */
        val resolvedStrategies: Map<String, TransferConflictStrategy>,
        /** 下一个需要决策的冲突索引。 */
        val nextConflictIndex: Int,
    )
}
