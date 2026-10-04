package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

/**
 * 文件标签规则。
 */
@Serializable
data class FileLabelRule(
    /** 规则唯一标识。 */
    val id: String,
    /** 用户配置的显示名称。 */
    val name: String,
    /** 文件匹配条件。 */
    val matcher: FileLabelMatcher,
    /** 文件名颜色，空值表示不覆盖。 */
    val textColorArgb: Long? = null,
    /** 行背景色，空值表示不覆盖。 */
    val backgroundColorArgb: Long? = null,
    /** 匹配后建议固定或优先显示的模型标记。 */
    val pinned: Boolean = false,
)

/**
 * 文件标签匹配条件。
 */
@Serializable
data class FileLabelMatcher(
    /** 文件名包含文本，空值表示不限制。 */
    val nameContains: String? = null,
    /** 文件扩展名，允许带点或不带点，空值表示不限制。 */
    val extension: String? = null,
    /** 文件类型，空值表示文件和目录都可匹配。 */
    val kind: VFileKind? = null,
    /** 最小字节数，空值表示不限制。 */
    val minSizeBytes: Long? = null,
    /** 最大字节数，空值表示不限制。 */
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
