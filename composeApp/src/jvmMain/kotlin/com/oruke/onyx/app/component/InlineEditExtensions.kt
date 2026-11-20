package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.PaneInlineEditMode
import com.oruke.onyx.core.model.PaneInlineEditState
import com.oruke.onyx.core.model.VFile

internal data class InlineEditConfirmResult(
    val tab: PaneTabState,
    val operation: InlineEditOperation?,
)

internal sealed interface InlineEditOperation {
    data class Rename(
        val entry: VFile,
        val targetName: String,
    ) : InlineEditOperation

    data class CreateFile(
        val parentLocation: String,
        val name: String,
    ) : InlineEditOperation

    data class CreateDirectory(
        val parentLocation: String,
        val name: String,
    ) : InlineEditOperation
}

internal fun PaneTabState.beginRenameInlineEdit(targetEntry: VFile): PaneTabState {
    if (inlineEditState != null) return this
    return withTabState { current ->
        current.copy(
            inlineEditState = PaneInlineEditState(
                mode = PaneInlineEditMode.RENAME,
                targetEntryId = targetEntry.id,
                draftName = targetEntry.name,
            ),
        )
    }
}

internal fun PaneTabState.beginCreateFileInlineEdit(draftName: String): PaneTabState {
    if (inlineEditState != null) return this
    return withTabState { current ->
        current.copy(
            inlineEditState = PaneInlineEditState(
                mode = PaneInlineEditMode.CREATE_FILE,
                draftName = draftName,
            ),
        )
    }
}

internal fun PaneTabState.beginCreateDirectoryInlineEdit(draftName: String): PaneTabState {
    if (inlineEditState != null) return this
    return withTabState { current ->
        current.copy(
            inlineEditState = PaneInlineEditState(
                mode = PaneInlineEditMode.CREATE_DIRECTORY,
                draftName = draftName,
            ),
        )
    }
}

internal fun PaneTabState.withInlineEditDraft(draft: String): PaneTabState {
    val currentInlineEdit = inlineEditState ?: return this
    if (draft == currentInlineEdit.draftName) return this
    return withTabState { current ->
        current.copy(
            inlineEditState = currentInlineEdit.copy(draftName = draft),
        )
    }
}

internal fun PaneTabState.clearInlineEditState(): PaneTabState {
    if (inlineEditState == null) return this
    return withTabState { current ->
        current.copy(inlineEditState = null)
    }
}

internal fun PaneTabState.confirmInlineEditState(
    currentEntries: List<VFile>,
): InlineEditConfirmResult {
    val currentInlineEdit = inlineEditState
    val normalizedDraft = currentInlineEdit?.draftName?.trim().orEmpty()
    return when {
        currentInlineEdit == null -> InlineEditConfirmResult(this, null)
        entriesState is PaneEntriesState.Failure || normalizedDraft.isBlank() ->
            InlineEditConfirmResult(clearInlineEditState(), null)

        else -> confirmValidInlineEdit(currentInlineEdit, normalizedDraft, currentEntries)
    }
}

/**
 * 将已经通过基础校验的内联编辑状态转换为文件操作。
 *
 * @param inlineEdit 当前内联编辑状态。
 * @param normalizedDraft 去除首尾空白后的目标名称。
 * @param currentEntries 当前面板条目，用于定位重命名目标。
 * @return 可以立即执行的操作，或仅清理编辑状态的结果。
 */
private fun PaneTabState.confirmValidInlineEdit(
    inlineEdit: PaneInlineEditState,
    normalizedDraft: String,
    currentEntries: List<VFile>,
): InlineEditConfirmResult {
    return when (inlineEdit.mode) {
        PaneInlineEditMode.RENAME -> {
            val targetEntry = currentEntries
                .firstOrNull { it.id == inlineEdit.targetEntryId }
            if (targetEntry != null && targetEntry.name != normalizedDraft) {
                InlineEditConfirmResult(
                    tab = this,
                    operation = InlineEditOperation.Rename(
                        entry = targetEntry,
                        targetName = normalizedDraft,
                    ),
                )
            } else {
                InlineEditConfirmResult(clearInlineEditState(), null)
            }
        }

        PaneInlineEditMode.CREATE_FILE -> {
            InlineEditConfirmResult(
                tab = this,
                operation = InlineEditOperation.CreateFile(
                    parentLocation = location,
                    name = normalizedDraft,
                ),
            )
        }

        PaneInlineEditMode.CREATE_DIRECTORY -> {
            InlineEditConfirmResult(
                tab = this,
                operation = InlineEditOperation.CreateDirectory(
                    parentLocation = location,
                    name = normalizedDraft,
                ),
            )
        }
    }
}

/**
 * 为内联新建文件或目录生成不重名的默认名称。
 *
 * 新建文件（`preserveExtension = true`）时将冲突序号插入扩展名之前（如 `New File (1).txt`），
 * 避免落到扩展名后变成 `.txt (1)`；同时遵循当前位置的大小写敏感语义防止 Windows/SMB 撞名。
 *
 * @param baseName 建议的基础文件名或目录名。
 * @param preserveExtension 是否在扩展名前插入编号；新建文件传 `true`，新建目录传 `false`。
 * @return 当前目录内可用的不冲突名称。
 */
internal fun PaneTabState.nextCreateName(
    baseName: String,
    preserveExtension: Boolean = false,
): String {
    val caseInsensitive = isCaseInsensitiveVfsLocation(location)
    val existingNames = allEntries.mapTo(mutableSetOf()) { entry ->
        if (caseInsensitive) entry.name.lowercase() else entry.name
    }
    val dotIndex = if (preserveExtension) baseName.lastIndexOf('.') else -1
    val hasExtension = dotIndex > 0 && dotIndex < baseName.lastIndex
    val stem = if (hasExtension) baseName.substring(0, dotIndex) else baseName
    val extension = if (hasExtension) baseName.substring(dotIndex) else ""

    var candidate = baseName
    var suffixIndex = 1
    while ((if (caseInsensitive) candidate.lowercase() else candidate) in existingNames) {
        candidate = "$stem ($suffixIndex)$extension"
        suffixIndex += 1
    }
    return candidate
}
