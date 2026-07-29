package com.oruke.onyx.vfs.smb

import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsRandomAccessHandle
import com.oruke.onyx.vfs.api.VfsRandomAccessMode
import jcifs.SmbRandomAccess
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 通过 jcifs-ng 保持远端文件句柄的 SMB 随机访问实现。
 *
 * @param file jcifs-ng 随机访问文件。
 * @param location SMB 文件位置，用于结构化错误。
 * @property mode 文件打开模式。
 */
internal class JcifsSmbRandomAccessHandle(
    private val file: SmbRandomAccess,
    private val location: String,
    override val mode: VfsRandomAccessMode,
) : VfsRandomAccessHandle {
    /**
     * 查询远端 SMB 文件长度。
     *
     * @return 文件字节数。
     */
    override suspend fun length(): Long = withSmbRandomAccess {
        file.length()
    }

    /**
     * 从 SMB 文件指定偏移读取数据。
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
    ): Int = withSmbRandomAccess {
        validateRandomAccessRange(position, buffer, bufferOffset, byteCount)
        synchronized(file) {
            file.seek(position)
            file.read(buffer, bufferOffset, byteCount)
        }
    }

    /**
     * 向 SMB 文件指定偏移写入数据。
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
    ) = withSmbRandomAccess {
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
     * 调整 SMB 文件长度。
     *
     * @param length 新文件长度。
     */
    override suspend fun setLength(length: Long) = withSmbRandomAccess {
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
     * 完成协议级刷新。
     */
    override suspend fun flush() {
        // jcifs-ng 的随机写在每次 SMB WRITE 完成后即提交，接口没有额外 flush 操作。
    }

    /**
     * 关闭远端 SMB 文件句柄。
     */
    override fun close() {
        try {
            file.close()
        } catch (failure: SmbException) {
            throw failure.toRandomAccessException()
        }
    }

    /**
     * 在 I/O 调度器执行 SMB 操作并转换 jcifs 异常。
     *
     * @param block 实际 SMB 随机访问动作。
     * @return 动作结果。
     */
    private suspend fun <T> withSmbRandomAccess(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (failure: SmbException) {
            throw failure.toRandomAccessException()
        }
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

    /**
     * 将 jcifs 随机访问异常转换为统一 VFS 错误。
     *
     * @return 带 SMB 位置的结构化异常。
     */
    private fun SmbException.toRandomAccessException(): VfsProviderException {
        val providerError = when {
            this is SmbAuthException ->
                VfsProviderError.AuthenticationRejected(VfsProtocol.SMB, location, message)

            message?.contains("access", ignoreCase = true) == true ->
                VfsProviderError.PermissionDenied(VfsProtocol.SMB, location, message)

            message?.contains("not found", ignoreCase = true) == true ->
                VfsProviderError.NotFound(VfsProtocol.SMB, location)

            else -> VfsProviderError.NetworkFailure(VfsProtocol.SMB, location, message)
        }
        return VfsProviderException(providerError, this)
    }
}
