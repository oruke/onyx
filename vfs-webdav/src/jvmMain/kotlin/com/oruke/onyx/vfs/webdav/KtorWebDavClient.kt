package com.oruke.onyx.vfs.webdav

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsContentSource
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsRandomAccessHandle
import com.oruke.onyx.vfs.api.VfsRandomAccessMode
import com.oruke.onyx.vfs.api.withVfsCopySuffix
import com.oruke.onyx.vfs.api.withVfsTrailingSlash
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * 基于 Ktor CIO 的 WebDAV 客户端实现。
 *
 * @param httpClient 外部注入的 HTTP 客户端；为空时按需惰性创建带超时配置的 CIO 客户端。
 * @property parser `PROPFIND` 多状态响应解析器。
 */
class KtorWebDavClient(
    httpClient: HttpClient? = null,
    private val parser: WebDavMultiStatusParser = WebDavMultiStatusParser(),
) : WebDavClient {
    /** 外部注入的客户端；为空时由本实例自行按需创建并在 close 时关闭。 */
    private val injectedHttpClient: HttpClient? = httpClient
    /** 仅当由本客户端自行惰性创建底层 CIO 客户端时才在 close 时释放。 */
    private val ownedHttpClient = lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MS
                socketTimeoutMillis = DEFAULT_SOCKET_TIMEOUT_MS
            }
        }
    }

    /** 负责发起 WebDAV HTTP 请求的客户端，首次访问远程资源时才初始化。 */
    private val httpClient: HttpClient
        get() = injectedHttpClient ?: ownedHttpClient.value

    override fun close() {
        if (injectedHttpClient == null && ownedHttpClient.isInitialized()) {
            ownedHttpClient.value.close()
        }
    }

    override suspend fun testConnection(
        location: String,
        authContext: VfsAuthContext,
    ): Unit = withContext(Dispatchers.IO) {
        val httpUrl = location.toHttpWebDavUrl()
        runWebDavRequest(location) {
            val response = httpClient.request(httpUrl) {
                method = HttpMethod("PROPFIND")
                header("Depth", "0")
                contentType(ContentType.Application.Xml)
                applyRequestTimeout(TEST_CONNECTION_TIMEOUT_MS)
                applyAuth(authContext, location)
                setBody(PROPFIND_BODY)
            }
            response.requireSuccess(location, authContext)
        }
    }

    override suspend fun list(
        location: String,
        authContext: VfsAuthContext,
    ): List<VFile> = withContext(Dispatchers.IO) {
        val httpUrl = location.toHttpWebDavUrl()
        runWebDavRequest(location) {
            val response = httpClient.request(httpUrl) {
                method = HttpMethod("PROPFIND")
                header("Depth", "1")
                contentType(ContentType.Application.Xml)
                applyRequestTimeout(METADATA_REQUEST_TIMEOUT_MS)
                applyAuth(authContext, location)
                setBody(PROPFIND_BODY)
            }
            response.requireSuccess(location, authContext)
            parser.parse(
                xml = response.bodyAsText(),
                requestLocation = location,
                requestHttpUrl = httpUrl,
            )
        }
    }

    override suspend fun delete(
        entries: List<VFile>,
        authContext: VfsAuthContext,
    ): Unit = withContext(Dispatchers.IO) {
        entries.forEach { entry ->
            runWebDavRequest(entry.location) {
                val response = httpClient.request(entry.location.toHttpWebDavUrl()) {
                    method = HttpMethod.Delete
                    applyRequestTimeout(METADATA_REQUEST_TIMEOUT_MS)
                    applyAuth(authContext, entry.location)
                }
                response.requireSuccess(entry.location, authContext, acceptedStatuses = MutationSuccessStatuses)
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
        runWebDavRequest(entry.location) {
            val response = httpClient.request(entry.location.toHttpWebDavUrl()) {
                method = HttpMethod("MOVE")
                header(HttpHeaders.Destination, targetLocation.toHttpWebDavUrl())
                header("Overwrite", "F")
                applyRequestTimeout(METADATA_REQUEST_TIMEOUT_MS)
                applyAuth(authContext, entry.location)
            }
            response.requireSuccess(entry.location, authContext, acceptedStatuses = MutationSuccessStatuses)
            entry.copy(
                id = targetLocation,
                name = targetName.trim(),
                location = targetLocation,
                parentLocation = entry.parentLocation,
            )
        }
    }

    override suspend fun createFile(
        parentLocation: String,
        name: String,
        authContext: VfsAuthContext,
    ): VFile = withContext(Dispatchers.IO) {
        validateTargetName(name)
        val targetLocation = webDavChildLocation(parentLocation, name.trim(), directory = false)
        runWebDavRequest(targetLocation) {
            if (resourceExists(targetLocation, authContext)) {
                throw VfsProviderException(VfsProviderError.AlreadyExists(VfsProtocol.WEBDAV, targetLocation))
            }
            val response = httpClient.request(targetLocation.toHttpWebDavUrl()) {
                method = HttpMethod.Put
                applyRequestTimeout(METADATA_REQUEST_TIMEOUT_MS)
                applyAuth(authContext, targetLocation)
                setBody(ByteArray(0))
            }
            response.requireSuccess(targetLocation, authContext, acceptedStatuses = MutationSuccessStatuses)
            targetLocation.toWebDavVFile(name = name.trim(), parentLocation = parentLocation, directory = false)
        }
    }

    override suspend fun createDirectory(
        parentLocation: String,
        name: String,
        authContext: VfsAuthContext,
    ): VFile = withContext(Dispatchers.IO) {
        validateTargetName(name)
        val targetLocation = webDavChildLocation(parentLocation, name.trim(), directory = true)
        runWebDavRequest(targetLocation) {
            if (resourceExists(targetLocation, authContext)) {
                throw VfsProviderException(VfsProviderError.AlreadyExists(VfsProtocol.WEBDAV, targetLocation))
            }
            val response = httpClient.request(targetLocation.toHttpWebDavUrl()) {
                method = HttpMethod("MKCOL")
                applyRequestTimeout(METADATA_REQUEST_TIMEOUT_MS)
                applyAuth(authContext, targetLocation)
            }
            response.requireSuccess(targetLocation, authContext, acceptedStatuses = MutationSuccessStatuses)
            targetLocation.toWebDavVFile(name = name.trim(), parentLocation = parentLocation, directory = true)
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
                runWebDavRequest(entry.location) {
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
                }
            }.flowOn(Dispatchers.IO),
        )
    }

    /**
     * 探测 WebDAV Range 能力并打开只读随机访问句柄。
     *
     * @param location WebDAV 文件位置。
     * @param mode 打开模式，仅支持只读。
     * @param authContext WebDAV 认证上下文。
     * @return WebDAV 随机访问句柄。
     */
    override suspend fun openRandomAccess(
        location: String,
        mode: VfsRandomAccessMode,
        authContext: VfsAuthContext,
    ): VfsRandomAccessHandle {
        if (mode != VfsRandomAccessMode.READ) {
            throw VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.WEBDAV,
                    location = location,
                    capability = VfsProviderCapability.WRITE_RANDOM_ACCESS,
                )
            )
        }
        return runWebDavRequest(location) {
            KtorWebDavRandomAccessHandle.open(httpClient, location, authContext)
        }
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
        runWebDavRequest(targetLocation) {
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
            targetLocation.toWebDavVFile(
                name = targetLocation.fileNameFromWebDavLocation(),
                parentLocation = parentLocation,
                directory = false,
            )
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
            runWebDavRequest(entry.location) {
                val response = httpClient.request(entry.location.toHttpWebDavUrl()) {
                    this.method = method
                    header(HttpHeaders.Destination, targetLocation.toHttpWebDavUrl())
                    header("Overwrite", if (conflictStrategy == TransferConflictStrategy.OVERWRITE) "T" else "F")
                    applyAuth(authContext, entry.location)
                }
                response.requireSuccess(targetLocation, authContext, acceptedStatuses = MutationSuccessStatuses)
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
                    val candidateLocation = webDavChildLocation(
                        parentLocation = targetDirectoryLocation,
                        name = candidateName,
                        directory = isDirectory,
                    )
                    if (!resourceExists(candidateLocation, authContext)) {
                        return candidateLocation
                    }
                    candidateName = entry.name.withVfsCopySuffix(index + 1)
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
        return runWebDavRequest(location) {
            val response = httpClient.request(location.toHttpWebDavUrl()) {
                method = HttpMethod("PROPFIND")
                header("Depth", "0")
                contentType(ContentType.Application.Xml)
                applyRequestTimeout(METADATA_REQUEST_TIMEOUT_MS)
                applyAuth(authContext, location)
                setBody(PROPFIND_BODY)
            }
            when (response.status) {
                HttpStatusCode.OK,
                HttpStatusCode.MultiStatus,
                -> true
                HttpStatusCode.NotFound -> false
                else -> {
                    response.requireSuccess(location, authContext)
                    true
                }
            }
        }
    }

    /** 仅当底层 HttpClient 已安装 HttpTimeout 插件时施加单请求超时，兼容测试注入 Mock HttpClient。 */
    private fun HttpRequestBuilder.applyRequestTimeout(timeoutMs: Long) {
        if (httpClient.pluginOrNull(HttpTimeout) != null) {
            timeout {
                requestTimeoutMillis = timeoutMs
            }
        }
    }

    /** 统一映射 WebDAV 网络/超时/DNS/SSL/地址解析异常，保留协程取消与已结构化协议错误。 */
    private inline fun <T> runWebDavRequest(location: String, block: () -> T): T {
        return try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: VfsProviderException) {
            throw failure
        } catch (failure: HttpRequestTimeoutException) {
            throw failure.toNetworkFailure(location)
        } catch (failure: UnresolvedAddressException) {
            throw failure.toNetworkFailure(location)
        } catch (failure: SSLException) {
            throw failure.toNetworkFailure(location)
        } catch (failure: IOException) {
            throw failure.toNetworkFailure(location)
        } catch (failure: IllegalArgumentException) {
            throw failure.toNetworkFailure(location)
        }
    }

    private fun HttpRequestBuilder.applyAuth(
        authContext: VfsAuthContext,
        location: String,
    ) {
        authContext.webDavAuthorizationHeader(location)?.let { value ->
            header(HttpHeaders.Authorization, value)
        }
    }

    private companion object {
        const val CONTENT_BUFFER_SIZE = 64 * 1024
        const val MAX_KEEP_BOTH_ATTEMPTS = 10_000
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L
        const val DEFAULT_SOCKET_TIMEOUT_MS = 30_000L
        const val TEST_CONNECTION_TIMEOUT_MS = 15_000L
        const val METADATA_REQUEST_TIMEOUT_MS = 30_000L
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
