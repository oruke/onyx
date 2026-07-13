package com.oruke.onyx.vfs.s3

import com.oruke.onyx.core.model.S3AddressingStyle
import com.oruke.onyx.core.model.S3ConnectionConfig
import com.oruke.onyx.core.model.S3ProviderPreset
import com.oruke.onyx.vfs.api.VfsAuthContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** S3 服务商端点解析与 Signature V4 请求目标测试。 */
class S3EndpointAndSignerTest {
    /** 验证 AWS、腾讯云和阿里云预设会根据 Region 生成官方端点。 */
    @Test
    fun cloudPresetsResolveRegionalEndpoints() {
        assertEquals(
            "s3.eu-west-1.amazonaws.com",
            S3EndpointResolver.resolve(config(S3ProviderPreset.AMAZON_S3, "eu-west-1")).host,
        )
        assertEquals(
            "cos.ap-guangzhou.myqcloud.com",
            S3EndpointResolver.resolve(config(S3ProviderPreset.TENCENT_COS, "ap-guangzhou")).host,
        )
        assertEquals(
            "oss-cn-hangzhou.aliyuncs.com",
            S3EndpointResolver.resolve(config(S3ProviderPreset.ALIBABA_OSS, "cn-hangzhou")).host,
        )
    }

    /** 验证 MinIO 和自定义预设必须提供不含路径的 HTTP(S) Endpoint。 */
    @Test
    fun explicitEndpointRejectsMissingOrPathValues() {
        assertFailsWith<IllegalArgumentException> {
            S3EndpointResolver.resolve(config(S3ProviderPreset.MINIO, "us-east-1"))
        }
        assertFailsWith<IllegalArgumentException> {
            S3EndpointResolver.resolve(
                config(
                    provider = S3ProviderPreset.CUSTOM,
                    region = "us-east-1",
                    endpoint = "https://storage.example.com/root",
                ),
            )
        }
    }

    /** 验证四类预设会生成各自要求的主机、路径、签名区域与兼容请求头。 */
    @Test
    fun signerBuildsProviderSpecificRequestTargets() {
        val signer = S3RequestSigner(FIXED_CLOCK)
        val location = S3Location.parse("s3://media/photos/a b.jpg")

        val aws = signer.signGetObject(location, AUTH, config(S3ProviderPreset.AMAZON_S3, "eu-west-1"))
        val tencent = signer.signGetObject(location, AUTH, config(S3ProviderPreset.TENCENT_COS, "ap-guangzhou"))
        val alibaba = signer.signGetObject(location, AUTH, config(S3ProviderPreset.ALIBABA_OSS, "cn-hangzhou"))
        val minio = signer.signGetObject(
            location,
            AUTH,
            config(
                provider = S3ProviderPreset.MINIO,
                region = "us-east-1",
                endpoint = "http://minio.example.com:9000",
                addressingStyle = S3AddressingStyle.PATH_STYLE,
            ),
        )

        assertEquals("https://media.s3.eu-west-1.amazonaws.com/photos/a%20b.jpg", aws.url)
        assertEquals("https://media.cos.ap-guangzhou.myqcloud.com/photos/a%20b.jpg", tencent.url)
        assertEquals("https://media.oss-cn-hangzhou.aliyuncs.com/photos/a%20b.jpg", alibaba.url)
        assertEquals("true", alibaba.headers["x-oss-s3-compat"])
        assertEquals("http://minio.example.com:9000/media/photos/a%20b.jpg", minio.url)
        assertTrue(tencent.headers.getValue("Authorization").contains("/ap-guangzhou/s3/aws4_request"))
    }

    /** 验证未注册结构化配置的旧调用路径仍沿用凭据中的 Region。 */
    @Test
    fun signerFallsBackToCredentialRegionWithoutRegisteredConfig() {
        val signer = S3RequestSigner(FIXED_CLOCK)
        val request = signer.signGetObject(
            location = S3Location.parse("s3://media/file.txt"),
            authContext = AUTH.copy(region = "ap-southeast-1"),
        )

        assertEquals("https://media.s3.ap-southeast-1.amazonaws.com/file.txt", request.url)
        assertTrue(request.headers.getValue("Authorization").contains("/ap-southeast-1/s3/aws4_request"))
    }

    /**
     * 构造测试所需的 S3 连接配置。
     *
     * @param provider 服务商预设。
     * @param region 签名区域。
     * @param endpoint 显式 Endpoint。
     * @param addressingStyle Bucket 寻址方式。
     * @return S3 连接配置。
     */
    private fun config(
        provider: S3ProviderPreset,
        region: String,
        endpoint: String = "",
        addressingStyle: S3AddressingStyle = S3AddressingStyle.VIRTUAL_HOSTED,
    ): S3ConnectionConfig = S3ConnectionConfig(
        provider = provider,
        endpoint = endpoint,
        region = region,
        addressingStyle = addressingStyle,
    )

    private companion object {
        /** 固定签名时间，保证签名作用域断言稳定。 */
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-07-13T08:00:00Z"), ZoneOffset.UTC)

        /** 测试专用脱敏访问凭据。 */
        val AUTH = VfsAuthContext.AwsCredentials(
            accessKeyId = "test-access-key",
            secretAccessKey = "test-secret-key",
            region = "ignored-region",
        )
    }
}
