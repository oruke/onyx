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
import java.nio.file.attribute.UserPrincipal
import kotlin.io.path.pathString
import kotlin.streams.toList

/**
 * Windows `$Recycle.Bin` 存储枚举服务。
 *
 * 仅扫描本地与可移动已挂载卷下的当前用户回收站目录，跳过离线或网络映射盘；
 * 使用 `NOFOLLOW_LINKS` 读取 `$R` 内容类型，防止重解析点或损坏链接影响列表加载。
 *
 * @param storageDevices 可选的平台设备服务，用于过滤网络卷并仅扫描已挂载本地/可移动卷。
 * @param rootsProvider 可选的自定义根路径提供器（测试或无设备快照时的回退）。
 * @param currentUserOwnerProvider 当前系统用户主体提供器，用于在提权环境下排除其他用户的 SID 目录。
 */
internal class WindowsRecycleBinStorageService(
    private val storageDevices: StorageDeviceService? = null,
    private val rootsProvider: (() -> List<Path>)? = null,
    private val currentUserOwnerProvider: () -> UserPrincipal? = ::resolveDefaultWindowsUserOwner,
) : PlatformTrashStorageService {

    override fun listEntries(): List<TrashStorageEntry> {
        val currentOwner = currentUserOwnerProvider()
        return candidateRoots()
            .asSequence()
            .flatMap { root -> scanRootRecycleBin(root, currentOwner).asSequence() }
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
     * 从已挂载本地/可移动设备快照或本地文件系统根解析待扫描的磁盘根路径。
     */
    private fun resolveDeviceOrSystemRoots(): List<Path> {
        val snapshot = storageDevices?.cachedSnapshot().orEmpty()
        return if (snapshot.isNotEmpty()) {
            snapshot
                .asSequence()
                .filter { device ->
                    device.state == StorageDeviceState.MOUNTED &&
                        (device.kind == StorageDeviceKind.LOCAL || device.kind == StorageDeviceKind.REMOVABLE)
                }
                .flatMap { device -> device.locations.asSequence() }
                .filterNot { location -> location.startsWith("\\\\") || location.startsWith("//") }
                .mapNotNull { location -> runCatching { Path.of(location) }.getOrNull() }
                .distinct()
                .toList()
        } else {
            runCatching {
                FileSystems.getDefault().rootDirectories
                    .asSequence()
                    .filterNot { root ->
                        val raw = root.pathString
                        raw.startsWith("\\\\") || raw.startsWith("//")
                    }
                    .toList()
            }.getOrDefault(emptyList())
        }
    }

    /**
     * 扫描单个磁盘根下的 `$Recycle.Bin`（兼容 NTFS SID 子目录及 FAT32/exFAT 直接存放 `$I` 的结构）。
     */
    private fun scanRootRecycleBin(root: Path, currentOwner: UserPrincipal?): List<TrashStorageEntry> {
        val recycleRoot = root.resolve(WINDOWS_RECYCLE_DIRECTORY_NAME)
        val rootChildren = recycleRoot.safeChildren()
        if (rootChildren.isEmpty()) return emptyList()

        val infoFiles = mutableListOf<Path>()
        for (child in rootChildren) {
            val fileName = child.fileName?.toString().orEmpty()
            if (fileName.startsWith(WINDOWS_RECYCLE_INFO_PREFIX)) {
                infoFiles.add(child)
            } else if (child.isPlainDirectoryNoFollow() && child.isOwnedByCurrentUserOrUnsupported(currentOwner)) {
                child.safeChildren()
                    .filter { candidate ->
                        candidate.fileName?.toString()?.startsWith(WINDOWS_RECYCLE_INFO_PREFIX) == true
                    }
                    .forEach(infoFiles::add)
            }
        }
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
        val originalPath = runCatching { Path.of(record.originalLocation).normalize() }.getOrNull()
        val displayName = originalPath?.fileName?.toString()?.takeIf { it.isNotBlank() }
            ?: record.originalLocation.substringAfterLast('\\').substringAfterLast('/')
                .ifBlank { record.contentPath.fileName.toString() }
        val originalParent = originalPath?.parent?.pathString
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
                originalLocation = originalPath?.pathString ?: record.originalLocation,
                originalParentLocation = originalParent,
                kind = kind,
                sizeBytes = sizeBytes,
                deletedAtEpochMillis = record.deletedAtMillis,
            ),
            contentPath = record.contentPath,
            metadataPath = record.infoPath,
        )
    }

    private fun Path.isPlainDirectoryNoFollow(): Boolean =
        runCatching { Files.isDirectory(this, LinkOption.NOFOLLOW_LINKS) }.getOrDefault(false)

    /**
     * 判断 SID 目录是否属于当前用户；在不支持 ACL 的文件系统（如 exFAT）上放行可读目录。
     */
    private fun Path.isOwnedByCurrentUserOrUnsupported(currentOwner: UserPrincipal?): Boolean {
        if (currentOwner == null) return true
        return try {
            Files.getOwner(this, LinkOption.NOFOLLOW_LINKS) == currentOwner
        } catch (_: UnsupportedOperationException) {
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun Path.safeChildren(): List<Path> = runCatching {
        if (!Files.isDirectory(this, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        Files.list(this).use { stream -> stream.toList() }
    }.getOrDefault(emptyList())

    private companion object {
        const val WINDOWS_RECYCLE_DIRECTORY_NAME = "\$Recycle.Bin"
        const val WINDOWS_RECYCLE_INFO_PREFIX = "\$I"

        fun resolveDefaultWindowsUserOwner(): UserPrincipal? {
            val userHome = System.getProperty("user.home")?.takeIf { it.isNotBlank() } ?: return null
            return runCatching {
                Files.getOwner(Path.of(userHome), LinkOption.NOFOLLOW_LINKS)
            }.getOrNull()
        }
    }
}
