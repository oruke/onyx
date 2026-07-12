package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.TrashMoveRecord
import com.oruke.onyx.vfs.api.TrashRestorationStatus
import java.awt.Desktop
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * Windows 单条目回收站移动服务，区分“移动失败”和“移动成功但恢复元数据不可用”。
 *
 * @param moveToSystemTrash 调用 Windows Shell 移入回收站的函数。
 * @param resolveRecycleRecord 根据原路径和移动时间查找 `$I`/`$R` 记录的函数。
 * @param currentTimeMillis 当前时间函数。
 */
internal class WindowsTrashMoveService(
    private val moveToSystemTrash: (Path) -> Boolean = { source ->
        Desktop.getDesktop().moveToTrash(source.toFile())
    },
    private val resolveRecycleRecord: (Path, Long) -> WindowsRecycleBinRecord? =
        WindowsRecycleBinMetadataService::awaitRecord,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    /**
     * 将条目移入 Windows 回收站并生成恢复能力明确的移动记录。
     *
     * @param entry 移动前的 VFS 条目。
     * @param source 本地绝对路径。
     * @return 回收站移动记录。
     */
    fun move(entry: VFile, source: Path): TrashMoveRecord {
        val movedAtMillis = currentTimeMillis()
        check(moveToSystemTrash(source)) {
            "Failed to move ${entry.name} to trash"
        }
        val recycleRecord = resolveRecycleRecord(source, movedAtMillis)
        return if (recycleRecord != null) {
            TrashMoveRecord(
                originalEntry = entry,
                trashedLocation = recycleRecord.contentPath.pathString,
                metadataLocation = recycleRecord.infoPath.pathString,
                restorationStatus = TrashRestorationStatus.AVAILABLE,
            )
        } else {
            TrashMoveRecord(
                originalEntry = entry,
                trashedLocation = "",
                metadataLocation = null,
                restorationStatus = TrashRestorationStatus.METADATA_UNAVAILABLE,
            )
        }
    }
}
