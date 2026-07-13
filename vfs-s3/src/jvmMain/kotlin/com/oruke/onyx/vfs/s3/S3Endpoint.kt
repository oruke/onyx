package com.oruke.onyx.vfs.s3

import com.oruke.onyx.core.model.DEFAULT_S3_REGION
import com.oruke.onyx.core.model.S3ConnectionConfig
import com.oruke.onyx.core.model.S3ProviderPreset
import java.net.URI

/** 解析完成且可直接用于 HTTP 请求的 S3 Endpoint。 */
data class S3ResolvedEndpoint(
    /** HTTP 或 HTTPS scheme。 */
    val scheme: String,
    /** 不包含 Bucket 的 Endpoint 主机名或 IP。 */
    val host: String,
    /** 显式端口；默认端口为 -1。 */
    val port: Int,
) {
    /** 带可选端口的 HTTP authority。 */
    val authority: String = if (port >= 0) "$host:$port" else host
}

/** S3 服务商预设与自定义 Endpoint 解析器。 */
object S3EndpointResolver {
    /**
     * 解析连接配置的最终 Endpoint。
     *
     * 显式 Endpoint 优先于服务商预设；MinIO 与自定义服务必须显式配置。
     *
     * @param config S3 连接配置。
     * @return 已验证的 HTTP(S) Endpoint。
     * @throws IllegalArgumentException Endpoint 或 Region 不符合当前预设要求时抛出。
     */
    fun resolve(config: S3ConnectionConfig): S3ResolvedEndpoint {
        val endpointText = config.endpoint.trim().ifBlank { presetEndpoint(config) }
        val normalizedText = if (endpointText.contains("://")) endpointText else "https://$endpointText"
        val uri = URI(normalizedText)
        require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
            "S3 endpoint must use HTTP or HTTPS"
        }
        require(!uri.host.isNullOrBlank()) { "S3 endpoint host is required" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "S3 endpoint must not contain credentials, query, or fragment"
        }
        require(uri.path.isNullOrBlank() || uri.path == "/") {
            "S3 endpoint path is not supported"
        }
        return S3ResolvedEndpoint(
            scheme = uri.scheme.lowercase(),
            host = uri.host.lowercase(),
            port = uri.port,
        )
    }

    /**
     * 判断连接配置能否解析为有效 Endpoint。
     *
     * @param config S3 连接配置。
     * @return 配置有效时返回 true。
     */
    fun isValid(config: S3ConnectionConfig): Boolean = runCatching { resolve(config) }.isSuccess

    /**
     * 根据服务商和 Region 生成标准 Endpoint。
     *
     * @param config S3 连接配置。
     * @return 不带 Bucket 的 HTTPS Endpoint。
     */
    private fun presetEndpoint(config: S3ConnectionConfig): String {
        val region = config.region.trim()
        return when (config.provider) {
            S3ProviderPreset.AMAZON_S3 -> "https://s3.${region.ifBlank { DEFAULT_S3_REGION }}.amazonaws.com"
            S3ProviderPreset.TENCENT_COS -> {
                require(region.isNotBlank()) { "Tencent COS region is required" }
                "https://cos.$region.myqcloud.com"
            }

            S3ProviderPreset.ALIBABA_OSS -> {
                require(region.isNotBlank()) { "Alibaba OSS region is required" }
                "https://oss-$region.aliyuncs.com"
            }

            S3ProviderPreset.MINIO,
            S3ProviderPreset.CUSTOM,
            -> throw IllegalArgumentException("Explicit S3 endpoint is required")
        }
    }
}
