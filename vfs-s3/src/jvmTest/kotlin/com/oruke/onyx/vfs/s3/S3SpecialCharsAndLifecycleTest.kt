package com.oruke.onyx.vfs.s3

import com.oruke.onyx.core.model.S3ConnectionConfig
import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.utils.io.InternalAPI
import java.nio.channels.UnresolvedAddressException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 验证 S3 对象路径特殊字符 RFC 3986 编解码（`REM-04`）及 Ktor S3 客户端异常映射与关闭生命周期（`REM-03`）。
 */
class S3SpecialCharsAndLifecycleTest {
    /** 验证 S3Location 对含 `+`、`#`、`?`、`%`、`[`、`]`、空格和中文的 key 往返解析不截断不失真。 */
    @Test
    fun s3LocationRoundTripsSpecialCharacterObjectKeys() {
        val base = S3Location(bucket = "media-bucket", prefix = "")
        val keys = listOf(
            "docs/c++ 100% notes.txt",
            "photos/trip#2?raw=1[hdr]/中文 照片+1.png",
            "archives/release%20v1.0#final.tar.zst",
        )

        keys.forEach { key ->
            val encodedLocation = base.toLocation(key, directory = false)
            assertTrue("#" !in encodedLocation, "编码后的 S3 位置不得残留裸 #: $encodedLocation")
            assertTrue("?" !in encodedLocation, "编码后的 S3 位置不得残留裸 ?: $encodedLocation")

            val reparsed = S3Location.parse(encodedLocation)
            assertEquals("media-bucket", reparsed.bucket)
            assertEquals(key, reparsed.objectKey)
        }
    }

    /** 验证未编码的含 `#` 与 `+` 原始路径输入也能被 `S3Location.parse` 完整保留而非被当成 fragment 截断。 */
    @Test
    fun s3LocationParsePreservesRawHashAndPlusInPath() {
        val parsed = S3Location.parse("s3://archive-bucket/release+2026/build#42/notes.md")
        assertEquals("archive-bucket", parsed.bucket)
        assertEquals("release+2026/build#42/notes.md", parsed.objectKey)
    }

    /** 验证 `objectExists` 与 `testConnection` 均将超时与 DNS 失败映射为结构化 `NetworkFailure`，且 `close()` 幂等。 */
    @Test
    fun mapsObjectExistsAndConnectionErrorsToNetworkFailureAndClosesSafely() = runBlocking {
        val auth = VfsAuthContext.AwsCredentials(
            accessKeyId = "AKIAEXAMPLE",
            secretAccessKey = "secret-key",
        )
        val config = S3ConnectionConfig(
            endpoint = "https://s3.us-east-1.amazonaws.com",
            region = "us-east-1",
        )
        val location = S3Location(bucket = "demo", prefix = "dir/file.txt")

        val timeoutClient = KtorS3Client(
            httpClient = HttpClient(FailingS3Engine { HttpRequestTimeoutException("https://s3.example.com", 15_000L) }),
        )
        val existsError = assertFailsWith<VfsProviderException> {
            timeoutClient.objectExists(
                location = location,
                authContext = auth,
                connectionConfig = config,
            )
        }
        assertTrue(existsError.error is VfsProviderError.NetworkFailure)

        val dnsClient = KtorS3Client(
            httpClient = HttpClient(FailingS3Engine { UnresolvedAddressException() }),
        )
        val testError = assertFailsWith<VfsProviderException> {
            dnsClient.testConnection(
                location = location,
                authContext = auth,
                connectionConfig = config,
            )
        }
        assertTrue(testError.error is VfsProviderError.NetworkFailure)

        val ownedClient = KtorS3Client()
        ownedClient.close()
        ownedClient.close()
    }
}

/**
 * 确定性抛出指定异常的最小 S3 Ktor 测试引擎。
 */
private class FailingS3Engine(
    private val failureFactory: () -> Throwable,
) : HttpClientEngineBase("failing-s3-test") {
    override val config: HttpClientEngineConfig = HttpClientEngineConfig()

    @InternalAPI
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        throw failureFactory()
    }
}
