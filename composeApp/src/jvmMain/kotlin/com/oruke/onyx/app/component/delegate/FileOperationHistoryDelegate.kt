package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.TrashMoveRecord
import com.oruke.onyx.vfs.api.TrashRestorationStatus
import com.oruke.onyx.vfs.api.TrashService
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.app.filesystem.FileHashService
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 文件操作历史委托，负责记录可逆文件操作并执行撤销 / 重做。
 *
 * 记录重命名、无冲突复制与移动、以及带恢复记录的回收站删除。
 *
 */
internal class FileOperationHistoryDelegate(
    /** 统一文件变更服务。 */
    private val fileCommandService: FileCommandService,
    /** 撤销前重新定位实际文件的查询服务。 */
    private val fileRepository: FileRepository,
    /** 可恢复删除记录的回收站服务。 */
    private val trashService: TrashService? = null,
    /** 校验副本未被用户修改的内容摘要服务。 */
    private val hashService: FileHashService? = null,
) {
    /** 对外暴露撤销、重做按钮的可用状态。 */
    private val _state = MutableStateFlow(FileOperationHistoryState())

    /** 组件和界面共同消费的历史状态。 */
    val state: StateFlow<FileOperationHistoryState> = _state.asStateFlow()

    /** 按完成顺序排列的可撤销记录。 */
    private val undoStack = mutableListOf<FileOperationRecord>()

    /** 已撤销、可恢复的记录。 */
    private val redoStack = mutableListOf<FileOperationRecord>()

    /** 防止连续快捷键并行执行同一个文件操作；状态写入均由组件主线程执行。 */
    private var operationInProgress = false

    /** 挂起期间出现其它文件操作时，不再恢复可能过时的历史栈。 */
    private var revision = 0L

    /**
     * 记录单个重命名操作。
     *
     * @param entry 重命名前的文件条目。
     * @param targetName 重命名后的名称。
     */
    fun recordRename(
        entry: VFile,
        targetName: String,
    ) {
        val step = FileRenameStep.from(entry, targetName) ?: return
        record(FileOperationRecord.RenameBatch(listOf(step)))
    }

    /**
     * 记录批量重命名操作。
     *
     * @param renameMap 重命名前文件条目到目标名称的映射。
     */
    fun recordBatchRename(renameMap: List<Pair<VFile, String>>) {
        val steps = renameMap.mapNotNull { (entry, targetName) -> FileRenameStep.from(entry, targetName) }
        if (steps.isEmpty()) return
        record(FileOperationRecord.RenameBatch(steps))
    }

    /**
     * 记录无冲突移动操作。
     *
     * @param entries 移动前的文件条目。
     * @param targetDirectoryLocation 目标目录位置。
     */
    fun recordMove(
        entries: List<VFile>,
        targetDirectoryLocation: String,
    ) {
        val steps = entries.mapNotNull { entry -> FileMoveStep.from(entry, targetDirectoryLocation) }
        if (steps.isEmpty()) return
        record(FileOperationRecord.MoveBatch(steps))
    }

    /**
     * 记录无冲突复制；捕获失败时清空旧历史，避免撤销误作用于更早操作。
     *
     * @param entries 原始源条目。
     * @param targetDirectoryLocation 复制目标目录。
     * @return 目标快照登记结果。
     */
    suspend fun recordCopy(entries: List<VFile>, targetDirectoryLocation: String): Result<Unit> {
        if (operationInProgress) {
            clear()
            return Result.failure(LocalizedException(I18nMessage(MessageKey.MSG_COPY_HISTORY_UNAVAILABLE)))
        }
        operationInProgress = true
        val startingRevision = revision
        updateState()
        return try {
            runCatching {
                val copyRecord = CopiedFilesHistoryRecord.capture(
                    entries,
                    targetDirectoryLocation,
                    fileRepository,
                    fileCommandService,
                    hashService
                )
                if (revision != startingRevision) {
                    throw LocalizedException(I18nMessage(MessageKey.MSG_COPY_HISTORY_UNAVAILABLE))
                }
                record(FileOperationRecord.CopyBatch(copyRecord))
            }.onFailure { failure ->
                clear()
                // 快照仍属于传输任务；取消必须交还任务执行器，不能被当成可忽略的历史错误。
                if (failure is CancellationException) throw failure
            }
        } finally {
            operationInProgress = false
            updateState()
        }
    }

    /** 不可逆或部分完成操作后清空旧历史，禁止 Ctrl+Z 静默跳过最近一次操作。 */
    fun clear() {
        revision++
        undoStack.clear()
        redoStack.clear()
        updateState()
    }

    /**
     * 记录移入回收站操作。
     *
     * @param records 回收站服务返回的恢复记录。
     */
    fun recordTrashDelete(records: List<TrashMoveRecord>) {
        val restorableRecords = records.filter { record ->
            record.restorationStatus == TrashRestorationStatus.AVAILABLE && record.trashedLocation.isNotBlank()
        }
        if (restorableRecords.isEmpty() || trashService == null) return
        record(FileOperationRecord.TrashBatch(restorableRecords))
    }

    /**
     * 撤销最近一次可逆文件操作。
     *
     * @return 撤销执行结果。
     */
    suspend fun undoLast(): Result<Unit> {
        if (operationInProgress) return Result.success(Unit)
        val record = undoStack.removeLastOrNull() ?: return Result.success(Unit)
        operationInProgress = true
        val startingRevision = revision
        updateState()
        return try {
            runCatching {
                record.undo()
                if (revision == startingRevision) redoStack += record else clear()
            }.onFailure { failure ->
                if (failure is CancellationException) {
                    clear()
                    throw failure
                }
                if (revision == startingRevision) undoStack += record else clear()
            }
        } finally {
            operationInProgress = false
            updateState()
        }
    }

    /**
     * 重做最近一次已撤销的文件操作。
     *
     * @return 重做执行结果。
     */
    suspend fun redoLast(): Result<Unit> {
        if (operationInProgress) return Result.success(Unit)
        val record = redoStack.removeLastOrNull() ?: return Result.success(Unit)
        operationInProgress = true
        val startingRevision = revision
        updateState()
        return try {
            runCatching {
                record.redo()
                if (revision == startingRevision) undoStack += record else clear()
            }.onFailure { failure ->
                if (failure is CancellationException) {
                    clear()
                    throw failure
                }
                if (revision == startingRevision) redoStack += record else clear()
            }
        } finally {
            operationInProgress = false
            updateState()
        }
    }

    /**
     * 将操作压入撤销栈。
     *
     * @param record 可逆文件操作记录。
     */
    private fun record(record: FileOperationRecord) {
        revision++
        undoStack += record
        if (undoStack.size > MAX_HISTORY_SIZE) {
            undoStack.removeAt(0)
        }
        redoStack.clear()
        updateState()
    }

    /**
     * 同步对外暴露的操作历史状态。
     */
    private fun updateState() {
        _state.value = FileOperationHistoryState(
            canUndo = !operationInProgress && undoStack.isNotEmpty(),
            canRedo = !operationInProgress && redoStack.isNotEmpty(),
        )
    }

    /**
     * 执行文件操作记录的撤销逻辑。
     */
    private suspend fun FileOperationRecord.undo() {
        when (this) {
            is FileOperationRecord.CopyBatch -> copyRecord.undo()
            is FileOperationRecord.RenameBatch -> steps.asReversed().forEach { step ->
                rename(parentLocation = step.parentLocation, currentName = step.newName, targetName = step.oldName)
            }

            is FileOperationRecord.MoveBatch -> steps.asReversed().forEach { step ->
                move(
                    name = step.name,
                    sourceDirectory = step.targetDirectoryLocation,
                    targetDirectory = step.sourceParentLocation,
                )
            }

            is FileOperationRecord.TrashBatch -> {
                requireNotNull(trashService).restoreFromTrash(records).getOrThrow()
            }
        }
    }

    /**
     * 执行文件操作记录的重做逻辑。
     */
    private suspend fun FileOperationRecord.redo() {
        when (this) {
            is FileOperationRecord.CopyBatch -> copyRecord.redo()
            is FileOperationRecord.RenameBatch -> steps.forEach { step ->
                rename(parentLocation = step.parentLocation, currentName = step.oldName, targetName = step.newName)
            }

            is FileOperationRecord.MoveBatch -> steps.forEach { step ->
                move(
                    name = step.name,
                    sourceDirectory = step.sourceParentLocation,
                    targetDirectory = step.targetDirectoryLocation,
                )
            }

            is FileOperationRecord.TrashBatch -> {
                records = requireNotNull(trashService)
                    .moveToTrash(records.map { record -> record.originalEntry })
                    .getOrThrow()
            }
        }
    }

    /**
     * 重命名当前目录中的指定条目。
     *
     * @param parentLocation 父目录位置。
     * @param currentName 当前名称。
     * @param targetName 目标名称。
     */
    private suspend fun rename(
        parentLocation: String,
        currentName: String,
        targetName: String,
    ) {
        val entry = findEntry(parentLocation, currentName)
        fileCommandService.rename(entry, targetName).getOrThrow()
    }

    /**
     * 移动当前目录中的指定条目。
     *
     * @param name 条目名称。
     * @param sourceDirectory 当前所在目录。
     * @param targetDirectory 目标目录。
     */
    private suspend fun move(
        name: String,
        sourceDirectory: String,
        targetDirectory: String,
    ) {
        ensureNameAvailable(targetDirectory, name)
        val entry = findEntry(sourceDirectory, name)
        fileCommandService.move(
            entries = listOf(entry),
            targetDirectoryLocation = targetDirectory,
            conflictStrategy = TransferConflictStrategy.KEEP_BOTH,
        ).getOrThrow()
    }

    /**
     * 从目录中按名称查找当前文件条目。
     *
     * @param parentLocation 父目录位置。
     * @param name 文件名。
     * @return 找到的当前文件条目。
     */
    private suspend fun findEntry(
        parentLocation: String,
        name: String,
    ): VFile {
        return fileRepository.list(parentLocation)
            .getOrThrow()
            .firstOrNull { entry -> entry.name == name }
            ?: error("Entry is no longer available: $parentLocation/$name")
    }

    /**
     * 确认目标目录没有同名条目，避免撤销/重做产生 keep-both 副本。
     *
     * @param directoryLocation 目标目录位置。
     * @param name 文件名。
     */
    private suspend fun ensureNameAvailable(
        directoryLocation: String,
        name: String,
    ) {
        val exists = fileRepository.list(directoryLocation)
            .getOrThrow()
            .any { entry -> entry.name == name }
        check(!exists) {
            "Target already contains an entry named $name"
        }
    }

    private sealed interface FileOperationRecord {
        /** 已登记完整副本快照的复制操作。 */
        data class CopyBatch(
            /** 负责内容校验和副本恢复的记录。 */
            val copyRecord: CopiedFilesHistoryRecord,
        ) : FileOperationRecord

        data class RenameBatch(
            val steps: List<FileRenameStep>,
        ) : FileOperationRecord

        data class MoveBatch(
            val steps: List<FileMoveStep>,
        ) : FileOperationRecord

        data class TrashBatch(
            var records: List<TrashMoveRecord>,
        ) : FileOperationRecord
    }

    private data class FileRenameStep(
        val parentLocation: String,
        val oldName: String,
        val newName: String,
    ) {
        companion object {
            /**
             * 从文件条目创建重命名步骤。
             *
             * @param entry 重命名前条目。
             * @param targetName 目标名称。
             * @return 可记录的重命名步骤；父目录缺失或名称未变化时返回 `null`。
             */
            fun from(
                entry: VFile,
                targetName: String,
            ): FileRenameStep? {
                val parentLocation = entry.parentLocation
                val normalizedTargetName = targetName.trim()
                val shouldSkip = parentLocation == null ||
                        normalizedTargetName.isBlank() ||
                        normalizedTargetName == entry.name
                return if (shouldSkip) {
                    null
                } else {
                    FileRenameStep(
                        parentLocation = parentLocation,
                        oldName = entry.name,
                        newName = normalizedTargetName,
                    )
                }
            }
        }
    }

    private data class FileMoveStep(
        val sourceParentLocation: String,
        val targetDirectoryLocation: String,
        val name: String,
    ) {
        companion object {
            /**
             * 从文件条目创建移动步骤。
             *
             * @param entry 移动前条目。
             * @param targetDirectoryLocation 目标目录位置。
             * @return 可记录的移动步骤；父目录缺失或原地移动时返回 `null`。
             */
            fun from(
                entry: VFile,
                targetDirectoryLocation: String,
            ): FileMoveStep? {
                val sourceParentLocation = entry.parentLocation
                return if (sourceParentLocation == null || sourceParentLocation == targetDirectoryLocation) {
                    null
                } else {
                    FileMoveStep(
                        sourceParentLocation = sourceParentLocation,
                        targetDirectoryLocation = targetDirectoryLocation,
                        name = entry.name,
                    )
                }
            }
        }
    }

    private companion object {
        const val MAX_HISTORY_SIZE = 100
    }
}

/**
 * 文件操作历史可用状态。
 *
 * @property canUndo 当前是否存在可撤销操作。
 * @property canRedo 当前是否存在可重做操作。
 */
data class FileOperationHistoryState(
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)
