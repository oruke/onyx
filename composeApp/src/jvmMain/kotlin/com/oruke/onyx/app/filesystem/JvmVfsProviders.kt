package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class LocalVfsProvider(
    private val localProvider: JvmLocalFileProvider,
) : VfsProvider {
    override val protocol: VfsProtocol = VfsProtocol.LOCAL

    override val capabilities: Set<VfsProviderCapability> = setOf(
        VfsProviderCapability.WATCH,
        VfsProviderCapability.TRASH,
        VfsProviderCapability.EXTERNAL_OPEN,
        VfsProviderCapability.READ_PREVIEW,
        VfsProviderCapability.THUMBNAIL,
    )

    override fun supports(location: String): Boolean {
        return !location.contains("://")
    }

    override fun defaultLocation(): String = localProvider.defaultLocation()

    override suspend fun list(location: String): Result<List<VFile>> = localProvider.list(location)

    override suspend fun totalSizeBytes(entries: List<VFile>): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            entries.sumOf { entry -> pathSizeBytes(Path.of(entry.location).normalize().toAbsolutePath()) }
        }
    }

    private fun pathSizeBytes(path: Path): Long {
        if (!Files.exists(path)) return 0L
        if (!Files.isDirectory(path)) return Files.size(path)

        var total = 0L
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    total += attrs.size()
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return total
    }
}

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

class ProviderBackedFileRepository(
    private val providerRegistry: VfsProviderRegistry,
) : FileRepository {
    override suspend fun list(location: String): Result<List<VFile>> {
        return providerRegistry.list(location)
    }

    override fun defaultLocation(): String = providerRegistry.defaultLocation()
}
