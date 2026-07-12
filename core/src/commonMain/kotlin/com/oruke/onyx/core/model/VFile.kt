package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

/** 跨本地与远程 provider 流转的统一虚拟文件快照。 */
data class VFile(
    /** provider 范围内稳定的条目 ID。 */
    val id: String,
    /** 条目显示名称。 */
    val name: String,
    /** 完整 VFS 位置。 */
    val location: String,
    /** 父目录 VFS 位置。 */
    val parentLocation: String?,
    /** 文件或目录类型。 */
    val kind: VFileKind,
    /** 文件大小；目录或未知时为空。 */
    val sizeBytes: Long?,
    /** 修改时间 epoch millis；未知时为空。 */
    val modifiedAtEpochMillis: Long?,
    /** 是否为隐藏条目。 */
    val hidden: Boolean,
    /** 当前 provider 对此条目支持的能力。 */
    val capabilities: Set<VFileCapability>,
)

@Serializable
/** 虚拟文件条目类型。 */
enum class VFileKind {
    FILE,
    DIRECTORY,
}

@Serializable
/** 单个虚拟文件条目可执行的基础能力。 */
enum class VFileCapability {
    LIST_CHILDREN,
    READ_METADATA,
    READ_CONTENT,
    WRITE_CONTENT,
    DELETE,
    RENAME,
}
