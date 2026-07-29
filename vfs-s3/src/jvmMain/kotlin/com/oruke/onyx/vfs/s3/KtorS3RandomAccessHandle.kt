package com.oruke.onyx.vfs.s3

import com.oruke.onyx.core.model.S3ConnectionConfig
import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsRandomAccessHandle
import com.oruke.onyx.vfs.api.VfsRandomAccessMode
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 使用签名 Range 请求直接读取 S3 兼容对象的随机访问句柄。
 *
 * @param httpClient 共享 Ktor HTTP 客户端。
 * @param signer S3 Signature V4 签名器。
 * @param location S3 对象位置。
 * @param authContext AWS 访问凭据。
 * @param connectionConfig S3 Endpoint 与兼容配置。
 * @param fileLength 打开时确认的对象长度。
 * @param entityTag 打开时记录的对象 ETag。
 */
internal class KtorS3RandomAccessHandle private constructor(
    private val httpClient: HttpClient,
    private val signer: S3RequestSigner,
    private val location: S3Location,
    private val authContext: VfsAuthContext.AwsCredentials,
    private val connectionConfig: S3ConnectionConfig,
    private val fileLength: Long,
    private val entityTag: String?,
) : VfsRandomAccessHandle {
    override val mode: VfsRandomAccessMode = VfsRandomAccessMode.READ

    /** 当前对象的完整 VFS 位置。 */
    private val objectLocation = location.toLocation(location.objectKey, directory = false)

    /**
     * 返回打开句柄时确认的对象长度。
     *
     * @return 对象字节数。
     */
    override suspend fun length(): Long = fileLength

    /**
     * 通过签名 Range 请求从指定偏移读取 S3 对象。
     *
     * @param position 对象绝对偏移。
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
        val response = executeRangeRequest(
            rangeHeader = "bytes=$position-${position + requestedCount - 1}",
            entityTag = entityTag,
        )
        response.requireRangeResponse(position, fileLength)
        response.readS3BodyInto(buffer, bufferOffset, requestedCount)
    }

    /**
     * 结束当前逻辑句柄。
     */
    override fun close() {
        // S3 Range 请求彼此独立，HttpClient 由 S3 客户端生命周期统一管理。
    }

    /**
     * 发起带签名的 S3 Range 请求。
     *
     * Range 与 If-Match 不属于 SigV4 必签头，可在基础对象请求签名后附加。
     *
     * @param rangeHeader HTTP Range 请求头。
     * @param entityTag 可选对象版本 ETag。
     * @return S3 HTTP 响应。
     */
    private suspend fun executeRangeRequest(
        rangeHeader: String,
        entityTag: String?,
    ): HttpResponse {
        val request = signer.signGetObject(location, authContext, connectionConfig)
        return httpClient.get(request.url) {
            request.headers.forEach { (name, value) -> header(name, value) }
            header(HttpHeaders.Range, rangeHeader)
            entityTag?.let { value -> header(HttpHeaders.IfMatch, value) }
        }
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
     * 校验 Range 响应起点与对象版本。
     *
     * @param expectedStart 期望响应起始偏移。
     * @param expectedLength 打开句柄时记录的对象长度。
     */
    private fun HttpResponse.requireRangeResponse(
        expectedStart: Long,
        expectedLength: Long,
    ) {
        val range = headers[HttpHeaders.ContentRange]?.toS3ContentRange()
        val failure = when {
            status != HttpStatusCode.PartialContent -> status.toS3RandomAccessException(objectLocation)
            range == null -> rangeFailure("S3 Range 响应缺少 Content-Range")
            range.start != expectedStart || range.totalLength != expectedLength ->
                rangeFailure("S3 对象在随机读取期间发生变化")

            else -> null
        }
        if (failure != null) {
            throw failure
        }
    }

    /**
     * 构建 S3 随机访问网络失败。
     *
     * @param reason 失败原因。
     * @return 结构化 VFS 异常。
     */
    private fun rangeFailure(reason: String): VfsProviderException {
        return VfsProviderException(
            VfsProviderError.NetworkFailure(
                protocol = VfsProtocol.S3,
                location = objectLocation,
                reason = reason,
            )
        )
    }

    /**
     * 创建并探测 S3 Range 随机访问句柄。
     */
    companion object {
        /**
         * 发送最小 Range 请求，确认 S3 兼容服务支持随机读取。
         *
         * @param httpClient 共享 Ktor HTTP 客户端。
         * @param signer S3 Signature V4 签名器。
         * @param location S3 对象位置。
         * @param authContext AWS 访问凭据。
         * @param connectionConfig S3 Endpoint 与兼容配置。
         * @return 可直接随机读取的句柄。
         */
        suspend fun open(
            httpClient: HttpClient,
            signer: S3RequestSigner,
            location: S3Location,
            authContext: VfsAuthContext.AwsCredentials,
            connectionConfig: S3ConnectionConfig,
        ): VfsRandomAccessHandle = withContext(Dispatchers.IO) {
            val objectLocation = location.toLocation(location.objectKey, directory = false)
            val request = signer.signGetObject(location, authContext, connectionConfig)
            val response = httpClient.get(request.url) {
                request.headers.forEach { (name, value) -> header(name, value) }
                header(HttpHeaders.Range, "bytes=0-0")
            }
            if (response.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                val emptyLength = response.headers[HttpHeaders.ContentRange]
                    ?.toS3UnsatisfiedLength()
                if (emptyLength == 0L) {
                    return@withContext KtorS3RandomAccessHandle(
                        httpClient = httpClient,
                        signer = signer,
                        location = location,
                        authContext = authContext,
                        connectionConfig = connectionConfig,
                        fileLength = 0L,
                        entityTag = response.headers[HttpHeaders.ETag],
                    )
                }
            }
            if (response.status != HttpStatusCode.PartialContent) {
                throw response.status.toS3RandomAccessException(objectLocation)
            }
            val range = response.headers[HttpHeaders.ContentRange]?.toS3ContentRange()
                ?: throw VfsProviderException(
                    VfsProviderError.NetworkFailure(
                        protocol = VfsProtocol.S3,
                        location = objectLocation,
                        reason = "S3 Range 探测缺少 Content-Range",
                    )
                )
            response.readS3BodyInto(ByteArray(1), 0, 1)
            KtorS3RandomAccessHandle(
                httpClient = httpClient,
                signer = signer,
                location = location,
                authContext = authContext,
                connectionConfig = connectionConfig,
                fileLength = range.totalLength,
                entityTag = response.headers[HttpHeaders.ETag],
            )
        }
    }
}

/**
 * 把 S3 响应体直接读取到调用方缓冲区。
 *
 * @param buffer 接收数据缓冲区。
 * @param bufferOffset 缓冲区起始位置。
 * @param byteCount 最多读取字节数。
 * @return 实际读取字节数。
 */
private suspend fun HttpResponse.readS3BodyInto(
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
 * 已满足 S3 Range 响应中的字节范围。
 *
 * @property start 响应起始偏移。
 * @property totalLength 完整对象长度。
 */
private data class S3ContentRange(
    val start: Long,
    val totalLength: Long,
)

/**
 * 解析满足请求的 S3 Content-Range。
 *
 * @return 起始位置和完整长度。
 */
private fun String.toS3ContentRange(): S3ContentRange? {
    val match = S3_CONTENT_RANGE_PATTERN.matchEntire(trim()) ?: return null
    return S3ContentRange(
        start = match.groupValues[1].toLong(),
        totalLength = match.groupValues[3].toLong(),
    )
}

/**
 * 解析无法满足 S3 Range 时返回的完整对象长度。
 *
 * @return 对象长度；格式不匹配时返回 `null`。
 */
private fun String.toS3UnsatisfiedLength(): Long? {
    return S3_UNSATISFIED_RANGE_PATTERN.matchEntire(trim())?.groupValues?.get(1)?.toLong()
}

/**
 * 将 HTTP 状态转换为 S3 随机访问错误。
 *
 * @param location S3 对象位置。
 * @return 结构化 VFS 异常。
 */
private fun HttpStatusCode.toS3RandomAccessException(location: String): VfsProviderException {
    val error = when (this) {
        HttpStatusCode.Unauthorized -> VfsProviderError.AuthenticationRejected(VfsProtocol.S3, location, description)
        HttpStatusCode.Forbidden -> VfsProviderError.PermissionDenied(VfsProtocol.S3, location, description)
        HttpStatusCode.NotFound -> VfsProviderError.NotFound(VfsProtocol.S3, location)
        HttpStatusCode.OK -> VfsProviderError.UnsupportedOperation(
            protocol = VfsProtocol.S3,
            location = location,
            capability = VfsProviderCapability.READ_RANDOM_ACCESS,
        )

        else -> VfsProviderError.NetworkFailure(VfsProtocol.S3, location, "HTTP $value $description")
    }
    return VfsProviderException(error)
}

/** 满足 S3 Range 的 Content-Range 格式。 */
private val S3_CONTENT_RANGE_PATTERN = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)

/** 无法满足 S3 Range 时的 Content-Range 格式。 */
private val S3_UNSATISFIED_RANGE_PATTERN = Regex("""bytes\s+\*/(\d+)""", RegexOption.IGNORE_CASE)
