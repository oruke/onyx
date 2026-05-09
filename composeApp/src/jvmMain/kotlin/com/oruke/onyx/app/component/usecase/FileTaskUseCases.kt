package com.oruke.onyx.app.component.usecase

import com.oruke.onyx.app.component.FileTransferOperation
import com.oruke.onyx.app.component.delegate.buildTaskDetail
import com.oruke.onyx.app.component.delegate.buildTransferTaskDetail
import com.oruke.onyx.app.filesystem.ArchiveService
import com.oruke.onyx.app.filesystem.FileCommandService
import com.oruke.onyx.app.filesystem.TransferConflictStrategy
import com.oruke.onyx.app.filesystem.TrashService
import com.oruke.onyx.app.filesystem.VfsProviderRegistry
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.msg_string_literal
import java.io.File

data class TaskProgress(
    val status: BackgroundTaskStatus,
    val detail: I18nMessage,
    val progress: Float? = null,
    val currentFileName: String? = null,
    val processedCount: Int? = null,
    val processedBytes: Long? = null,
    val totalBytes: Long? = null,
)

class FileTransferUseCase(
    private val fileCommandService: FileCommandService,
    private val providerRegistry: VfsProviderRegistry,
) {
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
                    Res.string.msg_string_literal,
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
                    detail = I18nMessage(Res.string.msg_string_literal, entry.name),
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
                        Res.string.msg_string_literal,
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

    data class FileTransferRequest(
        val entries: List<VFile>,
        val targetDirectoryLocation: String,
        val operation: FileTransferOperation,
        val conflictStrategies: Map<String, TransferConflictStrategy>,
    )
}

class DeleteEntriesUseCase(
    private val fileCommandService: FileCommandService,
    private val trashService: TrashService,
) {
    fun execute(request: DeleteEntriesRequest): Flow<TaskProgress> = flow {
        emit(
            TaskProgress(
                status = BackgroundTaskStatus.RUNNING,
                detail = I18nMessage(Res.string.msg_string_literal, buildTaskDetail(request.entries)),
                progress = 0f,
            )
        )
        request.entries.forEachIndexed { index, entry ->
            currentCoroutineContext().ensureActive()
            if (request.moveToTrash) {
                trashService.moveToTrash(listOf(entry)).getOrThrow()
            } else {
                fileCommandService.delete(listOf(entry)).getOrThrow()
            }
            emit(
                TaskProgress(
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(Res.string.msg_string_literal, entry.name),
                    progress = progressValue(index + 1, request.entries.size),
                    currentFileName = entry.name,
                    processedCount = index + 1,
                )
            )
        }
    }

    data class DeleteEntriesRequest(
        val entries: List<VFile>,
        val moveToTrash: Boolean,
    )
}

class BatchRenameUseCase(
    private val fileCommandService: FileCommandService,
) {
    fun execute(request: BatchRenameRequest): Flow<TaskProgress> = flow {
        emit(
            TaskProgress(
                status = BackgroundTaskStatus.RUNNING,
                detail = I18nMessage(Res.string.msg_string_literal, "Starting..."),
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
                    detail = I18nMessage(Res.string.msg_string_literal, detailText),
                    progress = progressValue(index + 1, request.renameMap.size),
                    currentFileName = newName,
                    processedCount = index + 1,
                )
            )
        }
    }

    data class BatchRenameRequest(
        val renameMap: List<Pair<VFile, String>>,
    )
}

class ArchiveExtractionUseCase(
    private val archiveService: ArchiveService,
) {
    fun extractEntriesToDirectory(
        request: ArchiveEntryExtractionRequest,
        resolvePassword: suspend (ArchivePasswordRequest) -> String,
    ): Flow<TaskProgress> = flow {
        emit(
            TaskProgress(
                status = BackgroundTaskStatus.RUNNING,
                detail = I18nMessage(Res.string.msg_string_literal, request.targetDirectoryLocation),
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
                    detail = I18nMessage(Res.string.msg_string_literal, group.first().third.name),
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
                    detail = I18nMessage(Res.string.msg_string_literal, archiveName),
                    progress = progressValue(processedCount, request.entries.size),
                    currentFileName = archiveName,
                    processedCount = processedCount,
                )
            )
        }
    }

    fun extractArchiveFiles(
        request: ArchiveFileExtractionRequest,
        resolvePassword: suspend (ArchivePasswordRequest) -> String,
    ): Flow<TaskProgress> = flow {
        emit(
            TaskProgress(
                status = BackgroundTaskStatus.RUNNING,
                detail = I18nMessage(Res.string.msg_string_literal, buildTaskDetail(request.archiveEntries)),
                progress = 0f,
            )
        )
        request.archiveEntries.forEachIndexed { index, entry ->
            currentCoroutineContext().ensureActive()
            emit(
                TaskProgress(
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(Res.string.msg_string_literal, entry.name),
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
                    detail = I18nMessage(Res.string.msg_string_literal, entry.name),
                    progress = progressValue(index + 1, request.archiveEntries.size),
                    currentFileName = entry.name,
                    processedCount = index + 1,
                )
            )
        }
    }

    data class ArchiveEntryExtractionRequest(
        val entries: List<VFile>,
        val targetDirectoryLocation: String,
    )

    data class ArchiveFileExtractionRequest(
        val archiveEntries: List<VFile>,
        val currentLocation: String,
        val extractAction: suspend (VFile, String, String?) -> Result<Unit>,
    )

    data class ArchivePasswordRequest(
        val archivePath: String,
        val archiveName: String,
    )
}

private fun progressValue(
    processedCount: Int,
    totalCount: Int,
): Float {
    if (totalCount <= 0) return 1f
    return processedCount.toFloat() / totalCount
}
