package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile

class ArchiveVfsProvider(
    private val archiveService: ArchiveService,
) : VfsProvider {
    override val protocol: VfsProtocol = VfsProtocol.ARCHIVE

    override val capabilities: Set<VfsProviderCapability> = setOf(
        VfsProviderCapability.READ_PREVIEW,
        VfsProviderCapability.THUMBNAIL,
    )

    override fun supports(location: String): Boolean = ArchiveService.isArchiveLocation(location)

    override suspend fun list(location: String): Result<List<VFile>> {
        val parsed = ArchiveService.parseArchiveLocation(location)
            ?: return Result.failure(VfsProviderNotFoundException(location))
        return archiveService.list(parsed.first, parsed.second)
    }
}
