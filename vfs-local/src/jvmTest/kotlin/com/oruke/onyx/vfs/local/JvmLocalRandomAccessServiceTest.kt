package com.oruke.onyx.vfs.local

import com.oruke.onyx.vfs.api.VfsRandomAccessMode
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * 验证本地与系统挂载路径的随机读写实现。
 */
class JvmLocalRandomAccessServiceTest {
    /**
     * 读写句柄应支持绝对偏移写入、截断与刷新。
     */
    @Test
    fun readsAndWritesAtAbsoluteOffsets() = runBlocking {
        val path = Files.createTempFile("onyx-random-access-", ".bin")
        try {
            Files.write(path, byteArrayOf(1, 2, 3, 4, 5))
            val handle = JvmLocalFileProvider()
                .openRandomAccess(path.toString(), VfsRandomAccessMode.READ_WRITE)
                .getOrThrow()
            try {
                handle.writeAt(1, byteArrayOf(9, 8), 0, 2)
                handle.setLength(4)
                handle.flush()

                val buffer = ByteArray(4)
                assertEquals(4, handle.readAt(0, buffer, 0, buffer.size))
                assertContentEquals(byteArrayOf(1, 9, 8, 4), buffer)
                assertEquals(4L, handle.length())
            } finally {
                handle.close()
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
