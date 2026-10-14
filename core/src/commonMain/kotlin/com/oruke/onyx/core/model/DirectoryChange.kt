package com.oruke.onyx.core.model

/** 供慢订阅者重放的有界身份变更记录，避免 StateFlow 合并更新时丢失批量重命名映射。 */
data class DirectoryRevisionChange(
    /** 变化发布后的内容版本。 */
    val revision: Long,
    /** 已提交的变化。 */
    val change: DirectoryChange,
)

/** 已确认的目录内容变化，选择和滚动由各订阅面板独立处理。 */
data class DirectoryChange(
    /** 已确认移除的原条目。 */
    val removed: List<VFile> = emptyList(),
    /** 已确认新增或替换的实际条目。 */
    val upserted: List<VFile> = emptyList(),
    /** 重命名时原 ID 与新 ID 的对应关系。 */
    val identityChanges: Map<String, String> = emptyMap(),
    /** 尚不能准确描述变化、必须后台校验的目录。 */
    val invalidatedDirectories: Set<String> = emptySet(),
)
