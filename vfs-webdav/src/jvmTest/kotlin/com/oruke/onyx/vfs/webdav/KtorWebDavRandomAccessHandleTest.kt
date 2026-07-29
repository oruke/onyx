package com.oruke.onyx.vfs.webdav

import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsRandomAccessMode
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
 * 验证 WebDAV 随机访问使用 HTTP Range 而不是下载完整文件。
 */
class KtorWebDavRandomAccessHandleTest {
    /**
     * 打开和读取应分别发送最小探测 Range 与目标字节 Range。
     */
    @Test
    fun readsRequestedWebDavRanges() = runBlocking {
        val content = "abcdef".encodeToByteArray()
        val requestedRanges = Collections.synchronizedList(mutableListOf<String>())
        val server = createRangeServer(content, requestedRanges)
        val httpClient = HttpClient(CIO)
        try {
            val location = "webdav://localhost:${server.address.port}/file.bin"
            val handle = KtorWebDavClient(httpClient).openRandomAccess(
                location = location,
                mode = VfsRandomAccessMode.READ,
                authContext = VfsAuthContext.None,
            )
            try {
                val buffer = ByteArray(3)
                assertEquals(3, handle.readAt(2, buffer, 0, buffer.size))
                assertContentEquals("cde".encodeToByteArray(), buffer)
                assertEquals(6L, handle.length())
                assertEquals(listOf("bytes=0-0", "bytes=2-4"), requestedRanges)
            } finally {
                handle.close()
            }
        } finally {
            httpClient.close()
            server.stop(0)
        }
    }

    /**
     * 创建仅响应单 Range 请求的本地 HTTP 服务。
     *
     * @param content 完整文件内容。
     * @param requestedRanges 记录收到的 Range 请求头。
     * @return 已启动的 HTTP 服务。
     */
    private fun createRangeServer(
        content: ByteArray,
        requestedRanges: MutableList<String>,
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext("/file.bin") { exchange ->
            val range = requireNotNull(exchange.requestHeaders.getFirst("Range"))
            requestedRanges += range
            val (start, end) = range.removePrefix("bytes=")
                .split('-', limit = 2)
                .map(String::toInt)
            val body = content.copyOfRange(start, end + 1)
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${content.size}")
            exchange.responseHeaders.add("ETag", "\"webdav-version\"")
            exchange.sendResponseHeaders(206, body.size.toLong())
            exchange.responseBody.use { output -> output.write(body) }
        }
        server.start()
        return server
    }
}
