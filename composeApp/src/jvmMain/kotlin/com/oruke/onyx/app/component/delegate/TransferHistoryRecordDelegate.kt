package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.TransferConflictStrategy

/**
 * 在传输提交后决定是否可登记反向操作，避免复杂历史逻辑进入任务执行器。
 * @param onMove 无冲突移动登记。
 * @param onCopy 无冲突复制快照登记。
 * @param onInvalidated 部分、冲突或不可逆操作使旧历史失效。
 */
internal class TransferHistoryRecordDelegate(
    /** 登记可逆的无冲突移动。 */
    private val onMove: (List<VFile>, String) -> Unit,
    /** 校验并登记完整副本快照。 */
    private val onCopy: suspend (List<VFile>, String) -> Unit,
    /** 清除不可逆操作之前的旧历史。 */
    private val onInvalidated: () -> Unit,
) {
    /**
     * 仅为无冲突、名称确定的成功传输登记历史。
     * @param operation 已完成的传输类型。
     * @param entries 原始源条目。
     * @param target 目标目录。
     * @param strategies 已使用的逐项冲突策略。
     */
    suspend fun record(
        operation: FileTransferOperation,
        entries: List<VFile>,
        target: String,
        strategies: Map<String, TransferConflictStrategy>
    ) {
        when {
            strategies.isNotEmpty() -> onInvalidated()
            operation == FileTransferOperation.MOVE -> onMove(entries, target)
            operation == FileTransferOperation.COPY -> onCopy(entries, target)
            else -> onInvalidated()
        }
    }
}
