package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.VFile

/**
 * 记录用户命令上下文，与被动外部目录变化明确区分。
 * @param entries 本次已经确定的目标条目。
 * @return 当前选择与目标一致时返回上下文，否则不执行自动续接。
 */
internal fun DefaultPaneComponent.captureRemovalContext(entries: List<VFile>): PaneRemovalContext? {
    val tab = activeTab() ?: return null
    val targets = entries.mapTo(linkedSetOf()) { it.id }
    if (targets.isEmpty() || !tab.selectedEntryIds.containsAll(targets)) return null
    return PaneRemovalContext(paneId, tab.id, tab.tabState.browserState.visitId,
        tab.tabState.browserState.interactionRevision, tab.location, targets, tab.selectedEntryIds,
        tab.selectionFocusId, currentVisibleEntries().map { it.id })
}

/**
 * 命令最终完成后选择一次最近存活项；导航或新交互发生后只保留内容更新。
 * @param context 发起访问与旧显示顺序。
 * @param removedIds 已确认消失的命令目标。
 */
internal fun DefaultPaneComponent.applyRemovalCompletion(context: PaneRemovalContext, removedIds: Set<String>) {
    val tab = activeTab() ?: return
    val browser = tab.tabState.browserState
    if (tab.id != context.tabId || browser.visitId != context.visitId) return
    if (browser.interactionRevision != context.interactionRevision ||
        removedIds.none { it in context.selectedIds }) return
    // 订阅通知和任务完成可能在同一主线程循环到达，先应用最新共享内容再计算邻项。
    directories.cached(tab.location)?.let { content ->
        updateTab(tab.id) { PaneDirectoryReducer.applyContent(it, content, null) }
    }
    val visible = currentVisibleEntries().mapTo(linkedSetOf()) { it.id }
    val remaining = context.selectedIds.intersect(visible)
    val index = context.visibleIds.indexOf(context.focusId).coerceAtLeast(0)
    val nearest = context.visibleIds.drop(index).firstOrNull { it in (remaining.ifEmpty { visible }) }
        ?: context.visibleIds.take(index).lastOrNull { it in (remaining.ifEmpty { visible }) }
    val selection = remaining.ifEmpty { setOfNotNull(nearest) }
    updateTab(tab.id) { current -> current.withTabState { state ->
        state.copy(selectedEntryIds = selection, selectionFocusId = nearest,
            selectionAnchorId = nearest, pendingScrollToEntryId = null)
    } }
}
