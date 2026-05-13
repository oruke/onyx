package com.oruke.onyx.app.filesystem

import com.oruke.onyx.app.usecase.FileCollectionRepository
import com.oruke.onyx.app.usecase.isFileCollectionLocation
import com.oruke.onyx.core.model.VFile

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

    override suspend fun list(location: String): Result<List<VFile>> {
        val collection = repository.findByLocation(location)
            ?: return Result.failure(VfsProviderNotFoundException(location))
        return Result.success(collection.entries)
    }

    override suspend fun totalSizeBytes(entries: List<VFile>): Result<Long> {
        return Result.success(entries.sumOf { entry -> entry.sizeBytes ?: 0L })
    }
}
