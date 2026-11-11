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
    onMoveSucceeded: (CompletedTransferHistory) -> Unit = {},
    /** 无冲突复制完成后同步登记历史快照。 */
    onCopySucceeded: suspend (CompletedTransferHistory) -> Unit = {},
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
     * 请求文件传输 — 规划相对目录结构并检测冲突后决定直接执行或弹出冲突对话框。
     * @param entries 本次明确请求传输的条目。
     * @param targetDirectoryLocation 用户发起操作时的目标目录。
     * @param operation 复制、移动或解压操作。
     * @param clearClipboardOnSuccess 是否在成功后清理本次读取的剪切载荷。
     * @param sourceBaseLocation 来源面板基准目录（用于保留内联展开子项的父目录结构）。
     * @return 无返回值，冲突与失败通过对话框或任务反馈。
     */
    @Suppress("TooGenericExceptionCaught") // 冲突预检边界统一将 Provider 异常转换为失败任务。
    fun requestTransferEntriesToDirectory(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        operation: FileTransferOperation,
        clearClipboardOnSuccess: Boolean,
        sourceBaseLocation: String? = null,
    ) {
        if (entries.isEmpty()) return
        val structure = TransferStructurePlanner.plan(entries, targetDirectoryLocation, sourceBaseLocation, pathService)
        if (isNoOpMove(structure, operation) || hasSelfOrDescendantTarget(structure)) {
            return
        }
        val clipboardToClear = clipboardManager.clipboard.value.takeIf { clearClipboardOnSuccess }
        scope.launch {
            val conflictingEntries = try {
                TransferStructurePlanner.detectConflictingEntries(structure, fileRepository)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                OnyxLogger.error("FileTransferDelegate", "冲突检测失败 (${operation.name})", failure)
                transferTasks.recordPreflightFailure(
                    entries = entries,
                    targetDirectoryLocation = targetDirectoryLocation,
                    operation = operation,
                    failure = failure,
                    retry = {
                        requestTransferEntriesToDirectory(
                            entries = entries,
                            targetDirectoryLocation = targetDirectoryLocation,
                            operation = operation,
                            clearClipboardOnSuccess = false,
                            sourceBaseLocation = sourceBaseLocation,
                        )
                    },
                )
                return@launch
            }
            if (conflictingEntries.isNotEmpty()) {
                val firstConflict = conflictingEntries.first()
                pendingTransferRequest = PendingTransferRequest(
                    structure = structure,
                    operation = operation,
                    clipboardToClear = clipboardToClear,
                    conflictingEntries = conflictingEntries,
                    resolvedStrategies = emptyMap(),
                    nextConflictIndex = 0,
                )
                dialogState.value = RootDialogState.ConflictResolution(
                    sourceName = firstConflict.name,
                    targetLocation = structure.effectiveTargetOf(firstConflict),
                    operation = operation,
                    currentIndex = 1,
                    total = conflictingEntries.size,
                )
                return@launch
            }
            transferTasks.launch(
                structure = structure,
                operation = operation,
                clipboardToClear = clipboardToClear,
                conflictStrategies = emptyMap(),
            )
        }
    }

    private fun isNoOpMove(structure: PlannedTransferStructure, operation: FileTransferOperation): Boolean {
        if (operation != FileTransferOperation.MOVE) return false
        return structure.entries.all { entry ->
            val parent = entry.parentLocation ?: return@all false
            pathService.normalizeLocation(parent) == pathService.normalizeLocation(structure.effectiveTargetOf(entry))
        }
    }

    private fun hasSelfOrDescendantTarget(structure: PlannedTransferStructure): Boolean {
        return structure.entries.any { entry ->
            pathService.isSameOrChildOf(structure.targetDirectoryLocation, entry.location) ||
                pathService.isSameOrChildOf(structure.effectiveTargetOf(entry), entry.location)
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

    private fun finishPendingTransfer(
        request: PendingTransferRequest,
        strategies: Map<String, TransferConflictStrategy>,
    ) {
        pendingTransferRequest = null
        dialogState.value = null
        transferTasks.launch(
            structure = request.structure,
            operation = request.operation,
            clipboardToClear = request.clipboardToClear,
            conflictStrategies = strategies,
        )
    }

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
            targetLocation = request.structure.effectiveTargetOf(nextConflict),
            operation = request.operation,
            currentIndex = nextConflictIndex + 1,
            total = request.conflictingEntries.size,
        )
    }

    /** 清除待处理的传输请求（用于对话框取消时）。 */
    fun clearPending() {
        pendingTransferRequest = null
    }

    /** 冲突待决期间保留的原始传输请求，剪贴板载荷不得随新选择改变。 */
    data class PendingTransferRequest(
        /** 本次结构化传输规划（含待处理条目与目标目录映射）。 */
        val structure: PlannedTransferStructure,
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
    ) {
        /** 本次需要处理的条目列表。 */
        val entries: List<VFile> get() = structure.entries

        /** 原始根目标目录。 */
        val targetDirectoryLocation: String get() = structure.targetDirectoryLocation
    }
}
