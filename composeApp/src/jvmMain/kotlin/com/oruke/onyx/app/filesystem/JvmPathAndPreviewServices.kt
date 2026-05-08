package com.oruke.onyx.app.filesystem

import androidx.compose.ui.unit.IntSize
import com.oruke.onyx.app.component.FileTransferOperation
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.pathString

class JvmVfsPathService : VfsPathService {
    override fun normalizeLocation(location: String): String {
        if (ArchiveService.isArchiveLocation(location)) return location
        return Path.of(location).normalize().toAbsolutePath().pathString
    }

    override fun parentLocation(location: String): String? {
        if (ArchiveService.isArchiveLocation(location)) {
            return ArchiveService.archiveParentLocation(location)
        }
        return runCatching { Path.of(location).parent?.pathString }.getOrNull()
    }

    override fun title(location: String): String {
        if (ArchiveService.isArchiveLocation(location)) {
            return ArchiveService.archiveLocationTitle(location)
        }
        return runCatching {
            val path = Path.of(location)
            path.fileName?.pathString?.ifBlank { location } ?: path.pathString
        }.getOrDefault(location)
    }

    override fun baseName(location: String): String? {
        if (ArchiveService.isArchiveLocation(location)) {
            return ArchiveService.archiveLocationTitle(location).takeIf { it.isNotBlank() }
        }
        return runCatching {
            Path.of(location).fileName?.pathString?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    override fun label(location: String): String {
        return baseName(location)
            ?: location.trimEnd('/').substringAfterLast('/').ifBlank { location }
    }

    override fun directChildName(ancestor: String, descendant: String): String? {
        return runCatching {
            val ancestorPath = Path.of(ancestor).toAbsolutePath().normalize()
            val descendantPath = Path.of(descendant).toAbsolutePath().normalize()
            if (!descendantPath.startsWith(ancestorPath) || descendantPath == ancestorPath) {
                return@runCatching null
            }
            ancestorPath.relativize(descendantPath).getName(0)?.pathString
        }.getOrNull()
    }

    override fun isDirectParent(parent: String, child: String): Boolean {
        return runCatching {
            val parentPath = Path.of(parent).toAbsolutePath().normalize()
            val childPath = Path.of(child).toAbsolutePath().normalize()
            childPath.parent == parentPath
        }.getOrDefault(false)
    }

    override fun isSameOrChildOf(location: String, parentLocation: String): Boolean {
        return runCatching {
            val target = Path.of(location).normalize().toAbsolutePath()
            val parent = Path.of(parentLocation).normalize().toAbsolutePath()
            target == parent || target.startsWith(parent)
        }.getOrDefault(false)
    }

    override fun resolveTransferOperation(sourceLocation: String, targetLocation: String): FileTransferOperation {
        return runCatching {
            val sourceStore = Files.getFileStore(Path.of(sourceLocation))
            val targetStore = Files.getFileStore(Path.of(targetLocation))
            if (sourceStore == targetStore) FileTransferOperation.MOVE else FileTransferOperation.COPY
        }.getOrDefault(FileTransferOperation.MOVE)
    }

    override fun buildBreadcrumbs(location: String): List<VfsBreadcrumb> {
        val parsed = ArchiveService.parseArchiveLocation(location)
        if (parsed != null) {
            val (archivePath, innerPath) = parsed
            val archiveFilePath = Path.of(archivePath).normalize().toAbsolutePath()
            val breadcrumbs = mutableListOf<VfsBreadcrumb>()
            var current = archiveFilePath.root ?: archiveFilePath
            breadcrumbs += VfsBreadcrumb(
                label = current.toString().ifBlank { "/" },
                location = current.toString().ifBlank { "/" },
            )
            archiveFilePath.iterator().forEach { segment ->
                current = current.resolve(segment)
                val loc = if (current == archiveFilePath) {
                    ArchiveService.archiveLocation(archivePath)
                } else {
                    current.toString()
                }
                breadcrumbs += VfsBreadcrumb(
                    label = segment.toString(),
                    location = loc,
                )
            }
            if (innerPath.isNotBlank()) {
                val segments = innerPath.trimEnd('/').split("/").filter { it.isNotEmpty() }
                var innerCurrent = ""
                segments.forEach { segment ->
                    innerCurrent = if (innerCurrent.isEmpty()) segment else "$innerCurrent/$segment"
                    breadcrumbs += VfsBreadcrumb(
                        label = segment,
                        location = ArchiveService.archiveLocation(archivePath, innerCurrent),
                    )
                }
            }
            return breadcrumbs.distinctBy { it.location }
        }

        val path = Path.of(location).normalize().toAbsolutePath()
        val breadcrumbs = mutableListOf<VfsBreadcrumb>()
        var current = path.root ?: path
        breadcrumbs += VfsBreadcrumb(
            label = current.toString().ifBlank { "/" },
            location = current.toString().ifBlank { "/" },
        )
        path.iterator().forEach { segment ->
            current = current.resolve(segment)
            breadcrumbs += VfsBreadcrumb(
                label = segment.toString(),
                location = current.toString(),
            )
        }
        return breadcrumbs.distinctBy { it.location }
    }
}

class JvmTerminalLauncherService : TerminalLauncherService {
    override suspend fun openTerminal(location: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val directory = File(location)
            check(directory.isDirectory) { "Location is not a directory: $location" }
            val candidates = listOfNotNull(
                System.getenv("TERMINAL"),
                "x-terminal-emulator",
                "gnome-terminal",
                "konsole",
                "kitty",
                "alacritty",
                "xterm",
            )
            var lastFailure: Throwable? = null
            for (command in candidates) {
                try {
                    ProcessBuilder(command).directory(directory).start()
                    return@runCatching
                } catch (failure: Throwable) {
                    lastFailure = failure
                }
            }
            throw IllegalStateException("No terminal command could be started", lastFailure)
        }
    }
}

class JvmPreviewService : PreviewService {
    override suspend fun loadTextPreview(request: PreviewTextRequest): PreviewTextResult = withContext(Dispatchers.IO) {
        runCatching {
            if (ArchiveService.isArchiveLocation(request.entry.location)) {
                return@withContext PreviewTextResult.Unavailable
            }
            val path = Path.of(request.entry.location)
            if (!Files.exists(path)) {
                return@withContext PreviewTextResult.Unavailable
            }
            if (Files.size(path) >= request.maxBytes) {
                return@withContext PreviewTextResult.TooLarge
            }
            Files.newBufferedReader(path).useLines { lines ->
                PreviewTextResult.Text(lines.take(request.maxLines).joinToString("\n"))
            }
        }.getOrDefault(PreviewTextResult.Unavailable)
    }
}

class JvmArchiveFileTypeService : ArchiveFileTypeService {
    override fun isArchiveFileName(fileName: String): Boolean = ArchiveService.isArchive(fileName)
}

class JvmArchiveEntryOpenService(
    private val archiveService: ArchiveService,
    private val externalOpenService: ExternalOpenService,
) : ArchiveEntryOpenService {
    override suspend fun openArchiveEntry(entry: VFile): Result<VFile> = withContext(Dispatchers.IO) {
        runCatching {
            val (archivePath, innerPath) = ArchiveService.parseArchiveLocation(entry.location)
                ?: error("不是压缩包内条目: ${entry.location}")
            check(innerPath.isNotBlank()) { "压缩包条目路径为空" }
            val bytes = archiveService.extractToBytes(archivePath, innerPath).getOrThrow()
                ?: error("压缩包条目不存在: $innerPath")
            check(bytes.isNotEmpty()) { "压缩包条目为空: $innerPath" }
            val tempDir = Files.createTempDirectory("onyx-archive-")
            val tempFile = tempDir.resolve(entry.name)
            Files.write(tempFile, bytes)
            tempFile.toFile().deleteOnExit()
            tempDir.toFile().deleteOnExit()
            entry.copy(location = tempFile.toString()).also { tempEntry ->
                externalOpenService.open(tempEntry).getOrThrow()
            }
        }
    }
}

class JvmImageMetadataService(
    private val archiveService: ArchiveService,
) : ImageMetadataService {
    override suspend fun readImageSize(entry: VFile): IntSize? = withContext(Dispatchers.IO) {
        runCatching {
            if (ArchiveService.isArchiveLocation(entry.location)) {
                val (archivePath, innerPath) = ArchiveService.parseArchiveLocation(entry.location)
                    ?: return@withContext null
                if (innerPath.isBlank()) return@withContext null
                val bytes = archiveService.extractToBytes(archivePath, innerPath).getOrNull()
                    ?: return@withContext null
                val image = Image.makeFromEncoded(bytes)
                try {
                    IntSize(image.width, image.height)
                } finally {
                    image.close()
                }
            } else {
                val path = Path.of(entry.location)
                if (!Files.exists(path)) return@withContext null
                val readers = ImageIO.getImageReadersBySuffix(entry.name.substringAfterLast('.', ""))
                if (!readers.hasNext()) return@withContext null
                val reader = readers.next()
                try {
                    ImageIO.createImageInputStream(path.toFile()).use { stream ->
                        reader.input = stream
                        IntSize(reader.getWidth(0), reader.getHeight(0))
                    }
                } finally {
                    reader.dispose()
                }
            }
        }.getOrNull()
    }
}
