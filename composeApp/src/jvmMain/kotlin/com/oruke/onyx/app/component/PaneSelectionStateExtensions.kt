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
    val nextIndex = if (currentIndex == -1) {
        if (offset >= 0) 0 else entries.lastIndex
    } else {
        (currentIndex + offset).coerceIn(0, entries.lastIndex)
    }
    val nextEntryId = entries[nextIndex].id

    return applyKeyboardSelectionTarget(entries, currentFocusId, nextEntryId, extendSelection)
}

/**
 * 将键盘选择直接移动到首项或末项，并触发视口滚动。
 *
 * @param entries 当前可见条目列表。
 * @param toEnd 为 `true` 时选择末项，否则选择首项。
 * @param extendSelection 是否保持锚点扩展选择。
 * @return 更新选择与待滚动目标后的标签状态。
 */
internal fun PaneTabState.moveSelectionToBoundaryState(
    entries: List<VFile>,
    toEnd: Boolean,
    extendSelection: Boolean,
): PaneTabState {
    if (entries.isEmpty()) return this
    val currentFocusId = SelectionReducer.resolveSelectionFocusId(
        entries = entries,
        focusId = selectionFocusId,
        anchorId = selectionAnchorId,
        selectedEntryIds = selectedEntryIds,
    )
    val targetEntryId = if (toEnd) entries.last().id else entries.first().id
    return applyKeyboardSelectionTarget(entries, currentFocusId, targetEntryId, extendSelection)
}

/**
 * 应用键盘选择目标并设置 [TabState.pendingScrollToEntryId] 驱动视口自动滚动可见。
 */
private fun PaneTabState.applyKeyboardSelectionTarget(
    entries: List<VFile>,
    currentFocusId: String?,
    nextEntryId: String,
    extendSelection: Boolean,
): PaneTabState {
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
                pendingScrollToEntryId = nextEntryId,
            )
        }
    } else {
        withTabState { current ->
            current.copy(
                selectedEntryIds = setOf(nextEntryId),
                selectionAnchorId = nextEntryId,
                selectionFocusId = nextEntryId,
                pendingScrollToEntryId = nextEntryId,
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

/**
 * 读取包含目录树展开子项的可见条目。
 *
 * @return 顶层条目与已展开子项的合并列表。
 */
internal fun PaneState.visibleEntriesIncludingExpanded(): List<VFile> {
    val entries = (entriesState as? PaneEntriesState.Ready)?.entries ?: return emptyList()
    if (inlineExpandedLocations.isEmpty()) return entries
    return SelectionReducer.collectVisibleEntries(
        entries = entries,
        expandedLocations = inlineExpandedLocations,
        expandedEntries = inlineExpandedEntries,
    )
}

/**
 * 在包含目录树展开子项的可见条目中按 ID 查找条目。
 *
 * @param entryId 条目 ID。
 * @return 匹配条目，ID 为空或未命中时返回 null。
 */
internal fun PaneState.findVisibleEntry(entryId: String?): VFile? {
    return entryId?.let { id -> visibleEntriesIncludingExpanded().firstOrNull { it.id == id } }
}
