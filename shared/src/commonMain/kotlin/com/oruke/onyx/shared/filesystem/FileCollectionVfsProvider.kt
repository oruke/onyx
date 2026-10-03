package com.oruke.onyx.shared.filesystem

import com.oruke.onyx.shared.usecase.FileCollectionRepository
import com.oruke.onyx.shared.usecase.isFileCollectionLocation
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.VfsProvider
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProtocol

/**
 * 文件集合虚拟目录 provider。
 *
 * @param repository 文件集合仓库。
 */
class FileCollectionVfsProvider(
    private val repository: FileCollectionRepository,
) : VfsProvider {
    override val protocol: VfsProtocol = VfsProtocol.COLLECTION

    override val capabilities: Set<VfsProviderCapability> = emptySet()

    override fun supports(location: String): Boolean {
        return location.isFileCollectionLocation()
    }

    /**
     * 读取集合快照，集合已失效时返回可本地化的不存在错误。
     *
     * @param location 文件集合的虚拟位置。
     * @return 集合内条目或集合不存在的结构化错误。
     */
    override suspend fun list(location: String): Result<List<VFile>> {
        val collection = repository.findByLocation(location)
            ?: return Result.failure(
                VfsProviderException(VfsProviderError.NotFound(VfsProtocol.COLLECTION, location)),
            )
        return Result.success(collection.entries)
    }

    override suspend fun totalSizeBytes(entries: List<VFile>): Result<Long> {
        return Result.success(entries.sumOf { entry -> entry.sizeBytes ?: 0L })
    }
}
