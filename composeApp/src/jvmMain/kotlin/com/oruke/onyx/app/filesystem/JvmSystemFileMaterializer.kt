package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.nio.file.Files

class JvmSystemFileMaterializer(
    private val archiveService: ArchiveService,
    contentServices: List<RoutableVfsContentService>,
) : SystemFileMaterializer {
    private val contentServices = contentServices.toList()
    private val materializedFiles = mutableMapOf<String, VFile>()

    override fun supports(entry: VFile): Boolean {
        if (entry.systemLocalPathOrNull() != null) return true
        if (entry.kind == VFileKind.DIRECTORY) return false
        return contentServices.any { service -> service.supports(entry.location) }
    }

    override suspend fun materialize(entry: VFile): Result<VFile> = withContext(Dispatchers.IO) {
        runCatching {
            entry.systemLocalPathOrNull()?.let { return@runCatching entry }
            val cached = synchronized(materializedFiles) {
                materializedFiles[entry.location]?.takeIf { cachedEntry ->
                    cachedEntry.systemLocalPathOrNull()?.let(Files::isRegularFile) == true
                }
            }
            if (cached != null) return@runCatching cached

            materializeArchiveEntry(entry)?.let { materialized ->
                synchronized(materializedFiles) {
                    materializedFiles[entry.location] = materialized
                }
                return@runCatching materialized
            }

            val service = contentServices.firstOrNull { candidate -> candidate.supports(entry.location) }
                ?: throw VfsProviderNotFoundException(entry.location)
            val source = service.readFile(entry).getOrThrow()
            val tempDir = Files.createTempDirectory("onyx-system-file-")
            val tempFile = tempDir.resolve(entry.safeSystemFileName())
            Files.newOutputStream(tempFile).use { output ->
                source.chunks.collect { chunk -> output.write(chunk) }
            }
            tempDir.toFile().deleteOnExit()
            tempFile.toFile().deleteOnExit()

            entry.copy(location = tempFile.toString()).also { materialized ->
                synchronized(materializedFiles) {
                    materializedFiles[entry.location] = materialized
                }
            }
        }
    }

    private suspend fun materializeArchiveEntry(entry: VFile): VFile? {
        val (archivePath, innerPath) = ArchiveService.parseArchiveLocation(entry.location) ?: return null
        if (innerPath.isBlank()) return null
        val tempDir = Files.createTempDirectory("onyx-system-file-")
        archiveService.extractEntriesToTemp(
            archivePath = archivePath,
            entryPaths = listOf(innerPath),
            targetDir = tempDir.toString(),
        ).getOrThrow()
        val tempFile = tempDir.resolve(entry.safeSystemFileName())
        tempDir.toFile().deleteOnExit()
        tempFile.toFile().deleteOnExit()
        return entry.copy(location = tempFile.toString())
    }

    private fun VFile.safeSystemFileName(): String {
        return name
            .replace('/', '_')
            .replace('\\', '_')
            .takeIf { value -> value.isNotBlank() }
            ?: "item"
    }
}
