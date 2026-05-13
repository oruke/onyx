package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile

enum class VfsProtocol {
    LOCAL,
    ARCHIVE,
    SMB,
    WEBDAV,
    S3,
}

enum class VfsProviderCapability {
    WATCH,
    TRASH,
    EXTERNAL_OPEN,
    READ_PREVIEW,
    READ_CONTENT,
    WRITE_CONTENT,
    THUMBNAIL,
    CREATE_FILE,
    CREATE_DIRECTORY,
    RENAME,
    DELETE,
    COPY,
    MOVE,
}

sealed interface VfsAuthContext {
    data object None : VfsAuthContext

    data class UsernamePassword(
        val username: String,
        val password: String,
        val domain: String? = null,
    ) : VfsAuthContext

    data class BearerToken(
        val token: String,
    ) : VfsAuthContext

    data class AwsCredentials(
        val accessKeyId: String,
        val secretAccessKey: String,
        val sessionToken: String? = null,
        val region: String? = null,
    ) : VfsAuthContext
}

sealed interface VfsProviderError {
    val protocol: VfsProtocol
    val location: String?

    data class AuthenticationRequired(
        override val protocol: VfsProtocol,
        override val location: String? = null,
    ) : VfsProviderError

    data class AuthenticationRejected(
        override val protocol: VfsProtocol,
        override val location: String? = null,
        val reason: String? = null,
    ) : VfsProviderError

    data class PermissionDenied(
        override val protocol: VfsProtocol,
        override val location: String? = null,
        val reason: String? = null,
    ) : VfsProviderError

    data class NotFound(
        override val protocol: VfsProtocol,
        override val location: String? = null,
    ) : VfsProviderError

    data class AlreadyExists(
        override val protocol: VfsProtocol,
        override val location: String? = null,
    ) : VfsProviderError

    data class NetworkFailure(
        override val protocol: VfsProtocol,
        override val location: String? = null,
        val reason: String? = null,
    ) : VfsProviderError

    data class UnsupportedOperation(
        override val protocol: VfsProtocol,
        override val location: String? = null,
        val capability: VfsProviderCapability? = null,
    ) : VfsProviderError

    data class CrossProviderTransferUnsupported(
        override val protocol: VfsProtocol,
        override val location: String? = null,
        val sourceProtocol: VfsProtocol,
        val sourceLocation: String?,
        val capability: VfsProviderCapability,
    ) : VfsProviderError
}

class VfsProviderException(
    val error: VfsProviderError,
) : IllegalStateException(error.toString())

class VfsProviderNotFoundException(
    location: String,
) : IllegalStateException("No VFS provider supports location: $location")

/**
 * VFS 目录分页请求。
 *
 * @property location 需要列出的目录位置。
 * @property pageSize 单页最大条目数。
 * @property pageToken provider 返回的下一页游标；第一页为 null。
 */
data class VfsDirectoryPageRequest(
    val location: String,
    val pageSize: Int = 500,
    val pageToken: String? = null,
) {
    init {
        require(pageSize > 0) {
            "pageSize must be positive"
        }
    }
}

/**
 * VFS 目录分页结果。
 *
 * @property entries 当前页条目。
 * @property nextPageToken 下一页游标；为 null 表示已经读取完毕。
 */
data class VfsDirectoryPage(
    val entries: List<VFile>,
    val nextPageToken: String?,
)

interface VfsProvider {
    val protocol: VfsProtocol

    val capabilities: Set<VfsProviderCapability>

    fun supports(location: String): Boolean

    fun defaultLocation(): String? = null

    suspend fun list(location: String): Result<List<VFile>>

    suspend fun totalSizeBytes(entries: List<VFile>): Result<Long> {
        return Result.success(entries.sumOf { entry -> entry.sizeBytes ?: 0L })
    }
}

/**
 * 支持增量列目录的 VFS provider。
 */
interface PagedVfsProvider : VfsProvider {
    /**
     * 按 provider 原生分页能力列出目录条目，避免大目录一次性读取和排序。
     *
     * @param request 分页请求。
     * @return 当前页条目与下一页游标。
     */
    suspend fun listPage(request: VfsDirectoryPageRequest): Result<VfsDirectoryPage>
}

class VfsProviderRegistry(
    providers: List<VfsProvider>,
) {
    private val providers = providers.toList()

    init {
        require(this.providers.isNotEmpty()) {
            "At least one VFS provider must be registered"
        }
    }

    fun providerFor(location: String): Result<VfsProvider> {
        val provider = providers.firstOrNull { candidate -> candidate.supports(location) }
        return if (provider != null) {
            Result.success(provider)
        } else {
            Result.failure(VfsProviderNotFoundException(location))
        }
    }

    fun defaultLocation(): String {
        return providers.firstNotNullOfOrNull { provider -> provider.defaultLocation() }
            ?: error("No VFS provider supplies a default location")
    }

    suspend fun list(location: String): Result<List<VFile>> {
        return providerFor(location).fold(
            onSuccess = { provider -> provider.list(location) },
            onFailure = { failure -> Result.failure(failure) },
        )
    }

    suspend fun totalSizeBytes(entries: List<VFile>): Result<Long> {
        return runCatching {
            entries
                .groupBy { entry -> providerFor(entry.location).getOrThrow() }
                .entries
                .sumOf { (provider, providerEntries) ->
                    provider.totalSizeBytes(providerEntries).getOrThrow()
                }
        }
    }
}
