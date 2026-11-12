package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.StorageDeviceKind
import com.oruke.onyx.core.model.StorageDeviceState
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.StorageDeviceService
import com.oruke.onyx.vfs.api.TrashItem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Windows `$Recycle.Bin` 存储枚举服务。
 *
 * 仅扫描本地与可移动已挂载卷下的当前用户回收站目录，跳过离线或网络映射盘；
 * 使用 `NOFOLLOW_LINKS` 读取 `$R` 内容类型，防止重解析点或损坏链接影响列表加载。
 *
 * @param storageDevices 可选的平台设备服务，用于过滤网络卷并仅扫描已挂载本地/可移动卷。
 * @param rootsProvider 可选的自定义根路径提供器（测试或无设备快照时的回退）。
 * @param currentUserSidProvider 当前系统用户 SID 提供器，用于直达当前用户回收站目录。
 */
internal class WindowsRecycleBinStorageService(
    private val storageDevices: StorageDeviceService? = null,
    private val rootsProvider: (() -> List<Path>)? = null,
    private val currentUserSidProvider: () -> String? = { WindowsProcessUserSidResolver.currentUserSid },
) : PlatformTrashStorageService {

    override fun listEntries(): List<TrashStorageEntry> {
        val currentSid = currentUserSidProvider()
        return candidateRoots()
            .asSequence()
            .flatMap { root -> scanRootRecycleBin(root, currentSid).asSequence() }
            .distinctBy { entry -> entry.item.id }
            .sortedWith(
                compareByDescending<TrashStorageEntry> { it.item.deletedAtEpochMillis ?: Long.MIN_VALUE }
                    .thenBy { it.item.displayName },
            )
            .toList()
    }

    /**
     * 获取允许扫描 `$Recycle.Bin` 的本地卷根路径列表，显式排除网络映射盘与 UNC 根。
     */
    private fun candidateRoots(): List<Path> {
        val customProvider = rootsProvider
        return if (customProvider != null) {
            customProvider()
        } else {
            resolveDeviceOrSystemRoots()
        }
    }

    /**
     * 从已挂载本地/可移动设备快照与本地文件系统根合并解析待扫描的磁盘根路径。
     */
    private fun resolveDeviceOrSystemRoots(): List<Path> {
        val deviceRoots = storageDevices?.cachedSnapshot().orEmpty()
            .asSequence()
            .filter { device ->
                device.state == StorageDeviceState.MOUNTED &&
                    (device.kind == StorageDeviceKind.LOCAL || device.kind == StorageDeviceKind.REMOVABLE)
            }
            .flatMap { device -> device.locations.asSequence() }
            .filterNot { location -> location.startsWith("\\\\") || location.startsWith("//") }
            .mapNotNull { location -> runCatching { Path.of(location) }.getOrNull() }
            .filter { path -> WindowsProcessUserSidResolver.isLocalOrRemovableSystemRoot(path) }

        val systemRoots = runCatching {
            FileSystems.getDefault().rootDirectories.asSequence()
        }.getOrDefault(emptySequence())
            .filter { root -> WindowsProcessUserSidResolver.isLocalOrRemovableSystemRoot(root) }

        return (deviceRoots + systemRoots)
            .distinctBy { it.toAbsolutePath().normalize() }
            .toList()
    }

    /**
     * 扫描单个磁盘根下的 `$Recycle.Bin`（兼容 NTFS SID 子目录及 FAT32/exFAT 直接存放 `$I` 的结构）。
     */
    private fun scanRootRecycleBin(root: Path, currentSid: String?): List<TrashStorageEntry> {
        val recycleRoot = root.resolve(WINDOWS_RECYCLE_DIRECTORY_NAME)
        val infoFiles = WindowsProcessUserSidResolver.collectRecycleBinInfoFiles(recycleRoot, currentSid)
        return infoFiles.mapNotNull(::toStorageEntry)
    }

    /**
     * 将单个 `$I` 元数据文件解析并关联对应的 `$R` 内容路径，生成 [TrashStorageEntry]。
     */
    private fun toStorageEntry(infoPath: Path): TrashStorageEntry? {
        val record = WindowsRecycleBinMetadataService.parseInfo(infoPath) ?: return null
        val contentAttrs = runCatching {
            Files.readAttributes(record.contentPath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() ?: return null
        val kind = if (contentAttrs.isDirectory) VFileKind.DIRECTORY else VFileKind.FILE
        val normalizedLocation = normalizeWindowsLocation(record.originalLocation)
        val displayName = extractWindowsFileName(normalizedLocation)
            .ifBlank { record.contentPath.fileName.toString() }
        val originalParent = extractWindowsParent(normalizedLocation)
        val sizeBytes = when {
            kind == VFileKind.DIRECTORY && record.originalSizeBytes <= 0L -> null
            record.originalSizeBytes > 0L -> record.originalSizeBytes
            kind == VFileKind.FILE -> contentAttrs.size()
            else -> null
        }
        val id = encodeTrashEntryId(record.contentPath)
        return TrashStorageEntry(
            item = TrashItem(
                id = id,
                displayName = displayName,
                originalLocation = normalizedLocation,
                originalParentLocation = originalParent,
                kind = kind,
                sizeBytes = sizeBytes,
                deletedAtEpochMillis = record.deletedAtMillis,
            ),
            contentPath = record.contentPath,
            metadataPath = record.infoPath,
        )
    }

    private fun normalizeWindowsLocation(rawLocation: String): String {
        if (rawLocation.length == DRIVE_ROOT_LENGTH && rawLocation[1] == ':') {
            return rawLocation
        }
        return rawLocation.trimEnd('\\', '/').ifBlank { rawLocation }
    }

    private fun extractWindowsFileName(location: String): String =
        location.substringAfterLast('\\').substringAfterLast('/')

    private fun extractWindowsParent(location: String): String? {
        val lastSep = maxOf(location.lastIndexOf('\\'), location.lastIndexOf('/'))
        return when {
            lastSep <= 0 -> null
            lastSep == DRIVE_ROOT_SEPARATOR_INDEX && location.getOrNull(1) == ':' ->
                location.substring(0, DRIVE_ROOT_LENGTH)
            location.startsWith("\\\\") ->
                location.substring(0, lastSep).takeIf { parent ->
                    parent.removePrefix("\\\\").contains('\\')
                }
            else -> location.substring(0, lastSep).takeIf { it.isNotBlank() }
        }
    }

    private companion object {
        const val WINDOWS_RECYCLE_DIRECTORY_NAME = "\$Recycle.Bin"
        const val DRIVE_ROOT_SEPARATOR_INDEX = 2
        const val DRIVE_ROOT_LENGTH = 3
    }
}
