package com.oruke.onyx.vfs.api

import com.oruke.onyx.core.model.VFile

/**
 * 外部打开服务，负责把文件交给系统默认应用或平台打开通道。
 */
interface ExternalOpenService {
    /**
     * 打开指定文件条目。
     *
     * @param entry 待打开条目。
     * @return 操作结果。
     */
    suspend fun open(entry: VFile): Result<Unit>
}

/**
 * 文本剪贴板服务，隔离平台剪贴板实现。
 */
interface TextClipboardService {
    /**
     * 复制文本到系统剪贴板。
     *
     * @param text 待复制文本。
     * @return 操作结果。
     */
    suspend fun copyText(text: String): Result<Unit>
}

/**
 * 回收站服务，负责平台可恢复删除能力。
 */
interface TrashService {
    /** 当前平台是否支持移动到回收站。 */
    val isSupported: Boolean

    /**
     * 将条目移动到回收站。
     *
     * @param entries 待处理条目。
     * @return 操作结果。
     */
    suspend fun moveToTrash(entries: List<VFile>): Result<List<TrashMoveRecord>>

    /**
     * 将回收站中的条目恢复到原位置。
     *
     * @param records 移入回收站时返回的恢复记录。
     * @return 恢复操作结果。
     */
    suspend fun restoreFromTrash(records: List<TrashMoveRecord>): Result<Unit>

    /**
     * 列举当前用户回收站中的全部可识别条目。
     *
     * @return 回收站条目快照列表。
     */
    suspend fun listItems(): Result<List<TrashItem>> = Result.success(emptyList())

    /**
     * 将指定回收站条目还原至各自的原始位置；若原父目录已不存在则自动重建，若目标已存在则拒绝覆盖。
     *
     * @param entryIds 待还原的回收站条目 ID（即 [TrashItem.id]）集合。
     * @return 还原成功条目与受影响原父目录集合。
     */
    suspend fun restoreItems(entryIds: Set<String>): Result<TrashRestoreOutcome> =
        Result.failure(UnsupportedOperationException("Trash restore is not supported"))

    /**
     * 从回收站永久删除指定条目（不递归符号链接与目录联接）。
     *
     * @param entryIds 待永久删除的回收站条目 ID 集合。
     * @return 实际永久删除的条目数量。
     */
    suspend fun deletePermanently(entryIds: Set<String>): Result<Int> =
        Result.failure(UnsupportedOperationException("Trash permanent delete is not supported"))

    /**
     * 清空当前用户回收站内的全部条目。
     *
     * @return 实际清理的条目数量。
     */
    suspend fun emptyTrash(): Result<Int> =
        Result.failure(UnsupportedOperationException("Empty trash is not supported"))
}

/**
 * 回收站移动记录，用于文件操作历史在条目消失后仍能恢复。
 *
 * @property originalEntry 移入回收站前的文件条目。
 * @property trashedLocation 平台回收站内的实际条目位置。
 * @property metadataLocation 平台回收站元数据位置；没有独立元数据时为 `null`。
 * @property restorationStatus 当前记录是否具备可靠恢复信息。
 */
data class TrashMoveRecord(
    val originalEntry: VFile,
    val trashedLocation: String,
    val metadataLocation: String? = null,
    val restorationStatus: TrashRestorationStatus = TrashRestorationStatus.AVAILABLE,
)

/** 回收站记录的恢复能力状态。 */
enum class TrashRestorationStatus {
    /** 已获得平台回收站位置，可以执行撤销。 */
    AVAILABLE,

    /** 文件已进入回收站，但平台元数据尚未出现，当前操作不能可靠撤销。 */
    METADATA_UNAVAILABLE,
}

/**
 * 系统文件物化服务，用于把非本地 VFS 条目导出为可交给外部系统的本地文件。
 */
interface SystemFileMaterializer {
    /**
     * 判断条目是否支持物化。
     *
     * @param entry 待检查条目。
     * @return 支持时返回 true。
     */
    fun supports(entry: VFile): Boolean

    /**
     * 将条目物化为系统本地文件。
     *
     * @param entry 待物化条目。
     * @return 物化后的本地条目。
     */
    suspend fun materialize(entry: VFile): Result<VFile>
}
