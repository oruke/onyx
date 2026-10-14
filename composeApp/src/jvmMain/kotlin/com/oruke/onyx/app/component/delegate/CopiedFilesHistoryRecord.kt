package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.filesystem.FileHashRequest
import com.oruke.onyx.app.filesystem.FileHashResult
import com.oruke.onyx.app.filesystem.FileHashService
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.TransferConflictStrategy

/** 保存无冲突复制的完整目标快照，撤销前核对内容，重做前核对源和目标冲突。 */
internal class CopiedFilesHistoryRecord private constructor(
    /** 复制前的源条目位置。 */
    private val sources: List<VFile>,
    /** 复制目标目录。 */
    internal val targetDirectory: String,
    /** 统一文件查询能力。 */
    private val repository: FileRepository,
    /** 统一文件变更能力。 */
    private val commands: FileCommandService,
    /** 文件内容摘要读取能力。 */
    private val hashService: FileHashService,
    /** 已提交副本的快照，重做成功后更新。 */
    private var targets: List<CopiedEntrySnapshot>,
) {
    /** 核对全部副本未改变后删除副本，保留用户后续修改的内容。 */
    suspend fun undo() {
        val current = targets.map { snapshot -> snapshot(entryAt(snapshot.entry), 0) }
        if (current != targets) throw LocalizedException(I18nMessage(MessageKey.MSG_COPY_HISTORY_CHANGED))
        commands.delete(current.map { snapshot -> snapshot.entry }).getOrThrow()
    }

    /** 在源内容仍与原副本一致且目标空闲时重新复制，并更新可撤销快照。 */
    suspend fun redo() {
        val currentSources = sources.map { source -> entryAt(source) }
        currentSources.zip(targets).forEach { (source, target) ->
            if (!snapshot(source, 0).sameContentAs(target)) {
                throw LocalizedException(I18nMessage(MessageKey.MSG_COPY_HISTORY_CHANGED))
            }
        }
        val existing = repository.list(targetDirectory).getOrThrow().map { entry -> entry.name }.toSet()
        if (sources.any { source -> source.name in existing }) {
            throw LocalizedException(I18nMessage(MessageKey.MSG_COPY_HISTORY_CHANGED))
        }
        commands.copy(currentSources, targetDirectory, TransferConflictStrategy.KEEP_BOTH).getOrThrow()
        targets = targetSnapshots()
    }

    /**
     * 从实际父目录重新定位条目，条目消失时拒绝撤销或重做。
     *
     * @param entry 历史条目位置。
     * @return 当前条目快照。
     */
    private suspend fun entryAt(entry: VFile): VFile {
        return repository.list(requireNotNull(entry.parentLocation)).getOrThrow()
            .firstOrNull { current -> current.location == entry.location }
            ?: throw LocalizedException(I18nMessage(MessageKey.MSG_COPY_HISTORY_CHANGED))
    }

    /**
     * 读取目录树及文件摘要，过深目录或不可读取内容不登记可撤销操作。
     *
     * @param entry 当前 VFS 条目。
     * @param depth 当前目录深度，限制循环挂载或异常目录递归。
     * @return 完整内容与元数据快照。
     */
    private suspend fun snapshot(entry: VFile, depth: Int): CopiedEntrySnapshot {
        if (depth > MAX_SNAPSHOT_DEPTH) throw LocalizedException(I18nMessage(MessageKey.MSG_COPY_HISTORY_UNAVAILABLE))
        val children = if (entry.kind == VFileKind.DIRECTORY) {
            repository.list(entry.location).getOrThrow().sortedBy { child -> child.name }
                .map { child -> snapshot(child, depth + 1) }
        } else emptyList()
        val hash = if (entry.kind == VFileKind.FILE) {
            hashService.readHash(FileHashRequest(entry, Long.MAX_VALUE)).requireCopyHistoryHash()
        } else null
        return CopiedEntrySnapshot(entry, hash, children)
    }

    /**
     * 从复制目标目录读取每一个新建副本的完整快照。
     *
     * @return 与源顺序一致的目标快照。
     */
    private suspend fun targetSnapshots(): List<CopiedEntrySnapshot> {
        val entries = repository.list(targetDirectory).getOrThrow()
        return sources.map { source ->
            val target = entries.firstOrNull { entry -> entry.name == source.name }
                ?: throw LocalizedException(I18nMessage(MessageKey.MSG_COPY_HISTORY_UNAVAILABLE))
            snapshot(target, 0)
        }
    }

    companion object {
        /**
         * 在复制已经完成后创建可撤销记录；不猜测冲突副本名称。
         *
         * @param sources 原始源条目。
         * @param targetDirectory 无冲突复制目标目录。
         * @param repository 统一文件查询服务。
         * @param commands 统一文件命令服务。
         * @param hashService 文件摘要服务；未提供时拒绝登记可撤销副本。
         * @return 完成目标快照后的记录。
         */
        suspend fun capture(
            sources: List<VFile>, targetDirectory: String, repository: FileRepository,
            commands: FileCommandService, hashService: FileHashService?,
        ): CopiedFilesHistoryRecord {
            val contentHashService =
                hashService ?: throw LocalizedException(I18nMessage(MessageKey.MSG_COPY_HISTORY_UNAVAILABLE))
            val record = CopiedFilesHistoryRecord(
                sources, targetDirectory, repository, commands, contentHashService, emptyList(),
            )
            record.targets = record.targetSnapshots()
            sources.zip(record.targets).forEach { (source, target) ->
                // 用户在提交后、快照完成前修改副本时，不把这份新内容登记为可删除副本。
                if (!record.snapshot(record.entryAt(source), 0).sameContentAs(target)) {
                    throw LocalizedException(I18nMessage(MessageKey.MSG_COPY_HISTORY_CHANGED))
                }
            }
            return record
        }

        /** 防止循环挂载导致历史快照无限递归的最大深度。 */
        private const val MAX_SNAPSHOT_DEPTH = 64
    }
}

/**
 * 将摘要服务结果转换成可安全登记的内容值，不接受未知或大小超限结果。
 * @return 已读取的摘要；失败时抛出可见原因。
 */
private fun FileHashResult.requireCopyHistoryHash(): String = when (this) {
    is FileHashResult.Hash -> value
    is FileHashResult.Failed -> throw LocalizedException(reason)
    else -> throw LocalizedException(I18nMessage(MessageKey.MSG_COPY_HISTORY_UNAVAILABLE))
}

/** 包含条目元数据、文件摘要与目录直接子项的历史快照。 */
private data class CopiedEntrySnapshot(
    /** 实际 VFS 条目与元数据。 */
    val entry: VFile,
    /** 文件的内容摘要，目录为空。 */
    val hash: String?,
    /** 排序后的直接子项快照。 */
    val children: List<CopiedEntrySnapshot>,
) {
    /**
     * 比较源与副本的内容，忽略协议复制可能改变的位置和时间戳。
     *
     * @param other 待比较副本快照。
     * @return 名称、类型、文件内容与完整目录树一致时为 true。
     */
    fun sameContentAs(other: CopiedEntrySnapshot): Boolean =
        entry.name == other.entry.name && entry.kind == other.entry.kind && hash == other.hash &&
                children.size == other.children.size && children.zip(other.children)
            .all { (a, b) -> a.sameContentAs(b) }
}
