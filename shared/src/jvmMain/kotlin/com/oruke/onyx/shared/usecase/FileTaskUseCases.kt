package com.oruke.onyx.shared.usecase

import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.vfs.archive.ArchiveProgressSink
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.TrashService
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

/** 永久删除或移入系统回收站的后台任务用例。 */
class DeleteEntriesUseCase(
    /** 永久删除使用的文件命令服务。 */
    private val fileCommandService: FileCommandService,
    /** 移入回收站使用的平台服务。 */
    private val trashService: TrashService,
) {
    /**
     * 顺序删除请求条目并发送每项进度和回收站记录。
     *
     * @param request 待删除条目及回收站策略。
     * @return 任务进度冷流。
     */
    fun execute(request: DeleteEntriesRequest): Flow<TaskProgress> = flow {
        emit(
            TaskProgress(
                status = BackgroundTaskStatus.RUNNING,
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, buildTaskDetail(request.entries)),
                progress = 0f,
            )
        )
        request.entries.forEachIndexed { index, entry ->
            currentCoroutineContext().ensureActive()
            val trashRecords = if (request.moveToTrash) {
                trashService.moveToTrash(listOf(entry)).getOrThrow()
            } else {
                fileCommandService.delete(listOf(entry)).getOrThrow()
                emptyList()
            }
            emit(
                TaskProgress(
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, entry.name),
                    progress = progressValue(index + 1, request.entries.size),
                    currentFileName = entry.name,
                    processedCount = index + 1,
                    trashRecords = trashRecords,
                    directoryChange = com.oruke.onyx.core.model.DirectoryChange(removed = listOf(entry)),
                )
            )
        }
    }

    /** 删除任务请求。 */
    data class DeleteEntriesRequest(
        /** 待删除条目。 */
        val entries: List<VFile>,
        /** 是否优先移入系统回收站。 */
        val moveToTrash: Boolean,
    )
}

/** 顺序执行批量重命名映射的后台任务用例，支持链式/循环两阶段临时名中转与部分失败明细记录。 */
class BatchRenameUseCase(
    /** 统一文件命令服务。 */
    private val fileCommandService: FileCommandService,
) {
    /**
     * 执行批量重命名并逐项发送进度（兼容流式调用，首个失败立即抛出异常）。
     *
     * @param request 原条目与新名称映射。
     * @return 任务进度冷流。
     */
    fun execute(request: BatchRenameRequest): Flow<TaskProgress> = flow {
        val summary = executeBatch(request, continueOnError = false) { progress ->
            emit(progress)
        }
        summary.failures.firstOrNull()?.let { firstFailure ->
            throw firstFailure.cause
        }
    }

    /**
     * 执行批量重命名并收集全部成功与失败明细，支持局部失败不丢失已成功条目的撤销记录。
     *
     * @param request 原条目与新名称映射。
     * @param onProgress 单步进度回调。
     * @return 包含成功条目映射、最新 [VFile] 与失败明细的执行汇总。
     */
    suspend fun executeCollecting(
        request: BatchRenameRequest,
        onProgress: suspend (TaskProgress) -> Unit,
    ): BatchRenameExecutionSummary {
        return executeBatch(request, continueOnError = true, onProgress = onProgress)
    }

    /**
     * 按父目录分组规划两阶段临时名中转并执行批量重命名。
     *
     * 当同目录内存在链式平移（如 `1.txt -> 2.txt, 2.txt -> 3.txt`）或循环互换（`a.txt <-> b.txt`）时，
     * 先将存在源/目标名交集的条目重命名为唯一临时名，再统一落位至最终目标名；若阶段 2 落位失败则尽量回滚该项临时名。
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun executeBatch(
        request: BatchRenameRequest,
        continueOnError: Boolean,
        onProgress: suspend (TaskProgress) -> Unit,
    ): BatchRenameExecutionSummary {
        onProgress(
            TaskProgress(
                status = BackgroundTaskStatus.RUNNING,
                detail = I18nMessage(MessageKey.MSG_BATCH_RENAME_STARTING),
                progress = 0f,
            )
        )
        val total = request.renameMap.size.coerceAtLeast(1)
        val succeededPairs = mutableListOf<Pair<VFile, String>>()
        val renamedEntries = linkedMapOf<String, VFile>()
        val failures = mutableListOf<BatchRenameItemFailure>()
        var processed = 0
        val normalizeKey: (String) -> String = { raw ->
            if (request.caseInsensitivePaths) raw.lowercase() else raw
        }

        val grouped = request.renameMap.groupBy { (entry, _) -> entry.parentLocation.orEmpty() }
        for ((_, groupItems) in grouped) {
            currentCoroutineContext().ensureActive()
            val effective = groupItems.filter { (entry, targetName) -> entry.name != targetName }
            val sourceKeyToEntryIds = effective.groupBy(
                keySelector = { (entry, _) -> normalizeKey(entry.name) },
                valueTransform = { (entry, _) -> entry.id },
            )
            // 判定是否与同目录内其他待迁移条目存在目标碰撞（单条目仅大小写改动由底层 provider 直接处理）。
            val needsStagingIds = effective.filter { (entry, targetName) ->
                val collidingIds = sourceKeyToEntryIds[normalizeKey(targetName)].orEmpty()
                collidingIds.any { id -> id != entry.id }
            }.map { (entry, _) -> entry.id }.toSet()

            // 若有条目目标撞到别的源条目，则被撞到的源条目同样需要提前迁出到临时名以腾出位置。
            val involvedTargetKeys = effective
                .filter { (entry, _) -> entry.id in needsStagingIds }
                .map { (_, targetName) -> normalizeKey(targetName) }
                .toSet()
            val stagingItems = effective.filter { (entry, _) ->
                entry.id in needsStagingIds || normalizeKey(entry.name) in involvedTargetKeys
            }
            val directItems = groupItems.filterNot { (entry, _) -> stagingItems.any { it.first.id == entry.id } }

            // 1. 优先执行无需中转的条目。
            for ((entry, newName) in directItems) {
                currentCoroutineContext().ensureActive()
                if (entry.name == newName) {
                    processed += 1
                    continue
                }
                val renameResult = fileCommandService.rename(entry, newName)
                processed += 1
                renameResult.fold(
                    onSuccess = { renamed ->
                        succeededPairs += entry to newName
                        renamedEntries[entry.id] = renamed
                        onProgress(
                            TaskProgress(
                                status = BackgroundTaskStatus.RUNNING,
                                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, "${entry.name} -> $newName"),
                                progress = progressValue(processed, total),
                                currentFileName = newName,
                                processedCount = processed,
                                directoryChange = com.oruke.onyx.core.model.DirectoryChange(
                                    removed = listOf(entry),
                                    upserted = listOf(renamed),
                                    identityChanges = mapOf(entry.id to renamed.id),
                                ),
                            )
                        )
                    },
                    onFailure = { failure ->
                        if (failure is CancellationException) throw failure
                        failures += BatchRenameItemFailure(entry = entry, targetName = newName, cause = failure)
                        if (!continueOnError) {
                            return BatchRenameExecutionSummary(succeededPairs, renamedEntries, failures)
                        }
                    },
                )
            }

            // 2. 对链式/循环冲突组执行两阶段临时名中转，全程受 NonCancellable 取消与事务回滚保护。
            if (stagingItems.isNotEmpty()) {
                val batchToken = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
                val remainingStaged = mutableListOf<StagedRename>()
                val committedInGroup = mutableListOf<CommittedStageRename>()
                try {
                    for ((stageIndex, pair) in stagingItems.withIndex()) {
                        val (originalEntry, finalName) = pair
                        currentCoroutineContext().ensureActive()
                        val tempName = ".onyx-br-$batchToken-$stageIndex"
                        val stageResult = fileCommandService.rename(originalEntry, tempName)
                        stageResult.fold(
                            onSuccess = { tempEntry ->
                                remainingStaged += StagedRename(originalEntry, tempEntry, finalName)
                            },
                            onFailure = { failure ->
                                if (failure is CancellationException) throw failure
                                processed += 1
                                failures += BatchRenameItemFailure(
                                    entry = originalEntry,
                                    targetName = finalName,
                                    cause = failure,
                                )
                                if (!continueOnError) {
                                    rollbackGroupAtomically(
                                        batchToken = batchToken,
                                        remainingStaged = remainingStaged,
                                        committedInGroup = committedInGroup,
                                        succeededPairs = succeededPairs,
                                        renamedEntries = renamedEntries,
                                        failures = failures,
                                    )
                                    return BatchRenameExecutionSummary(succeededPairs, renamedEntries, failures)
                                }
                            },
                        )
                    }

                    // 对无环链按逆拓扑序落位（先迁出终端如 2->3，再落位 1->2），避免前项占坑导致后项回滚碰撞。
                    val commitOrder = orderStagedForCommit(remainingStaged, normalizeKey)
                    for (step in commitOrder) {
                        currentCoroutineContext().ensureActive()
                        val commitResult = fileCommandService.rename(step.tempEntry, step.finalName)
                        processed += 1
                        commitResult.fold(
                            onSuccess = { renamed ->
                                remainingStaged.remove(step)
                                committedInGroup += CommittedStageRename(
                                    originalEntry = step.originalEntry,
                                    committedEntry = renamed,
                                    finalName = step.finalName,
                                )
                                succeededPairs += step.originalEntry to step.finalName
                                renamedEntries[step.originalEntry.id] = renamed
                                onProgress(
                                    TaskProgress(
                                        status = BackgroundTaskStatus.RUNNING,
                                        detail = I18nMessage(
                                            MessageKey.MSG_STRING_LITERAL,
                                            "${step.originalEntry.name} -> ${step.finalName}",
                                        ),
                                        progress = progressValue(processed, total),
                                        currentFileName = step.finalName,
                                        processedCount = processed,
                                        directoryChange = com.oruke.onyx.core.model.DirectoryChange(
                                            removed = listOf(step.originalEntry),
                                            upserted = listOf(renamed),
                                            identityChanges = mapOf(step.originalEntry.id to renamed.id),
                                        ),
                                    )
                                )
                            },
                            onFailure = { failure ->
                                if (failure is CancellationException) throw failure
                                if (!continueOnError) {
                                    failures += BatchRenameItemFailure(
                                        entry = step.originalEntry,
                                        targetName = step.finalName,
                                        cause = failure,
                                    )
                                    // 阶段 2 中止时对当前冲突组执行原子回滚（先腾出已落位占坑，再全部还原原名），杜绝残留 .onyx-br-*。
                                    rollbackGroupAtomically(
                                        batchToken = batchToken,
                                        remainingStaged = remainingStaged,
                                        committedInGroup = committedInGroup,
                                        succeededPairs = succeededPairs,
                                        renamedEntries = renamedEntries,
                                        failures = failures,
                                    )
                                    return BatchRenameExecutionSummary(succeededPairs, renamedEntries, failures)
                                }
                                remainingStaged.remove(step)
                                val rollbackResult = runCatching {
                                    fileCommandService.rename(step.tempEntry, step.originalEntry.name).getOrThrow()
                                }
                                val reportedEntry = if (rollbackResult.isSuccess) {
                                    step.originalEntry
                                } else {
                                    // 若在循环组中因前项已占原名导致直接恢复碰撞，则上报真实滞留临时文件条目供上层感知。
                                    step.tempEntry
                                }
                                failures += BatchRenameItemFailure(
                                    entry = reportedEntry,
                                    targetName = step.finalName,
                                    cause = failure,
                                )
                            },
                        )
                    }
                } catch (cancelled: CancellationException) {
                    if (remainingStaged.isNotEmpty()) {
                        withContext(NonCancellable) {
                            rollbackGroupAtomically(
                                batchToken = batchToken,
                                remainingStaged = remainingStaged,
                                committedInGroup = committedInGroup,
                                succeededPairs = succeededPairs,
                                renamedEntries = renamedEntries,
                                failures = null,
                            )
                        }
                    }
                    throw cancelled
                }
            }
        }
        return BatchRenameExecutionSummary(
            succeededPairs = succeededPairs,
            renamedEntriesByOriginalId = renamedEntries,
            failures = failures,
        )
    }

    /**
     * 对阶段 2 待落位条目按逆拓扑序排列：若 A 的目标名与 B 的原名相同（如 `1->2, 2->3`），
     * 则先落位终端 B（`2->3`）再落位 A（`1->2`），最大程度降低失败回滚时的目标名碰撞。
     */
    private fun orderStagedForCommit(
        staged: List<StagedRename>,
        normalizeKey: (String) -> String,
    ): List<StagedRename> {
        if (staged.size <= 1) return staged.toList()
        val byOriginalKey = staged.associateBy { normalizeKey(it.originalEntry.name) }
        val visited = HashSet<String>(staged.size)
        val visiting = HashSet<String>(staged.size)
        val ordered = ArrayList<StagedRename>(staged.size)

        fun dfs(step: StagedRename) {
            val id = step.originalEntry.id
            if (id in visited || id in visiting) return
            visiting += id
            val blocker = byOriginalKey[normalizeKey(step.finalName)]
            if (blocker != null && blocker.originalEntry.id != id) {
                dfs(blocker)
            }
            visiting -= id
            visited += id
            ordered += step
        }

        staged.forEach(::dfs)
        return ordered
    }

    /**
     * 对冲突组执行两步原子回滚：先将本组内已落位的条目迁回回滚临时名腾出原坑位，
     * 再将全部未落位及刚迁出的条目还原回原始文件名，并同步撤销已记入的成功列表。
     */
    private suspend fun rollbackGroupAtomically(
        batchToken: String,
        remainingStaged: MutableList<StagedRename>,
        committedInGroup: MutableList<CommittedStageRename>,
        succeededPairs: MutableList<Pair<VFile, String>>,
        renamedEntries: MutableMap<String, VFile>,
        failures: MutableList<BatchRenameItemFailure>?,
    ) = withContext(NonCancellable) {
        val toRestore = ArrayList<StagedRename>(remainingStaged.size + committedInGroup.size)
        toRestore.addAll(remainingStaged.asReversed())
        remainingStaged.clear()

        // 将本组已落位条目先迁出到回滚临时名，避免循环互换（如 a<->b）时已落位的 a 阻塞原 a 恢复。
        val committedSnapshot = committedInGroup.toList().asReversed()
        committedInGroup.clear()
        committedSnapshot.forEachIndexed { idx, committed ->
            val rollbackTempName = ".onyx-br-$batchToken-rb-$idx"
            val parkResult = runCatching {
                fileCommandService.rename(committed.committedEntry, rollbackTempName).getOrThrow()
            }
            parkResult.fold(
                onSuccess = { parkedEntry ->
                    succeededPairs.remove(committed.originalEntry to committed.finalName)
                    renamedEntries.remove(committed.originalEntry.id)
                    toRestore += StagedRename(committed.originalEntry, parkedEntry, committed.finalName)
                },
                onFailure = { parkFailure ->
                    if (parkFailure is CancellationException) throw parkFailure
                    failures?.add(
                        BatchRenameItemFailure(
                            entry = committed.committedEntry,
                            targetName = committed.originalEntry.name,
                            cause = parkFailure,
                        ),
                    )
                },
            )
        }

        for (step in toRestore) {
            val rollbackResult = runCatching {
                fileCommandService.rename(step.tempEntry, step.originalEntry.name).getOrThrow()
            }
            rollbackResult.onFailure { rollbackFailure ->
                if (rollbackFailure is CancellationException) throw rollbackFailure
                failures?.add(
                    BatchRenameItemFailure(
                        entry = step.tempEntry,
                        targetName = step.originalEntry.name,
                        cause = rollbackFailure,
                    ),
                )
            }
        }
    }

    /** 阶段 1 已迁至临时名的中间重命名记录。 */
    private data class StagedRename(
        val originalEntry: VFile,
        val tempEntry: VFile,
        val finalName: String,
    )

    /** 阶段 2 在当前冲突组内已落位的记录，用于整组原子回滚。 */
    private data class CommittedStageRename(
        val originalEntry: VFile,
        val committedEntry: VFile,
        val finalName: String,
    )

    /** 批量重命名请求。 */
    data class BatchRenameRequest(
        /** 原条目与目标名称的有序映射。 */
        val renameMap: List<Pair<VFile, String>>,
        /** 是否按大小写不敏感语义检测同目录中转碰撞（默认按宿主平台判定）。 */
        val caseInsensitivePaths: Boolean = System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true),
    )

    /** 批量重命名单项失败详情。 */
    data class BatchRenameItemFailure(
        /** 原文件条目。 */
        val entry: VFile,
        /** 目标文件名。 */
        val targetName: String,
        /** 失败异常。 */
        val cause: Throwable,
    )

    /** 批量重命名执行汇总结果。 */
    data class BatchRenameExecutionSummary(
        /** 成功完成的原条目与目标名映射（可直接写入撤销历史）。 */
        val succeededPairs: List<Pair<VFile, String>>,
        /** 原条目 ID 到重命名后新 [VFile] 的映射。 */
        val renamedEntriesByOriginalId: Map<String, VFile>,
        /** 失败条目与原因列表。 */
        val failures: List<BatchRenameItemFailure>,
    )
}

/** 压缩包内部条目和压缩文件批量提取用例。 */
class ArchiveExtractionUseCase(
    /** 压缩包读取与提取服务。 */
    private val archiveService: ArchiveService,
) {
    /**
     * 按所属压缩包分组提取内部条目到目录。
     *
     * @param request 内部条目与目标目录。
     * @param resolvePassword 加密压缩包的密码请求回调。
     * @return 任务进度冷流。
     */
    fun extractEntriesToDirectory(
        request: ArchiveEntryExtractionRequest,
        resolvePassword: suspend (ArchivePasswordRequest) -> String?,
    ): Flow<TaskProgress> = channelFlow {
        send(
            TaskProgress(
                status = BackgroundTaskStatus.RUNNING,
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, request.targetDirectoryLocation),
                progress = null,
            )
        )

        val grouped = request.entries.mapNotNull { entry ->
            ArchiveService.parseArchiveLocation(entry.location)?.let { (archivePath, innerPath) ->
                Triple(archivePath, innerPath, entry)
            }
        }.groupBy { it.first }

        var processedCount = 0
        for ((archivePath, group) in grouped) {
            currentCoroutineContext().ensureActive()
            val innerPaths = group.map { item -> item.second }.filter { innerPath -> innerPath.isNotBlank() }
            if (innerPaths.isEmpty()) continue

            val archiveName = File(archivePath).name
            send(
                TaskProgress(
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, group.first().third.name),
                    progress = processedCount.takeIf { count -> count > 0 }
                        ?.let { count -> progressValue(count, request.entries.size) },
                    currentFileName = archiveName,
                    processedCount = processedCount,
                )
            )

            val password = if (archiveService.isEncrypted(archivePath)) {
                resolvePassword(ArchivePasswordRequest(archivePath, archiveName))
            } else {
                null
            }
            val progressReporter = ArchiveTaskProgressReporter(
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, archiveName),
                currentFileName = archiveName,
                completedUnits = processedCount,
                currentUnitWeight = group.size,
                totalUnits = request.entries.size,
                exposeByteTotals = grouped.size == 1,
                onTaskProgress = { progress -> trySend(progress) },
            )
            archiveService.extractEntriesToTemp(
                archivePath = archivePath,
                entryPaths = innerPaths,
                targetDir = request.targetDirectoryLocation,
                password = password,
                progressSink = progressReporter,
            ).getOrThrow()

            processedCount += group.size
            send(
                TaskProgress(
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, archiveName),
                    progress = progressValue(processedCount, request.entries.size),
                    currentFileName = archiveName,
                    processedCount = processedCount,
                )
            )
        }
    }

    /**
     * 逐个提取压缩文件并发送进度。
     *
     * @param request 压缩文件、当前位置和实际提取动作。
     * @param resolvePassword 加密压缩包的密码请求回调。
     * @return 任务进度冷流。
     */
    fun extractArchiveFiles(
        request: ArchiveFileExtractionRequest,
        resolvePassword: suspend (ArchivePasswordRequest) -> String?,
    ): Flow<TaskProgress> = channelFlow {
        send(
            TaskProgress(
                status = BackgroundTaskStatus.RUNNING,
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, buildTaskDetail(request.archiveEntries)),
                progress = null,
            )
        )
        request.archiveEntries.forEachIndexed { index, entry ->
            currentCoroutineContext().ensureActive()
            send(
                TaskProgress(
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, entry.name),
                    progress = index.takeIf { processed -> processed > 0 }
                        ?.let { processed -> progressValue(processed, request.archiveEntries.size) },
                    currentFileName = entry.name,
                    processedCount = index,
                )
            )

            val password = if (archiveService.isEncrypted(entry.location)) {
                resolvePassword(ArchivePasswordRequest(entry.location, entry.name))
            } else {
                null
            }
            val progressReporter = ArchiveTaskProgressReporter(
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, entry.name),
                currentFileName = entry.name,
                completedUnits = index,
                currentUnitWeight = 1,
                totalUnits = request.archiveEntries.size,
                exposeByteTotals = request.archiveEntries.size == 1,
                onTaskProgress = { progress -> trySend(progress) },
            )
            request.extractAction(
                entry,
                request.currentLocation,
                password,
                progressReporter,
            ).getOrThrow()
            send(
                TaskProgress(
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, entry.name),
                    progress = progressValue(index + 1, request.archiveEntries.size),
                    currentFileName = entry.name,
                    processedCount = index + 1,
                )
            )
        }
    }

    /** 压缩包内部条目提取请求。 */
    data class ArchiveEntryExtractionRequest(
        /** 待提取的 archive VFS 条目。 */
        val entries: List<VFile>,
        /** 提取目标目录位置。 */
        val targetDirectoryLocation: String,
    )

    /** 压缩文件批量提取请求。 */
    data class ArchiveFileExtractionRequest(
        /** 待提取压缩文件。 */
        val archiveEntries: List<VFile>,
        /** 面板当前目录。 */
        val currentLocation: String,
        /** 单个压缩文件的实际提取动作。 */
        val extractAction: suspend (VFile, String, String?, ArchiveProgressSink) -> Result<Unit>,
    )

    /** 加密压缩包的密码请求上下文。 */
    data class ArchivePasswordRequest(
        /** 压缩文件完整路径。 */
        val archivePath: String,
        /** 用于界面展示的压缩文件名。 */
        val archiveName: String,
    )
}

/**
 * 将处理数量换算为 0 到 1 的进度。
 *
 * @param processedCount 已处理数量。
 * @param totalCount 总数量。
 * @return 归一化进度；总数非正时返回 1。
 */
internal fun progressValue(
    processedCount: Int,
    totalCount: Int,
): Float {
    if (totalCount <= 0) return 1f
    return processedCount.toFloat() / totalCount
}

/**
 * 构造包含目标位置的传输任务摘要。
 *
 * @param entries 待传输条目。
 * @param targetLocation 目标位置。
 * @return 适合任务列表展示的摘要。
 */
fun buildTransferTaskDetail(
    entries: List<VFile>,
    targetLocation: String,
): String {
    return "${buildTaskDetail(entries)} -> $targetLocation"
}

/**
 * 构造条目名称预览摘要。
 *
 * @param entries 任务条目。
 * @return 最多展示三个名称的摘要。
 */
fun buildTaskDetail(entries: List<VFile>): String {
    val preview = entries.take(TASK_PREVIEW_COUNT).joinToString { it.name }
    return if (entries.size <= TASK_PREVIEW_COUNT) preview else "$preview ..."
}

private const val TASK_PREVIEW_COUNT = 3
