package com.oruke.onyx.app.usecase

import com.oruke.onyx.core.model.FileLabelMatch
import com.oruke.onyx.core.model.FileLabelMatcher
import com.oruke.onyx.core.model.FileLabelRule
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind

/**
 * 文件标签规则匹配用例。
 */
class FileLabelRuleUseCase {
    /**
     * 计算文件条目命中的标签规则。
     *
     * @param entries 待匹配的文件条目。
     * @param rules 用户配置的标签规则。
     * @return 每个命中条目的标签匹配结果。
     */
    fun match(
        entries: List<VFile>,
        rules: List<FileLabelRule>,
    ): List<FileLabelMatch> {
        if (rules.isEmpty() || entries.isEmpty()) {
            return emptyList()
        }
        return entries.mapNotNull { entry ->
            val matchedRules = rules.filter { rule -> rule.matcher.matches(entry) }
            matchedRules.takeIf { it.isNotEmpty() }?.let { rulesForEntry ->
                FileLabelMatch(entry = entry, rules = rulesForEntry)
            }
        }
    }
}

/**
 * 判断标签匹配条件是否命中文件条目。
 *
 * @param entry 待匹配的文件条目。
 * @return `true` 表示条目满足条件。
 */
fun FileLabelMatcher.matches(entry: VFile): Boolean {
    if (kind != null && entry.kind != kind) {
        return false
    }
    val normalizedName = entry.name.lowercase()
    val normalizedNameContains = nameContains?.trim()?.lowercase()
    val normalizedExtensionInput = extension?.trim()
    val minSize = minSizeBytes
    val maxSize = maxSizeBytes
    if (!normalizedNameContains.isNullOrBlank() && normalizedNameContains !in normalizedName) {
        return false
    }
    if (!normalizedExtensionInput.isNullOrBlank() && entry.kind != VFileKind.FILE) {
        return false
    }
    if (!normalizedExtensionInput.isNullOrBlank()) {
        val normalizedExtension = normalizedExtensionInput.normalizeExtension()
        if (!normalizedName.endsWith(".$normalizedExtension")) {
            return false
        }
    }
    val size = entry.sizeBytes
    if (minSize != null && (size == null || size < minSize)) {
        return false
    }
    if (maxSize != null && (size == null || size > maxSize)) {
        return false
    }
    return true
}

/**
 * 统一扩展名匹配写法。
 *
 * @return 去掉前导点并转为小写的扩展名。
 */
private fun String.normalizeExtension(): String {
    return trim().trimStart('.').lowercase()
}
