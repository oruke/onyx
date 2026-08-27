package com.oruke.onyx.shared.usecase

/**
 * 批量重命名的名称变换规则。
 *
 * 统一普通文本与正则表达式的查找替换，避免预设与交互式窗口使用不同实现。
 */
object BatchRenameNameTransformations {
    /**
     * 按普通文本或正则表达式替换文件名中的匹配内容。
     *
     * 空查找文本表示不修改名称；正则表达式非法时通过 [Result] 返回失败，调用方可展示校验错误并阻止提交。
     *
     * @param name 原始完整文件名。
     * @param findText 待查找的普通文本或正则表达式。
     * @param replaceText 替换后的文本。
     * @param useRegex 是否将 [findText] 作为正则表达式处理。
     * @return 替换后的完整文件名或正则表达式编译失败。
     */
    fun applyFindReplace(
        name: String,
        findText: String,
        replaceText: String,
        useRegex: Boolean,
    ): Result<String> {
        return runCatching {
            when {
                findText.isEmpty() -> name
                useRegex -> Regex(findText).replace(name, replaceText)
                else -> name.replace(findText, replaceText)
            }
        }
    }
}
