package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.RemoteConnectionDialogError
import com.oruke.onyx.app.component.RemoteConnectionDraft
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.core.model.S3AddressingStyle
import com.oruke.onyx.core.model.S3ConnectionConfig
import com.oruke.onyx.core.model.S3ProviderPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 通用 S3 网络位置表单校验测试。 */
class RemoteConnectionDraftValidationTest {
    /** 验证 MinIO 和自定义预设必须填写 Endpoint。 */
    @Test
    fun explicitEndpointPresetRequiresEndpoint() {
        val draft = validS3Draft().copy(
            s3Config = S3ConnectionConfig(provider = S3ProviderPreset.MINIO),
        )

        assertEquals(RemoteConnectionDialogError.S3_ENDPOINT_INVALID, draft.saveValidationError(false))
    }

    /** 验证腾讯云和阿里云预设必须填写 Region。 */
    @Test
    fun regionalCloudPresetRequiresRegion() {
        val draft = validS3Draft().copy(
            s3Config = S3ConnectionConfig(
                provider = S3ProviderPreset.TENCENT_COS,
                region = "",
            ),
        )

        assertEquals(RemoteConnectionDialogError.S3_REGION_EMPTY, draft.saveValidationError(false))
    }

    /** 验证阿里云预设拒绝路径寻址，MinIO 的有效路径寻址配置可以保存。 */
    @Test
    fun addressingStyleFollowsProviderCapabilities() {
        val alibaba = validS3Draft().copy(
            s3Config = S3ConnectionConfig(
                provider = S3ProviderPreset.ALIBABA_OSS,
                region = "cn-hangzhou",
                addressingStyle = S3AddressingStyle.PATH_STYLE,
            ),
        )
        val minio = validS3Draft().copy(
            s3Config = S3ConnectionConfig(
                provider = S3ProviderPreset.MINIO,
                endpoint = "http://minio.example.test:9000",
                addressingStyle = S3AddressingStyle.PATH_STYLE,
            ),
        )

        assertEquals(RemoteConnectionDialogError.S3_ENDPOINT_INVALID, alibaba.saveValidationError(false))
        assertNull(minio.saveValidationError(false))
    }

    /**
     * 构造带脱敏凭据的基础 S3 草稿。
     *
     * @return 可通过基础地址与凭据校验的草稿。
     */
    private fun validS3Draft(): RemoteConnectionDraft = RemoteConnectionDraft(
        name = "Sample S3",
        protocol = RemoteConnectionProtocol.S3,
        location = "s3://sample-bucket/",
        username = "sample-access-key",
        secret = "sample-secret-key",
        secretChanged = true,
    )
}
