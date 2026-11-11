package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.StorageDeviceService
import com.oruke.onyx.vfs.api.TrashItem
import com.oruke.onyx.vfs.api.TrashMoveRecord
import com.oruke.onyx.vfs.api.TrashRestoreOutcome
import com.oruke.onyx.vfs.api.TrashService
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
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

/**
 * JVM 桌面回收站服务，负责移入系统回收站、枚举回收站内容、还原、永久删除与清空回收站。
 *
 * Windows 调用系统 Shell 创建真实回收站条目并通过 `$I`/`$R` 元数据枚举与还原；
 * Linux 遵循 FreeDesktop Trash 目录规范；macOS 使用当前用户的 `~/.Trash`。
 *
 * @param windowsTrashMoveService Windows 单条目回收站移入实现。
 * @param storageDevices 平台设备发现服务，用于过滤离线或网络盘的回收站扫描。
 * @param storageOverride 可选的回收站底层存储覆盖实例（主要用于单元测试）。
 */
internal class JvmDesktopTrashService(
    private val windowsTrashMoveService: WindowsTrashMoveService = WindowsTrashMoveService(),
    storageDevices: StorageDeviceService? = null,
    private val storageOverride: PlatformTrashStorageService? = null,
) : TrashService {

    private val windowsStorage = WindowsRecycleBinStorageService(storageDevices = storageDevices)
    private val freedesktopStorage = FreedesktopTrashStorageService(::linuxTrashRoot)
    private val macOsStorage = MacOsTrashStorageService(::macosTrashDirectory)

    /** 当前平台是否存在可用的回收站实现。 */
    override val isSupported: Boolean
        get() = storageOverride != null || when (currentHostPlatform()) {
            HostPlatform.WINDOWS -> Desktop.isDesktopSupported() && runCatching {
                Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)
            }.getOrDefault(false)

            HostPlatform.LINUX,
            HostPlatform.MACOS -> true

            HostPlatform.OTHER -> Desktop.isDesktopSupported() && runCatching {
                Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)
            }.getOrDefault(false)
        }

    override suspend fun moveToTrash(
        entries: List<VFile>,
    ): Result<List<TrashMoveRecord>> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isSupported) throw LocalizedException(I18nMessage(MessageKey.MSG_TRASH_UNSUPPORTED))
            entries.map(::moveEntryToTrash)
        }
    }

    override suspend fun restoreFromTrash(
        records: List<TrashMoveRecord>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            records.forEach(::restoreEntry)
        }
    }

    override suspend fun listItems(): Result<List<TrashItem>> = withContext(Dispatchers.IO) {
        runCatching {
            activeStorageService()?.listEntries()?.map(TrashStorageEntry::item).orEmpty()
        }
    }

    override suspend fun restoreItems(
        entryIds: Set<String>,
    ): Result<TrashRestoreOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            if (entryIds.isEmpty()) {
                return@runCatching TrashRestoreOutcome(emptyList(), emptySet())
            }
            val storage = activeStorageService()
                ?: throw LocalizedException(I18nMessage(MessageKey.MSG_TRASH_UNSUPPORTED))
            val byId = storage.listEntries().associateBy { entry -> entry.item.id }
            val targets = entryIds.map { id ->
                byId[id] ?: throw VfsProviderException(VfsProviderError.NotFound(VfsProtocol.SYSTEM, id))
            }
            executeTrashRestore(targets)
        }
    }

    override suspend fun deletePermanently(
        entryIds: Set<String>,
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            if (entryIds.isEmpty()) return@runCatching 0
            val storage = activeStorageService()
                ?: throw LocalizedException(I18nMessage(MessageKey.MSG_TRASH_UNSUPPORTED))
            val byId = storage.listEntries().associateBy { entry -> entry.item.id }
            val targets = entryIds.mapNotNull { id -> byId[id] }
            executeTrashPermanentDelete(targets)
        }
    }

    override suspend fun emptyTrash(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val storage = activeStorageService()
                ?: throw LocalizedException(I18nMessage(MessageKey.MSG_TRASH_UNSUPPORTED))
            val allEntries = storage.listEntries()
            if (allEntries.isEmpty()) 0 else executeTrashPermanentDelete(allEntries)
        }
    }

    private fun activeStorageService(): PlatformTrashStorageService? {
        storageOverride?.let { override -> return override }
        return when (currentHostPlatform()) {
            HostPlatform.WINDOWS -> windowsStorage
            HostPlatform.LINUX -> freedesktopStorage
            HostPlatform.MACOS -> macOsStorage
            HostPlatform.OTHER -> null
        }
    }

    private fun moveEntryToTrash(entry: VFile): TrashMoveRecord {
        val source = entry.requireSystemLocalPath("trash").normalize().toAbsolutePath()
        check(Files.exists(source)) { "Entry does not exist: ${entry.location}" }
        return when (currentHostPlatform()) {
            HostPlatform.WINDOWS -> windowsTrashMoveService.move(entry, source)
            HostPlatform.LINUX -> moveFreedesktopEntryToTrash(entry, source)
            HostPlatform.MACOS -> moveMacOsEntryToTrash(entry, source)
            HostPlatform.OTHER -> moveDesktopEntryToTrash(entry, source)
        }
    }

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

    private fun moveMacOsEntryToTrash(
        entry: VFile,
        source: Path,
    ): TrashMoveRecord {
        val filesDirectory = macosTrashDirectory()
        Files.createDirectories(filesDirectory)
        val trashName = uniqueTrashName(filesDirectory, null, source.name)
        val target = filesDirectory.resolve(trashName)
        Files.move(source, target)
        macOsStorage.recordMovedEntry(target, source.pathString)
        return TrashMoveRecord(
            originalEntry = entry,
            trashedLocation = target.pathString,
            metadataLocation = null,
        )
    }

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

    private fun restoreEntry(record: TrashMoveRecord) {
        val trashedLocation = record.trashedLocation
        check(trashedLocation.isNotBlank()) {
            "Trash record does not contain a restorable location for ${record.originalEntry.location}"
        }
        val trashedPath = Path.of(trashedLocation).normalize().toAbsolutePath()
        val originalPath = Path.of(record.originalEntry.location).normalize().toAbsolutePath()
        val originalParent = checkNotNull(originalPath.parent) {
            "Original parent directory is not available: ${record.originalEntry.parentLocation}"
        }
        if (!Files.exists(originalParent)) {
            Files.createDirectories(originalParent)
        }
        if (Files.exists(originalPath)) {
            throw FileAlreadyExistsException(originalPath.pathString)
        }
        Files.move(trashedPath, originalPath)
        record.metadataLocation?.let { metadataLocation ->
            Files.deleteIfExists(Path.of(metadataLocation).normalize().toAbsolutePath())
        }
        macOsStorage.forgetMovedEntry(trashedPath)
    }

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

    private fun String.withDuplicateSuffix(index: Int): String {
        val dotIndex = lastIndexOf('.')
        return if (dotIndex > 0) {
            "${substring(0, dotIndex)} ($index)${substring(dotIndex)}"
        } else {
            "$this ($index)"
        }
    }

    private fun linuxTrashRoot(): Path {
        val xdgDataHome = System.getenv("XDG_DATA_HOME")?.takeIf { value -> value.isNotBlank() }
        return if (xdgDataHome != null) {
            Path.of(xdgDataHome, "Trash")
        } else {
            Path.of(System.getProperty("user.home"), ".local", "share", "Trash")
        }
    }

    private fun macosTrashDirectory(): Path = Path.of(System.getProperty("user.home"), ".Trash")

    private fun currentHostPlatform(): HostPlatform {
        val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
        return when {
            osName.contains("mac") || osName.contains("darwin") -> HostPlatform.MACOS
            osName.contains("win") -> HostPlatform.WINDOWS
            osName.contains("nux") || osName.contains("nix") || osName.contains("linux") -> HostPlatform.LINUX
            else -> HostPlatform.OTHER
        }
    }

    private enum class HostPlatform {
        WINDOWS,
        MACOS,
        LINUX,
        OTHER,
    }

    private companion object {
        val FREEDESKTOP_DELETION_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }
}
