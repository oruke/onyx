package com.oruke.onyx.vfs.s3

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
import com.oruke.onyx.vfs.api.withVfsCopySuffix
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
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
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 基于 Ktor CIO 的 S3 客户端实现。
 *
 * @property httpClient 负责发起 S3 HTTP 请求的客户端。
 * @property signer AWS Signature V4 请求签名器。
 * @property parser S3 列表响应解析器。
 */
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
        } catch (failure: IOException) {
            throw failure.toS3NetworkException(location.directoryLocation)
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
        } catch (failure: IOException) {
            throw failure.toS3NetworkException(location.directoryLocation)
        }
    }

    /**
     * 使用 S3 ListObjectsV2 原生分页列出单页目录条目。
     *
     * @param location 当前目录位置。
     * @param pageSize 单页最大条目数。
     * @param pageToken S3 continuation token；第一页为 null。
     * @param authContext AWS 凭据。
     * @return 当前页条目与下一页 token。
     */
    override suspend fun listPage(
        location: S3Location,
        pageSize: Int,
        pageToken: String?,
        authContext: VfsAuthContext.AwsCredentials,
    ): S3ListPage = withContext(Dispatchers.IO) {
        try {
            val request = signer.signListObjectsV2(
                location = location,
                authContext = authContext,
                continuationToken = pageToken,
                maxKeys = pageSize,
            )
            val response = httpClient.get(request.url) {
                request.headers.forEach { (name, value) -> header(name, value) }
            }
            val body = response.requireSuccess(location.directoryLocation)
            parser.parse(body, location)
        } catch (failure: VfsProviderException) {
            throw failure
        } catch (failure: IOException) {
            throw failure.toS3NetworkException(location.directoryLocation)
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
                } catch (failure: IOException) {
                    throw failure.toS3NetworkException(entry.location)
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
        } catch (failure: IOException) {
            val location = targetLocation.toLocation(targetLocation.objectKey, directory = false)
            throw failure.toS3NetworkException(location)
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
        } catch (failure: IOException) {
            throw failure.toS3NetworkException(objectLocation)
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
        } catch (failure: IOException) {
            throw failure.toS3NetworkException(location.directoryLocation)
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
                    candidateName = name.withVfsCopySuffix(index + 1)
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
        return when (response.status) {
            HttpStatusCode.OK,
            HttpStatusCode.PartialContent,
            -> true
            HttpStatusCode.NotFound -> false
            else -> {
                response.requireObjectSuccess(location.toLocation(location.objectKey, directory = false))
                true
            }
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

    private companion object {
        const val CONTENT_BUFFER_SIZE = 64 * 1024
        const val MAX_KEEP_BOTH_ATTEMPTS = 10_000
    }
}
