package com.oruke.onyx.vfs.api

import com.oruke.onyx.core.model.VFile

/**
 * VFS 支持的协议类型。
 */
enum class VfsProtocol {
    LOCAL,
    ARCHIVE,
    COLLECTION,
    SMB,
    WEBDAV,
    S3,
}

/**
 * VFS provider 可声明的能力集合。
 */
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

/**
 * 访问 VFS provider 时使用的认证上下文。
 */
sealed interface VfsAuthContext {
    /**
     * 无认证信息。
     */
    data object None : VfsAuthContext

    /**
     * 用户名密码认证。
     *
     * @property username 用户名。
     * @property password 密码。
     * @property domain 可选域名。
     */
    data class UsernamePassword(
        val username: String,
        val password: String,
        val domain: String? = null,
    ) : VfsAuthContext

    /**
     * Bearer token 认证。
     *
     * @property token token 文本。
     */
    data class BearerToken(
        val token: String,
    ) : VfsAuthContext

    /**
     * AWS 访问凭据。
     *
     * @property accessKeyId 访问密钥 ID。
     * @property secretAccessKey 访问密钥。
     * @property sessionToken 可选临时会话 token。
     * @property region 可选 AWS region。
     */
    data class AwsCredentials(
        val accessKeyId: String,
        val secretAccessKey: String,
        val sessionToken: String? = null,
        val region: String? = null,
    ) : VfsAuthContext
}

/**
 * VFS provider 统一错误模型。
 */
sealed interface VfsProviderError {
    /** 发生错误的协议。 */
    val protocol: VfsProtocol

    /** 发生错误的位置。 */
    val location: String?

    /**
     * 需要认证信息。
     *
     * @property protocol 发生错误的协议。
     * @property location 发生错误的位置。
     */
    data class AuthenticationRequired(
        override val protocol: VfsProtocol,
        override val location: String? = null,
    ) : VfsProviderError

    /**
     * 认证信息被拒绝。
     *
     * @property protocol 发生错误的协议。
     * @property location 发生错误的位置。
     * @property reason 认证失败原因。
     */
    data class AuthenticationRejected(
        override val protocol: VfsProtocol,
        override val location: String? = null,
        val reason: String? = null,
    ) : VfsProviderError

    /**
     * 权限不足。
     *
     * @property protocol 发生错误的协议。
     * @property location 发生错误的位置。
     * @property reason 权限失败原因。
     */
    data class PermissionDenied(
        override val protocol: VfsProtocol,
        override val location: String? = null,
        val reason: String? = null,
    ) : VfsProviderError

    /**
     * 条目不存在。
     *
     * @property protocol 发生错误的协议。
     * @property location 发生错误的位置。
     */
    data class NotFound(
        override val protocol: VfsProtocol,
        override val location: String? = null,
    ) : VfsProviderError

    /**
     * 目标条目已存在。
     *
     * @property protocol 发生错误的协议。
     * @property location 发生错误的位置。
     */
    data class AlreadyExists(
        override val protocol: VfsProtocol,
        override val location: String? = null,
    ) : VfsProviderError

    /**
     * 网络或远程服务失败。
     *
     * @property protocol 发生错误的协议。
     * @property location 发生错误的位置。
     * @property reason 失败原因。
     */
    data class NetworkFailure(
        override val protocol: VfsProtocol,
        override val location: String? = null,
        val reason: String? = null,
    ) : VfsProviderError

    /**
     * 当前 provider 不支持该操作。
     *
     * @property protocol 发生错误的协议。
     * @property location 发生错误的位置。
     * @property capability 缺失的能力。
     */
    data class UnsupportedOperation(
        override val protocol: VfsProtocol,
        override val location: String? = null,
        val capability: VfsProviderCapability? = null,
    ) : VfsProviderError

    /**
     * 跨 provider 传输不受当前组合支持。
     *
     * @property protocol 目标 provider 协议。
     * @property location 目标位置。
     * @property sourceProtocol 源 provider 协议。
     * @property sourceLocation 源位置。
     * @property capability 缺失的传输能力。
     */
    data class CrossProviderTransferUnsupported(
        override val protocol: VfsProtocol,
        override val location: String? = null,
        val sourceProtocol: VfsProtocol,
        val sourceLocation: String?,
        val capability: VfsProviderCapability,
    ) : VfsProviderError
}

/**
 * VFS provider 语义化异常。
 */
class VfsProviderException(
    /** provider 错误模型。 */
    val error: VfsProviderError,

    /** 导致该错误的底层异常。 */
    cause: Throwable? = null,
) : IllegalStateException(error.toString(), cause)

/**
 * 没有找到可处理指定位置的 VFS provider。
 *
 * @param location VFS 位置。
 */
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

/**
 * VFS provider 基础接口。
 */
interface VfsProvider {
    /** provider 处理的协议。 */
    val protocol: VfsProtocol

    /** provider 声明的能力集合。 */
    val capabilities: Set<VfsProviderCapability>

    /**
     * 判断当前位置是否由该 provider 处理。
     *
     * @param location VFS 位置。
     * @return 支持时返回 true。
     */
    fun supports(location: String): Boolean

    /**
     * 返回 provider 默认入口位置。
     *
     * @return 默认位置；没有默认入口时返回 null。
     */
    fun defaultLocation(): String? = null

    /**
     * 列出目录直接子项。
     *
     * @param location 目录位置。
     * @return 子项列表。
     */
    suspend fun list(location: String): Result<List<VFile>>

    /**
     * 计算条目总大小。
     *
     * @param entries 待统计条目。
     * @return 总字节数。
     */
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

/**
 * VFS provider 注册表，负责按位置路由 provider。
 *
 * @param providers provider 列表。
 */
class VfsProviderRegistry(
    providers: List<VfsProvider>,
) {
    private val providers = providers.toList()

    init {
        require(this.providers.isNotEmpty()) {
            "At least one VFS provider must be registered"
        }
    }

    /**
     * 查找支持指定位置的 provider。
     *
     * @param location VFS 位置。
     * @return provider 或明确错误。
     */
    fun providerFor(location: String): Result<VfsProvider> {
        val provider = providers.firstOrNull { candidate -> candidate.supports(location) }
        return if (provider != null) {
            Result.success(provider)
        } else {
            Result.failure(VfsProviderNotFoundException(location))
        }
    }

    /**
     * 返回第一个可用默认位置。
     *
     * @return 默认 VFS 位置。
     */
    fun defaultLocation(): String {
        return providers.firstNotNullOfOrNull { provider -> provider.defaultLocation() }
            ?: error("No VFS provider supplies a default location")
    }

    /**
     * 通过注册表列出目录。
     *
     * @param location 目录位置。
     * @return 子项列表。
     */
    suspend fun list(location: String): Result<List<VFile>> {
        return providerFor(location).fold(
            onSuccess = { provider -> provider.list(location) },
            onFailure = { failure -> Result.failure(failure) },
        )
    }

    /**
     * 按 provider 分组计算总大小。
     *
     * @param entries 待统计条目。
     * @return 总字节数。
     */
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
