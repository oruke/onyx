package com.oruke.onyx.shared.filesystem

import com.oruke.onyx.core.model.DirectoryChange
import com.oruke.onyx.core.model.DirectoryRevisionChange

/** 身份日志达到此段数后合并早期批次，避免 StateFlow 合并时丢失大批量改名。 */
private const val MAX_JOURNAL_SEGMENTS = 256
/** 每次合并一半日志，避免每条后续变化都复制累计映射。 */
private const val JOURNAL_COMPACTION_SEGMENTS = 128
/** 最多保留的历史路径身份，限制长时间反复改名同一条目的内存占用。 */
private const val MAX_JOURNAL_IDENTITIES = 100_000

/**
 * 增加身份变化日志，合并早期批次而非按事件条数丢弃映射。
 * @param revision 本次确认内容版本。
 * @param identities 本次同时发生的旧新身份映射。
 * @return 段数与历史身份数量有界的日志。
 */
internal fun List<DirectoryRevisionChange>.appendIdentityChanges(
    revision: Long,
    identities: Map<String, String>,
): List<DirectoryRevisionChange> {
    if (identities.isEmpty()) return this
    val next = this + DirectoryRevisionChange(revision, DirectoryChange(identityChanges = identities))
    return if (next.size <= MAX_JOURNAL_SEGMENTS) next else {
        val prefix = next.take(JOURNAL_COMPACTION_SEGMENTS)
        val merged = prefix.combinedIdentityChanges().entries.toList().takeLast(MAX_JOURNAL_IDENTITIES)
            .associateTo(linkedMapOf()) { it.key to it.value }
        listOf(DirectoryRevisionChange(prefix.last().revision, DirectoryChange(identityChanges = merged))) +
            next.drop(JOURNAL_COMPACTION_SEGMENTS)
    }
}

/**
 * 按时间合成身份链，反向索引只更新真实受影响的旧身份，避免大批量改名的平方级扫描。
 * @return 日志范围内每个旧身份的最终目标；同一段中的映射按同时变化处理。
 */
fun List<DirectoryRevisionChange>.combinedIdentityChanges(): Map<String, String> {
    val combined = linkedMapOf<String, String>()
    val originsByTarget = mutableMapOf<String, MutableSet<String>>()
    for (item in this) {
        val changes = item.change.identityChanges
        val affected = changes.mapValues { (source, _) -> originsByTarget[source]?.toSet().orEmpty() + source }
        // 先移除全部旧目标索引，再写新目标，保证同一段不会被错误地串行级联。
        affected.values.flatten().forEach { origin ->
            combined[origin]?.let { oldTarget -> originsByTarget[oldTarget]?.remove(origin) }
        }
        affected.forEach { (source, origins) ->
            val target = changes.getValue(source)
            origins.forEach { origin -> combined[origin] = target }
            originsByTarget.getOrPut(target) { linkedSetOf() }.addAll(origins)
        }
    }
    return combined
}

/**
 * 估算身份日志的路径字符串与映射成本，防止日志绕过目录缓存预算。
 * @return 有界日志的保守估算字节数。
 */
internal fun List<DirectoryRevisionChange>.estimatedIdentityBytes(): Long = sumOf { item ->
    item.change.identityChanges.entries.sumOf { (source, target) ->
        IDENTITY_FIXED_BYTES + IDENTITY_CHARACTER_BYTES * (source.length + target.length)
    }
}

/** 每条历史映射的固定对象成本。 */
private const val IDENTITY_FIXED_BYTES = 128L
/** 路径字符串使用 UTF-16 估算。 */
private const val IDENTITY_CHARACTER_BYTES = 2L
