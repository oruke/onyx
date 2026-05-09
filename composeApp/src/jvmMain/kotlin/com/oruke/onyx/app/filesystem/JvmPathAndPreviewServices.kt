package com.oruke.onyx.app.filesystem

import androidx.compose.ui.unit.IntSize
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.pathString

class JvmVfsPathService : VfsPathService {
    override fun normalizeLocation(location: String): String {
        val trimmedLocation = location.trim()
        if (ArchiveService.isArchiveLocation(trimmedLocation)) return trimmedLocation
        remoteUri(trimmedLocation)?.let { uri ->
            return uri.toRemoteLocation(directory = true)
        }
        return Path.of(trimmedLocation).normalize().toAbsolutePath().pathString
    }

    override fun parentLocation(location: String): String? {
        if (ArchiveService.isArchiveLocation(location)) {
            return ArchiveService.archiveParentLocation(location)
        }
        remoteUri(location)?.let { uri ->
            return uri.remoteParentLocation()
        }
        return runCatching { Path.of(location).parent?.pathString }.getOrNull()
    }

    override fun title(location: String): String {
        if (ArchiveService.isArchiveLocation(location)) {
            return ArchiveService.archiveLocationTitle(location)
        }
        remoteUri(location)?.let { uri ->
            return uri.remoteBaseName() ?: uri.host.orEmpty()
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
        remoteUri(location)?.let { uri ->
            return uri.remoteBaseName() ?: uri.host
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
        val ancestorRemote = remoteUri(ancestor)
        val descendantRemote = remoteUri(descendant)
        if (ancestorRemote != null || descendantRemote != null) {
            return if (ancestorRemote != null && descendantRemote != null) {
                ancestorRemote.remoteDirectChildName(descendantRemote)
            } else {
                null
            }
        }
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
        val parentRemote = remoteUri(parent)
        val childRemote = remoteUri(child)
        if (parentRemote != null || childRemote != null) {
            return parentRemote?.remoteDirectChildName(childRemote ?: return false) != null &&
                childRemote.remoteParentLocation() == parentRemote.toRemoteLocation(directory = true)
        }
        return runCatching {
            val parentPath = Path.of(parent).toAbsolutePath().normalize()
            val childPath = Path.of(child).toAbsolutePath().normalize()
            childPath.parent == parentPath
        }.getOrDefault(false)
    }

    override fun isSameOrChildOf(location: String, parentLocation: String): Boolean {
        val locationRemote = remoteUri(location)
        val parentRemote = remoteUri(parentLocation)
        if (locationRemote != null || parentRemote != null) {
            return if (locationRemote != null && parentRemote != null) {
                locationRemote.isSameOrChildOfRemote(parentRemote)
            } else {
                false
            }
        }
        return runCatching {
            val target = Path.of(location).normalize().toAbsolutePath()
            val parent = Path.of(parentLocation).normalize().toAbsolutePath()
            target == parent || target.startsWith(parent)
        }.getOrDefault(false)
    }

    override fun resolveTransferOperation(sourceLocation: String, targetLocation: String): FileTransferOperation {
        val sourceRemote = remoteUri(sourceLocation)
        val targetRemote = remoteUri(targetLocation)
        if (sourceRemote != null || targetRemote != null) {
            return if (sourceRemote != null && targetRemote != null && sourceRemote.hasSameRemoteRoot(targetRemote)) {
                FileTransferOperation.MOVE
            } else {
                FileTransferOperation.COPY
            }
        }
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

        remoteUri(location)?.let { uri ->
            return uri.remoteBreadcrumbs()
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

    private fun remoteUri(location: String): URI? {
        if (!location.contains("://")) return null
        return runCatching {
            val uri = URI(location.encodeSpaces())
            val scheme = uri.scheme?.lowercase()
            if (scheme in REMOTE_SCHEMES && !uri.host.isNullOrBlank()) uri else null
        }.getOrNull()
    }

    private fun URI.toRemoteLocation(directory: Boolean): String {
        val normalizedPath = remotePathSegments()
            .joinToString(separator = "/", prefix = "/")
            .let { path ->
                when {
                    path == "/" -> path
                    directory -> path.withTrailingSlash()
                    else -> path
                }
            }
        return URI(scheme.lowercase(), null, host, port, normalizedPath, null, null).toASCIIString()
    }

    private fun URI.remoteParentLocation(): String? {
        val segments = remotePathSegments()
        if (segments.isEmpty()) return null
        val parentPath = segments.dropLast(1)
            .joinToString(separator = "/", prefix = "/")
            .let { path -> if (path == "/") path else path.withTrailingSlash() }
        return URI(scheme.lowercase(), null, host, port, parentPath, null, null).toASCIIString()
    }

    private fun URI.remoteBaseName(): String? {
        return remotePathSegments()
            .lastOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun URI.remoteBreadcrumbs(): List<VfsBreadcrumb> {
        val breadcrumbs = mutableListOf(
            VfsBreadcrumb(
                label = host,
                location = URI(scheme.lowercase(), null, host, port, "/", null, null).toASCIIString(),
            )
        )
        val segments = remotePathSegments()
        var currentPath = ""
        segments.forEach { segment ->
            currentPath = "$currentPath/$segment"
            val location = URI(
                scheme.lowercase(),
                null,
                host,
                port,
                currentPath.withTrailingSlash(),
                null,
                null,
            ).toASCIIString()
            breadcrumbs += VfsBreadcrumb(
                label = segment,
                location = location,
            )
        }
        return breadcrumbs.distinctBy { it.location }
    }

    private fun URI.remoteDirectChildName(descendant: URI): String? {
        if (!hasSameRemoteRoot(descendant)) return null
        val ancestorSegments = remotePathSegments()
        val descendantSegments = descendant.remotePathSegments()
        if (descendantSegments.size <= ancestorSegments.size) return null
        if (descendantSegments.take(ancestorSegments.size) != ancestorSegments) return null
        return descendantSegments[ancestorSegments.size]
    }

    private fun URI.isSameOrChildOfRemote(parent: URI): Boolean {
        if (!hasSameRemoteRoot(parent)) return false
        val segments = remotePathSegments()
        val parentSegments = parent.remotePathSegments()
        return segments == parentSegments || segments.take(parentSegments.size) == parentSegments
    }

    private fun URI.hasSameRemoteRoot(other: URI): Boolean {
        return scheme.equals(other.scheme, ignoreCase = true) &&
            host.equals(other.host, ignoreCase = true) &&
            effectivePort() == other.effectivePort()
    }

    private fun URI.effectivePort(): Int {
        return if (port >= 0) port else -1
    }

    private fun URI.remotePathSegments(): List<String> {
        return path
            ?.trim('/')
            ?.split('/')
            ?.filter { segment -> segment.isNotBlank() }
            .orEmpty()
    }

    private fun String.withTrailingSlash(): String {
        return if (endsWith('/')) this else "$this/"
    }

    private fun String.encodeSpaces(): String {
        return replace(" ", "%20")
    }

    private companion object {
        val REMOTE_SCHEMES = setOf("smb", "webdav", "webdavs", "s3")
    }
}

class JvmTerminalLauncherService : TerminalLauncherService {
    override suspend fun openTerminal(location: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val directory = File(location)
            check(directory.isDirectory) { "Location is not a directory: $location" }
            var lastFailure: Throwable? = null
            for (candidate in terminalLaunchCandidates(directory)) {
                try {
                    val processBuilder = ProcessBuilder(candidate.command)
                    if (candidate.useWorkingDirectory) {
                        processBuilder.directory(directory)
                    }
                    processBuilder.start()
                    return@runCatching
                } catch (failure: Throwable) {
                    lastFailure = failure
                }
            }
            throw IllegalStateException("No terminal command could be started", lastFailure)
        }
    }

    private fun terminalLaunchCandidates(directory: File): List<TerminalLaunchCandidate> {
        val configuredTerminal = System.getenv("TERMINAL")
            ?.takeIf { value -> value.isNotBlank() }
            ?.let { command -> TerminalLaunchCandidate(listOf(command), useWorkingDirectory = true) }
        val platformCandidates = when (currentHostPlatform()) {
            HostPlatform.WINDOWS -> listOf(
                TerminalLaunchCandidate(listOf("wt.exe", "-d", directory.absolutePath), useWorkingDirectory = false),
                TerminalLaunchCandidate(
                    listOf(
                        "powershell.exe",
                        "-NoExit",
                        "-Command",
                        "Set-Location -LiteralPath '${directory.absolutePath.escapePowerShellSingleQuoted()}'",
                    ),
                    useWorkingDirectory = false,
                ),
                TerminalLaunchCandidate(
                    listOf("cmd.exe", "/c", "start", "", "cmd.exe", "/K", "cd", "/d", directory.absolutePath),
                    useWorkingDirectory = false,
                ),
            )

            HostPlatform.MACOS -> listOf(
                TerminalLaunchCandidate(listOf("open", "-a", "Terminal", directory.absolutePath), useWorkingDirectory = false),
                TerminalLaunchCandidate(listOf("open", "-a", "iTerm", directory.absolutePath), useWorkingDirectory = false),
            )

            HostPlatform.LINUX,
            HostPlatform.OTHER -> listOf(
                TerminalLaunchCandidate(listOf("x-terminal-emulator"), useWorkingDirectory = true),
                TerminalLaunchCandidate(listOf("gnome-terminal"), useWorkingDirectory = true),
                TerminalLaunchCandidate(listOf("konsole"), useWorkingDirectory = true),
                TerminalLaunchCandidate(listOf("kitty"), useWorkingDirectory = true),
                TerminalLaunchCandidate(listOf("alacritty"), useWorkingDirectory = true),
                TerminalLaunchCandidate(listOf("xterm"), useWorkingDirectory = true),
            )
        }
        return listOfNotNull(configuredTerminal) + platformCandidates
    }

    private fun currentHostPlatform(): HostPlatform {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.contains("mac") || osName.contains("darwin") -> HostPlatform.MACOS
            osName.contains("win") -> HostPlatform.WINDOWS
            osName.contains("nux") || osName.contains("nix") || osName.contains("linux") -> HostPlatform.LINUX
            else -> HostPlatform.OTHER
        }
    }

    private fun String.escapePowerShellSingleQuoted(): String {
        return replace("'", "''")
    }

    private data class TerminalLaunchCandidate(
        val command: List<String>,
        val useWorkingDirectory: Boolean,
    )

    private enum class HostPlatform {
        WINDOWS,
        MACOS,
        LINUX,
        OTHER,
    }
}

class JvmPreviewService : PreviewService {
    override suspend fun loadTextPreview(request: PreviewTextRequest): PreviewTextResult = withContext(Dispatchers.IO) {
        try {
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
        } catch (failure: Throwable) {
            PreviewTextResult.Failed(failure.toI18nMessage())
        }
    }
}

class JvmFileTypeService : FileTypeService {
    override fun isImageFileName(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in ImageExtensions
    }

    override fun isArchiveFileName(fileName: String): Boolean = ArchiveService.isArchive(fileName)

    override fun isTextPreviewFileName(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in TextPreviewExtensions
    }

    private companion object {
        val ImageExtensions = setOf("png", "jpg", "jpeg", "gif", "svg", "webp", "bmp", "ico", "tiff", "tif")
        val TextPreviewExtensions = setOf("txt", "md", "log", "xml", "json", "java", "kt", "js", "css", "csv", "html")
    }
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
