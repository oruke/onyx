package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.BatchRenameCaseMode
import com.oruke.onyx.core.model.BatchRenameDraft
import com.oruke.onyx.core.model.BatchRenameEditMode
import com.oruke.onyx.core.model.BatchRenamePreset
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey

/** 按统一模板参数渲染名称，供预设规划器与可编辑草稿共用。 */
internal object BatchRenameTemplateRenderer {
    /**
     * 对原始名称应用查找、模板变量和完整名称大小写转换。
     * @param entry 原始文件或目录快照。
     * @param index 本批次中的零起始序号。
     * @param preset 已校验的模板参数。
     * @return 模板产生的目标名称。
     */
    fun render(entry: VFile, index: Int, preset: BatchRenamePreset): String {
        val dot = entry.name.lastIndexOf('.')
        val hasExtension = entry.kind == VFileKind.FILE && dot > 0 && dot < entry.name.lastIndex
        val baseName = if (hasExtension) entry.name.substring(0, dot) else entry.name
        val extension = if (hasExtension) entry.name.substring(dot + 1) else ""
        val sequence = (preset.startIndex.toLong() + index).toString().padStart(preset.padding, '0')
        val replaced = if (preset.pattern.isEmpty()) preset.replacement else {
            BatchRenameNameTransformations.applyFindReplace(
                entry.name, preset.pattern, preset.replacement, preset.useRegex,
            ).getOrElse { throw LocalizedException(I18nMessage(MessageKey.MSG_BATCH_RENAME_INVALID_PATTERN), it) }
        }
        val rendered = replaced.replace("{name}", baseName).replace("{ext}", extension)
            .replace("{index}", sequence).replace("{size}", entry.sizeBytes?.toString().orEmpty())
            .replace("{modified}", entry.modifiedAtEpochMillis?.toString().orEmpty())
        return when (preset.caseMode) {
            BatchRenameCaseMode.KEEP -> rendered
            BatchRenameCaseMode.LOWER -> rendered.lowercase()
            BatchRenameCaseMode.UPPER -> rendered.uppercase()
            BatchRenameCaseMode.TITLE -> rendered.split(' ', '-', '_').joinToString(" ") { part ->
                part.lowercase().replaceFirstChar { it.uppercase() }
            }
        }
    }
}

/**
 * 将持久化预设恢复为完整可编辑草稿，保留模板序号和大小写参数。
 * @return 应用表单草稿，或与模板字段等价的模板模式草稿。
 */
fun BatchRenamePreset.toEditableDraft(): BatchRenameDraft = draft ?: BatchRenameDraft(
    mode = BatchRenameEditMode.TEMPLATE,
    findText = pattern,
    replaceText = replacement,
    useRegex = useRegex,
    counterStart = startIndex.toString(),
    counterDigits = padding.toString(),
    templateCaseMode = caseMode,
)
