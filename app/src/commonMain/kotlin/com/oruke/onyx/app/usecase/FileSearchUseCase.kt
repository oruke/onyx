package com.oruke.onyx.app.usecase

import com.oruke.onyx.app.filesystem.FileRepository
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

data class FileSearchRequest(
    val rootLocation: String,
    val query: String,
    val includeDirectories: Boolean = true,
    val maxResults: Int = 500,
)

sealed interface FileSearchEvent {
    data class Progress(
        val scannedEntryCount: Int,
        val matchedCount: Int,
    ) : FileSearchEvent

    data class Results(
        val entries: List<VFile>,
        val scannedEntryCount: Int,
        val limitReached: Boolean,
    ) : FileSearchEvent

    data class Completed(
        val scannedEntryCount: Int,
        val limitReached: Boolean,
    ) : FileSearchEvent

    data class Failed(
        val scannedEntryCount: Int,
        val failure: Throwable,
    ) : FileSearchEvent
}

class FileSearchUseCase(
    private val fileRepository: FileRepository,
) {
    fun search(request: FileSearchRequest): Flow<FileSearchEvent> = flow {
        val matcher = SearchMatcher(request.query.trim())
        if (!matcher.isValid) {
            emit(FileSearchEvent.Completed(scannedEntryCount = 0, limitReached = false))
            return@flow
        }

        val directories = ArrayDeque<String>()
        val visitedDirectories = mutableSetOf<String>()
        val results = mutableListOf<VFile>()
        var scannedEntryCount = 0
        var limitReached = false
        directories.add(request.rootLocation)

        while (directories.isNotEmpty() && !limitReached) {
            currentCoroutineContext().ensureActive()
            val currentLocation = directories.removeFirst()
            if (!visitedDirectories.add(currentLocation)) {
                continue
            }

            val entries = fileRepository.list(currentLocation).getOrElse { failure ->
                emit(
                    FileSearchEvent.Failed(
                        scannedEntryCount = scannedEntryCount,
                        failure = failure,
                    )
                )
                return@flow
            }

            scannedEntryCount += entries.size
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                if (entry.kind == VFileKind.DIRECTORY) {
                    directories.add(entry.location)
                }
                if (entry.kind == VFileKind.DIRECTORY && !request.includeDirectories) {
                    continue
                }
                if (matcher.matches(entry.name)) {
                    results.add(entry)
                    if (results.size >= request.maxResults) {
                        limitReached = true
                        break
                    }
                }
            }

            emit(
                FileSearchEvent.Results(
                    entries = results.toList(),
                    scannedEntryCount = scannedEntryCount,
                    limitReached = limitReached,
                )
            )
            emit(
                FileSearchEvent.Progress(
                    scannedEntryCount = scannedEntryCount,
                    matchedCount = results.size,
                )
            )
        }

        emit(
            FileSearchEvent.Completed(
                scannedEntryCount = scannedEntryCount,
                limitReached = limitReached,
            )
        )
    }.flowOn(Dispatchers.Default)

    private class SearchMatcher(rawQuery: String) {
        private val query = rawQuery.lowercase()
        private val extensionQuery = query.takeIf { it.startsWith(".") && it.length > 1 }

        val isValid: Boolean = query.isNotBlank()

        fun matches(fileName: String): Boolean {
            val normalizedName = fileName.lowercase()
            val extension = extensionQuery
            return if (extension != null) {
                normalizedName.endsWith(extension)
            } else {
                normalizedName.contains(query)
            }
        }
    }
}
