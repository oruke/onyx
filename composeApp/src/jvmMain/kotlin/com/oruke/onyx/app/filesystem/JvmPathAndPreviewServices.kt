package com.oruke.onyx.app.filesystem

import androidx.compose.ui.unit.IntSize
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
import com.oruke.onyx.vfs.api.RoutableVfsRandomAccessService
import com.oruke.onyx.vfs.api.VfsContentSource
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderNotFoundException
import com.oruke.onyx.vfs.api.VfsProvider
import com.oruke.onyx.vfs.api.VfsRandomAccessMode
import com.oruke.onyx.vfs.archive.ArchiveBoundedBytes
import com.oruke.onyx.vfs.archive.ArchivePrefixBytes
import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.vfs.archive.ArchiveStreamOutcome

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

    override fun relativePathSegments(ancestor: String, descendant: String): List<String>? {
        val ancestorRemote = remoteUri(ancestor)
        val descendantRemote = remoteUri(descendant)
        if (ancestorRemote != null || descendantRemote != null) {
            return if (ancestorRemote != null && descendantRemote != null) {
                ancestorRemote.remoteRelativePathSegments(descendantRemote)
            } else {
                null
            }
        }
        return runCatching {
            val ancestorPath = Path.of(ancestor).toAbsolutePath().normalize()
            val descendantPath = Path.of(descendant).toAbsolutePath().normalize()
            when {
                descendantPath == ancestorPath -> emptyList()
                !descendantPath.startsWith(ancestorPath) -> null
                else -> {
                    val rel = ancestorPath.relativize(descendantPath)
                    (0 until rel.nameCount).map { index -> rel.getName(index).pathString }
                }
            }
        }.getOrNull()
    }

    override fun resolveChildDirectory(parentLocation: String, childSegments: List<String>): String {
        val cleaned = childSegments.filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return normalizeLocation(parentLocation)
        val remote = remoteUri(parentLocation)
        return if (remote != null) {
            remote.resolveRemoteChildDirectory(cleaned)
        } else {
            cleaned.fold(Path.of(parentLocation).normalize().toAbsolutePath()) { current, segment ->
                current.resolve(segment)
            }.normalize().pathString
        }
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
     * @param archivePath 压缩包本地或远程 VFS 位置。
     * @param innerPath 压缩包内部路径。
     * @return 去重后的面包屑列表。
     */
    private fun archiveBreadcrumbs(archivePath: String, innerPath: String): List<VfsBreadcrumb> {
        val archiveLocation = ArchiveService.archiveLocation(archivePath)
        val remoteArchiveUri = remoteUri(archivePath)
        val breadcrumbs = if (remoteArchiveUri != null) {
            remoteArchiveUri.remoteBreadcrumbs().toMutableList()
        } else {
            localBreadcrumbs(Path.of(archivePath).normalize().toAbsolutePath()).toMutableList()
        }
        val archiveIndex = breadcrumbs.lastIndex
        if (archiveIndex >= 0) {
            breadcrumbs[archiveIndex] = breadcrumbs[archiveIndex].copy(location = archiveLocation)
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

    /**
     * 将远程位置解析为结构化 URI。
     *
     * @param location 待解析 VFS 位置。
     * @return 受支持的远程 URI；本地位置或非法远程位置返回 `null`。
     */
    private fun remoteUri(location: String): URI? = RemoteVfsLocationParser.parse(location)
}

/** 根据宿主平台启动指定目录的终端，启动失败保留系统诊断并提供可本地化的业务原因。 */
internal class JvmTerminalLauncherService : TerminalLauncherService {
    /**
     * 校验目标目录并依次尝试当前平台支持的终端命令。
     *
     * @param location 用户选择的本地目录位置。
     * @return 任一终端成功启动时返回成功，否则返回包含最后一次系统异常的失败。
     */
    override suspend fun openTerminal(location: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val directory = File(location)
            if (!directory.isDirectory) {
                throw LocalizedException(I18nMessage(MessageKey.MSG_TERMINAL_LOCATION_NOT_DIRECTORY, location))
            }
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
            throw LocalizedException(I18nMessage(MessageKey.MSG_TERMINAL_LAUNCH_FAILED), lastFailure)
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
 * JVM 文本预览服务：
 * - 压缩包内条目优先使用 [ArchiveService.extractPrefixBytes] 仅解压头部前缀，杜绝超大归档文件整包解压 OOM；
 * - 远程协议优先使用 [RoutableVfsRandomAccessService] 按 Range 仅读前 `maxBytes + 1` 字节；
 * - 无随机访问能力的远程内容服务前置校验大小上限，避免整包缓冲打满内存与带宽；
 * - 本地文件通过流式前缀读取，O(maxBytes) 恒定内存。
 *
 * @param contentServices 可按 location 路由的兜底内容服务列表。
 * @param archiveService 压缩包前缀流式提取服务。
 * @param randomAccessService 支持 Range 前缀读取的统一随机访问服务。
 */
internal class JvmPreviewService(
    private val contentServices: List<RoutableVfsContentService> = emptyList(),
    private val archiveService: ArchiveService? = null,
    private val randomAccessService: RoutableVfsRandomAccessService? = null,
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
            val maxBytes = request.maxBytes.coerceIn(1L, MAX_BOUNDED_TEXT_READ_BYTES).toInt()
            if (ArchiveService.isArchiveLocation(request.entry.location)) {
                return@withContext loadArchivePrefixPreview(request, maxBytes)
            }
            loadRandomAccessPrefixPreview(request, maxBytes)?.let { return@withContext it }
            loadTextPreviewFromContentService(request, maxBytes)?.let { return@withContext it }
            if (request.entry.location.contains("://")) {
                return@withContext PreviewTextResult.Unavailable
            }
            val path = Path.of(request.entry.location)
            if (!Files.exists(path) || Files.isDirectory(path)) {
                return@withContext PreviewTextResult.Unavailable
            }
            val totalBytes = Files.size(path)
            val (prefixBytes, bytesTruncated) = readLocalPrefixBytes(path, maxBytes)
            TextPreviewDecoder.decode(
                bytes = prefixBytes,
                requestedEncoding = request.encoding,
                maxLines = request.maxLines,
                bytesTruncated = bytesTruncated,
                totalBytes = totalBytes,
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            PreviewTextResult.Failed(failure.toI18nMessage())
        }
    }

    /** 通过 [ArchiveService.extractPrefixBytes] 仅提取压缩包条目头部字节，达到上限立刻中止原生解压。 */
    private suspend fun loadArchivePrefixPreview(
        request: PreviewTextRequest,
        maxBytes: Int,
    ): PreviewTextResult {
        val service = archiveService
        if (service != null) {
            val (archivePath, innerPath) = ArchiveService.parseArchiveLocation(request.entry.location)
                ?: return PreviewTextResult.Unavailable
            if (innerPath.isBlank()) return PreviewTextResult.Unavailable
            return when (
                val prefix = service.extractPrefixBytes(archivePath, innerPath, maxBytes.toLong()).getOrElse { failure ->
                    if (failure is CancellationException) throw failure
                    return PreviewTextResult.Failed(failure.toI18nMessage())
                }
            ) {
                ArchivePrefixBytes.NotFound -> PreviewTextResult.Unavailable
                ArchivePrefixBytes.TooLarge -> PreviewTextResult.TooLarge
                is ArchivePrefixBytes.Found -> TextPreviewDecoder.decode(
                    bytes = prefix.bytes,
                    requestedEncoding = request.encoding,
                    maxLines = request.maxLines,
                    bytesTruncated = prefix.truncated,
                    totalBytes = prefix.totalSizeBytes ?: request.entry.sizeBytes,
                )
            }
        }
        // 仅用于未注入 ArchiveService 的兼容单测路径：必须先校验大小上限防止整包解压
        return loadTextPreviewFromContentService(request, maxBytes) ?: PreviewTextResult.Unavailable
    }

    /** 优先通过统一随机访问接口按 Range 仅读取前 `maxBytes + 1` 字节，适配 SMB / WebDAV / S3 大文件。 */
    private suspend fun loadRandomAccessPrefixPreview(
        request: PreviewTextRequest,
        maxBytes: Int,
    ): PreviewTextResult? {
        val randomAccess = randomAccessService ?: return null
        if (!randomAccess.supports(request.entry.location)) return null
        val handle = randomAccess.openRandomAccess(request.entry.location, VfsRandomAccessMode.READ)
            .getOrElse { failure ->
                if (failure is CancellationException) throw failure
                return null
            }
        return try {
            val totalLength = runCatching { handle.length() }.getOrNull()?.takeIf { it >= 0L }
                ?: request.entry.sizeBytes
            val output = ByteArrayOutputStream(maxBytes.coerceAtMost(64 * 1024))
            val chunk = ByteArray(8192)
            var position = 0L
            var remaining = maxBytes
            while (remaining > 0) {
                val read = handle.readAt(position, chunk, 0, minOf(chunk.size, remaining))
                if (read <= 0) break
                output.write(chunk, 0, read)
                position += read.toLong()
                remaining -= read
            }
            var truncated = totalLength != null && totalLength > position
            if (!truncated && remaining == 0) {
                val probe = ByteArray(1)
                if (handle.readAt(position, probe, 0, 1) > 0) {
                    truncated = true
                }
            }
            TextPreviewDecoder.decode(
                bytes = output.toByteArray(),
                requestedEncoding = request.encoding,
                maxLines = request.maxLines,
                bytesTruncated = truncated,
                totalBytes = totalLength,
            )
        } finally {
            handle.close()
        }
    }

    /**
     * 读取本地文件前 [maxBytes] 字节用于头部截断预览，避免超大文件整包载入内存。
     *
     * @param path 目标本地文件路径。
     * @param maxBytes 允许读取的最大字节数。
     * @return 已读取的前缀字节数组及是否发生字节截断的标记。
     */
    private fun readLocalPrefixBytes(path: Path, maxBytes: Int): Pair<ByteArray, Boolean> {
        val output = ByteArrayOutputStream(maxBytes.coerceAtMost(64 * 1024))
        var truncated = false
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            var remaining = maxBytes
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
            if (remaining == 0 && input.read() >= 0) {
                truncated = true
            }
        }
        return output.toByteArray() to truncated
    }

    /**
     * 通过兜底内容服务加载文本预览（不支持 Range 时先拦截超限大小防止整包载入内存）。
     *
     * @param request 文本预览请求。
     * @param maxBytes 最大允许前缀字节数。
     * @return 内容服务可处理时返回预览结果，否则返回 `null`。
     */
    private suspend fun loadTextPreviewFromContentService(
        request: PreviewTextRequest,
        maxBytes: Int,
    ): PreviewTextResult? {
        val contentService = contentServices.firstOrNull { service -> service.supports(request.entry.location) }
            ?: return null
        val declaredSize = request.entry.sizeBytes
        if (declaredSize != null && declaredSize > maxBytes.toLong()) {
            return PreviewTextResult.TooLarge
        }
        val source = contentService.readFile(request.entry).getOrThrow()
        val sourceSizeBytes = source.sizeBytes
        if (sourceSizeBytes != null && sourceSizeBytes > maxBytes.toLong()) {
            return PreviewTextResult.TooLarge
        }
        val output = ByteArrayOutputStream(maxBytes.coerceAtMost(64 * 1024))
        var readBytes = 0
        var bytesTruncated = false
        try {
            source.chunks.collect { chunk ->
                val remaining = maxBytes - readBytes
                if (remaining <= 0) {
                    bytesTruncated = true
                    throw PreviewPrefixReachedException()
                }
                if (chunk.size > remaining) {
                    output.write(chunk, 0, remaining)
                    readBytes += remaining
                    bytesTruncated = true
                    throw PreviewPrefixReachedException()
                }
                output.write(chunk)
                readBytes += chunk.size
            }
        } catch (_: PreviewPrefixReachedException) {
            bytesTruncated = true
        }
        val knownTotalBytes = sourceSizeBytes ?: declaredSize
        if (knownTotalBytes != null && knownTotalBytes > readBytes.toLong()) {
            bytesTruncated = true
        }
        return TextPreviewDecoder.decode(
            bytes = output.toByteArray(),
            requestedEncoding = request.encoding,
            maxLines = request.maxLines,
            bytesTruncated = bytesTruncated,
            totalBytes = knownTotalBytes,
        )
    }

    /** 预览前缀读取达到字节上限时用于提前中止流的内部控制异常。 */
    private class PreviewPrefixReachedException : RuntimeException()

    private companion object {
        /** 单次文本预览允许载入内存的最大字节数上限。 */
        const val MAX_BOUNDED_TEXT_READ_BYTES = 2L * 1024 * 1024
    }
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
     * 以恒定内存分块流式读取压缩包内部文件并增量计算 SHA-256，支持协程取消且绝不整包缓冲到堆内存。
     *
     * @param request 哈希读取请求。
     * @return 哈希值、大小超限或不可读取结果。
     */
    private suspend fun readArchiveHash(request: FileHashRequest): FileHashResult {
        val (archivePath, innerPath) = ArchiveService.parseArchiveLocation(request.entry.location)
            ?: return FileHashResult.Unavailable
        if (innerPath.isBlank()) return FileHashResult.Unavailable
        val digest = MessageDigest.getInstance(SHA_256)
        return when (
            archiveService.streamEntryBytes(
                archivePath = archivePath,
                innerPath = innerPath,
                maxBytes = request.maxBytes,
            ) { chunk, length ->
                digest.update(chunk, 0, length)
            }.getOrElse { failure ->
                if (failure is CancellationException) throw failure
                return FileHashResult.Failed(failure.toI18nMessage())
            }
        ) {
            ArchiveStreamOutcome.NotFound -> FileHashResult.Unavailable
            ArchiveStreamOutcome.TooLarge -> FileHashResult.TooLarge
            is ArchiveStreamOutcome.Completed -> digest.toHashResult()
        }
    }

    /**
     * 流式读取本地文件并计算哈希。
     *
     * @param request 哈希读取请求。
     * @return 哈希值、大小超限或不可读取结果。
     */
    private suspend fun readLocalHash(request: FileHashRequest): FileHashResult {
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
    private suspend fun hashLocalPath(path: Path): FileHashResult {
        val taskContext = kotlinx.coroutines.currentCoroutineContext()
        val digest = MessageDigest.getInstance(SHA_256)
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                taskContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.toHashResult()
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
            val encrypted = archiveService.isEncrypted(path.toString())
            val entryCount = if (!encrypted) {
                archiveService.list(path.toString(), "").getOrNull()?.size
            } else {
                null
            }
            ArchiveInfoResult.Info(
                encrypted = encrypted,
                canBrowse = true,
                canExtract = true,
                canWrite = false,
                entryCount = entryCount,
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
        val normalized = fileName.trim().lowercase()
        if (normalized in ExtensionlessTextFileNames) return true
        val ext = normalized.substringAfterLast('.', "")
        if (ext == normalized) return false
        return ext in TextPreviewExtensions
    }

    override fun isAudioFileName(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in AudioExtensions
    }

    override fun isVideoFileName(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in VideoExtensions
    }

    private companion object {
        val ImageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "tiff", "tif")
        val TextPreviewExtensions = setOf(
            "txt", "md", "markdown", "rst", "adoc", "log", "nfo", "diz",
            "xml", "xhtml", "htm", "html", "opf", "ncx", "svg", "rss", "atom",
            "json", "jsonc", "jsonl", "ndjson", "yaml", "yml", "toml", "ini", "cfg", "conf",
            "properties", "env", "csv", "tsv", "sql", "graphql", "gql", "proto",
            "java", "kt", "kts", "gradle", "groovy", "scala",
            "js", "mjs", "cjs", "jsx", "ts", "tsx", "css", "scss", "sass", "less", "vue", "svelte",
            "py", "pyi", "rb", "php", "go", "rs", "c", "h", "cpp", "cc", "cxx", "hpp", "hxx", "cs", "swift", "dart", "lua", "r",
            "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1", "psm1", "psd1",
            "srt", "ass", "ssa", "vtt", "lrc", "cue", "m3u", "m3u8",
            "gitignore", "gitattributes", "gitmodules", "editorconfig", "dockerignore", "npmrc", "lock",
        )
        val ExtensionlessTextFileNames = setOf(
            "readme", "license", "licence", "notice", "changelog", "authors", "contributors",
            "makefile", "dockerfile", "jenkinsfile", "procfile", "gemfile", "rakefile", "vagrantfile",
            ".gitignore", ".gitattributes", ".editorconfig", ".dockerignore", ".env",
        )
        val AudioExtensions = setOf(
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma",
            "aiff", "aif", "au", "ape", "wv", "mka", "alac", "caf",
        )
        val VideoExtensions = setOf(
            "mp4", "mkv", "mov", "avi", "webm", "wmv", "flv", "m4v",
            "mpg", "mpeg", "m2ts", "mts", "vob", "rmvb", "3gp", "ogv",
        )
    }
}

/**
 * 将压缩包内文件解压到临时位置后，通过统一外部打开服务交给系统应用。
 *
 * @param archiveService 读取归档条目内容的统一服务。
 * @param externalOpenService 打开已提取本地文件的平台入口。
 */
internal class JvmArchiveEntryOpenService(
    /** 提取原始归档条目内容的统一服务。 */
    private val archiveService: ArchiveService,
    /** 启动本地文件默认应用的统一入口。 */
    private val externalOpenService: ExternalOpenService,
) : ArchiveEntryOpenService {
    /**
     * 校验并提取单个归档文件，向界面返回可本地化的校验失败或原始底层异常。
     *
     * @param entry 待打开的统一归档文件条目。
     * @return 已启动的临时本地文件条目，或保留错误诊断的失败结果。
     */
    override suspend fun openArchiveEntry(entry: VFile): Result<VFile> = withContext(Dispatchers.IO) {
        runCatching {
            val (archivePath, innerPath) = ArchiveService.parseArchiveLocation(entry.location)
                ?: throw LocalizedException(I18nMessage(MessageKey.MSG_ARCHIVE_ENTRY_LOCATION_INVALID, entry.location))
            if (innerPath.isBlank()) {
                throw LocalizedException(I18nMessage(MessageKey.MSG_ARCHIVE_ENTRY_PATH_EMPTY))
            }
            val bytes = archiveService.extractToBytes(archivePath, innerPath).getOrThrow()
                ?: throw LocalizedException(I18nMessage(MessageKey.MSG_ARCHIVE_ENTRY_NOT_FOUND, innerPath))
            if (bytes.isEmpty()) {
                throw LocalizedException(I18nMessage(MessageKey.MSG_ARCHIVE_ENTRY_EMPTY, innerPath))
            }
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
                val bytes = archiveService.extractToBytes(
                    archivePath = archivePath,
                    innerPath = innerPath,
                    maxBytes = MAX_ARCHIVE_IMAGE_METADATA_BYTES,
                ).getOrNull()
                    ?: return@withContext null
                val image = Image.makeFromEncoded(bytes)
                try {
                    IntSize(image.width, image.height)
                } finally {
                    image.close()
                }
            } else if (entry.location.contains("://")) {
                null
            } else {
                val path = Path.of(entry.location)
                if (!Files.exists(path) || !Files.isRegularFile(path)) return@withContext null
                readLocalSizeViaImageIo(path, entry.name) ?: readLocalSizeViaSkia(path)
            }
        }.getOrNull()
    }

    /** 优先使用 ImageIO 仅读取图片头尺寸（避免整文件载入内存）。 */
    private fun readLocalSizeViaImageIo(path: Path, fileName: String): IntSize? {
        val suffix = fileName.substringAfterLast('.', "")
        if (suffix.isBlank()) return null
        val readers = ImageIO.getImageReadersBySuffix(suffix)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        return runCatching {
            ImageIO.createImageInputStream(path.toFile())?.use { stream ->
                reader.input = stream
                IntSize(reader.getWidth(0), reader.getHeight(0))
            }
        }.also {
            reader.dispose()
        }.getOrNull()
    }

    /**
     * 当 ImageIO 不支持 WebP/HEIF/ICO 等现代格式或读头失败时，回退到 Skia 解码并立即释放原生句柄。
     */
    private fun readLocalSizeViaSkia(path: Path): IntSize? {
        val size = runCatching { Files.size(path) }.getOrNull() ?: return null
        if (size <= 0L || size > MAX_LOCAL_IMAGE_METADATA_BYTES) return null
        val bytes = Files.readAllBytes(path)
        if (bytes.isEmpty()) return null
        val image = Image.makeFromEncoded(bytes)
        return try {
            IntSize(image.width, image.height)
        } finally {
            image.close()
        }
    }

    private companion object {
        /** 读取压缩包内图片尺寸时的最大允许字节数，防止超大归档图片触发 OOM。 */
        const val MAX_ARCHIVE_IMAGE_METADATA_BYTES = 20L * 1024 * 1024

        /** Skia 回退读取本地现代格式图片尺寸的最大允许字节数。 */
        const val MAX_LOCAL_IMAGE_METADATA_BYTES = 50L * 1024 * 1024
    }
}
