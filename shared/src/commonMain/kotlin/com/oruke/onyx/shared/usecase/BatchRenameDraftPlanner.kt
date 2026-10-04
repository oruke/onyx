package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.BatchRenameDraft
import com.oruke.onyx.core.model.BatchRenameEditCaseMode
import com.oruke.onyx.core.model.BatchRenameEditMode
import com.oruke.onyx.core.model.BatchRenamePreset
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind

/** 将组件草稿转换为名称预览，统一目录、扩展名和数字参数校验。 */
object BatchRenameDraftPlanner {
    /**
     * 在任何文件变更前计算并校验整批名称。
     * @param entries 按界面顺序排列的原始条目。
     * @param draft 用户草稿。
     * @return 完整条目与新名称，非法参数返回本地化异常。
     */
    fun preview(entries: List<VFile>, draft: BatchRenameDraft): Result<List<Pair<VFile, String>>> = runCatching {
        val counter = draft.validatedCounterParameters(entries.size)
        entries.mapIndexed { index, entry ->
            val name = when (draft.mode) {
                BatchRenameEditMode.FIND_REPLACE -> BatchRenameNameTransformations.applyFindReplace(
                    entry.name, draft.findText, draft.replaceText, draft.useRegex,
                ).getOrElse { throw LocalizedException(I18nMessage(MessageKey.MSG_BATCH_RENAME_INVALID_PATTERN), it) }

                BatchRenameEditMode.PREFIX_SUFFIX -> entry.transformBaseName { "${draft.prefix}$it${draft.suffix}" }
                BatchRenameEditMode.COUNTER -> entry.transformBaseName {
                    val parameters = requireNotNull(counter)
                    draft.counterTemplate + (parameters.start + index).toString().padStart(parameters.digits, '0')
                }

                BatchRenameEditMode.CASE_CHANGE -> entry.transformBaseName { base ->
                    when (draft.caseMode) {
                        BatchRenameEditCaseMode.UPPERCASE -> base.uppercase()
                        BatchRenameEditCaseMode.LOWERCASE -> base.lowercase()
                        BatchRenameEditCaseMode.TITLE_CASE -> base.split(" ", "_", "-").joinToString(" ") { word ->
                            word.lowercase().replaceFirstChar { it.uppercase() }
                        }
                    }
                }

                BatchRenameEditMode.TEMPLATE -> BatchRenameTemplateRenderer.render(
                    entry, index,
                    BatchRenamePreset(
                        id = "", name = "", pattern = draft.findText, replacement = draft.replaceText,
                        useRegex = draft.useRegex, startIndex = requireNotNull(counter).start.toInt(),
                        padding = counter.digits, caseMode = draft.templateCaseMode,
                    ),
                )
            }
            entry to name
        }
    }
}

/**
 * 转换基础名称，文件保留真实扩展名，目录和隐藏文件不拆出伪扩展名。
 * @param transform 基础名称转换。
 * @return 转换后的完整名称。
 */
private fun VFile.transformBaseName(transform: (String) -> String): String {
    val dot = name.lastIndexOf('.')
    val hasExtension = kind == VFileKind.FILE && dot > 0 && dot < name.lastIndex
    return if (hasExtension) transform(name.substring(0, dot)) + name.substring(dot) else transform(name)
}
