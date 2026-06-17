package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

class JvmLocalFileContentSearchService : FileContentSearchService {
    override fun supportsLocation(location: String): Boolean {
        return !location.contains("://")
    }

    override fun supports(entry: VFile): Boolean {
        return entry.kind == VFileKind.FILE && !entry.location.contains("://")
    }

    override suspend fun contains(
        entry: VFile,
        query: String,
        maxBytes: Long,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val path = Path.of(entry.location).normalize().toAbsolutePath()
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return@runCatching false
            }
            val size = Files.size(path)
            if (size > maxBytes) {
                return@runCatching false
            }
            val content = String(Files.readAllBytes(path), Charsets.UTF_8)
            content.lowercase().contains(query)
        }
    }
}
