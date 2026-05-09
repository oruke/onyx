package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile

class ProviderBackedFileRepository(
    private val providerRegistry: VfsProviderRegistry,
) : FileRepository {
    override suspend fun list(location: String): Result<List<VFile>> {
        return providerRegistry.list(location)
    }

    override fun defaultLocation(): String = providerRegistry.defaultLocation()
}
