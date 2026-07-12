package com.oruke.onyx.shared.usecase

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
    val normalizedName = entry.name.lowercase()
    return matchesKind(entry) &&
        matchesName(normalizedName) &&
        matchesExtension(entry, normalizedName) &&
        matchesSize(entry.sizeBytes)
}

/**
 * 检查条目类型条件。
 *
 * @param entry 待匹配条目。
 * @return 类型匹配或未配置类型时返回 true。
 */
private fun FileLabelMatcher.matchesKind(entry: VFile): Boolean = kind == null || entry.kind == kind

/**
 * 检查文件名包含条件。
 *
 * @param normalizedName 小写文件名。
 * @return 名称匹配或未配置名称时返回 true。
 */
private fun FileLabelMatcher.matchesName(normalizedName: String): Boolean {
    val expected = nameContains?.trim()?.lowercase()
    return expected.isNullOrBlank() || expected in normalizedName
}

/**
 * 检查扩展名条件，并确保扩展名只匹配文件。
 *
 * @param entry 待匹配条目。
 * @param normalizedName 小写文件名。
 * @return 扩展名匹配或未配置扩展名时返回 true。
 */
private fun FileLabelMatcher.matchesExtension(entry: VFile, normalizedName: String): Boolean {
    val expected = extension?.trim()
    return expected.isNullOrBlank() ||
        entry.kind == VFileKind.FILE && normalizedName.endsWith(".${expected.normalizeExtension()}")
}

/**
 * 检查文件大小范围。
 *
 * @param size 文件大小；未知时为空。
 * @return 大小条件全部满足时返回 true。
 */
private fun FileLabelMatcher.matchesSize(size: Long?): Boolean {
    val minimum = minSizeBytes
    val maximum = maxSizeBytes
    return (minimum == null || size != null && size >= minimum) &&
        (maximum == null || size != null && size <= maximum)
}

/**
 * 统一扩展名匹配写法。
 *
 * @return 去掉前导点并转为小写的扩展名。
 */
private fun String.normalizeExtension(): String {
    return trim().trimStart('.').lowercase()
}
