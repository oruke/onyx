package com.oruke.onyx.shared.usecase

import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** 文件递归搜索请求。 */
data class FileSearchRequest(
    /** 搜索根位置。 */
    val rootLocation: String,
    /** 用户输入的名称或结构化过滤表达式。 */
    val query: String,
    /** 普通名称匹配是否包含目录。 */
    val includeDirectories: Boolean = true,
    /** 单次搜索允许返回的最大结果数。 */
    val maxResults: Int = 500,
    /** 内容搜索允许读取的单文件最大字节数。 */
    val maxContentBytes: Long = 1_048_576L,
)

/** 文件搜索期间按顺序发送的状态事件。 */
sealed interface FileSearchEvent {
    /** 搜索扫描进度。 */
    data class Progress(
        /** 已扫描条目数量。 */
        val scannedEntryCount: Int,
        /** 已匹配条目数量。 */
        val matchedCount: Int,
    ) : FileSearchEvent

    /** 当前完整结果快照。 */
    data class Results(
        /** 当前已匹配条目。 */
        val entries: List<VFile>,
        /** 已扫描条目数量。 */
        val scannedEntryCount: Int,
        /** 是否已达到结果上限。 */
        val limitReached: Boolean,
    ) : FileSearchEvent

    /** 搜索正常结束事件。 */
    data class Completed(
        /** 最终扫描条目数量。 */
        val scannedEntryCount: Int,
        /** 是否因结果上限提前结束。 */
        val limitReached: Boolean,
    ) : FileSearchEvent

    /** 搜索失败事件。 */
    data class Failed(
        /** 失败前已扫描条目数量。 */
        val scannedEntryCount: Int,
        /** 导致搜索停止的异常。 */
        val failure: Throwable,
    ) : FileSearchEvent
}

/** 跨 VFS provider 的递归文件搜索用例。 */
class FileSearchUseCase(
    /** 统一文件读取仓储。 */
    private val fileRepository: FileRepository,
    /** 文件内容检索服务。 */
    private val contentSearchService: FileContentSearchService = UnsupportedFileContentSearchService,
    /** 用于检查 provider 内容读取能力的注册表。 */
    private val providerRegistry: VfsProviderRegistry? = null,
) {
    /**
     * 递归扫描根位置并持续发送结果快照、进度和最终状态。
     *
     * @param request 搜索根位置、表达式和容量限制。
     * @return 冷流；收集时在默认调度器执行搜索。
     */
    fun search(request: FileSearchRequest): Flow<FileSearchEvent> = flow {
        val matcher = SearchMatcher(request.query.trim())
        if (!matcher.isValid) {
            emit(FileSearchEvent.Completed(scannedEntryCount = 0, limitReached = false))
            return@flow
        }
        if (matcher.requiresContent && !supportsContentSearch(request.rootLocation)) {
            val failure = UnsupportedOperationException(
                "Content search is not supported for ${request.rootLocation}"
            )
            emit(
                FileSearchEvent.Failed(
                    scannedEntryCount = 0,
                    failure = failure,
                )
            )
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
            if (visitedDirectories.add(currentLocation)) {
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
                    if (!limitReached) {
                        if (entry.kind == VFileKind.DIRECTORY) directories.add(entry.location)
                        val shouldMatch = entry.kind != VFileKind.DIRECTORY ||
                            request.includeDirectories ||
                            matcher.requiresDirectories
                        if (shouldMatch) {
                            val matches = matcher.matches(
                                entry,
                                contentSearchService,
                                request.maxContentBytes,
                            ).getOrElse { failure ->
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
                                limitReached = results.size >= request.maxResults
                            }
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
        }

        emit(
            FileSearchEvent.Completed(
                scannedEntryCount = scannedEntryCount,
                limitReached = limitReached,
            )
        )
    }.flowOn(Dispatchers.Default)

    /** 组合元数据与文件内容条件的单条目匹配器。 */
    private class SearchMatcher(rawQuery: String) {
        /** 解析后的稳定搜索条件。 */
        private val criteria = FileSearchCriteria.parse(rawQuery)

        /** 查询是否至少包含一个有效条件。 */
        val isValid: Boolean = criteria.isValid

        /** 查询是否显式只匹配目录。 */
        val requiresDirectories: Boolean = criteria.kind == VFileKind.DIRECTORY

        /** 查询是否需要读取文件内容。 */
        val requiresContent: Boolean = criteria.requiresContent

        /**
         * 匹配单个条目的元数据与可选内容条件。
         *
         * @param entry 待匹配条目。
         * @param contentSearchService 内容检索服务。
         * @param maxContentBytes 可读取的最大内容字节数。
         * @return 成功时携带是否匹配，读取失败时携带异常。
         */
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

    /**
     * 检查根位置 provider 与内容服务是否同时支持内容搜索。
     *
     * @param rootLocation 搜索根位置。
     * @return 可以执行内容搜索时返回 true。
     */
    private fun supportsContentSearch(rootLocation: String): Boolean {
        val providerSupportsContent = providerRegistry
            ?.providerFor(rootLocation)
            ?.getOrNull()
            ?.capabilities
            ?.contains(VfsProviderCapability.READ_CONTENT)
            ?: true
        return providerSupportsContent && contentSearchService.supportsLocation(rootLocation)
    }
}

/** 文件内容搜索平台服务。 */
interface FileContentSearchService {
    /**
     * 检查服务是否支持指定位置空间。
     *
     * @param location VFS 位置。
     * @return 支持时返回 true。
     */
    fun supportsLocation(location: String): Boolean = true

    /**
     * 检查服务是否能够读取指定条目内容。
     *
     * @param entry 待检查条目。
     * @return 支持时返回 true。
     */
    fun supports(entry: VFile): Boolean

    /**
     * 在最大读取限制内检查文件内容是否包含查询文本。
     *
     * @param entry 待检索文件。
     * @param query 已规范化的小写查询文本。
     * @param maxBytes 最大读取字节数。
     * @return 成功时携带是否包含，读取失败时携带异常。
     */
    suspend fun contains(
        entry: VFile,
        query: String,
        maxBytes: Long,
    ): Result<Boolean>
}

/** 不支持文件内容读取时使用的显式空实现。 */
object UnsupportedFileContentSearchService : FileContentSearchService {
    /**
     * 明确拒绝所有位置的内容搜索。
     *
     * @param location 待检查位置。
     * @return 始终返回 false。
     */
    override fun supportsLocation(location: String): Boolean = false

    /**
     * 明确拒绝所有条目的内容搜索。
     *
     * @param entry 待检查条目。
     * @return 始终返回 false。
     */
    override fun supports(entry: VFile): Boolean = false

    /**
     * 返回内容搜索不受支持错误。
     *
     * @param entry 待检索文件。
     * @param query 查询文本。
     * @param maxBytes 最大读取字节数。
     * @return 固定失败结果。
     */
    override suspend fun contains(
        entry: VFile,
        query: String,
        maxBytes: Long,
    ): Result<Boolean> {
        val failure = UnsupportedOperationException(
            "Content search is not supported for ${entry.location}"
        )
        return Result.failure(failure)
    }
}
