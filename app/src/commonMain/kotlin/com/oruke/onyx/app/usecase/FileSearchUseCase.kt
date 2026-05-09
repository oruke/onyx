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
    val maxContentBytes: Long = 1_048_576L,
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
    private val contentSearchService: FileContentSearchService = UnsupportedFileContentSearchService,
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
                if (entry.kind == VFileKind.DIRECTORY && !request.includeDirectories && !matcher.requiresDirectories) {
                    continue
                }
                val matches = matcher.matches(entry, contentSearchService, request.maxContentBytes).getOrElse { failure ->
                    emit(
                        FileSearchEvent.Failed(
                            scannedEntryCount = scannedEntryCount,
                            failure = failure,
                        )
                    )
                    return@flow
                }
                if (matches) {
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
        private val criteria = SearchCriteria.parse(rawQuery)
        val isValid: Boolean = criteria.isValid
        val requiresDirectories: Boolean = criteria.kind == VFileKind.DIRECTORY

        suspend fun matches(
            entry: VFile,
            contentSearchService: FileContentSearchService,
            maxContentBytes: Long,
        ): Result<Boolean> {
            if (!criteria.matchesMetadata(entry)) {
                return Result.success(false)
            }
            return criteria.matchesContent(entry, contentSearchService, maxContentBytes)
        }
    }
}

interface FileContentSearchService {
    fun supports(entry: VFile): Boolean

    suspend fun contains(
        entry: VFile,
        query: String,
        maxBytes: Long,
    ): Result<Boolean>
}

object UnsupportedFileContentSearchService : FileContentSearchService {
    override fun supports(entry: VFile): Boolean = false

    override suspend fun contains(
        entry: VFile,
        query: String,
        maxBytes: Long,
    ): Result<Boolean> {
        return Result.failure(UnsupportedOperationException("Content search is not supported for ${entry.location}"))
    }
}

private data class SearchCriteria(
    val nameQuery: String,
    val extensionQuery: String?,
    val kind: VFileKind?,
    val minSizeBytes: Long?,
    val maxSizeBytes: Long?,
    val modifiedAfterEpochMillis: Long?,
    val modifiedBeforeEpochMillis: Long?,
    val contentQuery: String?,
) {
    val isValid: Boolean
        get() = nameQuery.isNotBlank() ||
            extensionQuery != null ||
            kind != null ||
            minSizeBytes != null ||
            maxSizeBytes != null ||
            modifiedAfterEpochMillis != null ||
            modifiedBeforeEpochMillis != null ||
            contentQuery != null

    fun matchesMetadata(entry: VFile): Boolean {
        if (kind != null && entry.kind != kind) {
            return false
        }
        val normalizedName = entry.name.lowercase()
        if (extensionQuery != null && !normalizedName.endsWith(extensionQuery)) {
            return false
        }
        if (nameQuery.isNotBlank() && !normalizedName.contains(nameQuery)) {
            return false
        }
        val size = entry.sizeBytes
        if (minSizeBytes != null && (size == null || size < minSizeBytes)) {
            return false
        }
        if (maxSizeBytes != null && (size == null || size > maxSizeBytes)) {
            return false
        }
        val modified = entry.modifiedAtEpochMillis
        if (modifiedAfterEpochMillis != null && (modified == null || modified < modifiedAfterEpochMillis)) {
            return false
        }
        if (modifiedBeforeEpochMillis != null && (modified == null || modified > modifiedBeforeEpochMillis)) {
            return false
        }
        return true
    }

    suspend fun matchesContent(
        entry: VFile,
        contentSearchService: FileContentSearchService,
        maxContentBytes: Long,
    ): Result<Boolean> {
        val query = contentQuery ?: return Result.success(true)
        if (entry.kind != VFileKind.FILE) {
            return Result.success(false)
        }
        if (!contentSearchService.supports(entry)) {
            return Result.failure(UnsupportedOperationException("Content search is not supported for ${entry.location}"))
        }
        return contentSearchService.contains(entry, query, maxContentBytes)
    }

    companion object {
        fun parse(rawQuery: String): SearchCriteria {
            var extensionQuery: String? = null
            var kind: VFileKind? = null
            var minSizeBytes: Long? = null
            var maxSizeBytes: Long? = null
            var modifiedAfterEpochMillis: Long? = null
            var modifiedBeforeEpochMillis: Long? = null
            var contentQuery: String? = null
            val nameTokens = mutableListOf<String>()

            rawQuery
                .trim()
                .split(Regex("\\s+"))
                .filter { token -> token.isNotBlank() }
                .forEach { token ->
                    val normalized = token.lowercase()
                    when {
                        normalized.startsWith(".") && normalized.length > 1 -> {
                            extensionQuery = normalized
                        }

                        normalized.startsWith("type:") || normalized.startsWith("kind:") -> {
                            kind = parseKind(normalized.substringAfter(':'))
                        }

                        normalized.startsWith("size") -> {
                            parseSizeFilter(normalized)?.let { filter ->
                                when (filter.operator) {
                                    FilterOperator.GREATER_THAN,
                                    FilterOperator.GREATER_THAN_OR_EQUALS -> minSizeBytes = filter.value

                                    FilterOperator.LESS_THAN,
                                    FilterOperator.LESS_THAN_OR_EQUALS -> maxSizeBytes = filter.value
                                }
                            }
                        }

                        normalized.startsWith("modified") || normalized.startsWith("mtime") -> {
                            parseDateFilter(normalized)?.let { filter ->
                                when (filter.operator) {
                                    FilterOperator.GREATER_THAN,
                                    FilterOperator.GREATER_THAN_OR_EQUALS -> modifiedAfterEpochMillis = filter.value

                                    FilterOperator.LESS_THAN,
                                    FilterOperator.LESS_THAN_OR_EQUALS -> modifiedBeforeEpochMillis = filter.value
                                }
                            }
                        }

                        normalized.startsWith("content:") || normalized.startsWith("contains:") -> {
                            token.substringAfter(':').trim().takeIf { value -> value.isNotBlank() }?.let { value ->
                                contentQuery = value.lowercase()
                            }
                        }

                        else -> nameTokens += normalized
                    }
                }

            return SearchCriteria(
                nameQuery = nameTokens.joinToString(" "),
                extensionQuery = extensionQuery,
                kind = kind,
                minSizeBytes = minSizeBytes,
                maxSizeBytes = maxSizeBytes,
                modifiedAfterEpochMillis = modifiedAfterEpochMillis,
                modifiedBeforeEpochMillis = modifiedBeforeEpochMillis,
                contentQuery = contentQuery,
            )
        }

        private fun parseKind(value: String): VFileKind? {
            return when (value) {
                "file", "files" -> VFileKind.FILE
                "dir", "dirs", "directory", "directories", "folder", "folders" -> VFileKind.DIRECTORY
                else -> null
            }
        }

        private fun parseSizeFilter(token: String): NumericFilter? {
            val parsed = parseFilterExpression(token.removePrefix("size")) ?: return null
            val bytes = parseSizeBytes(parsed.value) ?: return null
            return NumericFilter(parsed.operator, bytes)
        }

        private fun parseDateFilter(token: String): NumericFilter? {
            val prefix = if (token.startsWith("modified")) "modified" else "mtime"
            val parsed = parseFilterExpression(token.removePrefix(prefix)) ?: return null
            val epochMillis = parseDateEpochMillis(parsed.value) ?: return null
            val adjusted = when (parsed.operator) {
                FilterOperator.LESS_THAN,
                FilterOperator.LESS_THAN_OR_EQUALS -> epochMillis + MILLIS_PER_DAY - 1

                FilterOperator.GREATER_THAN,
                FilterOperator.GREATER_THAN_OR_EQUALS -> epochMillis
            }
            return NumericFilter(parsed.operator, adjusted)
        }

        private fun parseFilterExpression(value: String): ParsedFilterExpression? {
            val operator = when {
                value.startsWith(">=") -> FilterOperator.GREATER_THAN_OR_EQUALS
                value.startsWith("<=") -> FilterOperator.LESS_THAN_OR_EQUALS
                value.startsWith(">") -> FilterOperator.GREATER_THAN
                value.startsWith("<") -> FilterOperator.LESS_THAN
                else -> return null
            }
            val operand = value.removePrefix(operator.symbol).trim()
            if (operand.isBlank()) {
                return null
            }
            return ParsedFilterExpression(operator, operand)
        }

        private fun parseSizeBytes(value: String): Long? {
            val number = value.dropLastWhile { char -> char.isLetter() }
            val suffix = value.removePrefix(number).lowercase()
            val amount = number.toDoubleOrNull() ?: return null
            val multiplier = when (suffix) {
                "", "b" -> 1L
                "k", "kb" -> 1_024L
                "m", "mb" -> 1_048_576L
                "g", "gb" -> 1_073_741_824L
                "t", "tb" -> 1_099_511_627_776L
                else -> return null
            }
            return (amount * multiplier).toLong()
        }

        private fun parseDateEpochMillis(value: String): Long? {
            val parts = value.split('-')
            if (parts.size != 3) {
                return null
            }
            val year = parts[0].toIntOrNull() ?: return null
            val month = parts[1].toIntOrNull() ?: return null
            val day = parts[2].toIntOrNull() ?: return null
            if (month !in 1..12 || day !in 1..31) {
                return null
            }
            val adjustedYear = if (month <= 2) year - 1 else year
            val era = adjustedYear.floorDiv(400)
            val yearOfEra = adjustedYear - era * 400
            val adjustedMonth = month + if (month > 2) -3 else 9
            val dayOfYear = (153 * adjustedMonth + 2) / 5 + day - 1
            val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
            val epochDay = era * 146_097 + dayOfEra - DAYS_FROM_CIVIL_TO_EPOCH
            return epochDay * MILLIS_PER_DAY
        }

        private fun Int.floorDiv(other: Int): Int {
            var result = this / other
            if ((this xor other) < 0 && result * other != this) {
                result--
            }
            return result
        }
    }
}

private enum class FilterOperator(
    val symbol: String,
) {
    GREATER_THAN(">"),
    GREATER_THAN_OR_EQUALS(">="),
    LESS_THAN("<"),
    LESS_THAN_OR_EQUALS("<="),
}

private data class ParsedFilterExpression(
    val operator: FilterOperator,
    val value: String,
)

private data class NumericFilter(
    val operator: FilterOperator,
    val value: Long,
)

private const val MILLIS_PER_DAY = 86_400_000L
private const val DAYS_FROM_CIVIL_TO_EPOCH = 719_468
