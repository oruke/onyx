package com.oruke.onyx.vfs.webdav

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
import com.oruke.onyx.vfs.api.encodeVfsSpaces
import com.oruke.onyx.vfs.api.toVfsConnectionTestResult
import com.oruke.onyx.vfs.api.withVfsTrailingSlash
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
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
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.net.ssl.SSLException
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WebDAV 协议的 VFS Provider，负责把统一文件操作转换为 WebDAV 请求。
 *
 * @property authRepository WebDAV 认证上下文来源。
 * @property client 实际执行 WebDAV 请求的客户端。
 */
class WebDavVfsProvider(
    private val authRepository: WebDavAuthRepository = WebDavAuthRepository.None,
    private val client: WebDavClient = KtorWebDavClient(),
) : VfsProvider, RoutableFileCommandService, RoutableVfsContentService, VfsConnectionTester {
    override val protocol: VfsProtocol = VfsProtocol.WEBDAV

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
        val scheme = location.substringBefore("://", missingDelimiterValue = "")
        return scheme.equals(WEBDAV_SCHEME, ignoreCase = true) ||
            scheme.equals(WEBDAVS_SCHEME, ignoreCase = true)
    }

    override suspend fun list(location: String): Result<List<VFile>> {
        if (!supports(location)) {
            return Result.failure(VfsProviderNotFoundException(location))
        }
        val directoryLocation = location.withVfsTrailingSlash()
        return runCatching {
            client.list(
                location = directoryLocation,
                authContext = authRepository.authContext(location),
            )
        }
    }

    override suspend fun testConnection(request: VfsConnectionTestRequest): VfsConnectionTestResult {
        val testLocation = request.location.withVfsTrailingSlash()
        if (request.protocol != VfsProtocol.WEBDAV || !supports(request.location)) {
            return VfsConnectionTestResult.Failed(
                protocol = VfsProtocol.WEBDAV,
                location = request.location,
                error = VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.WEBDAV,
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
                protocol = VfsProtocol.WEBDAV,
                location = testLocation,
                capabilities = capabilities,
            )
        }.getOrElse { failure ->
            failure.toVfsConnectionTestResult(
                protocol = VfsProtocol.WEBDAV,
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
            client.copy(entries, targetDirectoryLocation.withVfsTrailingSlash(), conflictStrategy, authContext)
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
            client.move(entries, targetDirectoryLocation.withVfsTrailingSlash(), conflictStrategy, authContext)
        }
    }

    override suspend fun delete(entries: List<VFile>): Result<Unit> {
        if (entries.isEmpty()) return Result.success(Unit)
        val unsupported = entries.firstOrNull { entry -> !supports(entry.location) }
        return if (unsupported != null) {
            Result.failure(VfsProviderNotFoundException(unsupported.location))
        } else {
            runCatching {
                entries
                    .groupBy { entry -> authRepository.authContext(entry.location) }
                    .forEach { (authContext, groupedEntries) ->
                        client.delete(groupedEntries, authContext)
                    }
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
            client.rename(entry, targetName, authRepository.authContext(entry.location))
        }
    }

    override suspend fun createFile(
        parentLocation: String,
        name: String,
    ): Result<VFile> {
        return runCatching {
            client.createFile(parentLocation.withVfsTrailingSlash(), name, authRepository.authContext(parentLocation))
        }
    }

    override suspend fun createDirectory(
        parentLocation: String,
        name: String,
    ): Result<VFile> {
        return runCatching {
            client.createDirectory(
                parentLocation.withVfsTrailingSlash(),
                name,
                authRepository.authContext(parentLocation),
            )
        }
    }

    override suspend fun readFile(entry: VFile): Result<VfsContentSource> {
        if (!supports(entry.location)) {
            return Result.failure(VfsProviderNotFoundException(entry.location))
        }
        return runCatching {
            client.readFile(entry, authRepository.authContext(entry.location))
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
                parentLocation = parentLocation.withVfsTrailingSlash(),
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
        val unsupportedLocation = if (!supports(targetDirectoryLocation)) {
            targetDirectoryLocation
        } else {
            entries.firstOrNull { entry -> !supports(entry.location) }?.location
        }
        return if (unsupportedLocation != null) {
            Result.failure(VfsProviderNotFoundException(unsupportedLocation))
        } else {
            val targetEndpoint = webDavEndpointKey(targetDirectoryLocation)
            val hasDifferentEndpoint = targetEndpoint == null ||
                entries.any { entry -> webDavEndpointKey(entry.location) != targetEndpoint }
            val targetAuthContext = authRepository.authContext(targetDirectoryLocation)
            val hasDifferentSourceAuth = entries.any { entry ->
                authRepository.authContext(entry.location) != targetAuthContext
            }
            if (hasDifferentEndpoint || hasDifferentSourceAuth) {
                Result.failure(unsupported(targetDirectoryLocation, capability))
            } else {
                runCatching { block(targetAuthContext) }
            }
        }
    }

    private fun webDavEndpointKey(location: String): String? {
        return runCatching {
            val uri = URI(location.encodeVfsSpaces())
            val scheme = uri.scheme?.lowercase() ?: return@runCatching null
            val host = uri.host?.lowercase() ?: return@runCatching null
            val port = when {
                uri.port >= 0 -> uri.port
                scheme == WEBDAVS_SCHEME -> 443
                else -> 80
            }
            "$scheme://$host:$port"
        }.getOrNull()
    }

    private fun unsupported(
        location: String,
        capability: VfsProviderCapability,
    ): VfsProviderException {
        return VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.WEBDAV,
                location = location,
                capability = capability,
            )
        )
    }

    private companion object {
        const val WEBDAV_SCHEME = "webdav"
        const val WEBDAVS_SCHEME = "webdavs"
    }
}
