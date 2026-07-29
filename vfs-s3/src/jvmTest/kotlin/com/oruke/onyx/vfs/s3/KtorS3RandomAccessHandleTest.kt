package com.oruke.onyx.vfs.s3

import com.oruke.onyx.core.model.S3AddressingStyle
import com.oruke.onyx.core.model.S3ConnectionConfig
import com.oruke.onyx.core.model.S3ProviderPreset
import com.oruke.onyx.vfs.api.VfsAuthContext
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * 验证 S3 兼容对象使用签名 Range 请求随机读取。
 */
class KtorS3RandomAccessHandleTest {
    /**
     * 打开和读取应只请求探测字节与目标字节区间。
     */
    @Test
    fun readsRequestedS3Ranges() = runBlocking {
        val content = "0123456789".encodeToByteArray()
        val requestedRanges = Collections.synchronizedList(mutableListOf<String>())
        val server = createRangeServer(content, requestedRanges)
        val httpClient = HttpClient(CIO)
        try {
            val handle = KtorS3RandomAccessHandle.open(
                httpClient = httpClient,
                signer = S3RequestSigner(),
                location = S3Location.parse("s3://bucket/path/file.bin"),
                authContext = VfsAuthContext.AwsCredentials(
                    accessKeyId = "<ACCESS_KEY_ID>",
                    secretAccessKey = "<SECRET_ACCESS_KEY>",
                    region = "us-east-1",
                ),
                connectionConfig = S3ConnectionConfig(
                    provider = S3ProviderPreset.CUSTOM,
                    endpoint = "http://localhost:${server.address.port}",
                    region = "us-east-1",
                    addressingStyle = S3AddressingStyle.PATH_STYLE,
                ),
            )
            try {
                val buffer = ByteArray(4)
                assertEquals(4, handle.readAt(3, buffer, 0, buffer.size))
                assertContentEquals("3456".encodeToByteArray(), buffer)
                assertEquals(10L, handle.length())
                assertEquals(listOf("bytes=0-0", "bytes=3-6"), requestedRanges)
            } finally {
                handle.close()
            }
        } finally {
            httpClient.close()
            server.stop(0)
        }
    }

    /**
     * 创建忽略签名内容、仅响应单 Range 请求的本地 S3 兼容服务。
     *
     * @param content 完整对象内容。
     * @param requestedRanges 记录收到的 Range 请求头。
     * @return 已启动的 HTTP 服务。
     */
    private fun createRangeServer(
        content: ByteArray,
        requestedRanges: MutableList<String>,
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext("/bucket/path/file.bin") { exchange ->
            val range = requireNotNull(exchange.requestHeaders.getFirst("Range"))
            requestedRanges += range
            val (start, end) = range.removePrefix("bytes=")
                .split('-', limit = 2)
                .map(String::toInt)
            val body = content.copyOfRange(start, end + 1)
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${content.size}")
            exchange.responseHeaders.add("ETag", "\"s3-version\"")
            exchange.sendResponseHeaders(206, body.size.toLong())
            exchange.responseBody.use { output -> output.write(body) }
        }
        server.start()
        return server
    }
}
