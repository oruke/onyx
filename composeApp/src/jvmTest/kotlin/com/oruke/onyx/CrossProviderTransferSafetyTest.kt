package com.oruke.onyx

import com.oruke.onyx.vfs.api.ProviderBackedFileCommandService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import com.oruke.onyx.vfs.local.JvmLocalFileProvider
import com.oruke.onyx.vfs.local.LocalVfsProvider
import com.oruke.onyx.vfs.webdav.WebDavVfsProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 通过真实本地文件、WebDAV HTTP 和生产路由验证跨存储移动的数据保留。 */
class CrossProviderTransferSafetyTest {
    /** 跳过同名文件时保留源，但同批无冲突文件仍应完成写入并删除源。 */
    @Test
    fun skippedFileRemainsWhileOtherFileMoves() = runBlocking {
        withTransferEnvironment { root, local, service, server ->
            val skipped = Files.writeString(root.resolve("note.txt"), "source-new")
            val moved = Files.writeString(root.resolve("free.txt"), "free-content")
            service.move(local.list(root.toString()).getOrThrow(), server.location, TransferConflictStrategy.SKIP)
                .getOrThrow()
            assertEquals("source-new", Files.readString(skipped))
            assertFalse(Files.exists(moved))
            assertEquals("destination-old", server.contents["/target/note.txt"])
            assertEquals("free-content", server.contents["/target/free.txt"])
            assertTrue(server.requests.none { it == "PUT /target/note.txt" })
        }
    }

    /** 完整目录移动成功后源目录被删除，最终 HTTP 内容与源一致。 */
    @Test
    fun completeDirectoryMoveRemovesSourceOnlyAfterWriting() = runBlocking {
        withTransferEnvironment { root, local, service, server ->
            val folder = Files.createDirectory(root.resolve("folder"))
            Files.writeString(folder.resolve("free.txt"), "directory-content")
            service.move(local.list(root.toString()).getOrThrow(), server.location, TransferConflictStrategy.SKIP)
                .getOrThrow()
            assertFalse(Files.exists(folder))
            assertEquals("directory-content", server.contents["/target/folder/free.txt"])
        }
    }

    /** 新目录中的子项冲突只保留被跳过子项，不能递归删除含未移动内容的源父目录。 */
    @Test
    fun partiallySkippedDirectoryRetainsUnmovedChildren() = runBlocking {
        withTransferEnvironment { root, local, service, server ->
            server.collideAfterDirectoryCreation = true
            val folder = Files.createDirectory(root.resolve("folder"))
            val skipped = Files.writeString(folder.resolve("note.txt"), "source-new")
            val moved = Files.writeString(folder.resolve("free.txt"), "free-content")
            service.move(local.list(root.toString()).getOrThrow(), server.location, TransferConflictStrategy.SKIP)
                .getOrThrow()
            assertEquals("source-new", Files.readString(skipped))
            assertFalse(Files.exists(moved))
            assertEquals("concurrent-content", server.contents["/target/folder/note.txt"])
            assertEquals("free-content", server.contents["/target/folder/free.txt"])
        }
    }

    /** 目标写入失败时不删除任何源内容。 */
    @Test
    fun failedWriteRetainsSource() = runBlocking {
        withTransferEnvironment { root, local, service, server ->
            server.rejectWrites = true
            val source = Files.writeString(root.resolve("free.txt"), "source-new")
            val result =
                service.move(local.list(root.toString()).getOrThrow(), server.location, TransferConflictStrategy.SKIP)
            assertTrue(result.isFailure)
            assertEquals("source-new", Files.readString(source))
            assertFalse(server.contents.containsKey("/target/free.txt"))
        }
    }

    /** 保留两者的成功移动不得改变原远程文件。 */
    @Test
    fun keepBothCommitsRealHttpContentBeforeDeletingSource() = runBlocking {
        withTransferEnvironment { root, local, service, server ->
            val source = Files.writeString(root.resolve("note.txt"), "source-new")
            service.move(local.list(root.toString()).getOrThrow(), server.location, TransferConflictStrategy.KEEP_BOTH)
                .getOrThrow()
            assertFalse(Files.exists(source))
            assertEquals("destination-old", server.contents["/target/note.txt"])
            assertEquals(listOf("destination-old", "source-new"), server.contents.values.sorted())
        }
    }

    /**
     * 创建独占文件目录和真实 HTTP 环境，通过生产 Provider 运行整个传输链。
     *
     * @param block 真实源目录、本地 Provider、路由服务和远程测试环境上的验收流程。
     */
    private suspend fun withTransferEnvironment(
        block: suspend (Path, JvmLocalFileProvider, ProviderBackedFileCommandService, TransferWebDavServer) -> Unit,
    ) {
        val root = Files.createTempDirectory("onyx-cross-provider-safety-")
        try {
            TransferWebDavServer().use { server ->
                val local = JvmLocalFileProvider()
                val dav = WebDavVfsProvider()
                val service = ProviderBackedFileCommandService(
                    services = listOf(local, dav), contentServices = listOf(local, dav),
                    providerRegistry = VfsProviderRegistry(listOf(LocalVfsProvider(local), dav)),
                )
                block(root, local, service, server)
            }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}

/** 监听随机回环端口的真实 WebDAV 测试环境，支持目录枚举与写入冲突。 */
private class TransferWebDavServer : AutoCloseable {
    /** HTTP 请求记录，用于核对跳过时没有执行 PUT。 */
    val requests = CopyOnWriteArrayList<String>()

    /** 远程文件的最终内容。 */
    val contents = ConcurrentHashMap<String, String>().apply { put("/target/note.txt", "destination-old") }

    /** 已创建的远程目录。 */
    private val directories = ConcurrentHashMap.newKeySet<String>().apply { add("/target") }

    /** 是否在新建目录后模拟并发同名子文件。 */
    var collideAfterDirectoryCreation = false

    /** 是否拒绝 PUT，用于验证失败时保留源。 */
    var rejectWrites = false

    /** 本场景独占的回环 HTTP 服务。 */
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    /** 提供给生产 Provider 的随机端口 VFS 位置。 */
    val location: String get() = "webdav://localhost:${server.address.port}/target/"

    init {
        server.createContext("/", ::handleRequest)
        server.start()
    }

    /**
     * 处理生产 Provider 发来的请求，未知操作显式返回失败。
     *
     * @param exchange 本次 HTTP 请求和响应通道。
     */
    private fun handleRequest(exchange: HttpExchange) = exchange.use {
        val path = exchange.requestURI.path.trimEnd('/')
        requests += "${exchange.requestMethod} $path"
        var body = ""
        val status = when (exchange.requestMethod) {
            "PROPFIND" -> if (path in directories || contents.containsKey(path)) {
                body = directoryResponse(path)
                207
            } else 404

            "MKCOL" -> {
                directories += path
                if (collideAfterDirectoryCreation) contents["$path/note.txt"] = "concurrent-content"
                201
            }

            "PUT" -> if (rejectWrites) 500 else {
                contents[path] = exchange.requestBody.readAllBytes().decodeToString()
                201
            }

            else -> 405
        }
        val bytes = body.encodeToByteArray()
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
    }

    /**
     * 返回目录本身及直接子项的标准 WebDAV 多状态 XML。
     *
     * @param path 本次枚举路径。
     * @return 可被生产解析器读取的 XML。
     */
    private fun directoryResponse(path: String): String {
        val entries = (directories + contents.keys).filter { it == path || it.substringBeforeLast('/') == path }
        return "<D:multistatus xmlns:D=\"DAV:\">" + entries.joinToString("") { entry ->
            val kind = if (entry in directories) "<D:collection/>" else ""
            "<D:response><D:href>$entry</D:href><D:propstat><D:prop>" +
                    "<D:resourcetype>$kind</D:resourcetype><D:getcontentlength>${contents[entry]?.length ?: 0}</D:getcontentlength>" +
                    "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
        } + "</D:multistatus>"
    }

    /** 关闭本测试独占的服务器。 */
    override fun close() = server.stop(0)
}
