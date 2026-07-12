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

internal fun PaneTabState.nextCreateName(baseName: String): String {
    val existingNames = allEntries.mapTo(mutableSetOf()) { it.name }
    var candidate = baseName
    var suffixIndex = 1
    while (candidate in existingNames) {
        candidate = "$baseName ($suffixIndex)"
        suffixIndex += 1
    }
    return candidate
}
