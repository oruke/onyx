package com.oruke.onyx.vfs.api

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind

/**
 * 回收站内单个条目的跨平台元数据快照。
 *
 * @property id 稳定回收站虚拟位置（形如 `system://trash/item/...`），同时作为 [VFile.id] 与 [VFile.location]。
 * @property displayName 界面展示名称；已知原路径时优先使用原文件名。
 * @property originalLocation 移入回收站前的完整原始路径；外部移入且无元数据时为 `null`。
 * @property originalParentLocation 移入回收站前的原始父目录位置；无元数据时为 `null`。
 * @property kind 条目类型（普通文件或目录）。
 * @property sizeBytes 条目字节大小；目录或未知时可为 `null`。
 * @property deletedAtEpochMillis 删除时间戳（Unix epoch 毫秒）；无元数据时可回退为条目修改时间。
 */
data class TrashItem(
    val id: String,
    val displayName: String,
    val originalLocation: String?,
    val originalParentLocation: String?,
    val kind: VFileKind,
    val sizeBytes: Long?,
    val deletedAtEpochMillis: Long?,
) {
    /**
     * 转换为统一 [VFile] 抽象供列表、检查器与状态栏消费。
     *
     * 回收站内的目录不授予 [VFileCapability.LIST_CHILDREN]，防止双击进入、内联展开或被误判为拖放目标。
     *
     * @return 对应的虚拟文件对象。
     */
    fun toVFile(): VFile = VFile(
        id = id,
        name = displayName,
        location = id,
        parentLocation = originalParentLocation,
        kind = kind,
        sizeBytes = sizeBytes,
        modifiedAtEpochMillis = deletedAtEpochMillis,
        hidden = false,
        capabilities = setOf(VFileCapability.READ_METADATA, VFileCapability.DELETE),
        identityKey = id,
    )
}

/**
 * 回收站批量还原结果，包含已还原条目与需要刷新的原始父目录集合。
 *
 * @property restoredItems 已成功还原回原位置的条目列表。
 * @property affectedParentLocations 还原涉及的原始父目录集合，供目录缓存失效刷新。
 */
data class TrashRestoreOutcome(
    val restoredItems: List<TrashItem>,
    val affectedParentLocations: Set<String>,
)
