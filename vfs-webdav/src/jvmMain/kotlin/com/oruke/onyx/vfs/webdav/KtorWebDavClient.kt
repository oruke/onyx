package com.oruke.onyx.vfs.webdav

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsContentSource
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.encodeVfsSpaces
import com.oruke.onyx.vfs.api.withVfsCopySuffix
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
 * 基于 Ktor CIO 的 WebDAV 客户端实现。
 *
 * @property httpClient 负责发起 WebDAV HTTP 请求的客户端。
 * @property parser `PROPFIND` 多状态响应解析器。
 */
class KtorWebDavClient(
    private val httpClient: HttpClient = HttpClient(CIO),
    private val parser: WebDavMultiStatusParser = WebDavMultiStatusParser(),
) : WebDavClient {
    override suspend fun testConnection(
        location: String,
        authContext: VfsAuthContext,
    ): Unit = withContext(Dispatchers.IO) {
        val httpUrl = location.toHttpWebDavUrl()
        try {
            val response = httpClient.request(httpUrl) {
                method = HttpMethod("PROPFIND")
                header("Depth", "0")
                contentType(ContentType.Application.Xml)
                applyAuth(authContext, location)
                setBody(PROPFIND_BODY)
            }
            response.requireSuccess(location, authContext)
        } catch (failure: VfsProviderException) {
            throw failure
        } catch (failure: SSLException) {
            throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.WEBDAV,
                    location = location,
                    reason = failure.message,
                )
            )
        } catch (failure: IOException) {
            throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.WEBDAV,
                    location = location,
                    reason = failure.message,
                )
            )
        }
    }

    override suspend fun list(
        location: String,
        authContext: VfsAuthContext,
    ): List<VFile> = withContext(Dispatchers.IO) {
        val httpUrl = location.toHttpWebDavUrl()
        try {
            val response = httpClient.request(httpUrl) {
                method = HttpMethod("PROPFIND")
                header("Depth", "1")
                contentType(ContentType.Application.Xml)
                applyAuth(authContext, location)
                setBody(PROPFIND_BODY)
            }
            response.requireSuccess(location, authContext)
            parser.parse(
                xml = response.bodyAsText(),
                requestLocation = location,
                requestHttpUrl = httpUrl,
            )
        } catch (failure: VfsProviderException) {
            throw failure
        } catch (failure: SSLException) {
            throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.WEBDAV,
                    location = location,
                    reason = failure.message,
                )
            )
        } catch (failure: IOException) {
            throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.WEBDAV,
                    location = location,
                    reason = failure.message,
                )
            )
        }
    }

    override suspend fun delete(
        entries: List<VFile>,
        authContext: VfsAuthContext,
    ): Unit = withContext(Dispatchers.IO) {
        entries.forEach { entry ->
            try {
                val response = httpClient.request(entry.location.toHttpWebDavUrl()) {
                    method = HttpMethod.Delete
                    applyAuth(authContext, entry.location)
                }
                response.requireSuccess(entry.location, authContext, acceptedStatuses = MutationSuccessStatuses)
            } catch (failure: VfsProviderException) {
                throw failure
            } catch (failure: SSLException) {
                throw failure.toNetworkFailure(entry.location)
            } catch (failure: IOException) {
                throw failure.toNetworkFailure(entry.location)
            }
        }
    }

    override suspend fun copy(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
        authContext: VfsAuthContext,
    ): Unit = transfer(
        entries = entries,
        targetDirectoryLocation = targetDirectoryLocation,
        conflictStrategy = conflictStrategy,
        authContext = authContext,
        method = HttpMethod("COPY"),
    )

    override suspend fun move(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
        authContext: VfsAuthContext,
    ): Unit = transfer(
        entries = entries,
        targetDirectoryLocation = targetDirectoryLocation,
        conflictStrategy = conflictStrategy,
        authContext = authContext,
        method = HttpMethod("MOVE"),
    )

    override suspend fun rename(
        entry: VFile,
        targetName: String,
        authContext: VfsAuthContext,
    ): VFile = withContext(Dispatchers.IO) {
        validateTargetName(targetName)
        val targetLocation = webDavChildLocation(
            parentLocation = entry.parentLocation ?: entry.location.parentWebDavLocation(),
            name = targetName.trim(),
            directory = entry.kind == VFileKind.DIRECTORY,
        )
        try {
            val response = httpClient.request(entry.location.toHttpWebDavUrl()) {
                method = HttpMethod("MOVE")
                header(HttpHeaders.Destination, targetLocation.toHttpWebDavUrl())
                header("Overwrite", "F")
                applyAuth(authContext, entry.location)
            }
            response.requireSuccess(entry.location, authContext, acceptedStatuses = MutationSuccessStatuses)
            entry.copy(
                id = targetLocation,
                name = targetName.trim(),
                location = targetLocation,
                parentLocation = entry.parentLocation,
            )
        } catch (failure: VfsProviderException) {
            throw failure
        } catch (failure: SSLException) {
            throw failure.toNetworkFailure(entry.location)
        } catch (failure: IOException) {
            throw failure.toNetworkFailure(entry.location)
        }
    }

    override suspend fun createFile(
        parentLocation: String,
        name: String,
        authContext: VfsAuthContext,
    ): VFile = withContext(Dispatchers.IO) {
        validateTargetName(name)
        val targetLocation = webDavChildLocation(parentLocation, name.trim(), directory = false)
        try {
            if (resourceExists(targetLocation, authContext)) {
                throw VfsProviderException(VfsProviderError.AlreadyExists(VfsProtocol.WEBDAV, targetLocation))
            }
            val response = httpClient.request(targetLocation.toHttpWebDavUrl()) {
                method = HttpMethod.Put
                applyAuth(authContext, targetLocation)
                setBody(ByteArray(0))
            }
            response.requireSuccess(targetLocation, authContext, acceptedStatuses = MutationSuccessStatuses)
            targetLocation.toWebDavVFile(name = name.trim(), parentLocation = parentLocation, directory = false)
        } catch (failure: VfsProviderException) {
            throw failure
        } catch (failure: SSLException) {
            throw failure.toNetworkFailure(targetLocation)
        } catch (failure: IOException) {
            throw failure.toNetworkFailure(targetLocation)
        }
    }

    override suspend fun createDirectory(
        parentLocation: String,
        name: String,
        authContext: VfsAuthContext,
    ): VFile = withContext(Dispatchers.IO) {
        validateTargetName(name)
        val targetLocation = webDavChildLocation(parentLocation, name.trim(), directory = true)
        try {
            if (resourceExists(targetLocation, authContext)) {
                throw VfsProviderException(VfsProviderError.AlreadyExists(VfsProtocol.WEBDAV, targetLocation))
            }
            val response = httpClient.request(targetLocation.toHttpWebDavUrl()) {
                method = HttpMethod("MKCOL")
                applyAuth(authContext, targetLocation)
            }
            response.requireSuccess(targetLocation, authContext, acceptedStatuses = MutationSuccessStatuses)
            targetLocation.toWebDavVFile(name = name.trim(), parentLocation = parentLocation, directory = true)
        } catch (failure: VfsProviderException) {
            throw failure
        } catch (failure: SSLException) {
            throw failure.toNetworkFailure(targetLocation)
        } catch (failure: IOException) {
            throw failure.toNetworkFailure(targetLocation)
        }
    }

    override suspend fun readFile(
        entry: VFile,
        authContext: VfsAuthContext,
    ): VfsContentSource = withContext(Dispatchers.IO) {
        if (entry.kind == VFileKind.DIRECTORY) {
            throw VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.WEBDAV,
                    location = entry.location,
                    capability = VfsProviderCapability.READ_CONTENT,
                )
            )
        }
        VfsContentSource(
            name = entry.name,
            sizeBytes = entry.sizeBytes,
            chunks = flow {
                try {
                    val response = httpClient.request(entry.location.toHttpWebDavUrl()) {
                        method = HttpMethod.Get
                        applyAuth(authContext, entry.location)
                    }
                    response.requireSuccess(entry.location, authContext, acceptedStatuses = DownloadSuccessStatuses)
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
                    throw failure.toNetworkFailure(entry.location)
                } catch (failure: IOException) {
                    throw failure.toNetworkFailure(entry.location)
                }
            }.flowOn(Dispatchers.IO),
        )
    }

    override suspend fun writeFile(
        parentLocation: String,
        name: String,
        chunks: Flow<ByteArray>,
        conflictStrategy: TransferConflictStrategy,
        authContext: VfsAuthContext,
    ): VFile? = withContext(Dispatchers.IO) {
        validateTargetName(name)
        val targetLocation = resolveContentTargetLocation(
            parentLocation = parentLocation,
            name = name.trim(),
            conflictStrategy = conflictStrategy,
            authContext = authContext,
        ) ?: return@withContext null
        try {
            val response = httpClient.request(targetLocation.toHttpWebDavUrl()) {
                method = HttpMethod.Put
                applyAuth(authContext, targetLocation)
                setBody(
                    object : OutgoingContent.WriteChannelContent() {
                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            chunks.collect { chunk -> channel.writeFully(chunk) }
                        }
                    }
                )
            }
            response.requireSuccess(targetLocation, authContext, acceptedStatuses = MutationSuccessStatuses)
            targetLocation.toWebDavVFile(name = targetLocation.fileNameFromWebDavLocation(), parentLocation = parentLocation, directory = false)
        } catch (failure: VfsProviderException) {
            throw failure
        } catch (failure: SSLException) {
            throw failure.toNetworkFailure(targetLocation)
        } catch (failure: IOException) {
            throw failure.toNetworkFailure(targetLocation)
        }
    }

    private suspend fun transfer(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
        authContext: VfsAuthContext,
        method: HttpMethod,
    ): Unit = withContext(Dispatchers.IO) {
        val normalizedTargetDirectory = targetDirectoryLocation.withVfsTrailingSlash()
        if (!resourceExists(normalizedTargetDirectory, authContext)) {
            throw VfsProviderException(VfsProviderError.NotFound(VfsProtocol.WEBDAV, normalizedTargetDirectory))
        }
        entries.forEach { entry ->
            val targetLocation = resolveTransferTargetLocation(
                targetDirectoryLocation = normalizedTargetDirectory,
                entry = entry,
                conflictStrategy = conflictStrategy,
                authContext = authContext,
            ) ?: return@forEach
            try {
                val response = httpClient.request(entry.location.toHttpWebDavUrl()) {
                    this.method = method
                    header(HttpHeaders.Destination, targetLocation.toHttpWebDavUrl())
                    header("Overwrite", if (conflictStrategy == TransferConflictStrategy.OVERWRITE) "T" else "F")
                    applyAuth(authContext, entry.location)
                }
                response.requireSuccess(targetLocation, authContext, acceptedStatuses = MutationSuccessStatuses)
            } catch (failure: VfsProviderException) {
                throw failure
            } catch (failure: SSLException) {
                throw failure.toNetworkFailure(entry.location)
            } catch (failure: IOException) {
                throw failure.toNetworkFailure(entry.location)
            }
        }
    }

    private suspend fun resolveTransferTargetLocation(
        targetDirectoryLocation: String,
        entry: VFile,
        conflictStrategy: TransferConflictStrategy,
        authContext: VfsAuthContext,
    ): String? {
        val isDirectory = entry.kind == VFileKind.DIRECTORY
        val targetLocation = webDavChildLocation(targetDirectoryLocation, entry.name, directory = isDirectory)
        return when (conflictStrategy) {
            TransferConflictStrategy.OVERWRITE -> targetLocation
            TransferConflictStrategy.SKIP -> {
                if (resourceExists(targetLocation, authContext)) null else targetLocation
            }

            TransferConflictStrategy.KEEP_BOTH -> {
                var candidateName = entry.name
                repeat(MAX_KEEP_BOTH_ATTEMPTS) { index ->
                    val candidateLocation = webDavChildLocation(targetDirectoryLocation, candidateName, directory = isDirectory)
                    if (!resourceExists(candidateLocation, authContext)) {
                        return candidateLocation
                    }
                    candidateName = entry.name.withVfsCopySuffix(index + 1) + if (isDirectory) "/" else ""
                }
                throw VfsProviderException(VfsProviderError.AlreadyExists(VfsProtocol.WEBDAV, targetLocation))
            }
        }
    }

    private suspend fun resolveContentTargetLocation(
        parentLocation: String,
        name: String,
        conflictStrategy: TransferConflictStrategy,
        authContext: VfsAuthContext,
    ): String? {
        val targetLocation = webDavChildLocation(parentLocation, name, directory = false)
        return when (conflictStrategy) {
            TransferConflictStrategy.OVERWRITE -> targetLocation
            TransferConflictStrategy.SKIP -> {
                if (resourceExists(targetLocation, authContext)) null else targetLocation
            }

            TransferConflictStrategy.KEEP_BOTH -> {
                var candidateName = name
                repeat(MAX_KEEP_BOTH_ATTEMPTS) { index ->
                    val candidateLocation = webDavChildLocation(parentLocation, candidateName, directory = false)
                    if (!resourceExists(candidateLocation, authContext)) {
                        return candidateLocation
                    }
                    candidateName = name.withVfsCopySuffix(index + 1)
                }
                throw VfsProviderException(VfsProviderError.AlreadyExists(VfsProtocol.WEBDAV, targetLocation))
            }
        }
    }

    private suspend fun resourceExists(
        location: String,
        authContext: VfsAuthContext,
    ): Boolean {
        try {
            val response = httpClient.request(location.toHttpWebDavUrl()) {
                method = HttpMethod("PROPFIND")
                header("Depth", "0")
                contentType(ContentType.Application.Xml)
                applyAuth(authContext, location)
                setBody(PROPFIND_BODY)
            }
            return when (response.status.value) {
                200, 207 -> true
                404 -> false
                else -> {
                    response.requireSuccess(location, authContext)
                    true
                }
            }
        } catch (failure: VfsProviderException) {
            throw failure
        } catch (failure: SSLException) {
            throw failure.toNetworkFailure(location)
        } catch (failure: IOException) {
            throw failure.toNetworkFailure(location)
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyAuth(
        authContext: VfsAuthContext,
        location: String,
    ) {
        when (authContext) {
            VfsAuthContext.None -> Unit
            is VfsAuthContext.UsernamePassword -> {
                val raw = "${authContext.username}:${authContext.password}"
                val encoded = Base64.getEncoder().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
                header(HttpHeaders.Authorization, "Basic $encoded")
            }

            is VfsAuthContext.BearerToken -> header(HttpHeaders.Authorization, "Bearer ${authContext.token}")
            else -> throw VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.WEBDAV,
                    location = location,
                    capability = null,
                )
            )
        }
    }

    private fun HttpResponse.requireSuccess(
        location: String,
        authContext: VfsAuthContext,
        acceptedStatuses: Set<Int> = ListSuccessStatuses,
    ) {
        when {
            status.value in acceptedStatuses -> Unit
            status.value == 401 -> throw VfsProviderException(
                if (authContext == VfsAuthContext.None) {
                    VfsProviderError.AuthenticationRequired(VfsProtocol.WEBDAV, location)
                } else {
                    VfsProviderError.AuthenticationRejected(VfsProtocol.WEBDAV, location)
                }
            )

            status.value == 403 -> throw VfsProviderException(VfsProviderError.PermissionDenied(VfsProtocol.WEBDAV, location))
            status.value == 404 -> throw VfsProviderException(VfsProviderError.NotFound(VfsProtocol.WEBDAV, location))
            status.value == 412 -> throw VfsProviderException(VfsProviderError.AlreadyExists(VfsProtocol.WEBDAV, location))
            status.value in 500..599 -> throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.WEBDAV,
                    location = location,
                    reason = status.description,
                )
            )

            else -> throw VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.WEBDAV,
                    location = location,
                    capability = null,
                )
            )
        }
    }

    private fun Throwable.toNetworkFailure(location: String): VfsProviderException {
        return VfsProviderException(
            VfsProviderError.NetworkFailure(
                protocol = VfsProtocol.WEBDAV,
                location = location,
                reason = message,
            )
        )
    }

    private fun validateTargetName(targetName: String) {
        val sanitized = targetName.trim()
        if (sanitized.isBlank() || '/' in sanitized || '\\' in sanitized) {
            throw VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.WEBDAV,
                    capability = null,
                )
            )
        }
    }

    private fun webDavChildLocation(
        parentLocation: String,
        name: String,
        directory: Boolean,
    ): String {
        val parentUri = URI(parentLocation.withVfsTrailingSlash().encodeVfsSpaces())
        val childPath = parentUri.path.withVfsTrailingSlash() + name + (if (directory) "/" else "")
        return URI(parentUri.scheme, null, parentUri.host, parentUri.port, childPath, null, null).toASCIIString()
    }

    private fun String.fileNameFromWebDavLocation(): String {
        return URI(encodeVfsSpaces()).path.trimEnd('/').substringAfterLast('/').urlDecode()
    }

    private fun String.parentWebDavLocation(): String {
        val uri = URI(encodeVfsSpaces())
        val path = uri.path.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "").ifBlank { "/" }
        val parentPath = if (path == "/") path else path.withVfsTrailingSlash()
        return URI(uri.scheme, null, uri.host, uri.port, parentPath, null, null).toASCIIString()
    }

    private fun String.toWebDavVFile(
        name: String,
        parentLocation: String,
        directory: Boolean,
    ): VFile {
        return VFile(
            id = this,
            name = name,
            location = this,
            parentLocation = parentLocation.withVfsTrailingSlash(),
            kind = if (directory) VFileKind.DIRECTORY else VFileKind.FILE,
            sizeBytes = if (directory) null else 0L,
            modifiedAtEpochMillis = null,
            hidden = name.startsWith("."),
            capabilities = buildSet {
                add(VFileCapability.READ_METADATA)
                add(VFileCapability.RENAME)
                add(VFileCapability.DELETE)
                if (directory) {
                    add(VFileCapability.LIST_CHILDREN)
                } else {
                    add(VFileCapability.READ_CONTENT)
                    add(VFileCapability.WRITE_CONTENT)
                }
            },
        )
    }

    private companion object {
        val ListSuccessStatuses = setOf(200, 207)
        val MutationSuccessStatuses = setOf(200, 201, 204)
        val DownloadSuccessStatuses = setOf(200, 206)
        const val CONTENT_BUFFER_SIZE = 64 * 1024
        const val MAX_KEEP_BOTH_ATTEMPTS = 10_000
        val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:displayname/>
                <d:resourcetype/>
                <d:getcontentlength/>
                <d:getlastmodified/>
              </d:prop>
            </d:propfind>
        """.trimIndent()
    }
}
