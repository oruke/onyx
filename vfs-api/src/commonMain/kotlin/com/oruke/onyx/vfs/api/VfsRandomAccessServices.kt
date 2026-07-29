package com.oruke.onyx.vfs.api

/**
 * 随机访问文件的打开模式。
 */
enum class VfsRandomAccessMode {
    READ,
    READ_WRITE,
}

/**
 * 可按绝对偏移读写的 VFS 文件句柄。
 *
 * 该接口刻意使用挂起函数表达可能发生的网络 I/O；需要同步回调的第三方库应在适配层完成线程桥接。
 */
interface VfsRandomAccessHandle {
    /** 当前句柄的打开模式。 */
    val mode: VfsRandomAccessMode

    /**
     * 查询当前文件长度。
     *
     * @return 文件字节数。
     */
    suspend fun length(): Long

    /**
     * 从指定绝对位置读取数据。
     *
     * @param position 文件内的绝对字节偏移。
     * @param buffer 接收数据的缓冲区。
     * @param bufferOffset 缓冲区写入起始位置。
     * @param byteCount 最多读取的字节数。
     * @return 实际读取字节数；到达文件末尾时返回 `-1`。
     */
    suspend fun readAt(
        position: Long,
        buffer: ByteArray,
        bufferOffset: Int,
        byteCount: Int,
    ): Int

    /**
     * 从指定绝对位置写入完整数据段。
     *
     * @param position 文件内的绝对字节偏移。
     * @param buffer 待写入数据。
     * @param bufferOffset 缓冲区读取起始位置。
     * @param byteCount 需要写入的字节数。
     */
    suspend fun writeAt(
        position: Long,
        buffer: ByteArray,
        bufferOffset: Int,
        byteCount: Int,
    ) {
        throw UnsupportedOperationException("Random access handle is read-only")
    }

    /**
     * 调整文件长度。
     *
     * @param length 新的文件字节数。
     */
    suspend fun setLength(length: Long) {
        throw UnsupportedOperationException("Random access handle is read-only")
    }

    /**
     * 将待提交写入刷新到远端或本地存储。
     */
    suspend fun flush() = Unit

    /**
     * 关闭随机访问句柄并释放连接。
     */
    fun close()
}

/**
 * 可按位置路由的 VFS 随机访问服务。
 */
interface RoutableVfsRandomAccessService {
    /**
     * 判断服务是否支持指定位置。
     *
     * @param location 待检查的 VFS 位置。
     * @return 支持时返回 `true`。
     */
    fun supports(location: String): Boolean

    /**
     * 打开随机访问文件。
     *
     * @param location 文件 VFS 位置。
     * @param mode 打开模式。
     * @return 随机访问句柄或结构化失败。
     */
    suspend fun openRandomAccess(
        location: String,
        mode: VfsRandomAccessMode = VfsRandomAccessMode.READ,
    ): Result<VfsRandomAccessHandle>
}

/**
 * 随机访问服务注册表，负责把文件位置路由到具体协议实现。
 *
 * @param services 已注册服务列表。
 */
class VfsRandomAccessServiceRegistry(
    services: List<RoutableVfsRandomAccessService>,
) : RoutableVfsRandomAccessService {
    /** 不可变的协议服务快照。 */
    private val services = services.toList()

    init {
        require(this.services.isNotEmpty()) {
            "At least one VFS random access service must be registered"
        }
    }

    /**
     * 判断是否存在能够处理指定位置的协议服务。
     *
     * @param location 待检查的 VFS 位置。
     * @return 至少一个服务支持时返回 `true`。
     */
    override fun supports(location: String): Boolean {
        return services.any { service -> service.supports(location) }
    }

    /**
     * 把随机访问请求转发给首个支持该位置的协议服务。
     *
     * @param location 文件 VFS 位置。
     * @param mode 打开模式。
     * @return 随机访问句柄；没有匹配服务时返回 Provider 未找到错误。
     */
    override suspend fun openRandomAccess(
        location: String,
        mode: VfsRandomAccessMode,
    ): Result<VfsRandomAccessHandle> {
        val service = services.firstOrNull { candidate -> candidate.supports(location) }
            ?: return Result.failure(VfsProviderNotFoundException(location))
        return service.openRandomAccess(location, mode)
    }
}
