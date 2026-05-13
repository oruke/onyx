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


interface S3Client {
    suspend fun testConnection(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    )

    suspend fun list(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ): List<VFile>

    /**
     * 分页列出 S3 目录条目。
     *
     * @param location 当前目录位置。
     * @param pageSize 单页最大条目数。
     * @param pageToken S3 continuation token；第一页为 null。
     * @param authContext AWS 凭据。
     * @return 当前页条目和下一页 token。
     */
    suspend fun listPage(
        location: S3Location,
        pageSize: Int,
        pageToken: String?,
        authContext: VfsAuthContext.AwsCredentials,
    ): S3ListPage {
        return S3ListPage(
            entries = list(location, authContext),
            nextContinuationToken = null,
        )
    }

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
