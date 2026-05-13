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

    fun signGetObject(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ): S3SignedRequest {
        return signObject(
            method = "GET",
            location = location,
            authContext = authContext,
        )
    }

    fun signHeadObject(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ): S3SignedRequest {
        return signObject(
            method = "HEAD",
            location = location,
            authContext = authContext,
        )
    }

    fun signPutObject(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ): S3SignedRequest {
        return signObject(
            method = "PUT",
            location = location,
            authContext = authContext,
        )
    }

    fun signDeleteObject(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ): S3SignedRequest {
        return signObject(
            method = "DELETE",
            location = location,
            authContext = authContext,
        )
    }

    /**
     * 为 S3 对象级请求生成 AWS Signature V4 签名。
     *
     * @param method HTTP 方法。
     * @param location 对象位置。
     * @param authContext AWS 凭据。
     * @return 带 URL 和请求头的已签名请求。
     */
    private fun signObject(
        method: String,
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
    ): S3SignedRequest {
        val region = authContext.region ?: DEFAULT_REGION
        val host = "s3.$region.amazonaws.com"
        val canonicalUri = "/${awsEncode(location.bucket)}/${awsEncodePath(location.objectKey)}"
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
            method,
            canonicalUri,
            "",
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
        val url = "https://$host$canonicalUri"
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

/**
 * 创建当前 S3 目录下的子对象位置。
 *
 * @param name 子对象文件名。
 * @return 子对象位置。
 */
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

private fun awsEncodePath(value: String): String {
    return value.split('/').joinToString("/") { segment -> awsEncode(segment) }
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
