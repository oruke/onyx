package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.TrashMoveRecord
import com.oruke.onyx.vfs.api.TrashService
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/** 后台文件任务向 UI 发送的进度快照。 */
data class TaskProgress(
    /** 当前任务状态。 */
    val status: BackgroundTaskStatus,
    /** 当前阶段的国际化详情。 */
    val detail: I18nMessage,
    /** 0 到 1 的可选总体进度。 */
    val progress: Float? = null,
    /** 当前处理文件名。 */
    val currentFileName: String? = null,
    /** 已处理条目数。 */
    val processedCount: Int? = null,
    /** 已处理字节数。 */
    val processedBytes: Long? = null,
    /** 总字节数。 */
    val totalBytes: Long? = null,
    /** 当前步骤产生的回收站记录。 */
    val trashRecords: List<TrashMoveRecord> = emptyList(),
)

/** 按条目执行跨 provider 复制、移动或提取的后台任务用例。 */
class FileTransferUseCase(
    /** 统一文件命令服务。 */
    private val fileCommandService: FileCommandService,
    /** 用于计算跨 provider 条目总大小的注册表。 */
    private val providerRegistry: VfsProviderRegistry,
) {
    /**
     * 顺序传输条目并按文件与字节数发送进度。
     *
     * @param request 传输条目、目标、操作和冲突策略。
     * @param awaitReady 每个条目开始前等待任务调度器放行的回调。
     * @return 任务进度冷流。
     */
    fun execute(
        request: FileTransferRequest,
        awaitReady: suspend () -> Unit,
    ): Flow<TaskProgress> = flow {
        val entryBytesById = request.entries.associate { entry ->
            entry.id to providerRegistry.totalSizeBytes(listOf(entry)).getOrThrow()
        }
        val totalBytes = entryBytesById.values.sum()
        emit(
            TaskProgress(
                status = BackgroundTaskStatus.RUNNING,
                detail = I18nMessage(
                    MessageKey.MSG_STRING_LITERAL,
                    buildTransferTaskDetail(request.entries, request.targetDirectoryLocation),
                ),
                progress = 0f,
                totalBytes = totalBytes,
            )
        )

        var accumulatedBytes = 0L
        request.entries.forEachIndexed { index, entry ->
            currentCoroutineContext().ensureActive()
            awaitReady()

            val entryBytes = entryBytesById[entry.id] ?: entry.sizeBytes ?: 0L
            val conflictStrategy = request.conflictStrategies[entry.id] ?: TransferConflictStrategy.KEEP_BOTH
            emit(
                TaskProgress(
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, entry.name),
                    progress = progressValue(index, request.entries.size),
                    currentFileName = entry.name,
                    processedCount = index,
                    processedBytes = accumulatedBytes,
                    totalBytes = totalBytes,
                )
            )

            when (request.operation) {
                FileTransferOperation.COPY,
                FileTransferOperation.EXTRACT -> fileCommandService.copy(
                    entries = listOf(entry),
                    targetDirectoryLocation = request.targetDirectoryLocation,
                    conflictStrategy = conflictStrategy,
                )

                FileTransferOperation.MOVE -> fileCommandService.move(
                    entries = listOf(entry),
                    targetDirectoryLocation = request.targetDirectoryLocation,
                    conflictStrategy = conflictStrategy,
                )
            }.getOrThrow()

            accumulatedBytes += entryBytes
            emit(
                TaskProgress(
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(
                        MessageKey.MSG_STRING_LITERAL,
                        "${entry.name} -> ${request.targetDirectoryLocation}",
                    ),
                    progress = if (totalBytes > 0L) {
                        accumulatedBytes.toFloat() / totalBytes
                    } else {
                        progressValue(index + 1, request.entries.size)
                    },
                    currentFileName = entry.name,
                    processedCount = index + 1,
                    processedBytes = accumulatedBytes,
                    totalBytes = totalBytes,
                )
            )
        }
    }

    /** 文件传输请求。 */
    data class FileTransferRequest(
        /** 待传输条目。 */
        val entries: List<VFile>,
        /** 目标目录位置。 */
        val targetDirectoryLocation: String,
        /** 复制、移动或提取操作。 */
        val operation: FileTransferOperation,
        /** 按条目 ID 指定的冲突策略。 */
        val conflictStrategies: Map<String, TransferConflictStrategy>,
    )
}

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
    ): Flow<TaskProgress> = flow {
        emit(
            TaskProgress(
                status = BackgroundTaskStatus.RUNNING,
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, request.targetDirectoryLocation),
                progress = 0f,
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
            emit(
                TaskProgress(
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, group.first().third.name),
                    progress = progressValue(processedCount, request.entries.size),
                    currentFileName = archiveName,
                    processedCount = processedCount,
                )
            )

            val password = if (archiveService.isEncrypted(archivePath)) {
                resolvePassword(ArchivePasswordRequest(archivePath, archiveName))
            } else {
                null
            }
            archiveService.extractEntriesToTemp(
                archivePath = archivePath,
                entryPaths = innerPaths,
                targetDir = request.targetDirectoryLocation,
                password = password,
            ).getOrThrow()

            processedCount += group.size
            emit(
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
    ): Flow<TaskProgress> = flow {
        emit(
            TaskProgress(
                status = BackgroundTaskStatus.RUNNING,
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, buildTaskDetail(request.archiveEntries)),
                progress = 0f,
            )
        )
        request.archiveEntries.forEachIndexed { index, entry ->
            currentCoroutineContext().ensureActive()
            emit(
                TaskProgress(
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, entry.name),
                    progress = progressValue(index, request.archiveEntries.size),
                    currentFileName = entry.name,
                    processedCount = index,
                )
            )

            val password = if (archiveService.isEncrypted(entry.location)) {
                resolvePassword(ArchivePasswordRequest(entry.location, entry.name))
            } else {
                null
            }
            request.extractAction(entry, request.currentLocation, password).getOrThrow()
            emit(
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
        val extractAction: suspend (VFile, String, String?) -> Result<Unit>,
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
private fun progressValue(
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
