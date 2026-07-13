package com.oruke.onyx.vfs.s3

import com.oruke.onyx.core.model.DEFAULT_S3_REGION
import com.oruke.onyx.core.model.S3AddressingStyle
import com.oruke.onyx.core.model.S3ConnectionConfig
import com.oruke.onyx.core.model.S3ProviderPreset
import com.oruke.onyx.vfs.api.VfsAuthContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec


/**
 * 已签名的 S3 HTTP 请求。
 *
 * @property url 完整请求 URL。
 * @property headers 已签名请求头。
 */
data class S3SignedRequest(
    val url: String,
    val headers: Map<String, String>,
)

/**
 * AWS Signature V4 请求签名器。
 *
 * @property clock 签名时间来源。
 */
class S3RequestSigner(
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * 签名 ListObjectsV2 请求。
     *
     * @param location S3 目录位置。
     * @param authContext AWS 凭据。
     * @param connectionConfig S3 Endpoint 与寻址配置。
     * @param continuationToken 下一页 token。
     * @param maxKeys 单页最大条目数。
     * @return 已签名请求。
     */
    fun signListObjectsV2(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
        connectionConfig: S3ConnectionConfig = S3ConnectionConfig(region = ""),
        continuationToken: String?,
        maxKeys: Int? = null,
    ): S3SignedRequest {
        val region = connectionConfig.region.ifBlank { authContext.region ?: DEFAULT_S3_REGION }
        val target = connectionConfig.copy(region = region).requestTarget(location, objectKey = null)
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
            put("host", target.hostHeader)
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
            target.canonicalUri,
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
        val requestHeaders = headers +
            ("Authorization" to authorization) +
            connectionConfig.compatibilityHeaders()
        val url = "${target.url}?$canonicalQuery"
        return S3SignedRequest(url = url, headers = requestHeaders)
    }

    /**
     * 签名 GetObject 请求。
     *
     * @param location S3 对象位置。
     * @param authContext AWS 凭据。
     * @param connectionConfig S3 Endpoint 与寻址配置。
     * @return 已签名请求。
     */
    fun signGetObject(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
        connectionConfig: S3ConnectionConfig = S3ConnectionConfig(region = ""),
    ): S3SignedRequest {
        return signObject(
            method = "GET",
            location = location,
            authContext = authContext,
            connectionConfig = connectionConfig,
        )
    }

    /**
     * 签名 HeadObject 请求。
     *
     * @param location S3 对象位置。
     * @param authContext AWS 凭据。
     * @param connectionConfig S3 Endpoint 与寻址配置。
     * @return 已签名请求。
     */
    fun signHeadObject(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
        connectionConfig: S3ConnectionConfig = S3ConnectionConfig(region = ""),
    ): S3SignedRequest {
        return signObject(
            method = "HEAD",
            location = location,
            authContext = authContext,
            connectionConfig = connectionConfig,
        )
    }

    /**
     * 签名 PutObject 请求。
     *
     * @param location S3 对象位置。
     * @param authContext AWS 凭据。
     * @param connectionConfig S3 Endpoint 与寻址配置。
     * @return 已签名请求。
     */
    fun signPutObject(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
        connectionConfig: S3ConnectionConfig = S3ConnectionConfig(region = ""),
    ): S3SignedRequest {
        return signObject(
            method = "PUT",
            location = location,
            authContext = authContext,
            connectionConfig = connectionConfig,
        )
    }

    /**
     * 签名 DeleteObject 请求。
     *
     * @param location S3 对象位置。
     * @param authContext AWS 凭据。
     * @param connectionConfig S3 Endpoint 与寻址配置。
     * @return 已签名请求。
     */
    fun signDeleteObject(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
        connectionConfig: S3ConnectionConfig = S3ConnectionConfig(region = ""),
    ): S3SignedRequest {
        return signObject(
            method = "DELETE",
            location = location,
            authContext = authContext,
            connectionConfig = connectionConfig,
        )
    }

    /**
     * 为 S3 对象级请求生成 AWS Signature V4 签名。
     *
     * @param method HTTP 方法。
     * @param location 对象位置。
     * @param authContext AWS 凭据。
     * @param connectionConfig S3 Endpoint 与寻址配置。
     * @return 带 URL 和请求头的已签名请求。
     */
    private fun signObject(
        method: String,
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
        connectionConfig: S3ConnectionConfig,
    ): S3SignedRequest {
        val region = connectionConfig.region.ifBlank { authContext.region ?: DEFAULT_S3_REGION }
        val target = connectionConfig.copy(region = region).requestTarget(location, objectKey = location.objectKey)
        val now = clock.instant()
        val amzDate = AMZ_DATE_FORMATTER.format(now)
        val dateStamp = DATE_STAMP_FORMATTER.format(now)
        val credentialScope = "$dateStamp/$region/s3/aws4_request"
        val headers = buildMap {
            put("host", target.hostHeader)
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
            target.canonicalUri,
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
        val requestHeaders = headers +
            ("Authorization" to authorization) +
            connectionConfig.compatibilityHeaders()
        val url = target.url
        return S3SignedRequest(url = url, headers = requestHeaders)
    }

    /**
     * 派生指定日期、Region 与 S3 服务作用域的 Signature V4 签名密钥。
     *
     * @param secretAccessKey Secret Access Key。
     * @param dateStamp UTC 日期戳。
     * @param region 签名 Region。
     * @return 最终签名密钥字节。
     */
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
        const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
        val AMZ_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
        val DATE_STAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC)
    }
}

/** 签名器使用的最终请求地址组成。 */
private data class S3RequestTarget(
    /** Host 签名头，包含显式端口。 */
    val hostHeader: String,
    /** 已按 S3 规则编码的 Canonical URI。 */
    val canonicalUri: String,
    /** 不包含查询参数的完整 URL。 */
    val url: String,
)

/**
 * 根据 Endpoint 与 Bucket 寻址方式构造请求目标。
 *
 * @param location S3 位置。
 * @param objectKey 对象 key；空值表示 Bucket 列表请求。
 * @return 签名和发送共用的请求目标。
 */
private fun S3ConnectionConfig.requestTarget(
    location: S3Location,
    objectKey: String?,
): S3RequestTarget {
    val endpoint = S3EndpointResolver.resolve(this)
    val encodedObjectPath = objectKey
        ?.let(::awsEncodePath)
        ?.let { encoded -> if (encoded.isBlank()) "" else "/$encoded" }
        .orEmpty()
    val hostHeader: String
    val canonicalUri: String
    when (addressingStyle) {
        S3AddressingStyle.VIRTUAL_HOSTED -> {
            hostHeader = "${location.bucket}.${endpoint.authority}"
            canonicalUri = encodedObjectPath.ifBlank { "/" }
        }

        S3AddressingStyle.PATH_STYLE -> {
            hostHeader = endpoint.authority
            canonicalUri = "/${awsEncode(location.bucket)}$encodedObjectPath"
        }
    }
    return S3RequestTarget(
        hostHeader = hostHeader,
        canonicalUri = canonicalUri,
        url = "${endpoint.scheme}://$hostHeader$canonicalUri",
    )
}

/**
 * 返回特定服务商要求但不参与 AWS V4 签名的兼容请求头。
 *
 * @return HTTP 请求附加头。
 */
private fun S3ConnectionConfig.compatibilityHeaders(): Map<String, String> {
    return if (provider == S3ProviderPreset.ALIBABA_OSS) {
        mapOf("x-oss-s3-compat" to "true")
    } else {
        emptyMap()
    }
}

/**
 * 将查询参数按 Signature V4 规则排序并编码。
 *
 * @return 可直接写入 Canonical Request 的查询字符串。
 */
private fun Map<String, String>.toCanonicalQuery(): String {
    return entries
        .sortedWith(compareBy<Map.Entry<String, String>> { entry -> entry.key }.thenBy { entry -> entry.value })
        .joinToString("&") { (key, value) -> "${awsEncode(key)}=${awsEncode(value)}" }
}

/**
 * 按 AWS RFC 3986 规则编码单个路径或查询片段。
 *
 * @param value 原始文本。
 * @return Signature V4 使用的百分号编码文本。
 */
private fun awsEncode(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20")
        .replace("*", "%2A")
        .replace("%7E", "~")
}

/**
 * 编码对象 key，同时保留路径分隔符。
 *
 * @param value 原始对象 key。
 * @return 编码后的对象路径。
 */
private fun awsEncodePath(value: String): String {
    return value.split('/').joinToString("/") { segment -> awsEncode(segment) }
}

/**
 * 计算 HMAC-SHA256。
 *
 * @param key HMAC 密钥。
 * @param data 待签名文本。
 * @return 摘要字节。
 */
private fun hmac(
    key: ByteArray,
    data: String,
): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
}

/**
 * 计算字符串的 SHA-256 十六进制摘要。
 *
 * @return 小写十六进制摘要。
 */
private fun String.sha256Hex(): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .toHex()
}

/**
 * 将字节数组转换为小写十六进制文本。
 *
 * @return 十六进制文本。
 */
private fun ByteArray.toHex(): String {
    return joinToString("") { byte -> "%02x".format(byte) }
}
