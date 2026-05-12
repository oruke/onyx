package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLException
import javax.xml.parsers.DocumentBuilderFactory

interface S3AuthRepository {
    fun authContext(location: String): VfsAuthContext

    data object None : S3AuthRepository {
        override fun authContext(location: String): VfsAuthContext = VfsAuthContext.None
    }
}

class RemoteAuthStoreS3AuthRepository(
    private val remoteAuthStore: RemoteAuthStore,
) : S3AuthRepository {
    override fun authContext(location: String): VfsAuthContext {
        return remoteAuthStore.authContext(VfsProtocol.S3, location)
    }
}

class S3VfsProvider(
    private val authRepository: S3AuthRepository = S3AuthRepository.None,
    private val client: S3Client = KtorS3Client(),
) : VfsProvider, RoutableFileCommandService, RoutableVfsContentService, VfsConnectionTester {
    override val protocol: VfsProtocol = VfsProtocol.S3

    override val capabilities: Set<VfsProviderCapability> = setOf(
        VfsProviderCapability.READ_CONTENT,
        VfsProviderCapability.WRITE_CONTENT,
        VfsProviderCapability.CREATE_FILE,
        VfsProviderCapability.CREATE_DIRECTORY,
        VfsProviderCapability.RENAME,
        VfsProviderCapability.DELETE,
        VfsProviderCapability.COPY,
        VfsProviderCapability.MOVE,
    )

    override fun supports(location: String): Boolean {
        return location.startsWith(S3_SCHEME, ignoreCase = true)
    }

    override suspend fun list(location: String): Result<List<VFile>> {
        if (!supports(location)) {
            return Result.failure(VfsProviderNotFoundException(location))
        }
        return when (val authContext = authRepository.authContext(location)) {
            VfsAuthContext.None -> Result.failure(
                VfsProviderException(VfsProviderError.AuthenticationRequired(VfsProtocol.S3, location))
            )

            is VfsAuthContext.AwsCredentials -> runCatching {
                client.list(
                    location = S3Location.parse(location),
                    authContext = authContext,
                )
            }

            else -> Result.failure(
                VfsProviderException(
                    VfsProviderError.UnsupportedOperation(
                        protocol = VfsProtocol.S3,
                        location = location,
                        capability = null,
                    )
                )
            )
        }
    }

    override suspend fun testConnection(request: VfsConnectionTestRequest): VfsConnectionTestResult {
        if (request.protocol != VfsProtocol.S3 || !supports(request.location)) {
            return VfsConnectionTestResult.Failed(
                protocol = VfsProtocol.S3,
                location = request.location,
                error = VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.S3,
                    location = request.location,
                    capability = null,
                )
            )
        }
        val location = runCatching { S3Location.parse(request.location) }.getOrElse { failure ->
            return failure.toVfsConnectionTestResult(
                protocol = VfsProtocol.S3,
                location = request.location,
            )
        }
        val authContext = request.authContext.takeIf { it != VfsAuthContext.None }
            ?: authRepository.authContext(request.location)
        return when (authContext) {
            VfsAuthContext.None -> VfsConnectionTestResult.Failed(
                protocol = VfsProtocol.S3,
                location = location.directoryLocation,
                error = VfsProviderError.AuthenticationRequired(
                    protocol = VfsProtocol.S3,
                    location = location.directoryLocation,
                )
            )

            is VfsAuthContext.AwsCredentials -> runCatching {
                client.testConnection(
                    location = location,
                    authContext = authContext,
                )
                VfsConnectionTestResult.Reachable(
                    protocol = VfsProtocol.S3,
                    location = location.directoryLocation,
                    capabilities = capabilities,
                )
            }.getOrElse { failure ->
                failure.toVfsConnectionTestResult(
                    protocol = VfsProtocol.S3,
                    location = location.directoryLocation,
                )
            }

            else -> VfsConnectionTestResult.Failed(
                protocol = VfsProtocol.S3,
                location = location.directoryLocation,
                error = VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.S3,
                    location = location.directoryLocation,
                    capability = null,
                )
            )
        }
    }

    override suspend fun readFile(entry: VFile): Result<VfsContentSource> {
        if (!supports(entry.location)) {
            return Result.failure(VfsProviderNotFoundException(entry.location))
        }
        if (entry.kind == VFileKind.DIRECTORY) {
            return Result.failure(
                VfsProviderException(
                    VfsProviderError.UnsupportedOperation(
                        protocol = VfsProtocol.S3,
                        location = entry.location,
                        capability = VfsProviderCapability.READ_CONTENT,
                    )
                )
            )
        }
        return when (val authContext = authRepository.authContext(entry.location)) {
            VfsAuthContext.None -> Result.failure(
                VfsProviderException(VfsProviderError.AuthenticationRequired(VfsProtocol.S3, entry.location))
            )

            is VfsAuthContext.AwsCredentials -> runCatching {
                client.readFile(
                    entry = entry,
                    location = S3Location.parse(entry.location),
                    authContext = authContext,
                )
            }

            else -> Result.failure(
                VfsProviderException(
                    VfsProviderError.UnsupportedOperation(
                        protocol = VfsProtocol.S3,
                        location = entry.location,
                        capability = VfsProviderCapability.READ_CONTENT,
                    )
                )
            )
        }
    }

    /**
     * 将 S3 条目复制到目标目录。
     *
     * @param entries 待复制文件或目录。
     * @param targetDirectoryLocation 目标 S3 目录位置。
     * @param conflictStrategy 冲突处理策略。
     * @return 执行结果。
     */
    override suspend fun copy(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Unit> {
        return transferObjects(
            entries = entries,
            targetDirectoryLocation = targetDirectoryLocation,
            conflictStrategy = conflictStrategy,
            deleteSource = false,
        )
    }

    /**
     * 将 S3 条目移动到目标目录。
     *
     * @param entries 待移动文件或目录。
     * @param targetDirectoryLocation 目标 S3 目录位置。
     * @param conflictStrategy 冲突处理策略。
     * @return 执行结果。
     */
    override suspend fun move(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Unit> {
        return transferObjects(
            entries = entries,
            targetDirectoryLocation = targetDirectoryLocation,
            conflictStrategy = conflictStrategy,
            deleteSource = true,
        )
    }

    /**
     * 删除 S3 文件对象或目录前缀。
     *
     * @param entries 待删除文件或目录。
     * @return 执行结果。
     */
    override suspend fun delete(entries: List<VFile>): Result<Unit> {
        if (entries.isEmpty()) return Result.success(Unit)
        val unsupported = entries.firstOrNull { entry -> !supports(entry.location) }
        if (unsupported != null) {
            return Result.failure(VfsProviderNotFoundException(unsupported.location))
        }
        return runCatching {
            entries.forEach { entry ->
                deleteEntry(entry)
            }
        }
    }

    /**
     * 重命名 S3 文件对象。
     *
     * @param entry 待重命名文件。
     * @param targetName 目标文件名。
     * @return 重命名后的文件条目。
     */
    override suspend fun rename(
        entry: VFile,
        targetName: String,
    ): Result<VFile> {
        if (!supports(entry.location)) {
            return Result.failure(VfsProviderNotFoundException(entry.location))
        }
        return runCatching {
            val parentLocation = entry.parentLocation ?: S3Location.parse(entry.location).directoryLocation
            val target = if (entry.kind == VFileKind.DIRECTORY) {
                copyDirectory(
                    entry = entry,
                    targetDirectoryLocation = parentLocation,
                    targetName = targetName,
                    conflictStrategy = TransferConflictStrategy.SKIP,
                ) ?: throw VfsProviderException(
                    VfsProviderError.AlreadyExists(VfsProtocol.S3, parentLocation)
                )
            } else {
                val source = readFile(entry).getOrThrow()
                writeFile(
                    parentLocation = parentLocation,
                    name = targetName,
                    chunks = source.chunks,
                    conflictStrategy = TransferConflictStrategy.SKIP,
                ).getOrThrow() ?: throw VfsProviderException(
                    VfsProviderError.AlreadyExists(VfsProtocol.S3, parentLocation)
                )
            }
            delete(listOf(entry)).getOrThrow()
            target
        }
    }

    /**
     * 创建空 S3 文件对象。
     *
     * @param parentLocation 父目录位置。
     * @param name 文件名。
     * @return 新建文件条目。
     */
    override suspend fun createFile(
        parentLocation: String,
        name: String,
    ): Result<VFile> {
        return writeFile(
            parentLocation = parentLocation,
            name = name,
            chunks = flow { emit(ByteArray(0)) },
            conflictStrategy = TransferConflictStrategy.SKIP,
        ).mapCatching { entry ->
            entry ?: throw VfsProviderException(VfsProviderError.AlreadyExists(VfsProtocol.S3, parentLocation))
        }
    }

    /**
     * 创建 S3 目录占位对象。
     *
     * @param parentLocation 父目录位置。
     * @param name 目录名。
     * @return 新建目录条目。
     */
    override suspend fun createDirectory(
        parentLocation: String,
        name: String,
    ): Result<VFile> {
        if (!supports(parentLocation)) {
            return Result.failure(VfsProviderNotFoundException(parentLocation))
        }
        return when (val authContext = authRepository.authContext(parentLocation)) {
            VfsAuthContext.None -> Result.failure(
                VfsProviderException(VfsProviderError.AuthenticationRequired(VfsProtocol.S3, parentLocation))
            )

            is VfsAuthContext.AwsCredentials -> runCatching {
                val targetName = validateTargetName(name)
                val parent = S3Location.parse(parentLocation)
                val target = parent.copy(prefix = parent.directoryPrefix + targetName.withTrailingSlash())
                if (client.objectExists(target, authContext)) {
                    throw VfsProviderException(VfsProviderError.AlreadyExists(VfsProtocol.S3, target.directoryLocation))
                }
                client.createDirectory(target, authContext)
                target.toDirectoryVFile(name = targetName, parentLocation = parent.directoryLocation)
            }

            else -> Result.failure(unsupported(parentLocation, VfsProviderCapability.CREATE_DIRECTORY))
        }
    }

    override suspend fun writeFile(
        parentLocation: String,
        name: String,
        chunks: Flow<ByteArray>,
        conflictStrategy: TransferConflictStrategy,
    ): Result<VFile?> {
        if (!supports(parentLocation)) {
            return Result.failure(VfsProviderNotFoundException(parentLocation))
        }
        return when (val authContext = authRepository.authContext(parentLocation)) {
            VfsAuthContext.None -> Result.failure(
                VfsProviderException(VfsProviderError.AuthenticationRequired(VfsProtocol.S3, parentLocation))
            )

            is VfsAuthContext.AwsCredentials -> runCatching {
                client.writeFile(
                    parentLocation = S3Location.parse(parentLocation),
                    name = name,
                    chunks = chunks,
                    conflictStrategy = conflictStrategy,
                    authContext = authContext,
                )
            }

            else -> Result.failure(
                VfsProviderException(
                    VfsProviderError.UnsupportedOperation(
                        protocol = VfsProtocol.S3,
                        location = parentLocation,
                        capability = VfsProviderCapability.WRITE_CONTENT,
                    )
                )
            )
        }
    }

    /**
     * 在 S3 内复制或移动文件对象。
     *
     * @param entries 源文件对象列表。
     * @param targetDirectoryLocation 目标目录位置。
     * @param conflictStrategy 冲突处理策略。
     * @param deleteSource 写入完成后是否删除源对象。
     * @return 执行结果。
     */
    private suspend fun transferObjects(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
        deleteSource: Boolean,
    ): Result<Unit> {
        if (entries.isEmpty()) return Result.success(Unit)
        if (!supports(targetDirectoryLocation)) {
            return Result.failure(VfsProviderNotFoundException(targetDirectoryLocation))
        }
        val unsupported = entries.firstOrNull { entry -> !supports(entry.location) }
        if (unsupported != null) {
            return Result.failure(VfsProviderNotFoundException(unsupported.location))
        }
        return runCatching {
            entries.forEach { entry ->
                val transferred = if (entry.kind == VFileKind.DIRECTORY) {
                    if (isSameOrChildDirectory(targetDirectoryLocation, entry)) {
                        throw unsupported(targetDirectoryLocation, if (deleteSource) VfsProviderCapability.MOVE else VfsProviderCapability.COPY)
                    }
                    copyDirectory(
                        entry = entry,
                        targetDirectoryLocation = targetDirectoryLocation,
                        targetName = entry.name,
                        conflictStrategy = conflictStrategy,
                    ) != null
                } else {
                    val source = readFile(entry).getOrThrow()
                    writeFile(
                        parentLocation = targetDirectoryLocation,
                        name = entry.name,
                        chunks = source.chunks,
                        conflictStrategy = conflictStrategy,
                    ).getOrThrow() != null
                }
                if (deleteSource && transferred) {
                    delete(listOf(entry)).getOrThrow()
                }
            }
        }
    }

    /**
     * 递归复制 S3 目录，并保留空目录占位对象。
     *
     * @param entry 源目录条目。
     * @param targetDirectoryLocation 目标父目录位置。
     * @param targetName 目标目录名。
     * @param conflictStrategy 冲突处理策略。
     * @return 实际创建的目标目录；冲突策略为跳过且目标已存在时返回 `null`。
     */
    private suspend fun copyDirectory(
        entry: VFile,
        targetDirectoryLocation: String,
        targetName: String,
        conflictStrategy: TransferConflictStrategy,
    ): VFile? {
        val targetDirectory = createDirectoryForCopy(
            parentLocation = targetDirectoryLocation,
            name = targetName,
            conflictStrategy = conflictStrategy,
        ) ?: return null
        list(entry.location).getOrThrow().forEach { child ->
            if (child.kind == VFileKind.DIRECTORY) {
                copyDirectory(
                    entry = child,
                    targetDirectoryLocation = targetDirectory.location,
                    targetName = child.name,
                    conflictStrategy = conflictStrategy,
                )
            } else {
                val source = readFile(child).getOrThrow()
                writeFile(
                    parentLocation = targetDirectory.location,
                    name = child.name,
                    chunks = source.chunks,
                    conflictStrategy = conflictStrategy,
                ).getOrThrow()
            }
        }
        return targetDirectory
    }

    /**
     * 按复制语义创建 S3 目录，避免把目录冲突简单伪装成普通文件冲突。
     *
     * @param parentLocation 目标父目录位置。
     * @param name 源目录名或用户输入的新目录名。
     * @param conflictStrategy 冲突处理策略。
     * @return 创建或复用的目录条目；跳过冲突时返回 `null`。
     */
    private suspend fun createDirectoryForCopy(
        parentLocation: String,
        name: String,
        conflictStrategy: TransferConflictStrategy,
    ): VFile? {
        val targetName = resolveDirectoryNameForCopy(parentLocation, name, conflictStrategy) ?: return null
        val parent = S3Location.parse(parentLocation)
        return createDirectory(parentLocation, targetName).recoverCatching { failure ->
            if (conflictStrategy == TransferConflictStrategy.OVERWRITE && failure.isAlreadyExists()) {
                parent
                    .copy(prefix = parent.directoryPrefix + targetName.withTrailingSlash())
                    .toDirectoryVFile(name = targetName, parentLocation = parent.directoryLocation)
            } else {
                throw failure
            }
        }.getOrThrow()
    }

    /**
     * 根据冲突策略解析目录复制时的目标目录名。
     *
     * @param parentLocation 目标父目录位置。
     * @param name 原始目录名。
     * @param conflictStrategy 冲突处理策略。
     * @return 目标目录名；跳过冲突时返回 `null`。
     */
    private suspend fun resolveDirectoryNameForCopy(
        parentLocation: String,
        name: String,
        conflictStrategy: TransferConflictStrategy,
    ): String? {
        val targetName = validateTargetName(name)
        val existingNames = list(parentLocation).getOrThrow().mapTo(mutableSetOf()) { entry -> entry.name }
        return when (conflictStrategy) {
            TransferConflictStrategy.OVERWRITE -> targetName
            TransferConflictStrategy.SKIP -> targetName.takeUnless { it in existingNames }
            TransferConflictStrategy.KEEP_BOTH -> {
                var candidateName = targetName
                repeat(MAX_KEEP_BOTH_ATTEMPTS) { index ->
                    if (candidateName !in existingNames) return candidateName
                    candidateName = targetName.withCopySuffix(index + 1)
                }
                throw VfsProviderException(
                    VfsProviderError.AlreadyExists(
                        protocol = VfsProtocol.S3,
                        location = parentLocation,
                    )
                )
            }
        }
    }

    /**
     * 递归删除 S3 条目。
     *
     * @param entry 待删除条目。
     */
    private suspend fun deleteEntry(entry: VFile) {
        val authContext = authRepository.authContext(entry.location).requireAwsCredentials(entry.location)
        if (entry.kind == VFileKind.DIRECTORY) {
            list(entry.location).getOrThrow().forEach { child -> deleteEntry(child) }
        }
        client.deleteObject(
            location = S3Location.parse(entry.location),
            authContext = authContext,
        )
    }

    /**
     * 判断异常是否表示 S3 目标已存在。
     *
     * @return `true` 表示异常来自已存在错误。
     */
    private fun Throwable.isAlreadyExists(): Boolean {
        return this is VfsProviderException && error is VfsProviderError.AlreadyExists
    }

    /**
     * 判断目标目录是否落在源目录内部，避免递归复制时把刚创建的目标再次枚举进去。
     *
     * @param targetDirectoryLocation 目标父目录位置。
     * @param sourceDirectory 源目录条目。
     * @return `true` 表示目标与源相同或在源目录内部。
     */
    private fun isSameOrChildDirectory(
        targetDirectoryLocation: String,
        sourceDirectory: VFile,
    ): Boolean {
        val target = S3Location.parse(targetDirectoryLocation).directoryLocation
        val source = S3Location.parse(sourceDirectory.location).directoryLocation
        return target == source || target.startsWith(source)
    }

    /**
     * 校验 S3 文件或目录名。
     *
     * @param name 待校验名称。
     * @return 去除首尾空白后的名称。
     */
    private fun validateTargetName(name: String): String {
        val targetName = name.trim()
        if (targetName.isBlank() || '/' in targetName || '\\' in targetName) {
            throw unsupported("", VfsProviderCapability.CREATE_FILE)
        }
        return targetName
    }

    /**
     * 生成 S3 协议不支持异常。
     *
     * @param location 触发异常的位置。
     * @param capability 缺失能力。
     * @return VFS provider 异常。
     */
    private fun unsupported(
        location: String,
        capability: VfsProviderCapability,
    ): VfsProviderException {
        return VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.S3,
                location = location,
                capability = capability,
            )
        )
    }

    /**
     * 读取 AWS 凭据，类型不匹配时抛出明确协议错误。
     *
     * @param location 凭据作用位置。
     * @return AWS 凭据。
     */
    private fun VfsAuthContext.requireAwsCredentials(location: String): VfsAuthContext.AwsCredentials {
        return when (this) {
            VfsAuthContext.None -> throw VfsProviderException(
                VfsProviderError.AuthenticationRequired(VfsProtocol.S3, location)
            )

            is VfsAuthContext.AwsCredentials -> this
            else -> throw unsupported(location, VfsProviderCapability.READ_CONTENT)
        }
    }

    private companion object {
        const val S3_SCHEME = "s3://"
        const val MAX_KEEP_BOTH_ATTEMPTS = 10_000
    }
}

interface S3Client {
    suspend fun testConnection(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    )

    suspend fun list(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ): List<VFile>

    suspend fun readFile(
        entry: VFile,
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ): VfsContentSource {
        throw VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.S3,
                location = entry.location,
                capability = VfsProviderCapability.READ_CONTENT,
            )
        )
    }

    suspend fun writeFile(
        parentLocation: S3Location,
        name: String,
        chunks: Flow<ByteArray>,
        conflictStrategy: TransferConflictStrategy,
        authContext: VfsAuthContext.AwsCredentials,
    ): VFile? {
        throw VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.S3,
                location = parentLocation.directoryLocation,
                capability = VfsProviderCapability.WRITE_CONTENT,
            )
        )
    }

    suspend fun deleteObject(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ) {
        throw VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.S3,
                location = location.toLocation(location.objectKey, directory = false),
                capability = VfsProviderCapability.DELETE,
            )
        )
    }

    suspend fun createDirectory(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ) {
        throw VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.S3,
                location = location.directoryLocation,
                capability = VfsProviderCapability.CREATE_DIRECTORY,
            )
        )
    }

    suspend fun objectExists(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ): Boolean {
        throw VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.S3,
                location = location.toLocation(location.objectKey, directory = false),
                capability = VfsProviderCapability.READ_CONTENT,
            )
        )
    }
}

data class S3Location(
    val bucket: String,
    val prefix: String,
) {
    val objectKey: String = prefix.trimStart('/')

    val directoryPrefix: String = prefix.trim('/').let { value ->
        if (value.isBlank()) "" else "$value/"
    }

    val directoryLocation: String
        get() = toLocation(directoryPrefix, directory = true)

    fun toLocation(
        key: String,
        directory: Boolean,
    ): String {
        val path = if (directory) key.withTrailingSlash() else key
        return URI("s3", bucket, "/$path", null).toASCIIString()
    }

    companion object {
        fun parse(location: String): S3Location {
            val uri = URI(location.encodeSpaces())
            val bucket = uri.host
            if (bucket.isNullOrBlank()) {
                throw VfsProviderNotFoundException(location)
            }
            val prefix = uri.path
                .removePrefix("/")
                .trimStart('/')
            return S3Location(bucket = bucket, prefix = prefix)
        }
    }
}

class KtorS3Client(
    private val httpClient: HttpClient = HttpClient(CIO),
    private val signer: S3RequestSigner = S3RequestSigner(),
    private val parser: S3ListBucketResultParser = S3ListBucketResultParser(),
) : S3Client {
    override suspend fun testConnection(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ): Unit = withContext(Dispatchers.IO) {
        try {
            val request = signer.signListObjectsV2(
                location = location,
                authContext = authContext,
                continuationToken = null,
                maxKeys = 0,
            )
            val response = httpClient.get(request.url) {
                request.headers.forEach { (name, value) -> header(name, value) }
            }
            response.requireSuccess(location.directoryLocation)
        } catch (failure: VfsProviderException) {
            throw failure
        } catch (failure: SSLException) {
            throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.S3,
                    location = location.directoryLocation,
                    reason = failure.message,
                )
            )
        } catch (failure: IOException) {
            throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.S3,
                    location = location.directoryLocation,
                    reason = failure.message,
                )
            )
        }
    }

    override suspend fun list(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ): List<VFile> = withContext(Dispatchers.IO) {
        try {
            val entries = mutableListOf<VFile>()
            var continuationToken: String? = null
            do {
                val request = signer.signListObjectsV2(
                    location = location,
                    authContext = authContext,
                    continuationToken = continuationToken,
                )
                val response = httpClient.get(request.url) {
                    request.headers.forEach { (name, value) -> header(name, value) }
                }
                val body = response.requireSuccess(location.directoryLocation)
                val page = parser.parse(body, location)
                entries += page.entries
                continuationToken = page.nextContinuationToken
            } while (continuationToken != null)
            entries
        } catch (failure: VfsProviderException) {
            throw failure
        } catch (failure: SSLException) {
            throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.S3,
                    location = location.directoryLocation,
                    reason = failure.message,
                )
            )
        } catch (failure: IOException) {
            throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.S3,
                    location = location.directoryLocation,
                    reason = failure.message,
                )
            )
        }
    }

    override suspend fun readFile(
        entry: VFile,
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ): VfsContentSource = withContext(Dispatchers.IO) {
        VfsContentSource(
            name = entry.name,
            sizeBytes = entry.sizeBytes,
            chunks = flow {
                try {
                    val request = signer.signGetObject(
                        location = location,
                        authContext = authContext,
                    )
                    val response = httpClient.get(request.url) {
                        request.headers.forEach { (name, value) -> header(name, value) }
                    }
                    response.requireObjectSuccess(entry.location)
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(CONTENT_BUFFER_SIZE)
                    while (true) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read < 0) break
                        if (read > 0) {
                            emit(buffer.copyOf(read))
                        }
                    }
                } catch (failure: VfsProviderException) {
                    throw failure
                } catch (failure: SSLException) {
                    throw VfsProviderException(
                        VfsProviderError.NetworkFailure(
                            protocol = VfsProtocol.S3,
                            location = entry.location,
                            reason = failure.message,
                        )
                    )
                } catch (failure: IOException) {
                    throw VfsProviderException(
                        VfsProviderError.NetworkFailure(
                            protocol = VfsProtocol.S3,
                            location = entry.location,
                            reason = failure.message,
                        )
                    )
                }
            }.flowOn(Dispatchers.IO),
        )
    }

    override suspend fun writeFile(
        parentLocation: S3Location,
        name: String,
        chunks: Flow<ByteArray>,
        conflictStrategy: TransferConflictStrategy,
        authContext: VfsAuthContext.AwsCredentials,
    ): VFile? = withContext(Dispatchers.IO) {
        val targetName = validateTargetName(name)
        val targetLocation = resolveWriteTargetLocation(
            parentLocation = parentLocation,
            name = targetName,
            conflictStrategy = conflictStrategy,
            authContext = authContext,
        ) ?: return@withContext null
        val writtenBytes = AtomicLong(0)
        try {
            val request = signer.signPutObject(
                location = targetLocation,
                authContext = authContext,
            )
            val response = httpClient.request(request.url) {
                method = HttpMethod.Put
                request.headers.forEach { (headerName, value) -> header(headerName, value) }
                setBody(
                    object : OutgoingContent.WriteChannelContent() {
                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            chunks.collect { chunk ->
                                writtenBytes.addAndGet(chunk.size.toLong())
                                channel.writeFully(chunk)
                            }
                        }
                    }
                )
            }
            response.requireMutationSuccess(targetLocation.toLocation(targetLocation.objectKey, directory = false))
            targetLocation.toObjectVFile(
                name = targetLocation.objectKey.substringAfterLast('/'),
                parentLocation = parentLocation.directoryLocation,
                sizeBytes = writtenBytes.get(),
            )
        } catch (failure: VfsProviderException) {
            throw failure
        } catch (failure: SSLException) {
            throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.S3,
                    location = targetLocation.toLocation(targetLocation.objectKey, directory = false),
                    reason = failure.message,
                )
            )
        } catch (failure: IOException) {
            throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.S3,
                    location = targetLocation.toLocation(targetLocation.objectKey, directory = false),
                    reason = failure.message,
                )
            )
        }
    }

    override suspend fun deleteObject(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ): Unit = withContext(Dispatchers.IO) {
        val objectLocation = location.toLocation(location.objectKey, directory = false)
        try {
            val request = signer.signDeleteObject(
                location = location,
                authContext = authContext,
            )
            val response = httpClient.request(request.url) {
                method = HttpMethod.Delete
                request.headers.forEach { (headerName, value) -> header(headerName, value) }
            }
            response.requireDeleteSuccess(objectLocation)
        } catch (failure: VfsProviderException) {
            throw failure
        } catch (failure: SSLException) {
            throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.S3,
                    location = objectLocation,
                    reason = failure.message,
                )
            )
        } catch (failure: IOException) {
            throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.S3,
                    location = objectLocation,
                    reason = failure.message,
                )
            )
        }
    }

    override suspend fun createDirectory(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ): Unit = withContext(Dispatchers.IO) {
        try {
            val request = signer.signPutObject(
                location = location,
                authContext = authContext,
            )
            val response = httpClient.request(request.url) {
                method = HttpMethod.Put
                request.headers.forEach { (headerName, value) -> header(headerName, value) }
                setBody(ByteArray(0))
            }
            response.requireMutationSuccess(location.directoryLocation)
        } catch (failure: VfsProviderException) {
            throw failure
        } catch (failure: SSLException) {
            throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.S3,
                    location = location.directoryLocation,
                    reason = failure.message,
                )
            )
        } catch (failure: IOException) {
            throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.S3,
                    location = location.directoryLocation,
                    reason = failure.message,
                )
            )
        }
    }

    /**
     * 根据冲突策略解析 S3 写入目标对象。
     *
     * @param parentLocation 目标父目录位置。
     * @param name 目标文件名。
     * @param conflictStrategy 冲突处理策略。
     * @param authContext AWS 凭据。
     * @return 实际写入的目标对象位置；跳过时返回 `null`。
     */
    private suspend fun resolveWriteTargetLocation(
        parentLocation: S3Location,
        name: String,
        conflictStrategy: TransferConflictStrategy,
        authContext: VfsAuthContext.AwsCredentials,
    ): S3Location? {
        val targetLocation = parentLocation.childObject(name)
        return when (conflictStrategy) {
            TransferConflictStrategy.OVERWRITE -> targetLocation
            TransferConflictStrategy.SKIP -> {
                if (objectExists(targetLocation, authContext)) null else targetLocation
            }

            TransferConflictStrategy.KEEP_BOTH -> {
                var candidateName = name
                repeat(MAX_KEEP_BOTH_ATTEMPTS) { index ->
                    val candidate = parentLocation.childObject(candidateName)
                    if (!objectExists(candidate, authContext)) {
                        return candidate
                    }
                    candidateName = name.withCopySuffix(index + 1)
                }
                throw VfsProviderException(
                    VfsProviderError.AlreadyExists(
                        protocol = VfsProtocol.S3,
                        location = targetLocation.toLocation(targetLocation.objectKey, directory = false),
                    )
                )
            }
        }
    }

    /**
     * 使用 HEAD 判断 S3 对象是否存在。
     *
     * @param location 需要检查的对象位置。
     * @param authContext AWS 凭据。
     * @return `true` 表示对象已存在，`false` 表示对象不存在。
     */
    override suspend fun objectExists(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ): Boolean {
        val request = signer.signHeadObject(
            location = location,
            authContext = authContext,
        )
        val response = httpClient.request(request.url) {
            method = HttpMethod.Head
            request.headers.forEach { (headerName, value) -> header(headerName, value) }
        }
        return when (response.status.value) {
            200, 206 -> true
            404 -> false
            else -> {
                response.requireObjectSuccess(location.toLocation(location.objectKey, directory = false))
                true
            }
        }
    }

    private suspend fun HttpResponse.requireSuccess(location: String): String {
        val body = bodyAsText()
        when (status.value) {
            200 -> return body
            400 -> throw VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.S3,
                    location = location,
                    capability = null,
                )
            )

            401 -> throw VfsProviderException(VfsProviderError.AuthenticationRejected(VfsProtocol.S3, location))
            403 -> throw VfsProviderException(s3Error(body, location))
            404 -> throw VfsProviderException(VfsProviderError.NotFound(VfsProtocol.S3, location))
            in 500..599 -> throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.S3,
                    location = location,
                    reason = status.description,
                )
            )

            else -> throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.S3,
                    location = location,
                    reason = status.description,
                )
            )
        }
    }

    /**
     * 校验 S3 对象写入的文件名。
     *
     * @param name 用户或源文件提供的文件名。
     * @return 去除首尾空白后的合法文件名。
     */
    private fun validateTargetName(name: String): String {
        val targetName = name.trim()
        if (targetName.isBlank() || '/' in targetName || '\\' in targetName) {
            throw VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.S3,
                    capability = VfsProviderCapability.WRITE_CONTENT,
                )
            )
        }
        return targetName
    }

    private suspend fun HttpResponse.requireObjectSuccess(location: String) {
        when (status.value) {
            200, 206 -> Unit
            else -> requireSuccess(location)
        }
    }

    /**
     * 校验 S3 写入类请求是否成功。
     *
     * @param location 写入对象位置。
     */
    private suspend fun HttpResponse.requireMutationSuccess(location: String) {
        when (status.value) {
            200, 201, 204 -> Unit
            else -> requireSuccess(location)
        }
    }

    /**
     * 校验 S3 删除请求是否成功。
     *
     * @param location 删除对象位置。
     */
    private suspend fun HttpResponse.requireDeleteSuccess(location: String) {
        when (status.value) {
            200, 202, 204 -> Unit
            else -> requireSuccess(location)
        }
    }

    private fun s3Error(
        xml: String,
        location: String,
    ): VfsProviderError {
        val code = S3ErrorParser().parseCode(xml)
        return when (code) {
            "InvalidAccessKeyId",
            "SignatureDoesNotMatch",
            "ExpiredToken",
            "InvalidToken",
            -> VfsProviderError.AuthenticationRejected(VfsProtocol.S3, location, code)

            "AccessDenied" -> VfsProviderError.PermissionDenied(VfsProtocol.S3, location, code)
            "NoSuchBucket",
            "NoSuchKey",
            -> VfsProviderError.NotFound(VfsProtocol.S3, location)

            else -> VfsProviderError.NetworkFailure(VfsProtocol.S3, location, code)
        }
    }

    private companion object {
        const val CONTENT_BUFFER_SIZE = 64 * 1024
        const val MAX_KEEP_BOTH_ATTEMPTS = 10_000
    }
}

data class S3SignedRequest(
    val url: String,
    val headers: Map<String, String>,
)

class S3RequestSigner(
    private val clock: Clock = Clock.systemUTC(),
) {
    fun signListObjectsV2(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
        continuationToken: String?,
        maxKeys: Int? = null,
    ): S3SignedRequest {
        val region = authContext.region ?: DEFAULT_REGION
        val host = "s3.$region.amazonaws.com"
        val canonicalUri = "/${awsEncode(location.bucket)}"
        val queryParameters = buildMap {
            put("delimiter", "/")
            put("list-type", "2")
            put("prefix", location.directoryPrefix)
            if (continuationToken != null) {
                put("continuation-token", continuationToken)
            }
            if (maxKeys != null) {
                put("max-keys", maxKeys.toString())
            }
        }
        val canonicalQuery = queryParameters.toCanonicalQuery()
        val now = clock.instant()
        val amzDate = AMZ_DATE_FORMATTER.format(now)
        val dateStamp = DATE_STAMP_FORMATTER.format(now)
        val credentialScope = "$dateStamp/$region/s3/aws4_request"
        val headers = buildMap {
            put("host", host)
            put("x-amz-content-sha256", UNSIGNED_PAYLOAD)
            put("x-amz-date", amzDate)
            authContext.sessionToken?.let { token -> put("x-amz-security-token", token) }
        }.toSortedMap()
        val canonicalHeaders = headers.entries.joinToString(separator = "") { (name, value) ->
            "${name.lowercase()}:${value.trim()}\n"
        }
        val signedHeaders = headers.keys.joinToString(";") { key -> key.lowercase() }
        val canonicalRequest = listOf(
            "GET",
            canonicalUri,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            UNSIGNED_PAYLOAD,
        ).joinToString("\n")
        val stringToSign = listOf(
            ALGORITHM,
            amzDate,
            credentialScope,
            canonicalRequest.sha256Hex(),
        ).joinToString("\n")
        val signingKey = signingKey(
            secretAccessKey = authContext.secretAccessKey,
            dateStamp = dateStamp,
            region = region,
        )
        val signature = hmac(signingKey, stringToSign).toHex()
        val authorization = "$ALGORITHM Credential=${authContext.accessKeyId}/$credentialScope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"
        val requestHeaders = headers + ("Authorization" to authorization)
        val url = "https://$host$canonicalUri?$canonicalQuery"
        return S3SignedRequest(url = url, headers = requestHeaders)
    }

    fun signGetObject(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ): S3SignedRequest {
        return signObject(
            method = "GET",
            location = location,
            authContext = authContext,
        )
    }

    fun signHeadObject(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ): S3SignedRequest {
        return signObject(
            method = "HEAD",
            location = location,
            authContext = authContext,
        )
    }

    fun signPutObject(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ): S3SignedRequest {
        return signObject(
            method = "PUT",
            location = location,
            authContext = authContext,
        )
    }

    fun signDeleteObject(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ): S3SignedRequest {
        return signObject(
            method = "DELETE",
            location = location,
            authContext = authContext,
        )
    }

    /**
     * 为 S3 对象级请求生成 AWS Signature V4 签名。
     *
     * @param method HTTP 方法。
     * @param location 对象位置。
     * @param authContext AWS 凭据。
     * @return 带 URL 和请求头的已签名请求。
     */
    private fun signObject(
        method: String,
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ): S3SignedRequest {
        val region = authContext.region ?: DEFAULT_REGION
        val host = "s3.$region.amazonaws.com"
        val canonicalUri = "/${awsEncode(location.bucket)}/${awsEncodePath(location.objectKey)}"
        val now = clock.instant()
        val amzDate = AMZ_DATE_FORMATTER.format(now)
        val dateStamp = DATE_STAMP_FORMATTER.format(now)
        val credentialScope = "$dateStamp/$region/s3/aws4_request"
        val headers = buildMap {
            put("host", host)
            put("x-amz-content-sha256", UNSIGNED_PAYLOAD)
            put("x-amz-date", amzDate)
            authContext.sessionToken?.let { token -> put("x-amz-security-token", token) }
        }.toSortedMap()
        val canonicalHeaders = headers.entries.joinToString(separator = "") { (name, value) ->
            "${name.lowercase()}:${value.trim()}\n"
        }
        val signedHeaders = headers.keys.joinToString(";") { key -> key.lowercase() }
        val canonicalRequest = listOf(
            method,
            canonicalUri,
            "",
            canonicalHeaders,
            signedHeaders,
            UNSIGNED_PAYLOAD,
        ).joinToString("\n")
        val stringToSign = listOf(
            ALGORITHM,
            amzDate,
            credentialScope,
            canonicalRequest.sha256Hex(),
        ).joinToString("\n")
        val signingKey = signingKey(
            secretAccessKey = authContext.secretAccessKey,
            dateStamp = dateStamp,
            region = region,
        )
        val signature = hmac(signingKey, stringToSign).toHex()
        val authorization = "$ALGORITHM Credential=${authContext.accessKeyId}/$credentialScope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"
        val requestHeaders = headers + ("Authorization" to authorization)
        val url = "https://$host$canonicalUri"
        return S3SignedRequest(url = url, headers = requestHeaders)
    }

    private fun signingKey(
        secretAccessKey: String,
        dateStamp: String,
        region: String,
    ): ByteArray {
        val dateKey = hmac("AWS4$secretAccessKey".toByteArray(StandardCharsets.UTF_8), dateStamp)
        val dateRegionKey = hmac(dateKey, region)
        val dateRegionServiceKey = hmac(dateRegionKey, "s3")
        return hmac(dateRegionServiceKey, "aws4_request")
    }

    private companion object {
        const val ALGORITHM = "AWS4-HMAC-SHA256"
        const val DEFAULT_REGION = "us-east-1"
        const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
        val AMZ_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
        val DATE_STAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC)
    }
}

/**
 * 创建当前 S3 目录下的子对象位置。
 *
 * @param name 子对象文件名。
 * @return 子对象位置。
 */
private fun S3Location.childObject(name: String): S3Location {
    return copy(prefix = directoryPrefix + name)
}

/**
 * 将 S3 对象位置转换为文件条目。
 *
 * @param name 文件名。
 * @param parentLocation 父目录位置。
 * @param sizeBytes 已写入字节数。
 * @return S3 文件条目。
 */
private fun S3Location.toObjectVFile(
    name: String,
    parentLocation: String,
    sizeBytes: Long?,
): VFile {
    val location = toLocation(objectKey, directory = false)
    return VFile(
        id = location,
        name = name,
        location = location,
        parentLocation = parentLocation,
        kind = VFileKind.FILE,
        sizeBytes = sizeBytes,
        modifiedAtEpochMillis = null,
        hidden = name.startsWith("."),
        capabilities = setOf(
            VFileCapability.READ_METADATA,
            VFileCapability.READ_CONTENT,
            VFileCapability.WRITE_CONTENT,
            VFileCapability.DELETE,
            VFileCapability.RENAME,
        ),
    )
}

/**
 * 将 S3 前缀位置转换为目录条目。
 *
 * @param name 目录名。
 * @param parentLocation 父目录位置。
 * @return S3 目录条目。
 */
private fun S3Location.toDirectoryVFile(
    name: String,
    parentLocation: String,
): VFile {
    return VFile(
        id = directoryLocation,
        name = name,
        location = directoryLocation,
        parentLocation = parentLocation,
        kind = VFileKind.DIRECTORY,
        sizeBytes = null,
        modifiedAtEpochMillis = null,
        hidden = name.startsWith("."),
        capabilities = setOf(
            VFileCapability.READ_METADATA,
            VFileCapability.LIST_CHILDREN,
            VFileCapability.DELETE,
            VFileCapability.RENAME,
        ),
    )
}

/**
 * 为保留两者策略生成副本文件名。
 *
 * @param index 副本序号。
 * @return 带副本后缀的文件名。
 */
private fun String.withCopySuffix(index: Int): String {
    val dotIndex = lastIndexOf('.').takeIf { dot -> dot > 0 }
    return if (dotIndex == null) {
        "$this ($index)"
    } else {
        substring(0, dotIndex) + " ($index)" + substring(dotIndex)
    }
}

data class S3ListPage(
    val entries: List<VFile>,
    val nextContinuationToken: String?,
)

class S3ListBucketResultParser {
    fun parse(
        xml: String,
        location: S3Location,
    ): S3ListPage {
        val document = documentBuilderFactory().newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        val entries = buildList {
            val commonPrefixes = document.getElementsByTagNameNS("*", "CommonPrefixes")
            for (index in 0 until commonPrefixes.length) {
                val element = commonPrefixes.item(index) as? Element ?: continue
                val prefix = element.childText("Prefix") ?: continue
                val name = prefix.trimEnd('/').substringAfterLast('/')
                if (name.isBlank()) continue
                add(
                    VFile(
                        id = location.toLocation(prefix, directory = true),
                        name = name,
                        location = location.toLocation(prefix, directory = true),
                        parentLocation = location.directoryLocation,
                        kind = VFileKind.DIRECTORY,
                        sizeBytes = null,
                        modifiedAtEpochMillis = null,
                        hidden = name.startsWith("."),
                        capabilities = setOf(
                            VFileCapability.READ_METADATA,
                            VFileCapability.LIST_CHILDREN,
                            VFileCapability.DELETE,
                            VFileCapability.RENAME,
                        ),
                    )
                )
            }

            val contents = document.getElementsByTagNameNS("*", "Contents")
            for (index in 0 until contents.length) {
                val element = contents.item(index) as? Element ?: continue
                val key = element.childText("Key") ?: continue
                val relative = key.removePrefix(location.directoryPrefix)
                if (relative.isBlank() || relative.contains('/')) continue
                val name = relative.substringAfterLast('/')
                add(
                    VFile(
                        id = location.toLocation(key, directory = false),
                        name = name,
                        location = location.toLocation(key, directory = false),
                        parentLocation = location.directoryLocation,
                        kind = VFileKind.FILE,
                        sizeBytes = element.childText("Size")?.toLongOrNull(),
                        modifiedAtEpochMillis = element.childText("LastModified")?.toInstantMillisOrNull(),
                        hidden = name.startsWith("."),
                        capabilities = setOf(
                            VFileCapability.READ_METADATA,
                            VFileCapability.READ_CONTENT,
                            VFileCapability.WRITE_CONTENT,
                            VFileCapability.DELETE,
                            VFileCapability.RENAME,
                        ),
                    )
                )
            }
        }.sortedWith(
            compareByDescending<VFile> { entry -> entry.kind == VFileKind.DIRECTORY }
                .thenBy { entry -> entry.name.lowercase() }
        )
        val isTruncated = document.documentElement.childText("IsTruncated") == "true"
        val nextToken = document.documentElement.childText("NextContinuationToken")
            ?.takeIf { token -> token.isNotBlank() && isTruncated }
        return S3ListPage(entries = entries, nextContinuationToken = nextToken)
    }
}

class S3ErrorParser {
    fun parseCode(xml: String): String? {
        return runCatching {
            documentBuilderFactory().newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))
                .documentElement
                .childText("Code")
        }.getOrNull()
    }
}

private fun documentBuilderFactory(): DocumentBuilderFactory {
    return DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
}

private fun Element.childText(localName: String): String? {
    val nodes = getElementsByTagNameNS("*", localName)
    return nodes.item(0)?.textContent?.trim()
}

private fun Map<String, String>.toCanonicalQuery(): String {
    return entries
        .sortedWith(compareBy<Map.Entry<String, String>> { entry -> entry.key }.thenBy { entry -> entry.value })
        .joinToString("&") { (key, value) -> "${awsEncode(key)}=${awsEncode(value)}" }
}

private fun awsEncode(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20")
        .replace("*", "%2A")
        .replace("%7E", "~")
}

private fun awsEncodePath(value: String): String {
    return value.split('/').joinToString("/") { segment -> awsEncode(segment) }
}

private fun hmac(
    key: ByteArray,
    data: String,
): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
}

private fun String.sha256Hex(): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .toHex()
}

private fun ByteArray.toHex(): String {
    return joinToString("") { byte -> "%02x".format(byte) }
}

private fun String.withTrailingSlash(): String {
    return if (endsWith('/')) this else "$this/"
}

private fun String.encodeSpaces(): String {
    return replace(" ", "%20")
}

private fun String.toInstantMillisOrNull(): Long? {
    return runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
}
