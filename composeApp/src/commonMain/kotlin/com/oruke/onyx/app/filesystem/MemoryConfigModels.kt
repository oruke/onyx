package com.oruke.onyx.app.filesystem

/**
 * JVM 堆内存运行期快照与启动配置状态，用于设置页展示生效上限与写回目标。
 */
internal data class JvmMemoryStatus(
    /** 当前进程实际生效的 JVM 最大堆内存（MB）。 */
    val activeMaxHeapMb: Int = 0,
    /** 当前进程已分配使用的堆内存（MB）。 */
    val usedHeapMb: Int = 0,
    /** 启动配置文件 `Onyx.cfg` 中当前声明的 `-Xmx` 上限（MB）；未打包或未声明时为 `null`。 */
    val configuredMaxHeapMb: Int? = null,
    /** 是否存在可写入的打包启动配置文件 `Onyx.cfg`。 */
    val launcherConfigWritable: Boolean = false,
    /** 定位到的启动配置文件路径；开发模式未打包时为 `null`。 */
    val launcherConfigPath: String? = null,
    /** 最近一次写回启动配置失败的原因；未出错时为 `null`。 */
    val lastWriteError: String? = null,
    /** 本地磁盘缩略图缓存当前已占用的总字节数。 */
    val thumbnailDiskCacheBytes: Long = 0L,
    /** 本地磁盘缩略图缓存容量上限（字节）。 */
    val thumbnailDiskCacheLimitBytes: Long = DEFAULT_THUMBNAIL_DISK_CACHE_LIMIT_BYTES,
) {
    companion object {
        /** 默认磁盘缩略图缓存上限（512 MB）。 */
        const val DEFAULT_THUMBNAIL_DISK_CACHE_LIMIT_BYTES: Long = 512L * 1024 * 1024
    }
}

/**
 * JVM 堆内存启动配置读写边界，隔离底层 `Onyx.cfg` 文件路径定位与原子更新。
 */
internal interface MemoryConfigService {
    /**
     * 读取当前运行期 JVM 堆内存占用及打包启动配置 `Onyx.cfg` 状态。
     *
     * @return 运行期内存与启动配置快照。
     */
    suspend fun readStatus(): JvmMemoryStatus

    /**
     * 将用户手动指定的最大堆内存写入启动配置 `Onyx.cfg`（若当前为打包安装模式）。
     *
     * @param maxHeapMb 目标最大堆内存（单位 MB）。
     * @return 写回后的最新状态；写入失败时返回显式失败结果。
     */
    suspend fun applyMaxHeapMemoryMb(maxHeapMb: Int): Result<JvmMemoryStatus>
}

/**
 * 内存配置空实现，用于单元测试或无平台配置文件环境。
 */
internal object NoOpMemoryConfigService : MemoryConfigService {
    /**
     * 返回默认空内存状态。
     *
     * @return 默认内存状态快照。
     */
    override suspend fun readStatus(): JvmMemoryStatus = JvmMemoryStatus()

    /**
     * 不执行文件写入，直接返回成功状态。
     *
     * @param maxHeapMb 目标最大堆内存（单位 MB）。
     * @return 包含目标值的成功状态。
     */
    override suspend fun applyMaxHeapMemoryMb(maxHeapMb: Int): Result<JvmMemoryStatus> {
        return Result.success(JvmMemoryStatus(configuredMaxHeapMb = maxHeapMb))
    }
}
