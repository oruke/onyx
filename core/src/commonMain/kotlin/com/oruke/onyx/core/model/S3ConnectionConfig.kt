package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

/** Amazon S3 与 MinIO 通用的默认签名 Region。 */
const val DEFAULT_S3_REGION = "us-east-1"

/** S3 兼容服务的非密钥连接参数。 */
@Serializable
data class S3ConnectionConfig(
    /** 服务商预设。 */
    val provider: S3ProviderPreset = S3ProviderPreset.AMAZON_S3,
    /** 自定义或覆盖预设的 HTTP(S) Endpoint；空值表示使用预设推导地址。 */
    val endpoint: String = "",
    /** 签名与 Endpoint 推导使用的 Region。 */
    val region: String = DEFAULT_S3_REGION,
    /** Bucket 在请求 URL 中的寻址方式。 */
    val addressingStyle: S3AddressingStyle = S3AddressingStyle.VIRTUAL_HOSTED,
)

/** S3 兼容服务商预设。 */
@Serializable
enum class S3ProviderPreset {
    AMAZON_S3,
    TENCENT_COS,
    ALIBABA_OSS,
    MINIO,
    CUSTOM,
}

/** S3 Bucket 请求寻址方式。 */
@Serializable
enum class S3AddressingStyle {
    VIRTUAL_HOSTED,
    PATH_STYLE,
}

/**
 * 返回服务商预设的初始连接参数。
 *
 * @return 带默认 Region 与寻址方式的新配置。
 */
fun S3ProviderPreset.defaultS3ConnectionConfig(): S3ConnectionConfig {
    return when (this) {
        S3ProviderPreset.AMAZON_S3 -> S3ConnectionConfig(provider = this)
        S3ProviderPreset.TENCENT_COS,
        S3ProviderPreset.ALIBABA_OSS,
        -> S3ConnectionConfig(
            provider = this,
            region = "",
            addressingStyle = S3AddressingStyle.VIRTUAL_HOSTED,
        )

        S3ProviderPreset.MINIO,
        S3ProviderPreset.CUSTOM,
        -> S3ConnectionConfig(
            provider = this,
            addressingStyle = S3AddressingStyle.PATH_STYLE,
        )
    }
}

/**
 * 判断预设是否必须显式填写 Endpoint。
 *
 * @return MinIO 和自定义服务返回 true。
 */
fun S3ProviderPreset.requiresExplicitEndpoint(): Boolean {
    return this == S3ProviderPreset.MINIO || this == S3ProviderPreset.CUSTOM
}

/**
 * 判断预设是否允许用户切换 Bucket 寻址方式。
 *
 * 腾讯云新 Bucket 与阿里云 OSS 预设固定使用虚拟主机方式。
 *
 * @return 支持选择寻址方式时返回 true。
 */
fun S3ProviderPreset.allowsAddressingStyleSelection(): Boolean {
    return this != S3ProviderPreset.TENCENT_COS && this != S3ProviderPreset.ALIBABA_OSS
}
