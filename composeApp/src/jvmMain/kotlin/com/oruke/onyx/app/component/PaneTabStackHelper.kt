package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.TabSnapshot

internal data class PaneTabStackUpdate(
    val state: PaneState,
    val activeTab: PaneTabState,
)

internal data class PaneTabDetachUpdate(
    val state: PaneState,
    val activeTab: PaneTabState,
    val detachedTabSnapshot: TabSnapshot,
)

internal fun PaneState.withCreatedTab(
    paneId: PaneId,
    tab: PaneTabState,
): PaneTabStackUpdate {
    val nextTabs = tabs + tab
    return PaneTabStackUpdate(
        state = tab.toPaneState(
            paneId = paneId,
            activeTabId = tab.id,
            tabs = nextTabs,
        ),
        activeTab = tab,
    )
}

internal fun PaneState.withSelectedTab(
    paneId: PaneId,
    tabId: String,
): PaneTabStackUpdate? {
    val tab = tabs.firstOrNull { it.id == tabId } ?: return null
    return PaneTabStackUpdate(
        state = tab.toPaneState(
            paneId = paneId,
            activeTabId = tab.id,
            tabs = tabs,
        ),
        activeTab = tab,
    )
}

internal fun PaneState.withClosedTab(
    paneId: PaneId,
    tabId: String,
): PaneTabStackUpdate? {
    if (tabs.size <= 1) return null
    val tabIndex = tabs.indexOfFirst { it.id == tabId }
    if (tabIndex == -1) return null

    val nextTabs = tabs.filterNot { it.id == tabId }
    val nextActiveTab = if (activeTabId == tabId) {
        nextTabs.getOrNull(tabIndex.coerceAtMost(nextTabs.lastIndex)) ?: nextTabs.first()
    } else {
        nextTabs.first { it.id == activeTabId }
    }
    return PaneTabStackUpdate(
        state = nextActiveTab.toPaneState(
            paneId = paneId,
            activeTabId = nextActiveTab.id,
            tabs = nextTabs,
        ),
        activeTab = nextActiveTab,
    )
}

internal fun PaneState.withMovedTab(
    paneId: PaneId,
    tabId: String,
    targetIndex: Int,
): PaneTabStackUpdate? {
    val currentIndex = tabs.indexOfFirst { it.id == tabId }
    if (currentIndex == -1) return null

    val tab = tabs[currentIndex]
    val withoutTab = tabs.filterNot { it.id == tabId }
    val adjustedTargetIndex = if (currentIndex < targetIndex) targetIndex - 1 else targetIndex
    val insertIndex = adjustedTargetIndex.coerceIn(0, withoutTab.size)
    val nextTabs = withoutTab.toMutableList().apply { add(insertIndex, tab) }
    val activeTab = nextTabs.first { it.id == activeTabId }
    return PaneTabStackUpdate(
        state = activeTab.toPaneState(
            paneId = paneId,
            activeTabId = activeTabId,
            tabs = nextTabs,
        ),
        activeTab = activeTab,
    )
}

internal fun PaneState.withDetachedTab(
    paneId: PaneId,
    tabId: String,
    replacementTab: PaneTabState?,
): PaneTabDetachUpdate? {
    val tabIndex = tabs.indexOfFirst { it.id == tabId }
    if (tabIndex == -1) return null

    val detachedTab = tabs[tabIndex]
    if (tabs.size == 1) {
        replacementTab ?: return null
        return PaneTabDetachUpdate(
            state = replacementTab.toPaneState(
                paneId = paneId,
                activeTabId = replacementTab.id,
                tabs = listOf(replacementTab),
            ),
            activeTab = replacementTab,
            detachedTabSnapshot = detachedTab.toTabSnapshot(),
        )
    }

    val nextTabs = tabs.filterNot { it.id == tabId }
    val nextActiveTab = if (activeTabId == tabId) {
        nextTabs.getOrNull(tabIndex.coerceAtMost(nextTabs.lastIndex)) ?: nextTabs.first()
    } else {
        nextTabs.first { it.id == activeTabId }
    }
    return PaneTabDetachUpdate(
        state = nextActiveTab.toPaneState(
            paneId = paneId,
            activeTabId = nextActiveTab.id,
            tabs = nextTabs,
        ),
        activeTab = nextActiveTab,
        detachedTabSnapshot = detachedTab.toTabSnapshot(),
    )
}

internal fun PaneState.withAttachedTab(
    paneId: PaneId,
    tab: PaneTabState,
    targetIndex: Int,
): PaneTabStackUpdate {
    val insertIndex = targetIndex.coerceIn(0, tabs.size)
    val nextTabs = tabs.toMutableList().apply {
        add(insertIndex, tab)
    }
    return PaneTabStackUpdate(
        state = tab.toPaneState(
            paneId = paneId,
            activeTabId = tab.id,
            tabs = nextTabs,
        ),
        activeTab = tab,
    )
}
