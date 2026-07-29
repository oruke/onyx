package com.oruke.onyx.vfs.local

import com.oruke.onyx.vfs.api.VfsRandomAccessHandle
import com.oruke.onyx.vfs.api.VfsRandomAccessMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile

/**
 * 基于 JVM `RandomAccessFile` 的本地随机访问句柄。
 *
 * @param file 已打开的本地文件。
 * @property mode 文件打开模式。
 */
internal class JvmLocalRandomAccessHandle(
    private val file: RandomAccessFile,
    override val mode: VfsRandomAccessMode,
) : VfsRandomAccessHandle {
    /**
     * 查询本地文件当前长度。
     *
     * @return 文件字节数。
     */
    override suspend fun length(): Long = withContext(Dispatchers.IO) {
        file.length()
    }

    /**
     * 从本地文件指定偏移读取数据。
     *
     * @param position 文件绝对偏移。
     * @param buffer 接收数据的缓冲区。
     * @param bufferOffset 缓冲区起始位置。
     * @param byteCount 最多读取字节数。
     * @return 实际读取字节数；到达末尾时返回 `-1`。
     */
    override suspend fun readAt(
        position: Long,
        buffer: ByteArray,
        bufferOffset: Int,
        byteCount: Int,
    ): Int = withContext(Dispatchers.IO) {
        validateRandomAccessRange(position, buffer, bufferOffset, byteCount)
        synchronized(file) {
            file.seek(position)
            file.read(buffer, bufferOffset, byteCount)
        }
    }

    /**
     * 向本地文件指定偏移写入数据。
     *
     * @param position 文件绝对偏移。
     * @param buffer 待写入数据。
     * @param bufferOffset 缓冲区起始位置。
     * @param byteCount 需要写入的字节数。
     */
    override suspend fun writeAt(
        position: Long,
        buffer: ByteArray,
        bufferOffset: Int,
        byteCount: Int,
    ) = withContext(Dispatchers.IO) {
        require(mode == VfsRandomAccessMode.READ_WRITE) {
            "Random access handle is read-only"
        }
        validateRandomAccessRange(position, buffer, bufferOffset, byteCount)
        synchronized(file) {
            file.seek(position)
            file.write(buffer, bufferOffset, byteCount)
        }
    }

    /**
     * 调整本地文件长度。
     *
     * @param length 新文件长度。
     */
    override suspend fun setLength(length: Long) = withContext(Dispatchers.IO) {
        require(mode == VfsRandomAccessMode.READ_WRITE) {
            "Random access handle is read-only"
        }
        require(length >= 0L) {
            "File length must not be negative"
        }
        synchronized(file) {
            file.setLength(length)
        }
    }

    /**
     * 将本地随机写入同步到物理存储。
     */
    override suspend fun flush() = withContext(Dispatchers.IO) {
        if (mode == VfsRandomAccessMode.READ_WRITE) {
            synchronized(file) {
                file.fd.sync()
            }
        }
    }

    /**
     * 关闭本地随机访问文件。
     */
    override fun close() {
        file.close()
    }

    /**
     * 校验随机访问的文件位置与缓冲区范围。
     *
     * @param position 文件绝对偏移。
     * @param buffer 数据缓冲区。
     * @param bufferOffset 缓冲区起始位置。
     * @param byteCount 操作字节数。
     */
    private fun validateRandomAccessRange(
        position: Long,
        buffer: ByteArray,
        bufferOffset: Int,
        byteCount: Int,
    ) {
        require(position >= 0L) {
            "File position must not be negative"
        }
        require(bufferOffset >= 0 && byteCount >= 0 && bufferOffset <= buffer.size - byteCount) {
            "Buffer range is out of bounds"
        }
    }
}
