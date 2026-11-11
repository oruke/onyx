package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.buildTrashItemLocation
import com.oruke.onyx.vfs.api.TrashItem
import com.oruke.onyx.vfs.api.TrashRestoreOutcome
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.local.LocalPathDeletion
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.pathString

/**
 * 平台回收站底层存储接口，隔离 Windows `$Recycle.Bin`、Linux FreeDesktop Trash 与 macOS `~/.Trash` 枚举细节。
 */
internal interface PlatformTrashStorageService {
    /**
     * 扫描当前用户的平台回收站并返回按删除时间降序排列的物理条目列表。
     *
     * @return 回收站存储条目列表。
     */
    fun listEntries(): List<TrashStorageEntry>
}

/**
 * 回收站单个物理存储条目，将对外公开的 [TrashItem] 与底层内容及元数据路径绑定。
 *
 * @property item 面向业务与界面的回收站条目快照。
 * @property contentPath 回收站内的真实内容路径（如 `$R` 或 `files/<name>`）。
 * @property metadataPath 独立的平台元数据文件路径（如 `$I` 或 `info/<name>.trashinfo`）；无独立元数据时为 `null`。
 * @property onRemoved 条目从回收站移出或永久删除后清理附加会话记录的回调。
 */
internal data class TrashStorageEntry(
    val item: TrashItem,
    val contentPath: Path,
    val metadataPath: Path? = null,
    val onRemoved: (() -> Unit)? = null,
)

/**
 * 根据回收站内真实内容路径编码生成稳定的虚拟位置 ID。
 *
 * @param contentPath 回收站内真实内容路径。
 * @return 形如 `system://trash/item/...` 的稳定虚拟位置。
 */
internal fun encodeTrashEntryId(contentPath: Path): String {
    val normalized = contentPath.normalize().toAbsolutePath().pathString
    val encoded = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(normalized.toByteArray(StandardCharsets.UTF_8))
    return buildTrashItemLocation(encoded)
}

/**
 * 执行回收站条目批量还原：原父目录缺失时自动重建，目标位置已存在同名条目时拒绝覆盖，内容移回后再清理元数据。
 *
 * @param entries 待还原的物理存储条目列表。
 * @return 还原完成的条目与受影响的原父目录集合。
 */
internal fun executeTrashRestore(entries: List<TrashStorageEntry>): TrashRestoreOutcome {
    val restoredItems = mutableListOf<TrashItem>()
    val affectedParents = linkedSetOf<String>()
    for (entry in entries) {
        val (originalPath, originalParent) = resolveRestoreTargetPaths(entry)
        verifyRestorePreconditions(entry, originalPath)
        if (!Files.exists(originalParent)) {
            Files.createDirectories(originalParent)
        }
        Files.move(entry.contentPath, originalPath)
        entry.metadataPath?.let { metadata ->
            Files.deleteIfExists(metadata.normalize().toAbsolutePath())
        }
        entry.onRemoved?.invoke()
        restoredItems += entry.item
        affectedParents += originalParent.pathString
    }
    return TrashRestoreOutcome(
        restoredItems = restoredItems,
        affectedParentLocations = affectedParents,
    )
}

/**
 * 解析并校验待还原条目的原始目标路径与父目录。
 */
private fun resolveRestoreTargetPaths(entry: TrashStorageEntry): Pair<Path, Path> {
    val originalLocation = entry.item.originalLocation?.takeIf { it.isNotBlank() }
        ?: throw LocalizedException(I18nMessage(MessageKey.MSG_TRASH_ORIGINAL_PATH_UNKNOWN))
    val originalPath = Path.of(originalLocation).normalize().toAbsolutePath()
    val originalParent = originalPath.parent
        ?: throw VfsProviderException(
            VfsProviderError.UnsupportedOperation(VfsProtocol.LOCAL, originalLocation),
        )
    return originalPath to originalParent
}

/**
 * 校验待还原内容存在且目标原位置不存在同名冲突。
 */
private fun verifyRestorePreconditions(entry: TrashStorageEntry, originalPath: Path) {
    if (!Files.exists(entry.contentPath, LinkOption.NOFOLLOW_LINKS)) {
        throw VfsProviderException(VfsProviderError.NotFound(VfsProtocol.SYSTEM, entry.item.id))
    }
    if (Files.exists(originalPath, LinkOption.NOFOLLOW_LINKS)) {
        throw VfsProviderException(
            VfsProviderError.AlreadyExists(VfsProtocol.LOCAL, originalPath.pathString),
        )
    }
}

/**
 * 执行回收站条目永久删除：始终先经 [LocalPathDeletion] 安全删除内容（不穿透符号链接与 Windows Junction），成功后再删除元数据。
 *
 * @param entries 待永久删除的物理存储条目列表。
 * @return 实际删除的条目数量。
 */
internal fun executeTrashPermanentDelete(entries: List<TrashStorageEntry>): Int {
    var deletedCount = 0
    for (entry in entries) {
        LocalPathDeletion.delete(entry.contentPath)
        entry.metadataPath?.let { metadata ->
            Files.deleteIfExists(metadata.normalize().toAbsolutePath())
        }
        entry.onRemoved?.invoke()
        deletedCount += 1
    }
    return deletedCount
}
