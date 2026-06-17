package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.io.path.name
import kotlin.io.path.pathString
import com.oruke.onyx.vfs.api.TrashService
import com.oruke.onyx.vfs.api.TrashMoveRecord

/**
 * JVM 桌面回收站服务，负责移入系统回收站并生成可撤销恢复记录。
 *
 * Windows 继续交给系统 Shell 创建真实回收站条目，再读取 `$I` 元数据定位 `$R` 内容；
 * Linux 遵循 FreeDesktop Trash 目录结构；macOS 使用当前用户的 `~/.Trash`。
 */
class JvmDesktopTrashService : TrashService {
    /** 当前平台是否存在可用的回收站实现。 */
    override val isSupported: Boolean
        get() = when (currentHostPlatform()) {
            HostPlatform.WINDOWS -> Desktop.isDesktopSupported() && runCatching {
                Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)
            }.getOrDefault(false)

            HostPlatform.LINUX,
            HostPlatform.MACOS -> true

            HostPlatform.OTHER -> Desktop.isDesktopSupported() && runCatching {
                Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)
            }.getOrDefault(false)
        }

    /**
     * 将本地系统文件移入当前平台回收站。
     *
     * @param entries 需要移入回收站的本地文件条目。
     * @return 每个成功移入回收站条目的恢复记录。
     */
    override suspend fun moveToTrash(
        entries: List<VFile>,
    ): Result<List<TrashMoveRecord>> = withContext(Dispatchers.IO) {
        runCatching {
            check(isSupported) { "Trash is not supported on this platform" }
            entries.map { entry -> moveEntryToTrash(entry) }
        }
    }

    /**
     * 将历史记录中的回收站条目移回原位置。
     *
     * @param records 移入回收站时生成的恢复记录。
     * @return 恢复操作结果。
     */
    override suspend fun restoreFromTrash(
        records: List<TrashMoveRecord>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            records.forEach { record -> restoreEntry(record) }
        }
    }

    /**
     * 按当前平台分派回收站移动实现。
     *
     * @param entry 需要移入回收站的本地文件条目。
     * @return 可用于撤销的恢复记录。
     */
    private fun moveEntryToTrash(entry: VFile): TrashMoveRecord {
        val source = entry.requireSystemLocalPath("trash").normalize().toAbsolutePath()
        check(Files.exists(source)) { "Entry does not exist: ${entry.location}" }
        return when (currentHostPlatform()) {
            HostPlatform.WINDOWS -> moveWindowsEntryToTrash(entry, source)
            HostPlatform.LINUX -> moveFreedesktopEntryToTrash(entry, source)
            HostPlatform.MACOS -> moveSimpleDirectoryEntryToTrash(entry, source, macosTrashDirectory(), null)
            HostPlatform.OTHER -> moveDesktopEntryToTrash(entry, source)
        }
    }

    /**
     * 使用 Windows Shell 移入回收站，并读取 Shell 写入的恢复元数据。
     *
     * @param entry 需要移入回收站的文件条目。
     * @param source 本地绝对路径。
     * @return Windows 回收站恢复记录。
     */
    private fun moveWindowsEntryToTrash(
        entry: VFile,
        source: Path,
    ): TrashMoveRecord {
        val movedAtMillis = System.currentTimeMillis()
        check(Desktop.getDesktop().moveToTrash(source.toFile())) {
            "Failed to move ${entry.name} to trash"
        }
        val recycleRecord = WindowsRecycleBinMetadataService.awaitRecord(source, movedAtMillis)
            ?: error("Windows recycle bin record was not created for ${entry.location}")
        return TrashMoveRecord(
            originalEntry = entry,
            trashedLocation = recycleRecord.contentPath.pathString,
            metadataLocation = recycleRecord.infoPath.pathString,
        )
    }

    /**
     * 按 FreeDesktop Trash 规范把 Linux 文件移入用户回收站。
     *
     * @param entry 需要移入回收站的文件条目。
     * @param source 本地绝对路径。
     * @return Linux 回收站恢复记录。
     */
    private fun moveFreedesktopEntryToTrash(
        entry: VFile,
        source: Path,
    ): TrashMoveRecord {
        val trashRoot = linuxTrashRoot()
        val filesDirectory = trashRoot.resolve("files")
        val infoDirectory = trashRoot.resolve("info")
        Files.createDirectories(filesDirectory)
        Files.createDirectories(infoDirectory)
        val trashName = uniqueTrashName(filesDirectory, infoDirectory, source.name)
        val target = filesDirectory.resolve(trashName)
        val metadata = infoDirectory.resolve("$trashName.trashinfo")
        writeFreedesktopTrashInfo(metadata, source)
        runCatching {
            Files.move(source, target)
        }.onFailure {
            Files.deleteIfExists(metadata)
        }.getOrThrow()
        return TrashMoveRecord(
            originalEntry = entry,
            trashedLocation = target.pathString,
            metadataLocation = metadata.pathString,
        )
    }

    /**
     * 将文件移入 macOS 或通用目录式回收站。
     *
     * @param entry 需要移入回收站的文件条目。
     * @param source 本地绝对路径。
     * @param filesDirectory 回收站文件目录。
     * @param infoDirectory 可选的回收站元数据目录。
     * @return 目录式回收站恢复记录。
     */
    private fun moveSimpleDirectoryEntryToTrash(
        entry: VFile,
        source: Path,
        filesDirectory: Path,
        infoDirectory: Path?,
    ): TrashMoveRecord {
        Files.createDirectories(filesDirectory)
        infoDirectory?.let { directory -> Files.createDirectories(directory) }
        val trashName = uniqueTrashName(filesDirectory, infoDirectory, source.name)
        val target = filesDirectory.resolve(trashName)
        Files.move(source, target)
        return TrashMoveRecord(
            originalEntry = entry,
            trashedLocation = target.pathString,
            metadataLocation = null,
        )
    }

    /**
     * 非主流平台使用 JVM Desktop 回收站能力；该路径没有恢复元数据，因此不会进入撤销历史。
     *
     * @param entry 需要移入回收站的文件条目。
     * @param source 本地绝对路径。
     * @return 无元数据的回收站记录。
     */
    private fun moveDesktopEntryToTrash(
        entry: VFile,
        source: Path,
    ): TrashMoveRecord {
        check(Desktop.getDesktop().moveToTrash(source.toFile())) {
            "Failed to move ${entry.name} to trash"
        }
        return TrashMoveRecord(
            originalEntry = entry,
            trashedLocation = "",
            metadataLocation = null,
        )
    }

    /**
     * 根据恢复记录把条目移回原路径。
     *
     * @param record 需要恢复的回收站移动记录。
     */
    private fun restoreEntry(record: TrashMoveRecord) {
        val trashedLocation = record.trashedLocation
        check(trashedLocation.isNotBlank()) {
            "Trash record does not contain a restorable location for ${record.originalEntry.location}"
        }
        val trashedPath = Path.of(trashedLocation).normalize().toAbsolutePath()
        val originalPath = Path.of(record.originalEntry.location).normalize().toAbsolutePath()
        val originalParent = originalPath.parent
        check(originalParent != null && Files.isDirectory(originalParent)) {
            "Original parent directory is not available: ${record.originalEntry.parentLocation}"
        }
        if (Files.exists(originalPath)) {
            throw FileAlreadyExistsException(originalPath.pathString)
        }
        Files.move(trashedPath, originalPath)
        record.metadataLocation?.let { metadataLocation ->
            Files.deleteIfExists(Path.of(metadataLocation).normalize().toAbsolutePath())
        }
    }

    /**
     * 写入 Linux FreeDesktop Trash 元数据。
     *
     * @param metadata 元数据文件路径。
     * @param originalPath 原始本地路径。
     */
    private fun writeFreedesktopTrashInfo(
        metadata: Path,
        originalPath: Path,
    ) {
        val deletionDate = LocalDateTime.now().format(FREEDESKTOP_DELETION_DATE_FORMATTER)
        val content = buildString {
            appendLine("[Trash Info]")
            appendLine("Path=${URI(null, null, originalPath.pathString, null).rawPath}")
            appendLine("DeletionDate=$deletionDate")
        }
        Files.writeString(metadata, content, StandardCharsets.UTF_8)
    }

    /**
     * 生成回收站目录中不冲突的文件名。
     *
     * @param filesDirectory 回收站文件目录。
     * @param infoDirectory 可选的元数据目录。
     * @param sourceName 原始文件名。
     * @return 不与现有回收站内容冲突的文件名。
     */
    private fun uniqueTrashName(
        filesDirectory: Path,
        infoDirectory: Path?,
        sourceName: String,
    ): String {
        var index = 0
        while (true) {
            val candidate = if (index == 0) sourceName else sourceName.withDuplicateSuffix(index)
            val metadataPath = infoDirectory?.resolve("$candidate.trashinfo")
            if (!Files.exists(filesDirectory.resolve(candidate)) && metadataPath?.let(Files::exists) != true) {
                return candidate
            }
            index += 1
        }
    }

    /**
     * 为重复回收站文件名增加序号后缀。
     *
     * @param index 序号，从 1 开始递增。
     * @return 带序号的文件名。
     */
    private fun String.withDuplicateSuffix(index: Int): String {
        val dotIndex = lastIndexOf('.')
        return if (dotIndex > 0) {
            "${substring(0, dotIndex)} ($index)${substring(dotIndex)}"
        } else {
            "$this ($index)"
        }
    }

    /**
     * 返回 Linux 用户级回收站根目录。
     *
     * @return FreeDesktop Trash 根目录。
     */
    private fun linuxTrashRoot(): Path {
        val xdgDataHome = System.getenv("XDG_DATA_HOME")?.takeIf { value -> value.isNotBlank() }
        return if (xdgDataHome != null) {
            Path.of(xdgDataHome, "Trash")
        } else {
            Path.of(System.getProperty("user.home"), ".local", "share", "Trash")
        }
    }

    /**
     * 返回 macOS 当前用户回收站目录。
     *
     * @return `~/.Trash` 路径。
     */
    private fun macosTrashDirectory(): Path {
        return Path.of(System.getProperty("user.home"), ".Trash")
    }

    /**
     * 识别当前 JVM 所在宿主平台。
     *
     * @return 当前宿主平台枚举。
     */
    private fun currentHostPlatform(): HostPlatform {
        val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
        return when {
            osName.contains("mac") || osName.contains("darwin") -> HostPlatform.MACOS
            osName.contains("win") -> HostPlatform.WINDOWS
            osName.contains("nux") || osName.contains("nix") || osName.contains("linux") -> HostPlatform.LINUX
            else -> HostPlatform.OTHER
        }
    }

    /** 当前 JVM 宿主平台。 */
    private enum class HostPlatform {
        WINDOWS,
        MACOS,
        LINUX,
        OTHER,
    }

    private companion object {
        /** FreeDesktop Trash 删除时间格式。 */
        val FREEDESKTOP_DELETION_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }
}
