package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
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

interface WebDavAuthRepository {
    fun authContext(location: String): VfsAuthContext

    data object None : WebDavAuthRepository {
        override fun authContext(location: String): VfsAuthContext = VfsAuthContext.None
    }
}

class RemoteAuthStoreWebDavAuthRepository(
    private val remoteAuthStore: RemoteAuthStore,
) : WebDavAuthRepository {
    override fun authContext(location: String): VfsAuthContext {
        return remoteAuthStore.authContext(VfsProtocol.WEBDAV, location)
    }
}

class WebDavVfsProvider(
    private val authRepository: WebDavAuthRepository = WebDavAuthRepository.None,
    private val client: WebDavClient = KtorWebDavClient(),
) : VfsProvider, RoutableFileCommandService, VfsConnectionTester {
    override val protocol: VfsProtocol = VfsProtocol.WEBDAV

    override val capabilities: Set<VfsProviderCapability> = setOf(
        VfsProviderCapability.CREATE_FILE,
        VfsProviderCapability.CREATE_DIRECTORY,
        VfsProviderCapability.RENAME,
        VfsProviderCapability.DELETE,
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
        val directoryLocation = location.withTrailingSlash()
        return runCatching {
            client.list(
                location = directoryLocation,
                authContext = authRepository.authContext(location),
            )
        }
    }

    override suspend fun testConnection(request: VfsConnectionTestRequest): VfsConnectionTestResult {
        val testLocation = request.location.withTrailingSlash()
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
        return Result.failure(unsupported(targetDirectoryLocation, VfsProviderCapability.COPY))
    }

    override suspend fun move(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Unit> {
        return Result.failure(unsupported(targetDirectoryLocation, VfsProviderCapability.MOVE))
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
            client.rename(entry, targetName, authRepository.authContext(entry.location))
        }
    }

    override suspend fun createFile(
        parentLocation: String,
        name: String,
    ): Result<VFile> {
        return runCatching {
            client.createFile(parentLocation.withTrailingSlash(), name, authRepository.authContext(parentLocation))
        }
    }

    override suspend fun createDirectory(
        parentLocation: String,
        name: String,
    ): Result<VFile> {
        return runCatching {
            client.createDirectory(parentLocation.withTrailingSlash(), name, authRepository.authContext(parentLocation))
        }
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

interface WebDavClient {
    suspend fun testConnection(
        location: String,
        authContext: VfsAuthContext,
    )

    suspend fun list(
        location: String,
        authContext: VfsAuthContext,
    ): List<VFile>

    suspend fun delete(
        entries: List<VFile>,
        authContext: VfsAuthContext,
    )

    suspend fun rename(
        entry: VFile,
        targetName: String,
        authContext: VfsAuthContext,
    ): VFile

    suspend fun createFile(
        parentLocation: String,
        name: String,
        authContext: VfsAuthContext,
    ): VFile

    suspend fun createDirectory(
        parentLocation: String,
        name: String,
        authContext: VfsAuthContext,
    ): VFile
}

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
        val parentUri = URI(parentLocation.withTrailingSlash().encodeSpaces())
        val childPath = parentUri.path.withTrailingSlash() + name + if (directory) "/" else ""
        return URI(parentUri.scheme, null, parentUri.host, parentUri.port, childPath, null, null).toASCIIString()
    }

    private fun String.parentWebDavLocation(): String {
        val uri = URI(encodeSpaces())
        val path = uri.path.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "").ifBlank { "/" }
        val parentPath = if (path == "/") path else path.withTrailingSlash()
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
            parentLocation = parentLocation.withTrailingSlash(),
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
                }
            },
        )
    }

    private companion object {
        val ListSuccessStatuses = setOf(200, 207)
        val MutationSuccessStatuses = setOf(200, 201, 204)
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

class WebDavMultiStatusParser {
    fun parse(
        xml: String,
        requestLocation: String,
        requestHttpUrl: String,
    ): List<VFile> {
        val requestUri = URI(requestHttpUrl)
        val requestPath = requestUri.path.ifBlank { "/" }.withTrailingSlash()
        val requestLocationUri = URI(requestLocation.encodeSpaces())
        val parentLocation = requestLocation.withTrailingSlash()
        val document = documentBuilderFactory().newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        val responses = document.getElementsByTagNameNS("*", "response")
        return buildList {
            for (index in 0 until responses.length) {
                val response = responses.item(index) as? Element ?: continue
                val href = response.childText("href") ?: continue
                val hrefUri = requestUri.resolve(href.encodeSpaces())
                val hrefPath = hrefUri.path.ifBlank { "/" }
                if (hrefPath.withTrailingSlash() == requestPath) {
                    continue
                }
                val prop = response.successProp() ?: continue
                val directory = prop.hasCollectionType()
                val displayName = prop.childText("displayname")
                    ?.takeIf { value -> value.isNotBlank() }
                    ?: hrefPath.trimEnd('/').substringAfterLast('/').urlDecode()
                val location = hrefUri.toWebDavLocation(requestLocationUri.scheme, directory)
                add(
                    VFile(
                        id = location,
                        name = displayName.trimEnd('/'),
                        location = location,
                        parentLocation = parentLocation,
                        kind = if (directory) VFileKind.DIRECTORY else VFileKind.FILE,
                        sizeBytes = if (directory) null else prop.childText("getcontentlength")?.toLongOrNull(),
                        modifiedAtEpochMillis = prop.childText("getlastmodified")?.toEpochMillisOrNull(),
                        hidden = displayName.startsWith("."),
                        capabilities = buildSet {
                            add(VFileCapability.READ_METADATA)
                            if (directory) {
                                add(VFileCapability.LIST_CHILDREN)
                            } else {
                                add(VFileCapability.READ_CONTENT)
                            }
                        },
                    )
                )
            }
        }.sortedWith(
            compareByDescending<VFile> { entry -> entry.kind == VFileKind.DIRECTORY }
                .thenBy { entry -> entry.name.lowercase() }
        )
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

    private fun Element.successProp(): Element? {
        val propStats = getElementsByTagNameNS("*", "propstat")
        for (index in 0 until propStats.length) {
            val propStat = propStats.item(index) as? Element ?: continue
            val status = propStat.childText("status")
            if (status == null || status.contains(" 200 ")) {
                val props = propStat.getElementsByTagNameNS("*", "prop")
                return props.item(0) as? Element
            }
        }
        return null
    }

    private fun Element.hasCollectionType(): Boolean {
        val resourceTypes = getElementsByTagNameNS("*", "resourcetype")
        val resourceType = resourceTypes.item(0) as? Element ?: return false
        return resourceType.getElementsByTagNameNS("*", "collection").length > 0
    }

    private fun Element.childText(localName: String): String? {
        val nodes = getElementsByTagNameNS("*", localName)
        return nodes.item(0)?.textContent?.trim()
    }

    private fun URI.toWebDavLocation(
        sourceScheme: String,
        directory: Boolean,
    ): String {
        val scheme = if (sourceScheme.equals("webdavs", ignoreCase = true)) "webdavs" else "webdav"
        val path = path.ifBlank { "/" }.let { value ->
            if (directory) value.withTrailingSlash() else value
        }
        return URI(scheme, null, host, port, path, null, null).toASCIIString()
    }
}

private fun String.toHttpWebDavUrl(): String {
    val uri = URI(encodeSpaces())
    val httpScheme = when (uri.scheme.lowercase()) {
        "webdav" -> "http"
        "webdavs" -> "https"
        else -> throw VfsProviderNotFoundException(this)
    }
    val path = uri.path.ifBlank { "/" }
    return URI(httpScheme, uri.userInfo, uri.host, uri.port, path, uri.query, uri.fragment).toASCIIString()
}

private fun String.withTrailingSlash(): String {
    return if (endsWith('/')) this else "$this/"
}

private fun String.encodeSpaces(): String {
    return replace(" ", "%20")
}

private fun String.urlDecode(): String {
    return URLDecoder.decode(this, StandardCharsets.UTF_8)
}

private fun String.toEpochMillisOrNull(): Long? {
    return runCatching {
        ZonedDateTime.parse(this, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()
}
