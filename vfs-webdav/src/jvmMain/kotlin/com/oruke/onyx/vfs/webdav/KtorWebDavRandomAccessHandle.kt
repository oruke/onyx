package com.oruke.onyx.vfs.webdav

import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsRandomAccessHandle
import com.oruke.onyx.vfs.api.VfsRandomAccessMode
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 使用 HTTP Range 请求直接读取 WebDAV 文件的随机访问句柄。
 *
 * @param httpClient 共享 Ktor HTTP 客户端。
 * @param location WebDAV 文件位置。
 * @param authContext WebDAV 认证上下文。
 * @param fileLength 打开时确认的文件长度。
 * @param entityTag 打开时的强 ETag。
 * @param lastModified 打开时的 Last-Modified。
 */
internal class KtorWebDavRandomAccessHandle private constructor(
    private val httpClient: HttpClient,
    private val location: String,
    private val authContext: VfsAuthContext,
    private val fileLength: Long,
    private val entityTag: String?,
    private val lastModified: String?,
) : VfsRandomAccessHandle {
    override val mode: VfsRandomAccessMode = VfsRandomAccessMode.READ

    /**
     * 返回打开句柄时确认的远端文件长度。
     *
     * @return 文件字节数。
     */
    override suspend fun length(): Long = fileLength

    /**
     * 通过 HTTP Range 从指定偏移读取 WebDAV 文件。
     *
     * @param position 文件绝对偏移。
     * @param buffer 接收数据的缓冲区。
     * @param bufferOffset 缓冲区起始位置。
     * @param byteCount 最多读取字节数。
     * @return 实际读取字节数；到达末尾时返回 `-1`。
     */
    override suspend fun readAt(
        position: Long,
        buffer: ByteArray,
        bufferOffset: Int,
        byteCount: Int,
    ): Int = withContext(Dispatchers.IO) {
        validateRandomAccessRange(position, buffer, bufferOffset, byteCount)
        if (byteCount == 0) return@withContext 0
        if (position >= fileLength) return@withContext -1

        val requestedCount = minOf(byteCount.toLong(), fileLength - position).toInt()
        val response = httpClient.request(location.toHttpWebDavUrl()) {
            method = HttpMethod.Get
            header(HttpHeaders.Range, "bytes=$position-${position + requestedCount - 1}")
            entityTag?.let { value -> header(HttpHeaders.IfMatch, value) }
            if (entityTag == null) {
                lastModified?.let { value -> header(HttpHeaders.IfUnmodifiedSince, value) }
            }
            authContext.webDavAuthorizationHeader(location)?.let { value ->
                header(HttpHeaders.Authorization, value)
            }
        }
        response.requireRangeResponse(position, fileLength)
        response.readWebDavBodyInto(buffer, bufferOffset, requestedCount)
    }

    /**
     * 结束当前逻辑句柄。
     */
    override fun close() {
        // Range 读取不保持独占远端句柄，HttpClient 由 Provider 生命周期统一管理。
    }

    /**
     * 校验随机读取的文件位置与缓冲区范围。
     *
     * @param position 文件绝对偏移。
     * @param buffer 接收数据缓冲区。
     * @param bufferOffset 缓冲区起始位置。
     * @param byteCount 请求字节数。
     */
    private fun validateRandomAccessRange(
        position: Long,
        buffer: ByteArray,
        bufferOffset: Int,
        byteCount: Int,
    ) {
        require(position >= 0L) {
            "File position must not be negative"
        }
        require(bufferOffset >= 0 && byteCount >= 0 && bufferOffset <= buffer.size - byteCount) {
            "Buffer range is out of bounds"
        }
    }

    /**
     * 校验 Range 响应起点与文件版本。
     *
     * @param expectedStart 期望响应起始偏移。
     * @param expectedLength 打开句柄时记录的文件长度。
     */
    private fun HttpResponse.requireRangeResponse(
        expectedStart: Long,
        expectedLength: Long,
    ) {
        val range = headers[HttpHeaders.ContentRange]?.toWebDavContentRange()
        val failure = when {
            status != HttpStatusCode.PartialContent -> status.toRandomAccessException(location, authContext)
            range == null -> rangeFailure("WebDAV Range 响应缺少 Content-Range")
            range.start != expectedStart || range.totalLength != expectedLength ->
                rangeFailure("WebDAV 文件在随机读取期间发生变化")

            else -> null
        }
        if (failure != null) {
            throw failure
        }
    }

    /**
     * 构建 WebDAV 随机访问网络失败。
     *
     * @param reason 失败原因。
     * @return 结构化 VFS 异常。
     */
    private fun rangeFailure(reason: String): VfsProviderException {
        return VfsProviderException(
            VfsProviderError.NetworkFailure(
                protocol = VfsProtocol.WEBDAV,
                location = location,
                reason = reason,
            )
        )
    }

    /**
     * 创建并探测 WebDAV Range 随机访问句柄。
     */
    companion object {
        /**
         * 发送最小 Range 请求，确认服务端支持随机访问并记录文件版本。
         *
         * @param httpClient 共享 Ktor HTTP 客户端。
         * @param location WebDAV 文件位置。
         * @param authContext WebDAV 认证上下文。
         * @return 可直接随机读取的句柄。
         */
        suspend fun open(
            httpClient: HttpClient,
            location: String,
            authContext: VfsAuthContext,
        ): VfsRandomAccessHandle = withContext(Dispatchers.IO) {
            val response = httpClient.request(location.toHttpWebDavUrl()) {
                method = HttpMethod.Get
                header(HttpHeaders.Range, "bytes=0-0")
                authContext.webDavAuthorizationHeader(location)?.let { value ->
                    header(HttpHeaders.Authorization, value)
                }
            }
            if (response.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                val emptyLength = response.headers[HttpHeaders.ContentRange]
                    ?.toWebDavUnsatisfiedLength()
                if (emptyLength == 0L) {
                    return@withContext KtorWebDavRandomAccessHandle(
                        httpClient = httpClient,
                        location = location,
                        authContext = authContext,
                        fileLength = 0L,
                        entityTag = response.strongEntityTag(),
                        lastModified = response.headers[HttpHeaders.LastModified],
                    )
                }
            }
            if (response.status != HttpStatusCode.PartialContent) {
                throw response.status.toRandomAccessException(location, authContext)
            }
            val range = response.headers[HttpHeaders.ContentRange]?.toWebDavContentRange()
                ?: throw VfsProviderException(
                    VfsProviderError.NetworkFailure(
                        protocol = VfsProtocol.WEBDAV,
                        location = location,
                        reason = "WebDAV Range 探测缺少 Content-Range",
                    )
                )
            response.readWebDavBodyInto(ByteArray(1), 0, 1)
            KtorWebDavRandomAccessHandle(
                httpClient = httpClient,
                location = location,
                authContext = authContext,
                fileLength = range.totalLength,
                entityTag = response.strongEntityTag(),
                lastModified = response.headers[HttpHeaders.LastModified],
            )
        }
    }
}

/**
 * 把 WebDAV 响应体直接读取到调用方缓冲区。
 *
 * @param buffer 接收数据缓冲区。
 * @param bufferOffset 缓冲区起始位置。
 * @param byteCount 最多读取字节数。
 * @return 实际读取字节数。
 */
private suspend fun HttpResponse.readWebDavBodyInto(
    buffer: ByteArray,
    bufferOffset: Int,
    byteCount: Int,
): Int {
    val channel = bodyAsChannel()
    var totalRead = 0
    while (totalRead < byteCount) {
        val read = channel.readAvailable(buffer, bufferOffset + totalRead, byteCount - totalRead)
        if (read <= 0) break
        totalRead += read
    }
    return totalRead
}

/**
 * 已满足 Range 响应中的字节范围。
 *
 * @property start 响应起始偏移。
 * @property totalLength 完整文件长度。
 */
private data class WebDavContentRange(
    val start: Long,
    val totalLength: Long,
)

/**
 * 解析满足请求的 Content-Range。
 *
 * @return 起始位置和完整长度。
 */
private fun String.toWebDavContentRange(): WebDavContentRange? {
    val match = CONTENT_RANGE_PATTERN.matchEntire(trim()) ?: return null
    return WebDavContentRange(
        start = match.groupValues[1].toLong(),
        totalLength = match.groupValues[3].toLong(),
    )
}

/**
 * 解析无法满足 Range 时返回的完整文件长度。
 *
 * @return 文件长度；格式不匹配时返回 `null`。
 */
private fun String.toWebDavUnsatisfiedLength(): Long? {
    return UNSATISFIED_RANGE_PATTERN.matchEntire(trim())?.groupValues?.get(1)?.toLong()
}

/**
 * 返回仅可用于强一致性校验的 ETag。
 *
 * @return 强 ETag；弱 ETag 或缺失时返回 `null`。
 */
private fun HttpResponse.strongEntityTag(): String? {
    return headers[HttpHeaders.ETag]?.takeUnless { value -> value.startsWith("W/") }
}

/**
 * 将 HTTP 状态转换为 WebDAV 随机访问错误。
 *
 * @param location WebDAV 文件位置。
 * @param authContext 当前认证上下文。
 * @return 结构化 VFS 异常。
 */
private fun HttpStatusCode.toRandomAccessException(
    location: String,
    authContext: VfsAuthContext,
): VfsProviderException {
    val error = when (this) {
        HttpStatusCode.Unauthorized -> if (authContext == VfsAuthContext.None) {
            VfsProviderError.AuthenticationRequired(VfsProtocol.WEBDAV, location)
        } else {
            VfsProviderError.AuthenticationRejected(VfsProtocol.WEBDAV, location, description)
        }

        HttpStatusCode.Forbidden -> VfsProviderError.PermissionDenied(VfsProtocol.WEBDAV, location, description)
        HttpStatusCode.NotFound -> VfsProviderError.NotFound(VfsProtocol.WEBDAV, location)
        HttpStatusCode.OK -> VfsProviderError.UnsupportedOperation(
            protocol = VfsProtocol.WEBDAV,
            location = location,
            capability = VfsProviderCapability.READ_RANDOM_ACCESS,
        )

        else -> VfsProviderError.NetworkFailure(VfsProtocol.WEBDAV, location, "HTTP $value $description")
    }
    return VfsProviderException(error)
}

/** 满足 Range 的 Content-Range 格式。 */
private val CONTENT_RANGE_PATTERN = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)

/** 无法满足 Range 时的 Content-Range 格式。 */
private val UNSATISFIED_RANGE_PATTERN = Regex("""bytes\s+\*/(\d+)""", RegexOption.IGNORE_CASE)
