package com.oruke.onyx.vfs.api

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验证随机访问服务注册表的协议路由与句柄调用。
 */
class VfsRandomAccessServiceRegistryTest {
    /**
     * 注册表应把位置交给首个支持该协议的服务。
     */
    @Test
    fun routesLocationToMatchingService() = runBlocking {
        val source = byteArrayOf(10, 20, 30, 40)
        val registry = VfsRandomAccessServiceRegistry(
            listOf(
                ByteArrayRandomAccessService("smb://", source),
                ByteArrayRandomAccessService("webdav://", byteArrayOf(1)),
            )
        )

        val handle = registry.openRandomAccess("smb://server/share/file.bin").getOrThrow()
        val buffer = ByteArray(2)

        assertEquals(2, handle.readAt(1, buffer, 0, buffer.size))
        assertContentEquals(byteArrayOf(20, 30), buffer)
        assertTrue(registry.supports("webdav://server/file.bin"))
        assertFalse(registry.supports("s3://bucket/file.bin"))
    }
}

/**
 * 从内存字节数组提供只读随机访问的测试服务。
 *
 * @param prefix 支持的位置前缀。
 * @param content 文件内容。
 */
private class ByteArrayRandomAccessService(
    private val prefix: String,
    private val content: ByteArray,
) : RoutableVfsRandomAccessService {
    override fun supports(location: String): Boolean = location.startsWith(prefix)

    override suspend fun openRandomAccess(
        location: String,
        mode: VfsRandomAccessMode,
    ): Result<VfsRandomAccessHandle> {
        return Result.success(ByteArrayRandomAccessHandle(content))
    }
}

/**
 * 内存字节数组随机访问句柄。
 *
 * @param content 文件内容。
 */
private class ByteArrayRandomAccessHandle(
    private val content: ByteArray,
) : VfsRandomAccessHandle {
    override val mode: VfsRandomAccessMode = VfsRandomAccessMode.READ

    override suspend fun length(): Long = content.size.toLong()

    override suspend fun readAt(
        position: Long,
        buffer: ByteArray,
        bufferOffset: Int,
        byteCount: Int,
    ): Int {
        if (position >= content.size) return -1
        val read = minOf(byteCount, content.size - position.toInt())
        content.copyInto(buffer, bufferOffset, position.toInt(), position.toInt() + read)
        return read
    }

    override fun close() = Unit
}
