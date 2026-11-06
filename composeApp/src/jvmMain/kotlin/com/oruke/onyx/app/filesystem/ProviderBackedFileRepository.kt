package com.oruke.onyx.app.filesystem

import com.oruke.onyx.app.cache.OnyxStartupPreloader
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.FileEntryLookup
import com.oruke.onyx.vfs.api.QueryableVfsProvider
import com.oruke.onyx.vfs.api.VfsProviderRegistry

/**
 * 基于 Provider 注册表路由目录读取的统一文件仓库。
 *
 * @param providerRegistry 负责按协议定位具体文件系统实现的 Provider 注册表。
 * @param useStartupPreload 是否在启动首屏首次读取时消费 [OnyxStartupPreloader] 预加载的目录条目。
 */
class ProviderBackedFileRepository(
    /** 负责按协议定位具体文件系统实现的 Provider 注册表。 */
    private val providerRegistry: VfsProviderRegistry,
    /** 是否启用启动期首屏目录预加载结果消费。 */
    private val useStartupPreload: Boolean = false,
) : FileRepository {
    override suspend fun list(location: String): Result<List<VFile>> {
        if (useStartupPreload) {
            OnyxStartupPreloader.consumePreloadedDirectory(location)?.let { preloaded ->
                return preloaded.await()
            }
        }
        return providerRegistry.list(location)
    }

    /**
     * 按统一 Provider 能力查询条目，不支持时保留显式降级结果。
     * @param location 条目 VFS 位置。
     * @return 单项查询结果或 Provider 路由失败。
     */
    override suspend fun query(location: String): Result<FileEntryLookup> {
        val provider = providerRegistry.providerFor(location).getOrElse { return Result.failure(it) }
        return (provider as? QueryableVfsProvider)?.query(location)
            ?: Result.success(FileEntryLookup.Unsupported)
    }

    override fun defaultLocation(): String = providerRegistry.defaultLocation()
}
