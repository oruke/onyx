package com.oruke.onyx.shared.filesystem

import com.oruke.onyx.core.model.DirectoryChange
import com.oruke.onyx.core.model.VFile

/** 单条目录元数据的固定估算成本，不含文件内容和缩略图。 */
private const val ENTRY_FIXED_BYTES = 256L
/** JVM UTF-16 字符的估算字节数。 */
private const val CHARACTER_BYTES = 2L

/** 已完成读取的完整快照和在后台计算的内存预算。 */
internal data class DirectoryContentSnapshot(
    /** Provider 返回的完整目录条目。 */
    val entries: List<VFile>,
    /** 元数据的估算内存占用。 */
    val estimatedBytes: Long,
)

/** 已确认变化产生的不可变快照与内存增量，不重复建立整目录身份哈希表。 */
internal data class DirectoryContentMutation(
    /** 合并后的完整目录条目。 */
    val entries: List<VFile>,
    /** 相对上一个快照的估算内存变化。 */
    val estimatedByteDelta: Long,
)

/**
 * 单次扫描复制不可变快照，只比较命令或观察明确涉及的条目。
 * @param parent 当前目录位置。
 * @param change 已确认的真实文件变化。
 * @return 有实际内容或身份变化时返回新快照；重复通知返回 null。
 */
internal fun List<VFile>.applyDirectoryMutation(parent: String, change: DirectoryChange): DirectoryContentMutation? {
    val removed = change.removed.filter { it.parentLocation == parent }.mapTo(hashSetOf()) { it.id }
    val additions = change.upserted.filter { it.parentLocation == parent }.associateByTo(linkedMapOf()) { it.id }
    val next = ArrayList<VFile>(size + additions.size)
    var changed = change.identityChanges.isNotEmpty()
    var bytes = 0L
    for (entry in this) {
        val replacement = additions.remove(entry.id)
        when {
            replacement != null -> {
                next.add(replacement)
                if (replacement != entry) {
                    changed = true
                    bytes += replacement.estimatedMetadataBytes() - entry.estimatedMetadataBytes()
                }
            }
            entry.id in removed -> {
                changed = true
                bytes -= entry.estimatedMetadataBytes()
            }
            else -> next.add(entry)
        }
    }
    if (additions.isNotEmpty()) {
        changed = true
        next.addAll(additions.values)
        bytes += additions.values.sumOf { it.estimatedMetadataBytes() }
    }
    return if (changed) DirectoryContentMutation(next, bytes) else null
}

/**
 * 估算单条元数据成本，作为缓存预算而非 JVM 精确堆统计。
 * @return 固定对象成本与各元数据字符串的估算字节数。
 */
internal fun VFile.estimatedMetadataBytes(): Long = ENTRY_FIXED_BYTES + CHARACTER_BYTES *
    (id.length + location.length + (parentLocation?.length ?: 0) + name.length)
