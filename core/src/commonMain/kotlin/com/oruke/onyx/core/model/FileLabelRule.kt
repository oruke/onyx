package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

/**
 * 文件标签规则。
 *
 * @property id 规则唯一标识。
 * @property name 规则显示名称。
 * @property matcher 匹配条件。
 * @property textColorArgb 文件名颜色，空值表示不覆盖。
 * @property backgroundColorArgb 行背景色，空值表示不覆盖。
 * @property pinned 匹配后是否建议固定或优先显示。
 */
@Serializable
data class FileLabelRule(
    val id: String,
    val name: String,
    val matcher: FileLabelMatcher,
    val textColorArgb: Long? = null,
    val backgroundColorArgb: Long? = null,
    val pinned: Boolean = false,
)

/**
 * 文件标签匹配条件。
 *
 * @property nameContains 文件名包含文本，空值表示不限制。
 * @property extension 文件扩展名，允许带点或不带点，空值表示不限制。
 * @property kind 文件类型，空值表示文件和目录都可匹配。
 * @property minSizeBytes 最小字节数，空值表示不限制。
 * @property maxSizeBytes 最大字节数，空值表示不限制。
 */
@Serializable
data class FileLabelMatcher(
    val nameContains: String? = null,
    val extension: String? = null,
    val kind: VFileKind? = null,
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
)

/**
 * 文件标签匹配结果。
 *
 * @property entry 被匹配的文件条目。
 * @property rules 命中的标签规则。
 */
data class FileLabelMatch(
    val entry: VFile,
    val rules: List<FileLabelRule>,
)
