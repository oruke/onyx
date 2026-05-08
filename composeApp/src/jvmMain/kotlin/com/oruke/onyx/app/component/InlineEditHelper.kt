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
    return copy(
        inlineEditState = PaneInlineEditState(
            mode = PaneInlineEditMode.RENAME,
            targetEntryId = targetEntry.id,
            draftName = targetEntry.name,
        ),
    )
}

internal fun PaneTabState.beginCreateFileInlineEdit(draftName: String): PaneTabState {
    if (inlineEditState != null) return this
    return copy(
        inlineEditState = PaneInlineEditState(
            mode = PaneInlineEditMode.CREATE_FILE,
            draftName = draftName,
        ),
    )
}

internal fun PaneTabState.beginCreateDirectoryInlineEdit(draftName: String): PaneTabState {
    if (inlineEditState != null) return this
    return copy(
        inlineEditState = PaneInlineEditState(
            mode = PaneInlineEditMode.CREATE_DIRECTORY,
            draftName = draftName,
        ),
    )
}

internal fun PaneTabState.withInlineEditDraft(draft: String): PaneTabState {
    val currentInlineEdit = inlineEditState ?: return this
    if (draft == currentInlineEdit.draftName) return this
    return copy(
        inlineEditState = currentInlineEdit.copy(draftName = draft),
    )
}

internal fun PaneTabState.clearInlineEditState(): PaneTabState {
    if (inlineEditState == null) return this
    return copy(inlineEditState = null)
}

internal fun PaneTabState.confirmInlineEditState(
    currentEntries: List<VFile>,
): InlineEditConfirmResult {
    val currentInlineEdit = inlineEditState ?: return InlineEditConfirmResult(this, null)
    if (entriesState is PaneEntriesState.Failure) {
        return InlineEditConfirmResult(clearInlineEditState(), null)
    }

    val normalizedDraft = currentInlineEdit.draftName.trim()
    if (normalizedDraft.isBlank()) {
        return InlineEditConfirmResult(clearInlineEditState(), null)
    }

    return when (currentInlineEdit.mode) {
        PaneInlineEditMode.RENAME -> {
            val targetEntry = currentEntries
                .firstOrNull { it.id == currentInlineEdit.targetEntryId }
                ?: return InlineEditConfirmResult(clearInlineEditState(), null)
            if (targetEntry.name == normalizedDraft) {
                InlineEditConfirmResult(clearInlineEditState(), null)
            } else {
                InlineEditConfirmResult(
                    tab = this,
                    operation = InlineEditOperation.Rename(
                        entry = targetEntry,
                        targetName = normalizedDraft,
                    ),
                )
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
