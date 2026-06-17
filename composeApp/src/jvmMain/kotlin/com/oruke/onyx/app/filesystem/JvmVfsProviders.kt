package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import com.oruke.onyx.vfs.api.VfsProviderNotFoundException
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProtocol

class ProviderBackedFileRepository(
    private val providerRegistry: VfsProviderRegistry,
) : FileRepository {
    override suspend fun list(location: String): Result<List<VFile>> {
        return providerRegistry.list(location)
    }

    override fun defaultLocation(): String = providerRegistry.defaultLocation()
}
