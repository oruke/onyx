package com.oruke.onyx.vfs.s3

import com.oruke.onyx.core.model.S3ConnectionConfig
import com.oruke.onyx.core.model.S3ProviderPreset
import kotlin.test.Test
import kotlin.test.assertEquals

/** 运行期 S3 连接配置索引测试。 */
class S3ConnectionRepositoryTest {
    /** 验证嵌套根位置优先于父级配置，未注册位置回退到 AWS 默认值。 */
    @Test
    fun configurationUsesLongestMatchingRoot() {
        val repository = MutableS3ConnectionRepository()
        val parentConfig = S3ConnectionConfig(provider = S3ProviderPreset.MINIO, endpoint = "http://minio.test:9000")
        val nestedConfig = S3ConnectionConfig(provider = S3ProviderPreset.TENCENT_COS, region = "ap-guangzhou")
        repository.replaceAll(
            listOf(
                S3ConnectionRegistration("s3://bucket/", parentConfig),
                S3ConnectionRegistration("s3://bucket/archive/", nestedConfig),
            ),
        )

        assertEquals(nestedConfig, repository.configuration("s3://bucket/archive/2026/file.txt"))
        assertEquals(parentConfig, repository.configuration("s3://bucket/photos/file.jpg"))
        assertEquals(S3ConnectionConfig(region = ""), repository.configuration("s3://other/file.txt"))
    }
}
