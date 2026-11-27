package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.RoutableFileCommandService
import com.oruke.onyx.vfs.api.RoutableVfsContentService
import com.oruke.onyx.vfs.api.RoutableVfsRandomAccessService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsConnectionTestRequest
import com.oruke.onyx.vfs.api.VfsConnectionTestResult
import com.oruke.onyx.vfs.api.VfsConnectionTester
import com.oruke.onyx.vfs.api.VfsContentSource
import com.oruke.onyx.vfs.api.VfsDirectoryPage
import com.oruke.onyx.vfs.api.VfsDirectoryPageRequest
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsProvider
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsRandomAccessHandle
import com.oruke.onyx.vfs.api.VfsRandomAccessMode
import com.oruke.onyx.vfs.api.PagedVfsProvider
import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.vfs.archive.ArchiveVfsProvider
import com.oruke.onyx.vfs.s3.S3VfsProvider
import com.oruke.onyx.vfs.smb.SmbVfsProvider
import com.oruke.onyx.vfs.webdav.WebDavVfsProvider
import kotlinx.coroutines.flow.Flow

/** 归档协议声明的能力集合，与 [ArchiveVfsProvider.capabilities] 保持一致。 */
private val ARCHIVE_CAPABILITIES: Set<VfsProviderCapability> = setOf(
    VfsProviderCapability.READ_CONTENT,
    VfsProviderCapability.READ_PREVIEW,
    VfsProviderCapability.THUMBNAIL,
)

/** SMB 协议声明的能力集合，与 [SmbVfsProvider.capabilities] 保持一致。 */
private val SMB_CAPABILITIES: Set<VfsProviderCapability> = setOf(
    VfsProviderCapability.CREATE_FILE,
    VfsProviderCapability.CREATE_DIRECTORY,
    VfsProviderCapability.READ_CONTENT,
    VfsProviderCapability.WRITE_CONTENT,
    VfsProviderCapability.READ_RANDOM_ACCESS,
    VfsProviderCapability.WRITE_RANDOM_ACCESS,
    VfsProviderCapability.RENAME,
    VfsProviderCapability.DELETE,
    VfsProviderCapability.COPY,
    VfsProviderCapability.MOVE,
)

/** WebDAV 协议声明的能力集合，与 [WebDavVfsProvider.capabilities] 保持一致。 */
private val WEBDAV_CAPABILITIES: Set<VfsProviderCapability> = setOf(
    VfsProviderCapability.READ_CONTENT,
    VfsProviderCapability.WRITE_CONTENT,
    VfsProviderCapability.READ_RANDOM_ACCESS,
    VfsProviderCapability.CREATE_FILE,
    VfsProviderCapability.CREATE_DIRECTORY,
    VfsProviderCapability.RENAME,
    VfsProviderCapability.DELETE,
    VfsProviderCapability.COPY,
    VfsProviderCapability.MOVE,
)

/** S3 协议声明的能力集合，与 [S3VfsProvider.capabilities] 保持一致。 */
private val S3_CAPABILITIES: Set<VfsProviderCapability> = setOf(
    VfsProviderCapability.READ_CONTENT,
    VfsProviderCapability.WRITE_CONTENT,
    VfsProviderCapability.READ_RANDOM_ACCESS,
    VfsProviderCapability.CREATE_FILE,
    VfsProviderCapability.CREATE_DIRECTORY,
    VfsProviderCapability.RENAME,
    VfsProviderCapability.DELETE,
    VfsProviderCapability.COPY,
    VfsProviderCapability.MOVE,
)

/** SMB 协议前缀。 */
private const val SMB_SCHEME_PREFIX = "smb://"

/** WebDAV 协议名。 */
private const val WEBDAV_SCHEME = "webdav"

/** WebDAVS 协议名。 */
private const val WEBDAVS_SCHEME = "webdavs"

/** S3 协议前缀。 */
private const val S3_SCHEME_PREFIX = "s3://"

/**
 * 延迟初始化的归档协议路由代理，仅在真正访问 `archive://` 路径时才构建 [ArchiveVfsProvider]。
 *
 * @param factory 真实归档 Provider 的创建回调。
 */
internal class LazyRoutedArchiveProvider(
    factory: () -> ArchiveVfsProvider,
) : VfsProvider, RoutableVfsContentService {
    /** 线程安全的延迟 Provider 句柄。 */
    private val delegate: Lazy<ArchiveVfsProvider> = lazy(LazyThreadSafetyMode.SYNCHRONIZED, factory)

    /** 指示真实归档 Provider 是否已经完成实例化。 */
    val isInitialized: Boolean
        get() = delegate.isInitialized()

    override val protocol: VfsProtocol = VfsProtocol.ARCHIVE

    override val capabilities: Set<VfsProviderCapability> = ARCHIVE_CAPABILITIES

    override fun supports(location: String): Boolean = ArchiveService.isArchiveLocation(location)

    override suspend fun list(location: String): Result<List<VFile>> = delegate.value.list(location)

    override suspend fun totalSizeBytes(entries: List<VFile>): Result<Long> = delegate.value.totalSizeBytes(entries)

    override suspend fun readFile(entry: VFile): Result<VfsContentSource> = delegate.value.readFile(entry)

    override suspend fun writeFile(
        parentLocation: String,
        name: String,
        chunks: Flow<ByteArray>,
        conflictStrategy: TransferConflictStrategy,
    ): Result<VFile?> = delegate.value.writeFile(parentLocation, name, chunks, conflictStrategy)
}

/**
 * 延迟初始化的远程协议统一路由代理，避免应用冷启动时提前拉起 JCIFS 或 Ktor CIO HTTP 线程池。
 *
 * 在 `supports()` 仅做纯前缀匹配，绝不触发底层客户端构造；仅在首次真正发生目录列举、命令、内容读写、随机访问或连接测试时才初始化真实 Provider。
 */
internal open class LazyRoutedRemoteProvider<T>(
    /** 协议类型。 */
    override val protocol: VfsProtocol,
    /** 协议能力集合。 */
    override val capabilities: Set<VfsProviderCapability>,
    /** 纯字符串前缀匹配器。 */
    private val locationMatcher: (String) -> Boolean,
    /** 延迟创建真实远程 Provider 的工厂。 */
    factory: () -> T,
) : VfsProvider,
    RoutableFileCommandService,
    RoutableVfsContentService,
    RoutableVfsRandomAccessService,
    VfsConnectionTester,
    AutoCloseable
    where T : VfsProvider,
          T : RoutableFileCommandService,
          T : RoutableVfsContentService,
          T : RoutableVfsRandomAccessService,
          T : VfsConnectionTester,
          T : AutoCloseable {

    /** 线程安全的延迟远程 Provider 实例。 */
    private val delegate: Lazy<T> = lazy(LazyThreadSafetyMode.SYNCHRONIZED, factory)

    /** 指示底层真实远程 Provider 是否已经被初始化。 */
    val isInitialized: Boolean
        get() = delegate.isInitialized()

    /**
     * 获取底层已初始化或即时初始化的远程 Provider。
     *
     * @return 真实远程 Provider 实例。
     */
    protected fun provider(): T = delegate.value

    override fun supports(location: String): Boolean = locationMatcher(location)

    override suspend fun list(location: String): Result<List<VFile>> = provider().list(location)

    override suspend fun totalSizeBytes(entries: List<VFile>): Result<Long> = provider().totalSizeBytes(entries)

    override suspend fun copy(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Unit> = provider().copy(entries, targetDirectoryLocation, conflictStrategy)

    override suspend fun move(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Unit> = provider().move(entries, targetDirectoryLocation, conflictStrategy)

    override suspend fun delete(entries: List<VFile>): Result<Unit> = provider().delete(entries)

    override suspend fun rename(entry: VFile, targetName: String): Result<VFile> = provider().rename(entry, targetName)

    override suspend fun createFile(parentLocation: String, name: String): Result<VFile> =
        provider().createFile(parentLocation, name)

    override suspend fun createDirectory(parentLocation: String, name: String): Result<VFile> =
        provider().createDirectory(parentLocation, name)

    override suspend fun readFile(entry: VFile): Result<VfsContentSource> = provider().readFile(entry)

    override suspend fun writeFile(
        parentLocation: String,
        name: String,
        chunks: Flow<ByteArray>,
        conflictStrategy: TransferConflictStrategy,
    ): Result<VFile?> = provider().writeFile(parentLocation, name, chunks, conflictStrategy)

    override suspend fun openRandomAccess(
        location: String,
        mode: VfsRandomAccessMode,
    ): Result<VfsRandomAccessHandle> = provider().openRandomAccess(location, mode)

    override suspend fun testConnection(request: VfsConnectionTestRequest): VfsConnectionTestResult =
        provider().testConnection(request)

    override fun close() {
        if (delegate.isInitialized()) {
            delegate.value.close()
        }
    }
}

/**
 * 支持原生分页能力 [PagedVfsProvider] 的延迟远程路由代理（如 S3 协议）。
 */
internal open class LazyRoutedPagedRemoteProvider<T>(
    /** 协议类型。 */
    protocol: VfsProtocol,
    /** 协议能力集合。 */
    capabilities: Set<VfsProviderCapability>,
    /** 纯字符串前缀匹配器。 */
    locationMatcher: (String) -> Boolean,
    /** 延迟创建真实远程 Provider 的工厂。 */
    factory: () -> T,
) : LazyRoutedRemoteProvider<T>(protocol, capabilities, locationMatcher, factory),
    PagedVfsProvider
    where T : PagedVfsProvider,
          T : RoutableFileCommandService,
          T : RoutableVfsContentService,
          T : RoutableVfsRandomAccessService,
          T : VfsConnectionTester,
          T : AutoCloseable {

    override suspend fun listPage(request: VfsDirectoryPageRequest): Result<VfsDirectoryPage> =
        provider().listPage(request)
}

/**
 * SMB 协议延迟路由包装器，首次访问 `smb://` 时再加载 JCIFS 客户端。
 *
 * @param factory 创建真实 [SmbVfsProvider] 的回调。
 */
internal class LazySmbVfsProvider(
    factory: () -> SmbVfsProvider,
) : LazyRoutedRemoteProvider<SmbVfsProvider>(
    protocol = VfsProtocol.SMB,
    capabilities = SMB_CAPABILITIES,
    locationMatcher = { location -> location.startsWith(SMB_SCHEME_PREFIX, ignoreCase = true) },
    factory = factory,
)

/**
 * WebDAV 协议延迟路由包装器，首次访问 `webdav://` 或 `webdavs://` 时再拉起 Ktor 客户端。
 *
 * @param factory 创建真实 [WebDavVfsProvider] 的回调。
 */
internal class LazyWebDavVfsProvider(
    factory: () -> WebDavVfsProvider,
) : LazyRoutedRemoteProvider<WebDavVfsProvider>(
    protocol = VfsProtocol.WEBDAV,
    capabilities = WEBDAV_CAPABILITIES,
    locationMatcher = { location ->
        val scheme = location.substringBefore("://", missingDelimiterValue = "")
        scheme.equals(WEBDAV_SCHEME, ignoreCase = true) || scheme.equals(WEBDAVS_SCHEME, ignoreCase = true)
    },
    factory = factory,
)

/**
 * S3 协议延迟路由包装器，保留原生分页 [PagedVfsProvider] 能力并在首次访问 `s3://` 时再拉起 Ktor 客户端。
 *
 * @param factory 创建真实 [S3VfsProvider] 的回调。
 */
internal class LazyS3VfsProvider(
    factory: () -> S3VfsProvider,
) : LazyRoutedPagedRemoteProvider<S3VfsProvider>(
    protocol = VfsProtocol.S3,
    capabilities = S3_CAPABILITIES,
    locationMatcher = { location -> location.startsWith(S3_SCHEME_PREFIX, ignoreCase = true) },
    factory = factory,
)
