package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import com.oruke.onyx.vfs.api.VfsProviderNotFoundException
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProtocol

/**
 * 基于 Provider 注册表路由目录读取的统一文件仓库。
 *
 */
class ProviderBackedFileRepository(
    /** 负责按协议定位具体文件系统实现的 Provider 注册表。 */
    private val providerRegistry: VfsProviderRegistry,
) : FileRepository {
    override suspend fun list(location: String): Result<List<VFile>> {
        return providerRegistry.list(location)
    }

    override fun defaultLocation(): String = providerRegistry.defaultLocation()
}
