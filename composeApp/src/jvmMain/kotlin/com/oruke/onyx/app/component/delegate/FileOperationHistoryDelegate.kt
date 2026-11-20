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
import com.oruke.onyx.core.model.TRASH_LOCATION
import com.oruke.onyx.shared.usecase.TransferDirectoryStep
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
     * 获取即将执行的历史操作触及目录，失败也必须校验可能已经提交的部分。
     * @param redo 是否读取重做栈，false 表示撤销栈。
     * @return 待校验的目录集合；没有可执行记录时为空。
     */
    fun affectedDirectories(redo: Boolean): Set<String> {
        return when (val record = (if (redo) redoStack else undoStack).lastOrNull()) {
            is FileOperationRecord.CopyBatch -> record.copyRecord.affectedDirectories()
            is FileOperationRecord.RenameBatch -> record.steps.mapTo(linkedSetOf()) { it.parentLocation }
            is FileOperationRecord.MoveBatch -> record.steps.flatMap {
                listOf(it.sourceParentLocation, it.targetDirectoryLocation)
            }.toSet() + record.createdDirectories.mapNotNull { it.parentLocation }
            is FileOperationRecord.TrashBatch ->
                record.records.mapNotNull { it.originalEntry.parentLocation }.toSet() + TRASH_LOCATION
            null -> emptySet()
        }
    }

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
        entryTargetDirectories: Map<String, String> = emptyMap(),
        directorySteps: List<TransferDirectoryStep> = emptyList(),
        createdDirectories: List<VFile> = emptyList(),
    ) {
        val steps = entries.mapNotNull { entry ->
            FileMoveStep.from(entry, entryTargetDirectories[entry.id] ?: targetDirectoryLocation)
        }
        if (steps.isEmpty()) return
        record(FileOperationRecord.MoveBatch(steps, directorySteps, createdDirectories))
    }

    /**
     * 记录无冲突复制；捕获失败时清空旧历史，避免撤销误作用于更早操作。
     *
     * @param entries 原始源条目。
     * @param targetDirectoryLocation 复制目标目录。
     * @param entryTargetDirectories 嵌套子项对应的实际目标目录。
     * @param directorySteps 重做时需确保存在的中间目录链。
     * @param createdDirectories 本次复制新建的中间目录。
     * @return 目标快照登记结果。
     */
    suspend fun recordCopy(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        entryTargetDirectories: Map<String, String> = emptyMap(),
        directorySteps: List<TransferDirectoryStep> = emptyList(),
        createdDirectories: List<VFile> = emptyList(),
    ): Result<Unit> {
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
                    sources = entries,
                    targetDirectory = targetDirectoryLocation,
                    repository = fileRepository,
                    commands = fileCommandService,
                    hashService = hashService,
                    entryTargetDirectories = entryTargetDirectories,
                    directorySteps = directorySteps,
                    createdDirectories = createdDirectories,
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
     * 当批量撤销部分失败或内容校验失效时，已成功撤销的子步骤压入重做栈，
     * 仅保留尚未执行的剩余步骤并丢弃已失效条目，绝不将旧整批次原样压回栈顶卡死后续撤销。
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
            }.recoverCatching { failure ->
                if (failure is CancellationException) {
                    clear()
                    throw failure
                }
                if (revision != startingRevision) {
                    clear()
                } else if (failure is PartialHistoryFailureException) {
                    failure.completedRecord?.let { completed -> redoStack += completed }
                    failure.remainingRecord?.let { remaining -> undoStack += remaining }
                }
                // 不可逆内容校验失效或条目缺失时不再原样压回栈顶，防止永久阻塞更早的历史记录。
                throw (failure as? PartialHistoryFailureException)?.cause ?: failure
            }
        } finally {
            operationInProgress = false
            updateState()
        }
    }

    /**
     * 重做最近一次已撤销的文件操作。
     *
     * 当批量重做部分失败或内容校验失效时，已成功重做的子步骤压入撤销栈，
     * 仅保留尚未执行的剩余步骤并移除已失效条目，避免卡死重做栈。
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
            }.recoverCatching { failure ->
                if (failure is CancellationException) {
                    clear()
                    throw failure
                }
                if (revision != startingRevision) {
                    clear()
                } else if (failure is PartialHistoryFailureException) {
                    failure.completedRecord?.let { completed -> undoStack += completed }
                    failure.remainingRecord?.let { remaining -> redoStack += remaining }
                }
                throw (failure as? PartialHistoryFailureException)?.cause ?: failure
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
     * 执行文件操作记录的撤销逻辑，遇到部分失败时精确拆分已完成与剩余子批次。
     */
    private suspend fun FileOperationRecord.undo() {
        when (this) {
            is FileOperationRecord.CopyBatch -> copyRecord.undo()
            is FileOperationRecord.RenameBatch -> {
                val reversed = steps.asReversed()
                val completed = mutableListOf<FileRenameStep>()
                reversed.forEachIndexed { idx, step ->
                    try {
                        rename(parentLocation = step.parentLocation, currentName = step.newName, targetName = step.oldName)
                        completed += step
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Throwable) {
                        val remainingOriginalOrder = reversed.drop(idx + 1).asReversed()
                        throw PartialHistoryFailureException(
                            completedRecord = completed.takeIf { it.isNotEmpty() }
                                ?.let { FileOperationRecord.RenameBatch(it.asReversed()) },
                            remainingRecord = remainingOriginalOrder.takeIf { it.isNotEmpty() }
                                ?.let { FileOperationRecord.RenameBatch(it) },
                            cause = failure,
                        )
                    }
                }
            }

            is FileOperationRecord.MoveBatch -> {
                val reversed = steps.asReversed()
                val completed = mutableListOf<FileMoveStep>()
                reversed.forEachIndexed { idx, step ->
                    try {
                        move(
                            name = step.name,
                            sourceDirectory = step.targetDirectoryLocation,
                            targetDirectory = step.sourceParentLocation,
                        )
                        completed += step
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Throwable) {
                        val remainingOriginalOrder = reversed.drop(idx + 1).asReversed()
                        throw PartialHistoryFailureException(
                            completedRecord = completed.takeIf { it.isNotEmpty() }?.let {
                                FileOperationRecord.MoveBatch(
                                    steps = it.asReversed(),
                                    directorySteps = directorySteps,
                                    createdDirectories = createdDirectories,
                                )
                            },
                            remainingRecord = remainingOriginalOrder.takeIf { it.isNotEmpty() }?.let {
                                FileOperationRecord.MoveBatch(
                                    steps = it,
                                    directorySteps = directorySteps,
                                    createdDirectories = createdDirectories,
                                )
                            },
                            cause = failure,
                        )
                    }
                }
                deleteEmptyCreatedDirectories(createdDirectories, fileRepository, fileCommandService)
            }

            is FileOperationRecord.TrashBatch -> {
                requireNotNull(trashService).restoreFromTrash(records).getOrThrow()
            }
        }
    }

    /**
     * 执行文件操作记录的重做逻辑，遇到部分失败时精确拆分已完成与剩余子批次。
     */
    private suspend fun FileOperationRecord.redo() {
        when (this) {
            is FileOperationRecord.CopyBatch -> copyRecord.redo()
            is FileOperationRecord.RenameBatch -> {
                val completed = mutableListOf<FileRenameStep>()
                steps.forEachIndexed { idx, step ->
                    try {
                        rename(parentLocation = step.parentLocation, currentName = step.oldName, targetName = step.newName)
                        completed += step
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Throwable) {
                        val remaining = steps.drop(idx + 1)
                        throw PartialHistoryFailureException(
                            completedRecord = completed.takeIf { it.isNotEmpty() }
                                ?.let { FileOperationRecord.RenameBatch(it) },
                            remainingRecord = remaining.takeIf { it.isNotEmpty() }
                                ?.let { FileOperationRecord.RenameBatch(it) },
                            cause = failure,
                        )
                    }
                }
            }

            is FileOperationRecord.MoveBatch -> {
                createdDirectories = ensureHistoryDirectorySteps(directorySteps, fileRepository, fileCommandService)
                val completed = mutableListOf<FileMoveStep>()
                steps.forEachIndexed { idx, step ->
                    try {
                        move(
                            name = step.name,
                            sourceDirectory = step.sourceParentLocation,
                            targetDirectory = step.targetDirectoryLocation,
                        )
                        completed += step
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Throwable) {
                        val remaining = steps.drop(idx + 1)
                        throw PartialHistoryFailureException(
                            completedRecord = completed.takeIf { it.isNotEmpty() }?.let {
                                FileOperationRecord.MoveBatch(
                                    steps = it,
                                    directorySteps = directorySteps,
                                    createdDirectories = createdDirectories,
                                )
                            },
                            remainingRecord = remaining.takeIf { it.isNotEmpty() }?.let {
                                FileOperationRecord.MoveBatch(
                                    steps = it,
                                    directorySteps = directorySteps,
                                    createdDirectories = createdDirectories,
                                )
                            },
                            cause = failure,
                        )
                    }
                }
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
        val entries = fileRepository.list(parentLocation).getOrThrow()
        val caseInsensitive = com.oruke.onyx.app.component.isCaseInsensitiveVfsLocation(parentLocation)
        return entries.firstOrNull { entry -> entry.name == name }
            ?: if (caseInsensitive) {
                entries.firstOrNull { entry -> entry.name.equals(name, ignoreCase = true) }
            } else {
                null
            }
            ?: error("Entry is no longer available: $parentLocation/$name")
    }

    /**
     * 确认目标目录没有同名条目，按目标 VFS 大小写语义判重，避免 Windows/SMB 仅大小写撞名产生 keep-both 副本。
     *
     * @param directoryLocation 目标目录位置。
     * @param name 文件名。
     */
    private suspend fun ensureNameAvailable(
        directoryLocation: String,
        name: String,
    ) {
        val caseInsensitive = com.oruke.onyx.app.component.isCaseInsensitiveVfsLocation(directoryLocation)
        val exists = fileRepository.list(directoryLocation)
            .getOrThrow()
            .any { entry -> entry.name.equals(name, ignoreCase = caseInsensitive) }
        check(!exists) {
            "Target already contains an entry named $name"
        }
    }

    /** 批量历史操作部分执行失败时用于拆分已完成与剩余子记录的内部异常。 */
    private class PartialHistoryFailureException(
        val completedRecord: FileOperationRecord?,
        val remainingRecord: FileOperationRecord?,
        override val cause: Throwable,
    ) : RuntimeException(cause)

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
            val directorySteps: List<TransferDirectoryStep> = emptyList(),
            var createdDirectories: List<VFile> = emptyList(),
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
