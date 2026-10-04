package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

/** 批量重命名编辑模式。 */
@Serializable
enum class BatchRenameEditMode { FIND_REPLACE, PREFIX_SUFFIX, COUNTER, CASE_CHANGE, TEMPLATE }

/** 仅转换基础文件名的大小写模式。 */
@Serializable
enum class BatchRenameEditCaseMode { UPPERCASE, LOWERCASE, TITLE_CASE }

/** 组件持有的批量重命名草稿，可原样保存为预设。 */
@Serializable
data class BatchRenameDraft(
    /** 当前编辑模式。 */
    val mode: BatchRenameEditMode = BatchRenameEditMode.FIND_REPLACE,
    /** 查找文本或正则。 */
    val findText: String = "",
    /** 替换文本。 */
    val replaceText: String = "",
    /** 是否使用正则捕获组。 */
    val useRegex: Boolean = false,
    /** 基础名称前缀。 */
    val prefix: String = "",
    /** 基础名称后缀。 */
    val suffix: String = "",
    /** 序号前的文本模板。 */
    val counterTemplate: String = "IMG_",
    /** 用户输入的起始序号，保留未完成的输入以便校验。 */
    val counterStart: String = "1",
    /** 序号补零宽度。 */
    val counterDigits: String = "3",
    /** 基础名称大小写规则。 */
    val caseMode: BatchRenameEditCaseMode = BatchRenameEditCaseMode.LOWERCASE,
    /** 模板模式对完整输出名称应用的大小写策略。 */
    val templateCaseMode: BatchRenameCaseMode = BatchRenameCaseMode.KEEP,
)
