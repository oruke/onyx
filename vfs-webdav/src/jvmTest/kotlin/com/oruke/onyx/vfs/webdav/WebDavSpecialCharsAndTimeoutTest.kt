package com.oruke.onyx.vfs.webdav

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
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 验证 WebDAV 特殊字符 RFC 3986 编码/解码（`REM-04`）及 Ktor 客户端超时、异常映射与资源关闭（`REM-03`）。
 */
class WebDavSpecialCharsAndTimeoutTest {
    /** 验证含 `+`、`#`、`?`、`%`、`[`、`]`、空格及中文的文件名在往返编码后完整保留，且 `+` 绝不被误解码为空格。 */
    @Test
    fun roundTripsSpecialCharactersWithoutFormDecodingPlusOrTruncatingHash() {
        val trickyNames = listOf(
            "c++ 100% draft.txt",
            "report#2?v=1[final].pdf",
            "日本語+中文#100% 备份.zip",
        )
        val parent = "webdavs://dav.example.com:8443/library/team+docs/"

        trickyNames.forEach { fileName ->
            val childLocation = webDavChildLocation(parent, fileName, directory = false)
            assertEquals(fileName, childLocation.fileNameFromWebDavLocation())
            assertEquals(parent, childLocation.parentWebDavLocation())

            val httpUrl = childLocation.toHttpWebDavUrl()
            assertTrue(httpUrl.startsWith("https://dav.example.com:8443/library/team+docs/"))
            assertTrue("#" !in httpUrl, "HTTP URL 中的 # 必须已百分号编码: $httpUrl")
            assertTrue("?" !in httpUrl, "HTTP URL 中的 ? 必须已百分号编码: $httpUrl")
        }
    }

    /** 验证 PROPFIND 多状态解析器保留 href 中的字面量 `+`、`#` 与 `%25`。 */
    @Test
    fun multiStatusParserPreservesPlusAndEncodedSpecialChars() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/share/docs/</d:href>
                <d:propstat>
                  <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/share/docs/c++%20guide%231%3Fv%3D2%25ok.md</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype/>
                    <d:getcontentlength>42</d:getcontentlength>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val entries = WebDavMultiStatusParser().parse(
            xml = xml,
            requestLocation = "webdav://dav.example.com/share/docs/",
            requestHttpUrl = "http://dav.example.com/share/docs/",
        )

        assertEquals(1, entries.size)
        assertEquals("c++ guide#1?v=2%ok.md", entries.single().name)
        assertEquals("c++ guide#1?v=2%ok.md", entries.single().location.fileNameFromWebDavLocation())
    }

    /** 验证域名不可解析与请求超时均映射为结构化 `NetworkFailure`，且 `close()` 安全幂等。 */
    @Test
    fun mapsUnresolvedAddressAndTimeoutToNetworkFailureAndSupportsClose() = runBlocking {
        val unresolvedClient = KtorWebDavClient(
            httpClient = HttpClient(FailingWebDavEngine { UnresolvedAddressException() }),
        )
        val unresolvedError = assertFailsWith<VfsProviderException> {
            unresolvedClient.testConnection(
                location = "webdav://nonexistent.invalid/root/",
                authContext = VfsAuthContext.None,
            )
        }
        assertTrue(unresolvedError.error is VfsProviderError.NetworkFailure)

        val timeoutClient = KtorWebDavClient(
            httpClient = HttpClient(FailingWebDavEngine { HttpRequestTimeoutException("https://dav.example.com", 15_000L) }),
        )
        val timeoutError = assertFailsWith<VfsProviderException> {
            timeoutClient.list(
                location = "webdav://dav.example.com/root/",
                authContext = VfsAuthContext.None,
            )
        }
        assertTrue(timeoutError.error is VfsProviderError.NetworkFailure)

        // 自有惰性客户端未触发网络访问时关闭应无副作用，多次关闭幂等。
        val ownedClient = KtorWebDavClient()
        ownedClient.close()
        ownedClient.close()
    }
}

/**
 * 确定性抛出指定异常的最小 Ktor 测试引擎。
 */
private class FailingWebDavEngine(
    private val failureFactory: () -> Throwable,
) : HttpClientEngineBase("failing-webdav-test") {
    override val config: HttpClientEngineConfig = HttpClientEngineConfig()

    @InternalAPI
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        throw failureFactory()
    }
}
