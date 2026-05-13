package com.oruke.onyx.app.component

import com.oruke.onyx.app.component.delegate.SelectionReducer
import com.oruke.onyx.core.model.VFile

internal fun PaneTabState.resolveSelectionFocusEntry(entries: List<VFile>): VFile? {
    val focusId = SelectionReducer.resolveSelectionFocusId(
        entries = entries,
        focusId = selectionFocusId,
        anchorId = selectionAnchorId,
        selectedEntryIds = selectedEntryIds,
    ) ?: return null
    return entries.firstOrNull { it.id == focusId }
}

internal fun PaneTabState.selectEntryState(
    entries: List<VFile>,
    entryId: String,
    additive: Boolean,
    range: Boolean,
): PaneTabState {
    if (entries.none { it.id == entryId }) return this

    val currentAnchor = SelectionReducer.validEntryId(selectionAnchorId, entries)
    val currentFocus = SelectionReducer.validEntryId(selectionFocusId, entries)
    val nextSelection = when {
        range -> {
            val anchorId = currentAnchor ?: currentFocus ?: selectedEntryIds.firstOrNull() ?: entryId
            SelectionReducer.buildRangeSelection(
                entries = entries,
                anchorId = anchorId,
                targetId = entryId,
                additive = additive,
                existingSelection = selectedEntryIds,
            )
        }

        additive -> {
            if (selectedEntryIds.contains(entryId)) {
                selectedEntryIds - entryId
            } else {
                selectedEntryIds + entryId
            }
        }

        else -> setOf(entryId)
    }

    val finalSelection = nextSelection.ifEmpty { setOf(entryId) }
    return withTabState { current ->
        current.copy(
            selectedEntryIds = finalSelection,
            selectionAnchorId = if (range) {
                currentAnchor ?: currentFocus ?: entryId
            } else {
                entryId
            },
            selectionFocusId = entryId,
        )
    }
}

internal fun PaneTabState.withSelectedEntryIds(entryIds: Set<String>): PaneTabState {
    return withTabState { current ->
        current.copy(selectedEntryIds = entryIds)
    }
}

internal fun PaneTabState.moveSelectionState(
    entries: List<VFile>,
    offset: Int,
    extendSelection: Boolean,
): PaneTabState {
    if (entries.isEmpty()) return this

    val currentFocusId = SelectionReducer.resolveSelectionFocusId(
        entries = entries,
        focusId = selectionFocusId,
        anchorId = selectionAnchorId,
        selectedEntryIds = selectedEntryIds,
    )
    val currentIndex = entries.indexOfFirst { it.id == currentFocusId }
    val fallbackIndex = if (offset >= 0) 0 else entries.lastIndex
    val baseIndex = if (currentIndex == -1) fallbackIndex else currentIndex
    val nextIndex = (baseIndex + offset).coerceIn(0, entries.lastIndex)
    val nextEntryId = entries[nextIndex].id

    return if (extendSelection) {
        val anchorId = SelectionReducer.validEntryId(selectionAnchorId, entries)
            ?: currentFocusId
            ?: nextEntryId
        withTabState { current ->
            current.copy(
                selectedEntryIds = SelectionReducer.buildRangeSelection(
                    entries = entries,
                    anchorId = anchorId,
                    targetId = nextEntryId,
                    additive = false,
                    existingSelection = emptySet(),
                ),
                selectionAnchorId = anchorId,
                selectionFocusId = nextEntryId,
            )
        }
    } else {
        withTabState { current ->
            current.copy(
                selectedEntryIds = setOf(nextEntryId),
                selectionAnchorId = nextEntryId,
                selectionFocusId = nextEntryId,
            )
        }
    }
}

internal fun PaneTabState.selectAllEntriesState(entries: List<VFile>): PaneTabState {
    if (entries.isEmpty()) return this
    return withTabState { current ->
        current.copy(
            selectedEntryIds = entries.mapTo(linkedSetOf()) { it.id },
            selectionAnchorId = entries.first().id,
            selectionFocusId = entries.first().id,
        )
    }
}

internal fun PaneTabState.clearSelectionState(): PaneTabState {
    return withTabState { current ->
        current.copy(
            selectedEntryIds = emptySet(),
            selectionAnchorId = null,
            selectionFocusId = null,
        )
    }
}
