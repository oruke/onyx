package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.BatchRenameCaseMode
import com.oruke.onyx.core.model.BatchRenamePreset
import com.oruke.onyx.core.model.VFile

/**
 * 单条批量重命名预览。
 *
 * @property entry 原始文件条目。
 * @property newName 预览生成的新名称。
 * @property changed 是否会发生实际重命名。
 */
data class BatchRenamePreviewItem(
    val entry: VFile,
    val newName: String,
    val changed: Boolean,
)

/**
 * 批量重命名预览结果。
 *
 * @property preset 使用的预设。
 * @property items 预览明细。
 * @property undoMap 用于撤销预览的反向映射。
 */
data class BatchRenamePreview(
    val preset: BatchRenamePreset,
    val items: List<BatchRenamePreviewItem>,
    val undoMap: List<Pair<VFile, String>>,
)

/**
 * 批量重命名预设规划器。
 */
class BatchRenamePresetPlanner {
    /**
     * 基于预设生成批量重命名预览。
     *
     * @param entries 待重命名条目。
     * @param preset 批量重命名预设。
     * @return 预览结果。
     */
    fun preview(
        entries: List<VFile>,
        preset: BatchRenamePreset,
    ): Result<BatchRenamePreview> {
        return runCatching {
            val items = entries.mapIndexed { index, entry ->
                val newName = renderName(entry, index, preset)
                BatchRenamePreviewItem(
                    entry = entry,
                    newName = newName,
                    changed = newName != entry.name,
                )
            }
            BatchRenamePreview(
                preset = preset,
                items = items,
                undoMap = items.map { item -> item.entry to item.entry.name },
            )
        }
    }

    /**
     * 渲染单个条目的目标名称。
     *
     * @param entry 原始条目。
     * @param index 条目序号。
     * @param preset 批量重命名预设。
     * @return 目标名称。
     */
    private fun renderName(
        entry: VFile,
        index: Int,
        preset: BatchRenamePreset,
    ): String {
        val baseName = entry.name.substringBeforeLast('.', entry.name)
        val extension = entry.name.substringAfterLast('.', missingDelimiterValue = "")
        val sequence = (preset.startIndex + index).toString().padStart(preset.padding, '0')
        val replaced = if (preset.pattern.isEmpty()) {
            preset.replacement
        } else {
            BatchRenameNameTransformations.applyFindReplace(
                name = entry.name,
                findText = preset.pattern,
                replaceText = preset.replacement,
                useRegex = preset.useRegex,
            ).getOrThrow()
        }
        val rendered = replaced
            .replace("{name}", baseName)
            .replace("{ext}", extension)
            .replace("{index}", sequence)
            .replace("{size}", entry.sizeBytes?.toString().orEmpty())
            .replace("{modified}", entry.modifiedAtEpochMillis?.toString().orEmpty())
        return rendered.applyCaseMode(preset.caseMode)
    }
}

/**
 * 应用批量重命名大小写策略。
 *
 * @param mode 大小写策略。
 * @return 处理后的名称。
 */
private fun String.applyCaseMode(mode: BatchRenameCaseMode): String {
    return when (mode) {
        BatchRenameCaseMode.KEEP -> this
        BatchRenameCaseMode.LOWER -> lowercase()
        BatchRenameCaseMode.UPPER -> uppercase()
        BatchRenameCaseMode.TITLE -> split(' ', '-', '_').joinToString(" ") { part ->
            part.lowercase().replaceFirstChar { char -> char.uppercase() }
        }
    }
}
