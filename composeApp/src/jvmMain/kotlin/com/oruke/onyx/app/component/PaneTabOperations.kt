package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.PaneSessionSnapshot
import com.oruke.onyx.core.model.TabSnapshot
import java.util.UUID

/**
 * 创建并激活新标签。
 *
 * @param location 初始 VFS 位置。
 */
internal fun DefaultPaneComponent.createTab(location: String) {
    val tab = createTabState(pathService.normalizeLocation(location))
    val update = tabs().withCreatedTab(tab)
    applyTabStackUpdate(update)
    loadTab(update.activeTab.id, update.activeTab.location)
}

/**
 * 激活指定标签。
 *
 * @param tabId 标签 ID。
 */
internal fun DefaultPaneComponent.selectTab(tabId: String) {
    val update = tabs().withSelectedTab(tabId) ?: return
    applyTabStackUpdate(update)
    loadActiveTabIfIdle(update.activeTab)
}

/**
 * 关闭指定标签并选择替代标签。
 *
 * @param tabId 标签 ID。
 */
internal fun DefaultPaneComponent.closeTab(tabId: String) {
    val update = tabs().withClosedTab(mutableState.value.activeTabId, tabId) ?: return
    applyTabStackUpdate(update)
    loadActiveTabIfIdle(update.activeTab)
}

/**
 * 调整标签显示顺序。
 *
 * @param tabId 标签 ID。
 * @param targetIndex 目标索引。
 */
internal fun DefaultPaneComponent.moveTab(tabId: String, targetIndex: Int) {
    val update = tabs().withMovedTab(mutableState.value.activeTabId, tabId, targetIndex) ?: return
    applyTabStackUpdate(update)
}

/**
 * 从当前面板移出标签；唯一标签被移出时创建替代标签。
 *
 * @param tabId 标签 ID。
 * @return 被移出的标签快照。
 */
internal fun DefaultPaneComponent.detachPaneTab(tabId: String): TabSnapshot? {
    val currentTabs = tabs()
    val update = currentTabs.withDetachedTab(
        activeTabId = mutableState.value.activeTabId,
        tabId = tabId,
        replacementTab = if (currentTabs.size == 1) {
            createTabState(fileRepository.defaultLocation())
        } else {
            null
        },
    ) ?: return null
    applyTabStackUpdate(update)
    loadActiveTabIfIdle(update.activeTab)
    return update.detachedTabSnapshot
}

/**
 * 将外部标签快照插入当前面板。
 *
 * @param tabSnapshot 标签快照。
 * @param targetIndex 目标索引。
 */
internal fun DefaultPaneComponent.attachPaneTab(tabSnapshot: TabSnapshot, targetIndex: Int) {
    val restoredTab = tabSnapshot.toPaneTabState(pathService)
    val currentTabs = tabs()
    val uniqueTab = if (currentTabs.any { tab -> tab.id == restoredTab.id }) {
        restoredTab.copy(id = UUID.randomUUID().toString())
    } else {
        restoredTab
    }
    val update = currentTabs.withAttachedTab(uniqueTab, targetIndex)
    applyTabStackUpdate(update)
    loadActiveTabIfIdle(update.activeTab)
}

/**
 * 恢复面板标签会话与活动标签。
 *
 * @param snapshot 面板会话快照。
 */
internal fun DefaultPaneComponent.restorePaneSession(snapshot: PaneSessionSnapshot) {
    val restoredTabs = snapshot.tabs
        .ifEmpty { listOf(createTabState(fileRepository.defaultLocation()).toTabSnapshot()) }
        .map { tabSnapshot -> tabSnapshot.toPaneTabState(pathService) }
    val activeTabId = snapshot.activeTabId.takeIf { candidate ->
        restoredTabs.any { tab -> tab.id == candidate }
    } ?: restoredTabs.first().id
    val activeTab = restoredTabs.first { tab -> tab.id == activeTabId }
    navigateTabStack(restoredTabs, activeTabId)
    applyActiveTab(activeTab, activeTabId)
    loadTab(activeTab.id, activeTab.location)
}

/**
 * 仅在标签尚未加载时触发目录读取。
 *
 * @param tab 活动标签状态。
 */
private fun DefaultPaneComponent.loadActiveTabIfIdle(tab: PaneTabState) {
    if (tab.entriesState == PaneEntriesState.Idle) loadTab(tab.id, tab.location)
}
