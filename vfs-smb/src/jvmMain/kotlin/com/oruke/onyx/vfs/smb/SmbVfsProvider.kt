package com.oruke.onyx.vfs.smb

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.RoutableFileCommandService
import com.oruke.onyx.vfs.api.RoutableVfsContentService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsConnectionTestRequest
import com.oruke.onyx.vfs.api.VfsConnectionTestResult
import com.oruke.onyx.vfs.api.VfsConnectionTester
import com.oruke.onyx.vfs.api.VfsContentSource
import com.oruke.onyx.vfs.api.VfsProvider
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProviderNotFoundException
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.toVfsConnectionTestResult
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.net.MalformedURLException
import java.net.UnknownHostException
import java.util.Properties

/**
 * SMB 协议的 VFS Provider，负责把统一文件操作路由到 SMB 客户端。
 *
 * @property authRepository SMB 认证上下文来源。
 * @property client 实际执行 SMB 请求的客户端。
 */
class SmbVfsProvider(
    private val authRepository: SmbAuthRepository = SmbAuthRepository.None,
    private val client: SmbClient = JcifsSmbClient(),
) : VfsProvider, RoutableFileCommandService, RoutableVfsContentService, VfsConnectionTester {
    override val protocol: VfsProtocol = VfsProtocol.SMB

    override val capabilities: Set<VfsProviderCapability> = setOf(
        VfsProviderCapability.CREATE_FILE,
        VfsProviderCapability.CREATE_DIRECTORY,
        VfsProviderCapability.READ_CONTENT,
        VfsProviderCapability.WRITE_CONTENT,
        VfsProviderCapability.RENAME,
        VfsProviderCapability.DELETE,
        VfsProviderCapability.COPY,
        VfsProviderCapability.MOVE,
    )

    override fun supports(location: String): Boolean {
        return location.startsWith(SMB_SCHEME, ignoreCase = true)
    }

    override suspend fun list(location: String): Result<List<VFile>> {
        if (!supports(location)) {
            return Result.failure(VfsProviderNotFoundException(location))
        }
        return runCatching {
            client.list(
                location = directoryLocation(location),
                authContext = authRepository.authContext(location),
            )
        }
    }

    override suspend fun testConnection(request: VfsConnectionTestRequest): VfsConnectionTestResult {
        val testLocation = directoryLocation(request.location)
        if (request.protocol != VfsProtocol.SMB || !supports(request.location)) {
            return VfsConnectionTestResult.Failed(
                protocol = VfsProtocol.SMB,
                location = request.location,
                error = VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.SMB,
                    location = request.location,
                    capability = null,
                )
            )
        }
        val authContext = request.authContext.takeIf { it != VfsAuthContext.None }
            ?: authRepository.authContext(request.location)
        return runCatching {
            client.testConnection(
                location = testLocation,
                authContext = authContext,
            )
            VfsConnectionTestResult.Reachable(
                protocol = VfsProtocol.SMB,
                location = testLocation,
                capabilities = capabilities,
            )
        }.getOrElse { failure ->
            failure.toVfsConnectionTestResult(
                protocol = VfsProtocol.SMB,
                location = testLocation,
            )
        }
    }

    override suspend fun copy(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Unit> {
        return runTransferCommand(
            entries = entries,
            targetDirectoryLocation = targetDirectoryLocation,
            capability = VfsProviderCapability.COPY,
        ) { authContext ->
            client.copy(entries, directoryLocation(targetDirectoryLocation), conflictStrategy, authContext)
        }
    }

    override suspend fun move(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Unit> {
        return runTransferCommand(
            entries = entries,
            targetDirectoryLocation = targetDirectoryLocation,
            capability = VfsProviderCapability.MOVE,
        ) { authContext ->
            client.move(entries, directoryLocation(targetDirectoryLocation), conflictStrategy, authContext)
        }
    }

    override suspend fun delete(entries: List<VFile>): Result<Unit> {
        if (entries.isEmpty()) return Result.success(Unit)
        val unsupported = entries.firstOrNull { entry -> !supports(entry.location) }
        if (unsupported != null) {
            return Result.failure(VfsProviderNotFoundException(unsupported.location))
        }
        return runCatching {
            entries
                .groupBy { entry -> authRepository.authContext(entry.location) }
                .forEach { (authContext, groupedEntries) ->
                    client.delete(groupedEntries, authContext)
                }
        }
    }

    override suspend fun rename(
        entry: VFile,
        targetName: String,
    ): Result<VFile> {
        if (!supports(entry.location)) {
            return Result.failure(VfsProviderNotFoundException(entry.location))
        }
        return runCatching {
            client.rename(
                entry = entry,
                targetName = targetName,
                authContext = authRepository.authContext(entry.location),
            )
        }
    }

    override suspend fun createFile(
        parentLocation: String,
        name: String,
    ): Result<VFile> {
        return runCreateCommand(parentLocation) { authContext ->
            client.createFile(directoryLocation(parentLocation), name, authContext)
        }
    }

    override suspend fun createDirectory(
        parentLocation: String,
        name: String,
    ): Result<VFile> {
        return runCreateCommand(parentLocation) { authContext ->
            client.createDirectory(directoryLocation(parentLocation), name, authContext)
        }
    }

    override suspend fun readFile(entry: VFile): Result<VfsContentSource> {
        if (!supports(entry.location)) {
            return Result.failure(VfsProviderNotFoundException(entry.location))
        }
        return runCatching {
            client.readFile(
                entry = entry,
                authContext = authRepository.authContext(entry.location),
            )
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
        return runCatching {
            client.writeFile(
                parentLocation = directoryLocation(parentLocation),
                name = name,
                chunks = chunks,
                conflictStrategy = conflictStrategy,
                authContext = authRepository.authContext(parentLocation),
            )
        }
    }

    private suspend fun runTransferCommand(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        capability: VfsProviderCapability,
        block: suspend (VfsAuthContext) -> Unit,
    ): Result<Unit> {
        if (entries.isEmpty()) return Result.success(Unit)
        if (!supports(targetDirectoryLocation)) {
            return Result.failure(VfsProviderNotFoundException(targetDirectoryLocation))
        }
        val unsupported = entries.firstOrNull { entry -> !supports(entry.location) }
        if (unsupported != null) {
            return Result.failure(VfsProviderNotFoundException(unsupported.location))
        }

        val targetAuthContext = authRepository.authContext(targetDirectoryLocation)
        val hasDifferentSourceAuth = entries.any { entry -> authRepository.authContext(entry.location) != targetAuthContext }
        if (hasDifferentSourceAuth) {
            return Result.failure(
                VfsProviderException(
                    VfsProviderError.UnsupportedOperation(
                        protocol = VfsProtocol.SMB,
                        location = targetDirectoryLocation,
                        capability = capability,
                    )
                )
            )
        }

        return runCatching {
            block(targetAuthContext)
        }
    }

    private suspend fun runCreateCommand(
        parentLocation: String,
        block: suspend (VfsAuthContext) -> VFile,
    ): Result<VFile> {
        if (!supports(parentLocation)) {
            return Result.failure(VfsProviderNotFoundException(parentLocation))
        }
        return runCatching {
            block(authRepository.authContext(parentLocation))
        }
    }

    private fun directoryLocation(location: String): String {
        return if (location.endsWith('/')) location else "$location/"
    }

    private companion object {
        const val SMB_SCHEME = "smb://"
    }
}
