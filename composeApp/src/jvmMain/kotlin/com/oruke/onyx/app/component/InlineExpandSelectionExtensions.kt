package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind

/**
 * 解析面板当前有效选中条目：
 * - 当未展开目录或已展开目录未选中任何后代时，保留该目录本身（整目录操作）；
 * - 当已展开目录与全部直接及已展开后代子项同时选中（如全选）且无筛选词时，收敛为该目录本身；
 * - 当已展开目录仅与部分后代子项同时选中（如从父目录 Shift 连选前几项）时，裁剪父目录并仅保留实际选中的子项。
 *
 * @return 去重与父子裁剪后的有效选中条目列表。
 */
internal fun PaneState.effectiveSelectedEntries(): List<VFile> {
    if (selectedEntryIds.isEmpty()) return emptyList()
    val topEntries = (entriesState as? PaneEntriesState.Ready)?.entries.orEmpty()
    if (inlineExpandedLocations.isEmpty()) {
        return topEntries.filter { entry -> entry.id in selectedEntryIds }
    }
    return collectEffectiveSelectedEntries(topEntries)
}

/**
 * 读取面板当前可见的全部条目（含顶层与内联展开子目录）。
 *
 * @return 当前可见条目列表。
 */
internal fun PaneState.visibleEntries(): List<VFile> {
    val topEntries = (entriesState as? PaneEntriesState.Ready)?.entries.orEmpty()
    return if (inlineExpandedLocations.isEmpty()) {
        topEntries
    } else {
        collectAllVisibleEntries(topEntries, this)
    }
}

private fun PaneState.collectEffectiveSelectedEntries(entries: List<VFile>): List<VFile> {
    return buildList {
        entries.forEach { entry ->
            val expandedChildren = loadedExpandedChildrenOf(entry)
            val isEntrySelected = entry.id in selectedEntryIds
            when {
                expandedChildren.isNullOrEmpty() -> if (isEntrySelected) add(entry)
                !isEntrySelected -> addAll(collectEffectiveSelectedEntries(expandedChildren))
                else -> {
                    val selectedDescendants = collectEffectiveSelectedEntries(expandedChildren)
                    val keepWholeDirectory = selectedDescendants.isEmpty() ||
                        (filterQuery.isBlank() && isExpandedSubtreeCompletelySelected(expandedChildren))
                    if (keepWholeDirectory) {
                        add(entry)
                    } else {
                        addAll(selectedDescendants)
                    }
                }
            }
        }
    }
}

private fun PaneState.loadedExpandedChildrenOf(entry: VFile): List<VFile>? {
    if (entry.kind != VFileKind.DIRECTORY || entry.location !in inlineExpandedLocations) {
        return null
    }
    return inlineExpandedEntries[entry.location]?.entries
}

private fun PaneState.isExpandedSubtreeCompletelySelected(children: List<VFile>): Boolean {
    if (children.isEmpty()) return false
    return children.all { child ->
        if (child.id !in selectedEntryIds) {
            false
        } else if (child.kind == VFileKind.DIRECTORY && child.location in inlineExpandedLocations) {
            val grandChildren = inlineExpandedEntries[child.location]?.entries ?: return@all false
            grandChildren.isEmpty() || isExpandedSubtreeCompletelySelected(grandChildren)
        } else {
            true
        }
    }
}

private fun collectAllVisibleEntries(entries: List<VFile>, paneState: PaneState): List<VFile> {
    return buildList {
        entries.forEach { entry ->
            add(entry)
            if (entry.location in paneState.inlineExpandedLocations) {
                paneState.inlineExpandedEntries[entry.location]?.entries?.let { children ->
                    addAll(collectAllVisibleEntries(children, paneState))
                }
            }
        }
    }
}
