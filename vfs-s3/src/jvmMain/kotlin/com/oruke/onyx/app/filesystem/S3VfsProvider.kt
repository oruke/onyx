package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLException
import javax.xml.parsers.DocumentBuilderFactory

interface S3AuthRepository {
    fun authContext(location: String): VfsAuthContext

    data object None : S3AuthRepository {
        override fun authContext(location: String): VfsAuthContext = VfsAuthContext.None
    }
}

class RemoteAuthStoreS3AuthRepository(
    private val remoteAuthStore: RemoteAuthStore,
) : S3AuthRepository {
    override fun authContext(location: String): VfsAuthContext {
        return remoteAuthStore.authContext(VfsProtocol.S3, location)
    }
}

class S3VfsProvider(
    private val authRepository: S3AuthRepository = S3AuthRepository.None,
    private val client: S3Client = KtorS3Client(),
) : VfsProvider, VfsConnectionTester {
    override val protocol: VfsProtocol = VfsProtocol.S3

    override val capabilities: Set<VfsProviderCapability> = emptySet()

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
        val location = runCatching { S3Location.parse(request.location) }.getOrElse { failure ->
            return failure.toVfsConnectionTestResult(
                protocol = VfsProtocol.S3,
                location = request.location,
            )
        }
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

    private companion object {
        const val S3_SCHEME = "s3://"
    }
}

interface S3Client {
    suspend fun testConnection(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    )

    suspend fun list(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ): List<VFile>
}

data class S3Location(
    val bucket: String,
    val prefix: String,
) {
    val directoryPrefix: String = prefix.trim('/').let { value ->
        if (value.isBlank()) "" else "$value/"
    }

    val directoryLocation: String
        get() = toLocation(directoryPrefix, directory = true)

    fun toLocation(
        key: String,
        directory: Boolean,
    ): String {
        val path = if (directory) key.withTrailingSlash() else key
        return URI("s3", bucket, "/$path", null).toASCIIString()
    }

    companion object {
        fun parse(location: String): S3Location {
            val uri = URI(location.encodeSpaces())
            val bucket = uri.host
            if (bucket.isNullOrBlank()) {
                throw VfsProviderNotFoundException(location)
            }
            val prefix = uri.path
                .removePrefix("/")
                .trimStart('/')
            return S3Location(bucket = bucket, prefix = prefix)
        }
    }
}

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
        } catch (failure: SSLException) {
            throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.S3,
                    location = location.directoryLocation,
                    reason = failure.message,
                )
            )
        } catch (failure: IOException) {
            throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.S3,
                    location = location.directoryLocation,
                    reason = failure.message,
                )
            )
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
        } catch (failure: SSLException) {
            throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.S3,
                    location = location.directoryLocation,
                    reason = failure.message,
                )
            )
        } catch (failure: IOException) {
            throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.S3,
                    location = location.directoryLocation,
                    reason = failure.message,
                )
            )
        }
    }

    private suspend fun HttpResponse.requireSuccess(location: String): String {
        val body = bodyAsText()
        when (status.value) {
            200 -> return body
            400 -> throw VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.S3,
                    location = location,
                    capability = null,
                )
            )

            401 -> throw VfsProviderException(VfsProviderError.AuthenticationRejected(VfsProtocol.S3, location))
            403 -> throw VfsProviderException(s3Error(body, location))
            404 -> throw VfsProviderException(VfsProviderError.NotFound(VfsProtocol.S3, location))
            in 500..599 -> throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.S3,
                    location = location,
                    reason = status.description,
                )
            )

            else -> throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.S3,
                    location = location,
                    reason = status.description,
                )
            )
        }
    }

    private fun s3Error(
        xml: String,
        location: String,
    ): VfsProviderError {
        val code = S3ErrorParser().parseCode(xml)
        return when (code) {
            "InvalidAccessKeyId",
            "SignatureDoesNotMatch",
            "ExpiredToken",
            "InvalidToken",
            -> VfsProviderError.AuthenticationRejected(VfsProtocol.S3, location, code)

            "AccessDenied" -> VfsProviderError.PermissionDenied(VfsProtocol.S3, location, code)
            "NoSuchBucket",
            "NoSuchKey",
            -> VfsProviderError.NotFound(VfsProtocol.S3, location)

            else -> VfsProviderError.NetworkFailure(VfsProtocol.S3, location, code)
        }
    }
}

data class S3SignedRequest(
    val url: String,
    val headers: Map<String, String>,
)

class S3RequestSigner(
    private val clock: Clock = Clock.systemUTC(),
) {
    fun signListObjectsV2(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
        continuationToken: String?,
        maxKeys: Int? = null,
    ): S3SignedRequest {
        val region = authContext.region ?: DEFAULT_REGION
        val host = "s3.$region.amazonaws.com"
        val canonicalUri = "/${awsEncode(location.bucket)}"
        val queryParameters = buildMap {
            put("delimiter", "/")
            put("list-type", "2")
            put("prefix", location.directoryPrefix)
            if (continuationToken != null) {
                put("continuation-token", continuationToken)
            }
            if (maxKeys != null) {
                put("max-keys", maxKeys.toString())
            }
        }
        val canonicalQuery = queryParameters.toCanonicalQuery()
        val now = clock.instant()
        val amzDate = AMZ_DATE_FORMATTER.format(now)
        val dateStamp = DATE_STAMP_FORMATTER.format(now)
        val credentialScope = "$dateStamp/$region/s3/aws4_request"
        val headers = buildMap {
            put("host", host)
            put("x-amz-content-sha256", UNSIGNED_PAYLOAD)
            put("x-amz-date", amzDate)
            authContext.sessionToken?.let { token -> put("x-amz-security-token", token) }
        }.toSortedMap()
        val canonicalHeaders = headers.entries.joinToString(separator = "") { (name, value) ->
            "${name.lowercase()}:${value.trim()}\n"
        }
        val signedHeaders = headers.keys.joinToString(";") { key -> key.lowercase() }
        val canonicalRequest = listOf(
            "GET",
            canonicalUri,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            UNSIGNED_PAYLOAD,
        ).joinToString("\n")
        val stringToSign = listOf(
            ALGORITHM,
            amzDate,
            credentialScope,
            canonicalRequest.sha256Hex(),
        ).joinToString("\n")
        val signingKey = signingKey(
            secretAccessKey = authContext.secretAccessKey,
            dateStamp = dateStamp,
            region = region,
        )
        val signature = hmac(signingKey, stringToSign).toHex()
        val authorization = "$ALGORITHM Credential=${authContext.accessKeyId}/$credentialScope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"
        val requestHeaders = headers + ("Authorization" to authorization)
        val url = "https://$host$canonicalUri?$canonicalQuery"
        return S3SignedRequest(url = url, headers = requestHeaders)
    }

    private fun signingKey(
        secretAccessKey: String,
        dateStamp: String,
        region: String,
    ): ByteArray {
        val dateKey = hmac("AWS4$secretAccessKey".toByteArray(StandardCharsets.UTF_8), dateStamp)
        val dateRegionKey = hmac(dateKey, region)
        val dateRegionServiceKey = hmac(dateRegionKey, "s3")
        return hmac(dateRegionServiceKey, "aws4_request")
    }

    private companion object {
        const val ALGORITHM = "AWS4-HMAC-SHA256"
        const val DEFAULT_REGION = "us-east-1"
        const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
        val AMZ_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
        val DATE_STAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC)
    }
}

data class S3ListPage(
    val entries: List<VFile>,
    val nextContinuationToken: String?,
)

class S3ListBucketResultParser {
    fun parse(
        xml: String,
        location: S3Location,
    ): S3ListPage {
        val document = documentBuilderFactory().newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        val entries = buildList {
            val commonPrefixes = document.getElementsByTagNameNS("*", "CommonPrefixes")
            for (index in 0 until commonPrefixes.length) {
                val element = commonPrefixes.item(index) as? Element ?: continue
                val prefix = element.childText("Prefix") ?: continue
                val name = prefix.trimEnd('/').substringAfterLast('/')
                if (name.isBlank()) continue
                add(
                    VFile(
                        id = location.toLocation(prefix, directory = true),
                        name = name,
                        location = location.toLocation(prefix, directory = true),
                        parentLocation = location.directoryLocation,
                        kind = VFileKind.DIRECTORY,
                        sizeBytes = null,
                        modifiedAtEpochMillis = null,
                        hidden = name.startsWith("."),
                        capabilities = setOf(VFileCapability.READ_METADATA, VFileCapability.LIST_CHILDREN),
                    )
                )
            }

            val contents = document.getElementsByTagNameNS("*", "Contents")
            for (index in 0 until contents.length) {
                val element = contents.item(index) as? Element ?: continue
                val key = element.childText("Key") ?: continue
                val relative = key.removePrefix(location.directoryPrefix)
                if (relative.isBlank() || relative.contains('/')) continue
                val name = relative.substringAfterLast('/')
                add(
                    VFile(
                        id = location.toLocation(key, directory = false),
                        name = name,
                        location = location.toLocation(key, directory = false),
                        parentLocation = location.directoryLocation,
                        kind = VFileKind.FILE,
                        sizeBytes = element.childText("Size")?.toLongOrNull(),
                        modifiedAtEpochMillis = element.childText("LastModified")?.toInstantMillisOrNull(),
                        hidden = name.startsWith("."),
                        capabilities = setOf(VFileCapability.READ_METADATA, VFileCapability.READ_CONTENT),
                    )
                )
            }
        }.sortedWith(
            compareByDescending<VFile> { entry -> entry.kind == VFileKind.DIRECTORY }
                .thenBy { entry -> entry.name.lowercase() }
        )
        val isTruncated = document.documentElement.childText("IsTruncated") == "true"
        val nextToken = document.documentElement.childText("NextContinuationToken")
            ?.takeIf { token -> token.isNotBlank() && isTruncated }
        return S3ListPage(entries = entries, nextContinuationToken = nextToken)
    }
}

class S3ErrorParser {
    fun parseCode(xml: String): String? {
        return runCatching {
            documentBuilderFactory().newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))
                .documentElement
                .childText("Code")
        }.getOrNull()
    }
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

private fun Element.childText(localName: String): String? {
    val nodes = getElementsByTagNameNS("*", localName)
    return nodes.item(0)?.textContent?.trim()
}

private fun Map<String, String>.toCanonicalQuery(): String {
    return entries
        .sortedWith(compareBy<Map.Entry<String, String>> { entry -> entry.key }.thenBy { entry -> entry.value })
        .joinToString("&") { (key, value) -> "${awsEncode(key)}=${awsEncode(value)}" }
}

private fun awsEncode(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20")
        .replace("*", "%2A")
        .replace("%7E", "~")
}

private fun hmac(
    key: ByteArray,
    data: String,
): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
}

private fun String.sha256Hex(): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .toHex()
}

private fun ByteArray.toHex(): String {
    return joinToString("") { byte -> "%02x".format(byte) }
}

private fun String.withTrailingSlash(): String {
    return if (endsWith('/')) this else "$this/"
}

private fun String.encodeSpaces(): String {
    return replace(" ", "%20")
}

private fun String.toInstantMillisOrNull(): Long? {
    return runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
}
