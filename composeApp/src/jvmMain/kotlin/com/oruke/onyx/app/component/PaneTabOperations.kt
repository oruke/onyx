package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.PaneSessionSnapshot
import java.util.UUID

/**
 * 创建并激活新标签。
 *
 * @param location 初始 VFS 位置。
 */
internal fun DefaultPaneComponent.createTab(location: String) {
    captureActiveVisit()
    val tab = createTabState(pathService.normalizeLocation(location))
    val update = tabs().withCreatedTab(tab)
    restoreExpansions(emptyMap())
    applyTabStackUpdate(update)
    loadTab(update.activeTab.id, update.activeTab.location)
}

/**
 * 激活指定标签。
 *
 * @param tabId 标签 ID。
 */
internal fun DefaultPaneComponent.selectTab(tabId: String) {
    if (tabId == mutableState.value.activeTabId) return
    captureActiveVisit()
    val update = tabs().withSelectedTab(tabId) ?: return
    restoreExpansions(tabExpansions[tabId].orEmpty())
    applyTabStackUpdate(update)
    loadTab(update.activeTab.id, update.activeTab.location)
}

/**
 * 关闭指定标签并选择替代标签。
 *
 * @param tabId 标签 ID。
 */
internal fun DefaultPaneComponent.closeTab(tabId: String) {
    captureActiveVisit()
    val update = tabs().withClosedTab(mutableState.value.activeTabId, tabId) ?: return
    navigationController.forget(tabId)
    tabExpansions.remove(tabId)
    restoreExpansions(tabExpansions[update.activeTab.id].orEmpty())
    applyTabStackUpdate(update)
    loadTab(update.activeTab.id, update.activeTab.location)
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
internal fun DefaultPaneComponent.detachPaneTab(tabId: String): PaneTabTransfer? {
    captureActiveVisit()
    val currentTabs = tabs()
    val detached = currentTabs.firstOrNull { it.id == tabId } ?: return null
    val transfer = PaneTabTransfer(navigationController.capture(detached), tabExpansions[tabId].orEmpty())
    // 已从同一不可变标签集合找到目标，唯一标签的替代项也已提供，移出结果必须存在。
    val update = checkNotNull(currentTabs.withDetachedTab(
        activeTabId = mutableState.value.activeTabId,
        tabId = tabId,
        replacementTab = if (currentTabs.size == 1) {
            createTabState(fileRepository.defaultLocation())
        } else {
            null
        },
    ))
    navigationController.forget(tabId)
    tabExpansions.remove(tabId)
    restoreExpansions(tabExpansions[update.activeTab.id].orEmpty())
    applyTabStackUpdate(update)
    loadTab(update.activeTab.id, update.activeTab.location)
    return transfer
}

/**
 * 将外部标签快照插入当前面板。
 *
 * @param tabSnapshot 标签快照。
 * @param targetIndex 目标索引。
 */
internal fun DefaultPaneComponent.attachPaneTab(tabSnapshot: PaneTabTransfer, targetIndex: Int) {
    captureActiveVisit()
    val restoredTab = tabSnapshot.tab
    val currentTabs = tabs()
    val uniqueTab = if (currentTabs.any { tab -> tab.id == restoredTab.id }) {
        restoredTab.copy(id = UUID.randomUUID().toString())
    } else {
        restoredTab
    }
    val update = currentTabs.withAttachedTab(uniqueTab, targetIndex)
    tabExpansions[uniqueTab.id] = tabSnapshot.expansions
    restoreExpansions(tabSnapshot.expansions)
    applyTabStackUpdate(update)
    loadTab(update.activeTab.id, update.activeTab.location)
}

/**
 * 恢复面板标签会话与活动标签。
 *
 * @param snapshot 面板会话快照。
 */
internal fun DefaultPaneComponent.restorePaneSession(snapshot: PaneSessionSnapshot) {
    tabs().forEach { navigationController.forget(it.id) }
    tabExpansions.clear()
    restoreExpansions(emptyMap())
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
