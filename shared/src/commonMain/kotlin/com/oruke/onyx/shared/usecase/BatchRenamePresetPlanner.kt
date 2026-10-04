package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.BatchRenamePreset
import com.oruke.onyx.core.model.VFile

/** 单条批量重命名预览。 */
data class BatchRenamePreviewItem(
    /** 原始文件条目。 */
    val entry: VFile,
    /** 预览生成的新名称。 */
    val newName: String,
    /** 是否会发生实际重命名。 */
    val changed: Boolean,
)

/** 批量重命名预览结果。 */
data class BatchRenamePreview(
    /** 使用的保存预设。 */
    val preset: BatchRenamePreset,
    /** 按输入顺序生成的名称明细。 */
    val items: List<BatchRenamePreviewItem>,
    /** 用于撤销改名的反向映射。 */
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
            val draftPreview = preset.draft?.let { BatchRenameDraftPlanner.preview(entries, it).getOrThrow() }
            val items = entries.mapIndexed { index, entry ->
                val newName =
                    draftPreview?.get(index)?.second ?: BatchRenameTemplateRenderer.render(entry, index, preset)
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

}
