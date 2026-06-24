package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneRoleState
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.TrashMoveRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 文件操作历史外观 — 封装撤销/重做、传输源到目标、以及操作记录的写入逻辑。
 *
 * 从 DefaultRootComponent 剥离的纯业务逻辑，不持有 UI 状态。
 */
internal class RootFileOperationHistoryFacade(
    private val scope: CoroutineScope,
    private val delegate: FileOperationHistoryDelegate,
    private val refreshAllPanes: () -> Unit,
    private val showOperationFailure: (Throwable) -> Unit,
    private val activePane: () -> PaneId,
    private val paneState: (PaneId) -> PaneState,
    private val requestTransferSelectedToDirectory: (PaneId, String, FileTransferOperation) -> Unit,
) {

    /**
     * 撤销最近一次可逆文件操作。
     *
     * @return 无直接返回值；失败信息会投递到当前活动面板。
     */
    fun undoLastFileOperation() {
        scope.launch {
            delegate.undoLast()
                .onSuccess {
                    refreshAllPanes()
                }
                .onFailure { failure ->
                    showOperationFailure(failure)
                }
        }
    }

    /**
     * 重做最近一次已撤销的文件操作。
     *
     * @return 无直接返回值；失败信息会投递到当前活动面板。
     */
    fun redoLastFileOperation() {
        scope.launch {
            delegate.redoLast()
                .onSuccess {
                    refreshAllPanes()
                }
                .onFailure { failure ->
                    showOperationFailure(failure)
                }
        }
    }

    /**
     * 按 Source / Destination 角色把源面板选中项传输到目标面板目录。
     *
     * @param operation 传输操作类型。
     * @return 无返回值。
     */
    fun requestTransferSourceToDestination(operation: FileTransferOperation) {
        val roles = PaneRoleState.fromSource(activePane())
        requestTransferSelectedToDirectory(
            roles.sourcePaneId,
            paneState(roles.destinationPaneId).location,
            operation,
        )
    }

    /**
     * 记录内联重命名产生的可撤销操作。
     *
     * @param source 重命名前的文件条目。
     * @param renamed 重命名后的文件条目。
     * @return 无返回值。
     */
    fun recordRenameOperation(source: VFile, renamed: VFile) {
        delegate.recordRename(source, renamed.name)
    }

    /**
     * 记录批量重命名产生的可撤销操作。
     *
     * @param renameMap 重命名前条目到目标名称的映射。
     * @return 无返回值。
     */
    fun recordBatchRenameOperation(renameMap: List<Pair<VFile, String>>) {
        delegate.recordBatchRename(renameMap)
    }

    /**
     * 记录无冲突移动产生的可撤销操作。
     *
     * @param entries 移动前的文件条目。
     * @param targetDirectoryLocation 移动目标目录。
     * @return 无返回值。
     */
    fun recordMoveOperation(
        entries: List<VFile>,
        targetDirectoryLocation: String,
    ) {
        delegate.recordMove(entries, targetDirectoryLocation)
    }

    /**
     * 记录移入回收站产生的可撤销操作。
     *
     * @param records 回收站服务返回的恢复记录。
     * @return 无返回值。
     */
    fun recordTrashDeleteOperation(records: List<TrashMoveRecord>) {
        delegate.recordTrashDelete(records)
    }
}
