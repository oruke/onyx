package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.DirectoryChange
import com.oruke.onyx.core.model.VFile

/**
 * 把创建或改名返回的真实条目同步给全部订阅者，只有未发生新交互的发起访问才自动定位。
 * @param tabId 发起标签。
 * @param origin 发起时的访问状态。
 * @param change 已提交变化。
 * @param entry 真实结果条目。
 * @param selectNew 是否选中新建条目；重命名保留原多选。
 */
internal fun DefaultPaneComponent.applyEntryMutation(
    tabId: String,
    origin: TabState,
    change: DirectoryChange,
    entry: VFile,
    selectNew: Boolean,
) {
    directories.apply(change)
    val current = tab(tabId) ?: return
    if (current.location != origin.location || current.tabState.browserState.visitId != origin.browserState.visitId ||
        current.tabState.browserState.interactionRevision != origin.browserState.interactionRevision) return
    directories.cached(current.location)?.let { content ->
        updateTab(tabId) { PaneDirectoryReducer.applyContent(it, content, null) }
    }
    val visible = currentVisibleEntries().any { it.id == entry.id }
    updateTab(tabId) { value -> value.withTabState { state ->
        val ids = if (selectNew) setOf(entry.id) else origin.selectedEntryIds.mapTo(linkedSetOf()) {
            change.identityChanges[it] ?: it
        }
        state.copy(selectedEntryIds = if (visible) ids else state.selectedEntryIds,
            selectionFocusId = if (visible) entry.id else state.selectionFocusId,
            selectionAnchorId = if (visible) entry.id else state.selectionAnchorId,
            pendingScrollToEntryId = if (visible) entry.id else null,
            inlineEditState = null,
            operationFeedback = null)
    } }
}
