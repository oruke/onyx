package com.oruke.onyx.vfs.archive

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.RoutableVfsContentService
import com.oruke.onyx.vfs.api.VfsContentSource
import com.oruke.onyx.vfs.api.VfsProvider
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProviderNotFoundException
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import kotlinx.coroutines.flow.flowOf

class ArchiveVfsProvider(
    private val archiveService: ArchiveService,
) : VfsProvider, RoutableVfsContentService {
    override val protocol: VfsProtocol = VfsProtocol.ARCHIVE

    override val capabilities: Set<VfsProviderCapability> = setOf(
        VfsProviderCapability.READ_CONTENT,
        VfsProviderCapability.READ_PREVIEW,
        VfsProviderCapability.THUMBNAIL,
    )

    override fun supports(location: String): Boolean = ArchiveService.isArchiveLocation(location)

    override suspend fun list(location: String): Result<List<VFile>> {
        val parsed = ArchiveService.parseArchiveLocation(location)
            ?: return Result.failure(VfsProviderNotFoundException(location))
        return archiveService.list(parsed.first, parsed.second)
    }

    override suspend fun readFile(entry: VFile): Result<VfsContentSource> {
        if (!supports(entry.location)) {
            return Result.failure(VfsProviderNotFoundException(entry.location))
        }
        if (entry.kind == VFileKind.DIRECTORY) {
            return Result.failure(unsupported(entry.location, VfsProviderCapability.READ_CONTENT))
        }
        val (archivePath, innerPath) = ArchiveService.parseArchiveLocation(entry.location)
            ?: return Result.failure(VfsProviderNotFoundException(entry.location))
        if (innerPath.isBlank()) {
            return Result.failure(unsupported(entry.location, VfsProviderCapability.READ_CONTENT))
        }
        return archiveService.extractToBytes(archivePath, innerPath).mapCatching { bytes ->
            val content = bytes ?: throw VfsProviderException(VfsProviderError.NotFound(VfsProtocol.ARCHIVE, entry.location))
            VfsContentSource(
                name = entry.name,
                sizeBytes = content.size.toLong(),
                chunks = flowOf(content),
            )
        }
    }

    override suspend fun writeFile(
        parentLocation: String,
        name: String,
        chunks: kotlinx.coroutines.flow.Flow<ByteArray>,
        conflictStrategy: TransferConflictStrategy,
    ): Result<VFile?> {
        return Result.failure(unsupported(parentLocation, VfsProviderCapability.WRITE_CONTENT))
    }

    private fun unsupported(
        location: String,
        capability: VfsProviderCapability,
    ): VfsProviderException {
        return VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.ARCHIVE,
                location = location,
                capability = capability,
            )
        )
    }
}
