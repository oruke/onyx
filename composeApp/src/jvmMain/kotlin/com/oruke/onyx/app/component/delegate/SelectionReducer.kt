package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.InlineExpandedEntry
import com.oruke.onyx.core.model.PaneStatusInfo
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind

/**
 * 选择操作纯逻辑工具 — 无状态，所有方法均为纯函数。
 *
 * 从 DefaultPaneComponent 提取，负责：
 * - 范围选择计算
 * - 选择状态校正（reconcile）
 * - 状态栏信息构建
 * - 内联展开条目收集
 */
object SelectionReducer {

    /**
     * 构建范围选择：从 anchor 到 target 的所有条目 ID。
     *
     * @param additive 如果为 true，将范围合并到已有选择中；否则替换。
     */
    fun buildRangeSelection(
        entries: List<VFile>,
        anchorId: String,
        targetId: String,
        additive: Boolean,
        existingSelection: Set<String>,
    ): Set<String> {
        val anchorIndex = entries.indexOfFirst { it.id == anchorId }.takeIf { it >= 0 } ?: return setOf(targetId)
        val targetIndex = entries.indexOfFirst { it.id == targetId }.takeIf { it >= 0 } ?: return setOf(anchorId)
        val range = entries
            .subList(minOf(anchorIndex, targetIndex), maxOf(anchorIndex, targetIndex) + 1)
            .mapTo(linkedSetOf()) { it.id }
        return if (additive) existingSelection + range else range
    }

    /**
     * 校正选择状态 — 移除不再可见的条目 ID。
     */
    fun reconcileSelection(
        entries: List<VFile>,
        selectedEntryIds: Set<String>,
        anchorId: String?,
        focusId: String?,
    ): SelectionState {
        val visibleIds = entries.mapTo(mutableSetOf()) { it.id }
        // 允许空选择——用户清空选择后不应自动选中首项
        val nextSelectedEntryIds = selectedEntryIds.intersect(visibleIds)
        val nextAnchorId = anchorId?.takeIf { visibleIds.contains(it) }
        val nextFocusId = focusId?.takeIf { visibleIds.contains(it) } ?: nextAnchorId
        return SelectionState(
            selectedEntryIds = nextSelectedEntryIds,
            anchorId = nextAnchorId,
            focusId = nextFocusId,
        )
    }

    /**
     * 验证 entryId 是否仍在可见条目中。
     */
    fun validEntryId(
        entryId: String?,
        entries: List<VFile>,
    ): String? {
        return entryId?.takeIf { candidate -> entries.any { it.id == candidate } }
    }

    /**
     * 获取当前选择焦点 ID — 优先 focusId，回退到 anchorId，最后取第一个选中项。
     */
    fun resolveSelectionFocusId(
        entries: List<VFile>,
        focusId: String?,
        anchorId: String?,
        selectedEntryIds: Set<String>,
    ): String? {
        return focusId
            ?.takeIf { id -> entries.any { it.id == id } }
            ?: anchorId
                ?.takeIf { id -> entries.any { it.id == id } }
            ?: selectedEntryIds.firstOrNull()
    }

    /**
     * 构建状态栏统计信息。
     */
    fun buildStatusInfo(
        allEntries: List<VFile>,
        visibleEntries: List<VFile>,
        selectedEntryIds: Set<String>,
    ): PaneStatusInfo {
        val selectedEntries = visibleEntries.filter { entry -> selectedEntryIds.contains(entry.id) }
        return PaneStatusInfo(
            totalItemCount = allEntries.size,
            visibleItemCount = visibleEntries.size,
            directoryCount = visibleEntries.count { entry -> entry.kind == VFileKind.DIRECTORY },
            fileCount = visibleEntries.count { entry -> entry.kind == VFileKind.FILE },
            selectedCount = selectedEntries.size,
            selectedSizeBytes = selectedEntries.sumOf { entry -> entry.sizeBytes ?: 0L },
        )
    }

    /**
     * 递归收集所有可见条目（包括内联展开的子项）。
     */
    fun collectVisibleEntries(
        entries: List<VFile>,
        expandedLocations: Set<String>,
        expandedEntries: Map<String, InlineExpandedEntry>,
    ): List<VFile> {
        return buildList {
            entries.forEach { entry ->
                add(entry)
                if (entry.location in expandedLocations) {
                    val expanded = expandedEntries[entry.location]
                    if (expanded?.entries != null) {
                        addAll(
                            collectVisibleEntries(
                                entries = expanded.entries,
                                expandedLocations = expandedLocations,
                                expandedEntries = expandedEntries,
                            )
                        )
                    }
                }
            }
        }
    }

    data class SelectionState(
        val selectedEntryIds: Set<String>,
        val anchorId: String?,
        val focusId: String?,
    )
}
