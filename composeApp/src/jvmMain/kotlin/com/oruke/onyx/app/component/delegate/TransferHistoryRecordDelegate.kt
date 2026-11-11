package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.shared.usecase.TransferDirectoryStep
import com.oruke.onyx.vfs.api.TransferConflictStrategy

/**
 * 已完成传输的可逆历史登记上下文。
 *
 * @property entries 原始源条目。
 * @property targetDirectoryLocation 根目标目录。
 * @property entryTargetDirectories 按条目 ID 指定的实际落盘父目录。
 * @property directorySteps 保留相对目录结构所需的中间目录创建步骤。
 * @property createdDirectories 本次传输实际新建的中间目录。
 */
internal data class CompletedTransferHistory(
    val entries: List<VFile>,
    val targetDirectoryLocation: String,
    val entryTargetDirectories: Map<String, String> = emptyMap(),
    val directorySteps: List<TransferDirectoryStep> = emptyList(),
    val createdDirectories: List<VFile> = emptyList(),
)

/**
 * 在传输提交后决定是否可登记反向操作，避免复杂历史逻辑进入任务执行器。
 * @param onMove 无冲突移动登记。
 * @param onCopy 无冲突复制快照登记。
 * @param onInvalidated 部分、冲突或不可逆操作使旧历史失效。
 */
internal class TransferHistoryRecordDelegate(
    /** 登记可逆的无冲突移动。 */
    private val onMove: (CompletedTransferHistory) -> Unit,
    /** 校验并登记完整副本快照。 */
    private val onCopy: suspend (CompletedTransferHistory) -> Unit,
    /** 清除不可逆操作之前的旧历史。 */
    private val onInvalidated: () -> Unit,
) {
    /**
     * 仅为无冲突、名称确定的成功传输登记历史。
     * @param operation 已完成的传输类型。
     * @param history 本次已完成传输的结构化历史信息。
     * @param strategies 已使用的逐项冲突策略。
     */
    suspend fun record(
        operation: FileTransferOperation,
        history: CompletedTransferHistory,
        strategies: Map<String, TransferConflictStrategy>,
    ) {
        when {
            strategies.isNotEmpty() -> onInvalidated()
            operation == FileTransferOperation.MOVE -> onMove(history)
            operation == FileTransferOperation.COPY -> onCopy(history)
            else -> onInvalidated()
        }
    }
}
