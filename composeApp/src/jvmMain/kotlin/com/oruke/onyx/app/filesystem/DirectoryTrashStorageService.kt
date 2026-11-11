package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.TrashItem
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.pathString
import kotlin.streams.toList

/**
 * Linux FreeDesktop Trash 规范回收站存储枚举服务。
 *
 * 读取 `$XDG_DATA_HOME/Trash`（默认 `~/.local/share/Trash`）下的 `info/<name>.trashinfo` 与 `files/<name>`，
 * 严格按 RFC 2396 解码百分号编码的 UTF-8 字节序列（保留字面量 `+`），并拒绝 `..` 路径逃逸。
 *
 * @param trashRootProvider FreeDesktop Trash 根目录提供器。
 */
internal class FreedesktopTrashStorageService(
    private val trashRootProvider: () -> Path,
) : PlatformTrashStorageService {

    override fun listEntries(): List<TrashStorageEntry> {
        val trashRoot = trashRootProvider().normalize().toAbsolutePath()
        val filesDir = trashRoot.resolve(FILES_DIR_NAME)
        val infoDir = trashRoot.resolve(INFO_DIR_NAME)
        if (!Files.isDirectory(filesDir, LinkOption.NOFOLLOW_LINKS)) return emptyList()

        val infoByTrashName = scanTrashInfoFiles(infoDir, trashRoot.parent)
        val entries = runCatching {
            Files.list(filesDir).use { stream -> stream.toList() }
        }.getOrDefault(emptyList()).mapNotNull { contentPath ->
            val attrs = runCatching {
                Files.readAttributes(contentPath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }.getOrNull() ?: return@mapNotNull null
            val trashName = contentPath.fileName?.toString() ?: return@mapNotNull null
            val parsedInfo = infoByTrashName[trashName]
            val kind = if (attrs.isDirectory) VFileKind.DIRECTORY else VFileKind.FILE
            val originalPath = parsedInfo?.originalPath
            val displayName = originalPath?.fileName?.toString()?.takeIf { it.isNotBlank() } ?: trashName
            val deletedAt = parsedInfo?.deletedAtEpochMillis ?: attrs.lastModifiedTime().toMillis()
            TrashStorageEntry(
                item = TrashItem(
                    id = encodeTrashEntryId(contentPath),
                    displayName = displayName,
                    originalLocation = originalPath?.pathString,
                    originalParentLocation = originalPath?.parent?.pathString,
                    kind = kind,
                    sizeBytes = if (kind == VFileKind.FILE) attrs.size() else null,
                    deletedAtEpochMillis = deletedAt,
                ),
                contentPath = contentPath,
                metadataPath = parsedInfo?.infoFile ?: infoDir.resolve("$trashName$TRASH_INFO_SUFFIX")
                    .takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) },
            )
        }
        return entries.sortedWith(
            compareByDescending<TrashStorageEntry> { it.item.deletedAtEpochMillis ?: Long.MIN_VALUE }
                .thenBy { it.item.displayName },
        )
    }

    private fun scanTrashInfoFiles(infoDir: Path, topDir: Path?): Map<String, ParsedTrashInfo> {
        if (!Files.isDirectory(infoDir, LinkOption.NOFOLLOW_LINKS)) return emptyMap()
        val infoFiles = runCatching {
            Files.list(infoDir).use { stream -> stream.toList() }
        }.getOrDefault(emptyList())
        val map = LinkedHashMap<String, ParsedTrashInfo>()
        for (infoFile in infoFiles) {
            val trashName = infoFile.fileName?.toString()
                ?.takeIf { it.endsWith(TRASH_INFO_SUFFIX) }
                ?.removeSuffix(TRASH_INFO_SUFFIX)
                ?.takeIf { it.isNotEmpty() }
            if (trashName != null) {
                parseFreedesktopTrashInfo(infoFile, topDir)?.let { parsed ->
                    map[trashName] = parsed
                }
            }
        }
        return map
    }

    private data class ParsedTrashInfo(
        val infoFile: Path,
        val originalPath: Path?,
        val deletedAtEpochMillis: Long?,
    )

    companion object {
        private const val FILES_DIR_NAME = "files"
        private const val INFO_DIR_NAME = "info"
        private const val TRASH_INFO_SUFFIX = ".trashinfo"
        private const val TRASH_INFO_HEADER = "[Trash Info]"
        private const val HEX_RADIX = 16
        private const val HEX_NIBBLE_BITS = 4
        private const val PERCENT_ESCAPE_LENGTH = 3
        private val DELETION_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        /**
         * 解析单个 `.trashinfo` 文件中的原始路径与删除时间。
         */
        private fun parseFreedesktopTrashInfo(infoFile: Path, topDir: Path?): ParsedTrashInfo? {
            val lines = runCatching { Files.readAllLines(infoFile, StandardCharsets.UTF_8) }.getOrNull()
                ?: return null
            val firstNonBlank = lines.firstOrNull { it.isNotBlank() }?.trim() ?: return null
            if (firstNonBlank != TRASH_INFO_HEADER) return null

            var rawPathValue: String? = null
            var rawDeletionDate: String? = null
            for (line in lines.drop(1)) {
                val trimmed = line.trim()
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) break
                when {
                    trimmed.startsWith("Path=") && rawPathValue == null -> {
                        rawPathValue = trimmed.removePrefix("Path=")
                    }
                    trimmed.startsWith("DeletionDate=") && rawDeletionDate == null -> {
                        rawDeletionDate = trimmed.removePrefix("DeletionDate=")
                    }
                }
            }
            val resolvedOriginal = rawPathValue?.let { raw -> resolveOriginalPath(raw, topDir) }
            val deletedMillis = rawDeletionDate?.let(::parseDeletionDateMillis)
            return ParsedTrashInfo(
                infoFile = infoFile,
                originalPath = resolvedOriginal,
                deletedAtEpochMillis = deletedMillis,
            )
        }

        /**
         * 按 FreeDesktop 规范解码并解析 `Path=` 字段：拒绝包含 `..` 的逃逸路径，支持绝对路径或相对于 `$topdir` 的路径。
         */
        internal fun resolveOriginalPath(rawEncodedPath: String, topDir: Path?): Path? {
            val decoded = decodeRfc2396Path(rawEncodedPath)
                .takeIf { raw -> raw.isNotBlank() && raw.split('/', '\\').none { it == ".." } }
            val candidate = decoded?.let { valid -> runCatching { Path.of(valid) }.getOrNull() }
            return when {
                candidate == null -> null
                candidate.isAbsolute -> candidate.normalize()
                topDir != null -> topDir.resolve(candidate).normalize().toAbsolutePath()
                else -> null
            }
        }

        /**
         * 严格按 RFC 2396 解码百分号编码的 UTF-8 字节序列，保留 `+` 等未转义字符原样不变。
         *
         * @param encoded 百分号编码文本。
         * @return 解码后的 UTF-8 字符串。
         */
        internal fun decodeRfc2396Path(encoded: String): String {
            if (!encoded.contains('%')) return encoded
            val result = StringBuilder(encoded.length)
            val byteBuffer = ByteArrayOutputStream()
            var index = 0
            while (index < encoded.length) {
                val ch = encoded[index]
                val hi = if (ch == '%' && index + PERCENT_ESCAPE_LENGTH <= encoded.length) {
                    encoded[index + 1].digitToIntOrNull(HEX_RADIX)
                } else {
                    null
                }
                val lo = if (hi != null) encoded[index + 2].digitToIntOrNull(HEX_RADIX) else null
                if (hi != null && lo != null) {
                    byteBuffer.write((hi shl HEX_NIBBLE_BITS) or lo)
                    index += PERCENT_ESCAPE_LENGTH
                } else {
                    if (byteBuffer.size() > 0) {
                        result.append(byteBuffer.toString(StandardCharsets.UTF_8))
                        byteBuffer.reset()
                    }
                    result.append(ch)
                    index += 1
                }
            }
            if (byteBuffer.size() > 0) {
                result.append(byteBuffer.toString(StandardCharsets.UTF_8))
            }
            return result.toString()
        }

        private fun parseDeletionDateMillis(rawDate: String): Long? = runCatching {
            LocalDateTime.parse(rawDate.trim(), DELETION_DATE_FORMATTER)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }
}

/**
 * macOS `~/.Trash` 目录式回收站存储枚举服务。
 *
 * 自动跳过 `.DS_Store` 与 `.localized` 系统元数据文件，并通过内存映射保留本会话由 Onyx 移入回收站条目的原始位置；
 * 由外部 Finder 移入且缺少原始路径的条目仍可安全展示、永久删除或随清空回收站移除。
 *
 * @param trashDirectoryProvider `~/.Trash` 目录提供器。
 */
internal class MacOsTrashStorageService(
    private val trashDirectoryProvider: () -> Path,
) : PlatformTrashStorageService {

    private val sessionOriginalLocations = ConcurrentHashMap<String, RecordedMacTrashOrigin>()

    /**
     * 记录本会话移入 `~/.Trash` 的原始路径与删除时间。
     *
     * @param trashedPath 移入 `~/.Trash` 后的目标路径。
     * @param originalLocation 移入前的完整本地路径。
     * @param deletedAtEpochMillis 移入回收站的时间戳。
     */
    fun recordMovedEntry(
        trashedPath: Path,
        originalLocation: String,
        deletedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val key = trashedPath.normalize().toAbsolutePath().pathString
        sessionOriginalLocations[key] = RecordedMacTrashOrigin(originalLocation, deletedAtEpochMillis)
    }

    /**
     * 移除已还原或永久删除条目的会话记录。
     *
     * @param trashedPath `~/.Trash` 中的目标路径。
     */
    fun forgetMovedEntry(trashedPath: Path) {
        sessionOriginalLocations.remove(trashedPath.normalize().toAbsolutePath().pathString)
    }

    override fun listEntries(): List<TrashStorageEntry> {
        val trashDir = trashDirectoryProvider().normalize().toAbsolutePath()
        if (!Files.isDirectory(trashDir, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val children = runCatching {
            Files.list(trashDir).use { stream -> stream.toList() }
        }.getOrDefault(emptyList())

        return children.mapNotNull { contentPath ->
            val fileName = contentPath.fileName?.toString() ?: return@mapNotNull null
            if (fileName in IGNORED_SYSTEM_FILES) return@mapNotNull null
            val attrs = runCatching {
                Files.readAttributes(contentPath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }.getOrNull() ?: return@mapNotNull null
            val normalizedContent = contentPath.normalize().toAbsolutePath()
            val recorded = sessionOriginalLocations[normalizedContent.pathString]
            val originalPath = recorded?.originalLocation?.let { raw ->
                runCatching { Path.of(raw).normalize().toAbsolutePath() }.getOrNull()
            }
            val kind = if (attrs.isDirectory) VFileKind.DIRECTORY else VFileKind.FILE
            val displayName = originalPath?.fileName?.toString()?.takeIf { it.isNotBlank() } ?: fileName
            TrashStorageEntry(
                item = TrashItem(
                    id = encodeTrashEntryId(normalizedContent),
                    displayName = displayName,
                    originalLocation = originalPath?.pathString ?: recorded?.originalLocation,
                    originalParentLocation = originalPath?.parent?.pathString,
                    kind = kind,
                    sizeBytes = if (kind == VFileKind.FILE) attrs.size() else null,
                    deletedAtEpochMillis = recorded?.deletedAtEpochMillis ?: attrs.lastModifiedTime().toMillis(),
                ),
                contentPath = normalizedContent,
                metadataPath = null,
                onRemoved = { forgetMovedEntry(normalizedContent) },
            )
        }.sortedWith(
            compareByDescending<TrashStorageEntry> { it.item.deletedAtEpochMillis ?: Long.MIN_VALUE }
                .thenBy { it.item.displayName },
        )
    }

    private data class RecordedMacTrashOrigin(
        val originalLocation: String,
        val deletedAtEpochMillis: Long,
    )

    private companion object {
        val IGNORED_SYSTEM_FILES = setOf(".DS_Store", ".localized")
    }
}
