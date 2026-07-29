package com.oruke.onyx.vfs.archive

import com.oruke.onyx.vfs.api.RoutableVfsRandomAccessService
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsRandomAccessHandle
import com.oruke.onyx.vfs.api.VfsRandomAccessMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile

/**
 * 为独立使用 `ArchiveService` 的调用方提供本地文件兼容入口。
 *
 * 应用运行时会注入完整 VFS 随机访问注册表；该实现只负责没有 DI 的本地测试与工具调用。
 */
internal object StandaloneArchiveRandomAccessService : RoutableVfsRandomAccessService {
    /**
     * 判断位置是否为独立模式能够读取的本地路径。
     *
     * @param location 待检查位置。
     * @return 不含 VFS 协议时返回 `true`。
     */
    override fun supports(location: String): Boolean {
        return !location.contains("://")
    }

    /**
     * 为独立归档调用打开本地只读随机访问句柄。
     *
     * @param location 本地文件路径。
     * @param mode 打开模式，仅支持只读。
     * @return 本地随机访问句柄或不支持错误。
     */
    override suspend fun openRandomAccess(
        location: String,
        mode: VfsRandomAccessMode,
    ): Result<VfsRandomAccessHandle> = withContext(Dispatchers.IO) {
        if (!supports(location) || mode != VfsRandomAccessMode.READ) {
            return@withContext Result.failure(
                VfsProviderException(
                    VfsProviderError.UnsupportedOperation(
                        protocol = VfsProtocol.LOCAL,
                        location = location,
                        capability = if (mode == VfsRandomAccessMode.READ) {
                            VfsProviderCapability.READ_RANDOM_ACCESS
                        } else {
                            VfsProviderCapability.WRITE_RANDOM_ACCESS
                        },
                    )
                )
            )
        }
        runCatching {
            StandaloneArchiveRandomAccessHandle(RandomAccessFile(location, "r"))
        }
    }
}

/**
 * 独立归档调用使用的本地只读随机访问句柄。
 *
 * @param file 已打开的本地文件。
 */
private class StandaloneArchiveRandomAccessHandle(
    private val file: RandomAccessFile,
) : VfsRandomAccessHandle {
    override val mode: VfsRandomAccessMode = VfsRandomAccessMode.READ

    /**
     * 查询本地归档文件长度。
     *
     * @return 文件字节数。
     */
    override suspend fun length(): Long = withContext(Dispatchers.IO) {
        file.length()
    }

    /**
     * 从指定偏移读取本地归档数据。
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
        require(position >= 0L) {
            "File position must not be negative"
        }
        require(bufferOffset >= 0 && byteCount >= 0 && bufferOffset <= buffer.size - byteCount) {
            "Buffer range is out of bounds"
        }
        synchronized(file) {
            file.seek(position)
            file.read(buffer, bufferOffset, byteCount)
        }
    }

    /**
     * 关闭本地归档文件。
     */
    override fun close() {
        file.close()
    }
}
