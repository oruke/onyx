package com.oruke.onyx.shared.filesystem

import com.oruke.onyx.shared.usecase.FileCollectionRepository
import com.oruke.onyx.shared.usecase.isFileCollectionLocation
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.VfsProvider
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderNotFoundException
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

    override suspend fun list(location: String): Result<List<VFile>> {
        val collection = repository.findByLocation(location)
            ?: return Result.failure(VfsProviderNotFoundException(location))
        return Result.success(collection.entries)
    }

    override suspend fun totalSizeBytes(entries: List<VFile>): Result<Long> {
        return Result.success(entries.sumOf { entry -> entry.sizeBytes ?: 0L })
    }
}
