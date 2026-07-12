package com.oruke.onyx.app.filesystem

import androidx.compose.ui.unit.IntSize
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.io.path.pathString
import kotlin.coroutines.cancellation.CancellationException
import com.oruke.onyx.vfs.api.ExternalOpenService
import com.oruke.onyx.vfs.api.RoutableVfsContentService
import com.oruke.onyx.vfs.api.VfsContentSource
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderNotFoundException
import com.oruke.onyx.vfs.api.VfsProvider
import com.oruke.onyx.vfs.archive.ArchiveService

internal class JvmVfsPathService : VfsPathService {
    override fun normalizeLocation(location: String): String {
        val trimmedLocation = location.trim()
        val remote = remoteUri(trimmedLocation)
        return when {
            ArchiveService.isArchiveLocation(trimmedLocation) -> trimmedLocation
            remote != null -> remote.toRemoteLocation(directory = true)
            else -> Path.of(trimmedLocation).normalize().toAbsolutePath().pathString
        }
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

    override fun isLocationAvailable(location: String): Boolean {
        val trimmedLocation = location.trim()
        return when {
            trimmedLocation.isBlank() -> false
            ArchiveService.isArchiveLocation(trimmedLocation) -> true
            remoteUri(trimmedLocation) != null -> true
            else -> runCatching {
                Files.exists(Path.of(trimmedLocation).normalize().toAbsolutePath())
            }.getOrDefault(false)
        }
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
        return if (parentRemote != null || childRemote != null) {
            parentRemote != null && childRemote != null &&
                parentRemote.remoteDirectChildName(childRemote) != null &&
                childRemote.remoteParentLocation() == parentRemote.toRemoteLocation(directory = true)
        } else {
            runCatching {
                val parentPath = Path.of(parent).toAbsolutePath().normalize()
                val childPath = Path.of(child).toAbsolutePath().normalize()
                childPath.parent == parentPath
            }.getOrDefault(false)
        }
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
        val remote = remoteUri(location)
        return when {
            parsed != null -> archiveBreadcrumbs(parsed.first, parsed.second)
            remote != null -> remote.remoteBreadcrumbs()
            else -> localBreadcrumbs(Path.of(location).normalize().toAbsolutePath())
        }
    }

    /**
     * 构建压缩包文件及其内部路径的面包屑。
     *
     * @param archivePath 压缩包本地路径。
     * @param innerPath 压缩包内部路径。
     * @return 去重后的面包屑列表。
     */
    private fun archiveBreadcrumbs(archivePath: String, innerPath: String): List<VfsBreadcrumb> {
        val archiveFilePath = Path.of(archivePath).normalize().toAbsolutePath()
        val breadcrumbs = localBreadcrumbs(archiveFilePath).toMutableList()
        val archiveIndex = breadcrumbs.indexOfLast { crumb -> crumb.location == archiveFilePath.toString() }
        if (archiveIndex >= 0) {
            breadcrumbs[archiveIndex] = breadcrumbs[archiveIndex].copy(
                location = ArchiveService.archiveLocation(archivePath),
            )
        }
        var innerCurrent = ""
        innerPath.trimEnd('/').split("/").filter { it.isNotEmpty() }.forEach { segment ->
            innerCurrent = if (innerCurrent.isEmpty()) segment else "$innerCurrent/$segment"
            breadcrumbs += VfsBreadcrumb(
                label = segment,
                location = ArchiveService.archiveLocation(archivePath, innerCurrent),
            )
        }
        return breadcrumbs.distinctBy { it.location }
    }

    /**
     * 构建本地绝对路径的面包屑。
     *
     * @param path 已规范化的本地绝对路径。
     * @return 从根路径到目标路径的面包屑列表。
     */
    private fun localBreadcrumbs(path: Path): List<VfsBreadcrumb> {
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
        return URI(scheme.lowercase(), null, host, port, normalizedPath, null, null).toString()
    }

    private fun URI.remoteParentLocation(): String? {
        val segments = remotePathSegments()
        if (segments.isEmpty()) return null
        val parentPath = segments.dropLast(1)
            .joinToString(separator = "/", prefix = "/")
            .let { path -> if (path == "/") path else path.withTrailingSlash() }
        return URI(scheme.lowercase(), null, host, port, parentPath, null, null).toString()
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
                location = URI(scheme.lowercase(), null, host, port, "/", null, null).toString(),
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
            ).toString()
            breadcrumbs += VfsBreadcrumb(
                label = segment,
                location = location,
            )
        }
        return breadcrumbs.distinctBy { it.location }
    }

    private fun URI.remoteDirectChildName(descendant: URI): String? {
        val ancestorSegments = remotePathSegments()
        val descendantSegments = descendant.remotePathSegments()
        val isDirectDescendant = hasSameRemoteRoot(descendant) &&
            descendantSegments.size > ancestorSegments.size &&
            descendantSegments.take(ancestorSegments.size) == ancestorSegments
        return descendantSegments.getOrNull(ancestorSegments.size).takeIf { isDirectDescendant }
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

    private companion object {
        val REMOTE_SCHEMES = setOf("smb", "webdav", "webdavs", "s3")
    }
}

/**
 * 确保远程目录路径以斜杠结尾。
 *
 * @return 带目录结尾斜杠的路径。
 */
private fun String.withTrailingSlash(): String = if (endsWith('/')) this else "$this/"

/**
 * 将用户输入位置中的空格转义为 URI 可解析形式。
 *
 * @return 转义空格后的 URI 文本。
 */
private fun String.encodeSpaces(): String = replace(" ", "%20")

internal class JvmTerminalLauncherService : TerminalLauncherService {
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
                } catch (failure: IOException) {
                    lastFailure = failure
                } catch (failure: SecurityException) {
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
                TerminalLaunchCandidate(
                    listOf("open", "-a", "Terminal", directory.absolutePath),
                    useWorkingDirectory = false,
                ),
                TerminalLaunchCandidate(
                    listOf("open", "-a", "iTerm", directory.absolutePath),
                    useWorkingDirectory = false,
                ),
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

/**
 * JVM 文本预览服务，优先通过统一 VFS 内容服务读取文件内容。
 *
 * @param contentServices 可按 location 路由的内容服务列表。
 */
internal class JvmPreviewService(
    private val contentServices: List<RoutableVfsContentService> = emptyList(),
) : PreviewService {
    /**
     * 加载指定文件的文本预览。
     *
     * @param request 文本预览请求。
     * @return 文本预览结果。
     */
    @Suppress("TooGenericExceptionCaught") // 内容服务边界将未建模异常转换为预览失败结果。
    override suspend fun loadTextPreview(request: PreviewTextRequest): PreviewTextResult = withContext(Dispatchers.IO) {
        try {
            if (request.entry.kind != VFileKind.FILE) return@withContext PreviewTextResult.Unavailable
            val routedPreview = loadTextPreviewFromContentService(request)
            if (routedPreview != null) return@withContext routedPreview
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
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            PreviewTextResult.Failed(failure.toI18nMessage())
        }
    }

    /**
     * 通过统一内容服务加载文本预览。
     *
     * @param request 文本预览请求。
     * @return 内容服务可处理时返回预览结果，否则返回 `null`。
     */
    private suspend fun loadTextPreviewFromContentService(request: PreviewTextRequest): PreviewTextResult? {
        val contentService = contentServices.firstOrNull { service -> service.supports(request.entry.location) }
        return contentService?.let { service -> loadTextPreviewFromService(service, request) }
    }

    /**
     * 从已匹配的内容服务读取受大小和行数约束的文本。
     *
     * @param contentService 可以处理目标位置的统一内容服务。
     * @param request 文本预览请求。
     * @return 文本、超限状态或读取失败异常。
     */
    private suspend fun loadTextPreviewFromService(
        contentService: RoutableVfsContentService,
        request: PreviewTextRequest,
    ): PreviewTextResult {
        val source = contentService.readFile(request.entry).getOrThrow()
        val sourceSizeBytes = source.sizeBytes
        if (sourceSizeBytes != null && sourceSizeBytes >= request.maxBytes) {
            return PreviewTextResult.TooLarge
        }
        val output = ByteArrayOutputStream()
        var readBytes = 0L
        val tooLarge = try {
            source.chunks.collect { chunk ->
                if (readBytes + chunk.size >= request.maxBytes) {
                    throw PreviewLimitExceededException()
                }
                readBytes += chunk.size
                output.write(chunk)
            }
            false
        } catch (_: PreviewLimitExceededException) {
            true
        }
        return if (tooLarge) {
            PreviewTextResult.TooLarge
        } else {
            val text = output.toString(Charsets.UTF_8)
                .lineSequence()
                .take(request.maxLines)
                .joinToString("\n")
            PreviewTextResult.Text(text)
        }
    }

    /**
     * 预览读取超过单文件上限时使用的内部中断信号。
     */
    private class PreviewLimitExceededException : RuntimeException()
}

internal class JvmFileHashService(
    private val archiveService: ArchiveService,
) : FileHashService {
    @Suppress("TooGenericExceptionCaught") // 哈希读取边界将本地与远程读取异常转换为统一结果。
    override suspend fun readHash(request: FileHashRequest): FileHashResult = withContext(Dispatchers.IO) {
        try {
            if (request.entry.kind != VFileKind.FILE) {
                return@withContext FileHashResult.Unavailable
            }
            if ((request.entry.sizeBytes ?: 0L) > request.maxBytes) {
                return@withContext FileHashResult.TooLarge
            }
            when {
                ArchiveService.isArchiveLocation(request.entry.location) -> readArchiveHash(request)
                request.entry.location.contains("://") -> FileHashResult.Unavailable
                else -> readLocalHash(request)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            FileHashResult.Failed(failure.toI18nMessage())
        }
    }

    /**
     * 读取压缩包内部文件并计算哈希。
     *
     * @param request 哈希读取请求。
     * @return 哈希值、大小超限或不可读取结果。
     */
    private suspend fun readArchiveHash(request: FileHashRequest): FileHashResult {
        val (archivePath, innerPath) = ArchiveService.parseArchiveLocation(request.entry.location)
            ?: return FileHashResult.Unavailable
        if (innerPath.isBlank()) return FileHashResult.Unavailable
        val bytes = archiveService.extractToBytes(archivePath, innerPath).getOrNull()
            ?: return FileHashResult.Unavailable
        if (bytes.size.toLong() > request.maxBytes) return FileHashResult.TooLarge
        return hashBytes(bytes)
    }

    /**
     * 流式读取本地文件并计算哈希。
     *
     * @param request 哈希读取请求。
     * @return 哈希值、大小超限或不可读取结果。
     */
    private fun readLocalHash(request: FileHashRequest): FileHashResult {
        val path = Path.of(request.entry.location)
        if (!Files.exists(path) || Files.isDirectory(path)) return FileHashResult.Unavailable
        return if (Files.size(path) > request.maxBytes) {
            FileHashResult.TooLarge
        } else {
            hashLocalPath(path)
        }
    }

    /**
     * 流式读取已校验的本地文件并计算摘要。
     *
     * @param path 已确认存在且为普通文件的路径。
     * @return 格式化后的哈希结果。
     */
    private fun hashLocalPath(path: Path): FileHashResult {
        val digest = MessageDigest.getInstance(SHA_256)
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.toHashResult()
    }

    /**
     * 计算内存字节数据的 SHA-256。
     *
     * @param bytes 待计算的文件内容。
     * @return 格式化后的哈希结果。
     */
    private fun hashBytes(bytes: ByteArray): FileHashResult {
        return MessageDigest.getInstance(SHA_256).apply { update(bytes) }.toHashResult()
    }

    /**
     * 将已完成输入的摘要转换为统一哈希结果。
     *
     * @return 小写十六进制哈希结果。
     */
    private fun MessageDigest.toHashResult(): FileHashResult {
        return FileHashResult.Hash(
            algorithm = SHA_256,
            value = digest().joinToString("") { byte -> "%02x".format(byte) },
        )
    }

    private companion object {
        const val SHA_256 = "SHA-256"
        const val HASH_BUFFER_SIZE = 64 * 1024
    }
}

internal class JvmArchiveInfoService(
    private val archiveService: ArchiveService,
) : ArchiveInfoService {
    @Suppress("TooGenericExceptionCaught") // 压缩包元数据边界将引擎异常转换为统一结果。
    override suspend fun readInfo(request: ArchiveInfoRequest): ArchiveInfoResult = withContext(Dispatchers.IO) {
        try {
            val entry = request.entry
            val isUnsupportedEntry = entry.kind != VFileKind.FILE ||
                !ArchiveService.isArchive(entry.name) ||
                entry.location.contains("://")
            if (isUnsupportedEntry) {
                return@withContext ArchiveInfoResult.Unavailable
            }
            val path = Path.of(entry.location)
            if (!Files.exists(path) || Files.isDirectory(path)) {
                return@withContext ArchiveInfoResult.Unavailable
            }
            ArchiveInfoResult.Info(
                encrypted = archiveService.isEncrypted(path.toString()),
                canBrowse = true,
                canExtract = true,
                canWrite = false,
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            ArchiveInfoResult.Failed(failure.toI18nMessage())
        }
    }
}

internal class JvmFileTypeService : FileTypeService {
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

internal class JvmArchiveEntryOpenService(
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

internal class JvmImageMetadataService(
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
