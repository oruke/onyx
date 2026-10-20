package com.oruke.onyx.app.component

import com.oruke.onyx.shared.filesystem.DirectoryContentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 在后台派生目录显示结果，主线程只应用仍匹配当前访问和交互状态的快照。
 * @param tabId 订阅所属标签。
 * @param location 订阅所属目录。
 * @param visitId 发起订阅的访问身份。
 * @param content 共享目录内容和同步状态。
 */
internal suspend fun DefaultPaneComponent.applyDirectoryContent(
    tabId: String,
    location: String,
    visitId: Long,
    content: DirectoryContentState,
) {
    var applied = false
    while (!applied) {
        val current = tab(tabId)?.takeIf {
            it.location == location && it.tabState.browserState.visitId == visitId
        } ?: return
        val chrome = mutableState.value.chromeState
        val focusName = if (content.entries != null) pendingFocusEntryName[tabId] else null
        val prepared = withContext(Dispatchers.Default) {
            val mapped = PaneDirectoryReducer.applyContent(current, content, focusName)
            // 静默无变化已在共享层阻止发布；收到真实状态时仍完成派生，保证缓存导航与显式定位正确。
            mapped.withDerivedState(chrome.inlineExpandedLocations, chrome.inlineExpandedEntries,
                preserveOrder = focusName == null && mapped.deferDirectoryOrdering)
        }
        // 排序期间发生的新选择、筛选或展开优先；重算最新状态，不能覆盖用户交互。
        if (tab(tabId) === current && mutableState.value.chromeState == chrome) {
            if (focusName != null) pendingFocusEntryName.remove(tabId)
            updateTab(tabId, derive = false) { prepared }
            applied = true
        }
    }
}
