package com.oruke.onyx.vfs.s3

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.RoutableFileCommandService
import com.oruke.onyx.vfs.api.RoutableVfsContentService
import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsConnectionTestRequest
import com.oruke.onyx.vfs.api.VfsConnectionTestResult
import com.oruke.onyx.vfs.api.VfsConnectionTester
import com.oruke.onyx.vfs.api.VfsContentSource
import com.oruke.onyx.vfs.api.VfsDirectoryPage
import com.oruke.onyx.vfs.api.VfsDirectoryPageRequest
import com.oruke.onyx.vfs.api.PagedVfsProvider
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProviderNotFoundException
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.toVfsConnectionTestResult
import com.oruke.onyx.vfs.api.withVfsTrailingSlash
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * S3 协议的 VFS Provider，负责把统一文件操作转换为对象存储请求。
 *
 * @property authRepository S3 认证上下文来源。
 * @property client 实际执行 S3 请求的客户端。
 */
class S3VfsProvider(
    private val authRepository: S3AuthRepository = S3AuthRepository.None,
    private val client: S3Client = KtorS3Client(),
) : PagedVfsProvider,
    RoutableFileCommandService,
    RoutableVfsContentService,
    VfsConnectionTester,
    S3TransferGateway {
    /** S3 递归复制与移动服务。 */
    private val transferService = S3TransferService(this)

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

    /**
     * 分页列出 S3 目录，避免大 bucket 前缀一次性读取全部对象。
     *
     * @param request VFS 目录分页请求。
     * @return 当前页条目和下一页游标。
     */
    override suspend fun listPage(request: VfsDirectoryPageRequest): Result<VfsDirectoryPage> {
        if (!supports(request.location)) {
            return Result.failure(VfsProviderNotFoundException(request.location))
        }
        return when (val authContext = authRepository.authContext(request.location)) {
            VfsAuthContext.None -> Result.failure(
                VfsProviderException(VfsProviderError.AuthenticationRequired(VfsProtocol.S3, request.location))
            )

            is VfsAuthContext.AwsCredentials -> runCatching {
                val page = client.listPage(
                    location = S3Location.parse(request.location),
                    pageSize = request.pageSize,
                    pageToken = request.pageToken,
                    authContext = authContext,
                )
                VfsDirectoryPage(
                    entries = page.entries,
                    nextPageToken = page.nextContinuationToken,
                )
            }

            else -> Result.failure(
                VfsProviderException(
                    VfsProviderError.UnsupportedOperation(
                        protocol = VfsProtocol.S3,
                        location = request.location,
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
        return runCatching { S3Location.parse(request.location) }.fold(
            onSuccess = { location -> testParsedConnection(request, location) },
            onFailure = { failure ->
                failure.toVfsConnectionTestResult(
                    protocol = VfsProtocol.S3,
                    location = request.location,
                )
            },
        )
    }

    /**
     * 使用已解析 S3 位置执行连接测试。
     *
     * @param request 原始连接测试请求。
     * @param location 已解析 S3 位置。
     * @return 连接测试结果。
     */
    private suspend fun testParsedConnection(
        request: VfsConnectionTestRequest,
        location: S3Location,
    ): VfsConnectionTestResult {
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
        val validationFailure = when {
            !supports(entry.location) -> VfsProviderNotFoundException(entry.location)
            entry.kind == VFileKind.DIRECTORY -> VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.S3,
                    location = entry.location,
                    capability = VfsProviderCapability.READ_CONTENT,
                )
            )
            else -> null
        }
        return if (validationFailure != null) {
            Result.failure(validationFailure)
        } else {
            when (val authContext = authRepository.authContext(entry.location)) {
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
        return transferService.transferObjects(
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
        return transferService.transferObjects(
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
        return if (unsupported != null) {
            Result.failure(VfsProviderNotFoundException(unsupported.location))
        } else {
            runCatching {
                entries.forEach { entry ->
                    deleteEntry(entry)
                }
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
                transferService.copyDirectory(
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
                val target = parent.copy(prefix = parent.directoryPrefix + targetName.withVfsTrailingSlash())
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
    }
}
