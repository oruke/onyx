package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.filesystem.FileHashRequest
import com.oruke.onyx.app.filesystem.FileHashResult
import com.oruke.onyx.app.filesystem.FileHashService
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.shared.usecase.TransferDirectoryStep
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.TransferConflictStrategy

/** 历史快照允许计算内容哈希的单文件最大字节数（4 MiB）。 */
internal const val MAX_COPY_HISTORY_HASH_FILE_BYTES = 4L * 1024L * 1024L

/** 单次复制历史捕获允许消耗的累计哈希字节预算（8 MiB）。 */
internal const val MAX_COPY_HISTORY_TOTAL_HASH_BYTES = 8L * 1024L * 1024L

/** 单次复制历史捕获最多允许计算哈希的小文件数量。 */
private const val MAX_COPY_HISTORY_HASH_FILES = 32

/** 单次复制历史快照允许遍历的最大目录树节点数。 */
private const val MAX_COPY_HISTORY_SNAPSHOT_ENTRIES = 512

/** 防止循环挂载导致历史快照无限递归的最大深度。 */
private const val MAX_SNAPSHOT_DEPTH = 64

/**
 * 历史快照构建过程中的预算状态，限制哈希计算的字节数、文件数以及总遍历节点数。
 */
private class SnapshotBudget(
    private var remainingHashBytes: Long = MAX_COPY_HISTORY_TOTAL_HASH_BYTES,
    private var remainingHashFiles: Int = MAX_COPY_HISTORY_HASH_FILES,
    private var remainingEntries: Int = MAX_COPY_HISTORY_SNAPSHOT_ENTRIES,
) {
    /** 消耗一个节点额度；超出上限时抛出不可撤销异常。 */
    fun consumeEntry() {
        ensureHistoryAvailable(remainingEntries > 0)
        remainingEntries--
    }

    /**
     * 尝试为指定大小的文件获取哈希计算额度。
     *
     * @param sizeBytes 待哈希文件字节数。
     * @return 成功扣减额度时返回 true；超出单文件上限或预算用尽时返回 false。
     */
    fun tryAcquireHash(sizeBytes: Long?): Boolean {
        val bytes = sizeBytes ?: 0L
        val withinFileLimit = bytes in 0L..MAX_COPY_HISTORY_HASH_FILE_BYTES
        if (!withinFileLimit || remainingHashFiles <= 0 || bytes > remainingHashBytes) {
            return false
        }
        remainingHashFiles--
        remainingHashBytes -= bytes
        return true
    }
}

/** 保存无冲突复制的完整目标快照，撤销前核对内容，重做前核对源和目标冲突。 */
internal class CopiedFilesHistoryRecord private constructor(
    /** 复制前的源条目位置。 */
    private val sources: List<VFile>,
    /** 复制根目标目录。 */
    internal val targetDirectory: String,
    /** 嵌套子项对应的实际落盘父目录。 */
    private val entryTargetDirectories: Map<String, String>,
    /** 重做时需确保存在的中间目录链。 */
    private val directorySteps: List<TransferDirectoryStep>,
    /** 统一文件查询能力。 */
    private val repository: FileRepository,
    /** 统一文件变更能力。 */
    private val commands: FileCommandService,
    /** 文件内容摘要读取能力。 */
    private val hashService: FileHashService,
    /** 本次复制新建的中间目录，重做成功后更新。 */
    private var createdDirectories: List<VFile>,
    /** 已提交副本的快照，重做成功后更新。 */
    private var targets: List<CopiedEntrySnapshot>,
    /** 复制源条目的快照，重做前校验。 */
    private var sourceSnapshots: List<CopiedEntrySnapshot>,
) {
    /** 返回撤销或重做可能触及的全部目录。 */
    internal fun affectedDirectories(): Set<String> = buildSet {
        add(targetDirectory)
        addAll(entryTargetDirectories.values)
        createdDirectories.mapNotNullTo(this) { dir -> dir.parentLocation }
    }

    /** 核对全部副本未改变后删除副本，并清理本次新建且已为空的中间目录。 */
    suspend fun undo() {
        val currentTargets = locateEntries(targets.map { it.entry })
        currentTargets.zip(targets).forEach { (actual, expected) ->
            verifySnapshot(actual, expected, 0)
        }
        commands.delete(currentTargets).getOrThrow()
        deleteEmptyCreatedDirectories(createdDirectories, repository, commands)
    }

    /** 在源内容仍与原快照一致且目标空闲时重新复制，并更新可撤销快照。 */
    suspend fun redo() {
        val currentSources = locateEntries(sources)
        currentSources.zip(sourceSnapshots).forEach { (source, expected) ->
            verifySnapshot(source, expected, 0)
        }
        verifyRedoTargetsAvailable(currentSources)
        createdDirectories = ensureHistoryDirectorySteps(directorySteps, repository, commands)
        currentSources.groupBy(::effectiveTargetOf).forEach { (effectiveDir, groupedSources) ->
            commands.copy(groupedSources, effectiveDir, TransferConflictStrategy.KEEP_BOTH).getOrThrow()
        }
        refreshCapturedSnapshots()
    }

    private fun effectiveTargetOf(source: VFile): String =
        entryTargetDirectories[source.id] ?: targetDirectory

    private suspend fun verifyRedoTargetsAvailable(currentSources: List<VFile>) {
        if (directorySteps.isEmpty()) {
            val existing = repository.list(targetDirectory).getOrThrow().mapTo(mutableSetOf()) { it.name }
            ensureHistoryUnchanged(currentSources.none { source -> source.name in existing })
            return
        }
        currentSources.groupBy(::effectiveTargetOf).forEach { (effectiveDir, groupedSources) ->
            val existing = if (effectiveDir == targetDirectory) {
                repository.list(targetDirectory).getOrThrow().mapTo(mutableSetOf()) { it.name }
            } else {
                repository.list(effectiveDir).getOrNull()?.mapTo(mutableSetOf()) { it.name }.orEmpty()
            }
            ensureHistoryUnchanged(groupedSources.none { source -> source.name in existing })
        }
    }

    /**
     * 按父目录位置缓存文件列表，重新定位给定条目；任一条目不存在时抛出内容变更异常。
     *
     * @param entries 待定位条目列表。
     * @return 定位到的实际 VFS 条目列表，顺序与输入一致。
     */
    private suspend fun locateEntries(entries: List<VFile>): List<VFile> {
        val dirCache = mutableMapOf<String, List<VFile>>()
        return entries.map { entry ->
            val parent = requireNotNull(entry.parentLocation)
            val listed = dirCache.getOrPut(parent) { repository.list(parent).getOrThrow() }
            listed.firstOrNull { it.location == entry.location }
                ?: throw LocalizedException(I18nMessage(MessageKey.MSG_COPY_HISTORY_CHANGED))
        }
    }

    /**
     * 捕获目标副本的结构快照，在预算内对小文件读取哈希，大文件回退为元数据指纹。
     *
     * @param entry 目标条目。
     * @param depth 当前遍历深度。
     * @param budget 快照资源预算。
     * @return 目标条目快照。
     */
    private suspend fun captureTargetSnapshot(
        entry: VFile,
        depth: Int,
        budget: SnapshotBudget,
    ): CopiedEntrySnapshot {
        ensureHistoryAvailable(depth <= MAX_SNAPSHOT_DEPTH)
        budget.consumeEntry()
        val children = if (entry.kind == VFileKind.DIRECTORY) {
            repository.list(entry.location).getOrThrow().sortedBy { it.name }
                .map { child -> captureTargetSnapshot(child, depth + 1, budget) }
        } else {
            emptyList()
        }
        val hash = if (entry.kind == VFileKind.FILE) {
            captureFileHash(entry, budget)
        } else {
            null
        }
        return CopiedEntrySnapshot(entry, hash, children)
    }

    /**
     * 依据预算决定读取文件哈希或校验元数据指纹。
     *
     * @param entry 文件条目。
     * @param budget 快照预算。
     * @return 哈希字符串；超出预算或单文件上限时返回 null。
     */
    private suspend fun captureFileHash(entry: VFile, budget: SnapshotBudget): String? {
        if (!budget.tryAcquireHash(entry.sizeBytes)) {
            ensureHistoryAvailable(entry.hasMetadataFingerprint())
            return null
        }
        return when (val hashResult = hashService.readHash(FileHashRequest(entry, MAX_COPY_HISTORY_HASH_FILE_BYTES))) {
            is FileHashResult.Hash -> hashResult.value
            is FileHashResult.TooLarge -> {
                ensureHistoryAvailable(entry.hasMetadataFingerprint())
                null
            }
            is FileHashResult.Failed -> throw LocalizedException(hashResult.reason)
            else -> {
                ensureHistoryAvailable(false)
                null
            }
        }
    }

    /**
     * 捕获与目标快照匹配的源条目快照，校验双方一致性。
     *
     * @param source 源条目。
     * @param target 对应目标条目的快照。
     * @param depth 当前遍历深度。
     * @return 源条目快照。
     */
    private suspend fun captureMatchingSource(
        source: VFile,
        target: CopiedEntrySnapshot,
        depth: Int,
    ): CopiedEntrySnapshot {
        ensureHistoryAvailable(depth <= MAX_SNAPSHOT_DEPTH)
        ensureHistoryUnchanged(source.name == target.entry.name && source.kind == target.entry.kind)
        val children = if (source.kind == VFileKind.DIRECTORY) {
            val sourceChildren = repository.list(source.location).getOrThrow().sortedBy { it.name }
            ensureHistoryUnchanged(sourceChildren.size == target.children.size)
            sourceChildren.zip(target.children).map { (sourceChild, targetChild) ->
                captureMatchingSource(sourceChild, targetChild, depth + 1)
            }
        } else {
            emptyList()
        }
        val hash = if (source.kind == VFileKind.FILE) {
            captureMatchingSourceFileHash(source, target)
        } else {
            null
        }
        return CopiedEntrySnapshot(source, hash, children)
    }

    /**
     * 捕获并核对源文件的哈希或元数据指纹。
     *
     * @param source 源文件。
     * @param target 对应目标快照。
     * @return 源文件哈希；目标未计算哈希时返回 null。
     */
    private suspend fun captureMatchingSourceFileHash(
        source: VFile,
        target: CopiedEntrySnapshot,
    ): String? {
        if (target.hash != null) {
            val sizeMatches = source.sizeBytes == null || target.entry.sizeBytes == null ||
                source.sizeBytes == target.entry.sizeBytes
            ensureHistoryUnchanged(sizeMatches)
            val hashResult = hashService.readHash(FileHashRequest(source, MAX_COPY_HISTORY_HASH_FILE_BYTES))
            val hash = hashResult.unwrapVerifiedHash()
            ensureHistoryUnchanged(hash == target.hash)
            return hash
        }
        ensureHistoryAvailable(source.hasMetadataFingerprint())
        ensureHistoryUnchanged(source.sizeBytes == target.entry.sizeBytes)
        return null
    }

    /**
     * 统一校验实际条目与历史快照是否一致。
     *
     * @param actual 当前实际条目。
     * @param expected 历史快照。
     * @param depth 当前遍历深度。
     */
    private suspend fun verifySnapshot(
        actual: VFile,
        expected: CopiedEntrySnapshot,
        depth: Int,
    ) {
        ensureHistoryAvailable(depth <= MAX_SNAPSHOT_DEPTH)
        ensureHistoryUnchanged(actual.name == expected.entry.name && actual.kind == expected.entry.kind)
        if (actual.kind == VFileKind.DIRECTORY) {
            val actualChildren = repository.list(actual.location).getOrThrow().sortedBy { it.name }
            ensureHistoryUnchanged(actualChildren.size == expected.children.size)
            actualChildren.zip(expected.children).forEach { (actualChild, expectedChild) ->
                verifySnapshot(actualChild, expectedChild, depth + 1)
            }
        } else if (actual.kind == VFileKind.FILE) {
            verifyFileSnapshot(actual, expected)
        }
    }

    /**
     * 校验文件实际元数据与哈希是否与快照一致。
     *
     * @param actual 当前实际文件条目。
     * @param expected 历史快照。
     */
    private suspend fun verifyFileSnapshot(
        actual: VFile,
        expected: CopiedEntrySnapshot,
    ) {
        val metadataMatches = actual.sizeBytes == expected.entry.sizeBytes &&
            actual.modifiedAtEpochMillis == expected.entry.modifiedAtEpochMillis
        ensureHistoryUnchanged(metadataMatches)
        if (expected.hash == null) {
            ensureHistoryUnchanged(actual.hasMetadataFingerprint())
        } else {
            val hashResult = hashService.readHash(FileHashRequest(actual, MAX_COPY_HISTORY_HASH_FILE_BYTES))
            val hash = hashResult.unwrapVerifiedHash()
            ensureHistoryUnchanged(hash == expected.hash)
        }
    }

    /**
     * 刷新目标和源条目的快照，在首次捕获与重做完成后建立最新可撤销快照。
     */
    private suspend fun refreshCapturedSnapshots() {
        val dirCache = mutableMapOf<String, List<VFile>>()
        val targetEntries = sources.map { source ->
            val effectiveDir = effectiveTargetOf(source)
            val entries = dirCache.getOrPut(effectiveDir) { repository.list(effectiveDir).getOrThrow() }
            entries.firstOrNull { it.name == source.name }
                ?: throw LocalizedException(I18nMessage(MessageKey.MSG_COPY_HISTORY_UNAVAILABLE))
        }
        val currentSources = locateEntries(sources)
        val budget = SnapshotBudget()
        val newTargets = targetEntries.map { target -> captureTargetSnapshot(target, 0, budget) }
        val newSourceSnapshots = currentSources.zip(newTargets).map { (source, target) ->
            captureMatchingSource(source, target, 0)
        }
        targets = newTargets
        sourceSnapshots = newSourceSnapshots
    }

    companion object {
        /**
         * 在复制已经完成后创建可撤销记录；不猜测冲突副本名称。
         *
         * @param sources 原始源条目。
         * @param targetDirectory 无冲突复制根目标目录。
         * @param repository 统一文件查询服务。
         * @param commands 统一文件变更服务。
         * @param hashService 文件摘要服务；未提供时拒绝登记可撤销副本。
         * @param entryTargetDirectories 按条目 ID 指定的实际目标目录。
         * @param directorySteps 重做时需确保存在的中间目录链。
         * @param createdDirectories 本次复制新建的中间目录。
         * @return 完成目标快照后的记录。
         */
        suspend fun capture(
            sources: List<VFile>,
            targetDirectory: String,
            repository: FileRepository,
            commands: FileCommandService,
            hashService: FileHashService?,
            entryTargetDirectories: Map<String, String> = emptyMap(),
            directorySteps: List<TransferDirectoryStep> = emptyList(),
            createdDirectories: List<VFile> = emptyList(),
        ): CopiedFilesHistoryRecord {
            val contentHashService =
                hashService ?: throw LocalizedException(I18nMessage(MessageKey.MSG_COPY_HISTORY_UNAVAILABLE))
            val record = CopiedFilesHistoryRecord(
                sources = sources,
                targetDirectory = targetDirectory,
                entryTargetDirectories = entryTargetDirectories,
                directorySteps = directorySteps,
                repository = repository,
                commands = commands,
                hashService = contentHashService,
                createdDirectories = createdDirectories,
                targets = emptyList(),
                sourceSnapshots = emptyList(),
            )
            record.refreshCapturedSnapshots()
            return record
        }
    }
}

/** 包含条目元数据、文件摘要与目录直接子项的历史快照。 */
private data class CopiedEntrySnapshot(
    /** 实际 VFS 条目与元数据。 */
    val entry: VFile,
    /** 文件的内容摘要，大文件或目录为空。 */
    val hash: String?,
    /** 排序后的直接子项快照。 */
    val children: List<CopiedEntrySnapshot>,
)

/** 判断文件是否具有完整的元数据指纹（字节数与修改时间戳均有效）。 */
private fun VFile.hasMetadataFingerprint(): Boolean =
    (sizeBytes ?: -1L) >= 0L && (modifiedAtEpochMillis ?: 0L) > 0L

/**
 * 校验历史快照构建的前提条件，不满足时抛出不可用异常。
 *
 * @param condition 必须满足的前提条件。
 */
private fun ensureHistoryAvailable(condition: Boolean) {
    if (!condition) {
        throw LocalizedException(I18nMessage(MessageKey.MSG_COPY_HISTORY_UNAVAILABLE))
    }
}

/**
 * 校验历史条目状态未发生改变，改变时抛出不可撤销异常。
 *
 * @param condition 必须满足的未改变条件。
 */
private fun ensureHistoryUnchanged(condition: Boolean) {
    if (!condition) {
        throw LocalizedException(I18nMessage(MessageKey.MSG_COPY_HISTORY_CHANGED))
    }
}

/**
 * 解包核对阶段的文件哈希结果，非哈希分支精确转换为对应业务异常。
 *
 * @return 成功计算的内容哈希字符串。
 * @throws LocalizedException 当哈希失败、文件超出预算或服务不可用时。
 */
private fun FileHashResult.unwrapVerifiedHash(): String {
    val failureMessage = when (this) {
        is FileHashResult.Hash -> return value
        is FileHashResult.Failed -> reason
        is FileHashResult.TooLarge -> I18nMessage(MessageKey.MSG_COPY_HISTORY_CHANGED)
        is FileHashResult.Unavailable -> I18nMessage(MessageKey.MSG_COPY_HISTORY_UNAVAILABLE)
    }
    throw LocalizedException(failureMessage)
}
