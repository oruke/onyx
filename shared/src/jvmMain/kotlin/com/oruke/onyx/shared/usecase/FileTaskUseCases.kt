package com.oruke.onyx.shared.usecase

import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.vfs.archive.ArchiveProgressSink
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.TrashService
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
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

/** 顺序执行批量重命名映射的后台任务用例。 */
class BatchRenameUseCase(
    /** 统一文件命令服务。 */
    private val fileCommandService: FileCommandService,
) {
    /**
     * 执行批量重命名并逐项发送进度。
     *
     * @param request 原条目与新名称映射。
     * @return 任务进度冷流。
     */
    fun execute(request: BatchRenameRequest): Flow<TaskProgress> = flow {
        emit(
            TaskProgress(
                status = BackgroundTaskStatus.RUNNING,
                detail = I18nMessage(MessageKey.MSG_BATCH_RENAME_STARTING),
                progress = 0f,
            )
        )
        request.renameMap.forEachIndexed { index, (entry, newName) ->
            currentCoroutineContext().ensureActive()
            fileCommandService.rename(entry, newName).getOrThrow()
            val detailText = "${entry.name} -> $newName"
            emit(
                TaskProgress(
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, detailText),
                    progress = progressValue(index + 1, request.renameMap.size),
                    currentFileName = newName,
                    processedCount = index + 1,
                )
            )
        }
    }

    /** 批量重命名请求。 */
    data class BatchRenameRequest(
        /** 原条目与目标名称的有序映射。 */
        val renameMap: List<Pair<VFile, String>>,
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
        resolvePassword: suspend (ArchivePasswordRequest) -> String,
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
        resolvePassword: suspend (ArchivePasswordRequest) -> String,
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
