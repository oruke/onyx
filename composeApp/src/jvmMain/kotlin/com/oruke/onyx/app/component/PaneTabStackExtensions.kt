package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.TabSnapshot

internal data class PaneTabStackUpdate(
    val tabs: List<PaneTabState>,
    val activeTab: PaneTabState,
)

internal data class PaneTabDetachUpdate(
    val tabs: List<PaneTabState>,
    val activeTab: PaneTabState,
    val detachedTabSnapshot: TabSnapshot,
)

internal fun List<PaneTabState>.withCreatedTab(
    tab: PaneTabState,
): PaneTabStackUpdate {
    val nextTabs = this + tab
    return PaneTabStackUpdate(
        tabs = nextTabs,
        activeTab = tab,
    )
}

internal fun List<PaneTabState>.withSelectedTab(
    tabId: String,
): PaneTabStackUpdate? {
    val tab = firstOrNull { it.id == tabId } ?: return null
    return PaneTabStackUpdate(
        tabs = this,
        activeTab = tab,
    )
}

internal fun List<PaneTabState>.withClosedTab(
    activeTabId: String,
    tabId: String,
): PaneTabStackUpdate? {
    if (size <= 1) return null
    val tabIndex = indexOfFirst { it.id == tabId }
    if (tabIndex == -1) return null

    val nextTabs = filterNot { it.id == tabId }
    val nextActiveTab = if (activeTabId == tabId) {
        nextTabs.getOrNull(tabIndex.coerceAtMost(nextTabs.lastIndex)) ?: nextTabs.first()
    } else {
        nextTabs.first { it.id == activeTabId }
    }
    return PaneTabStackUpdate(
        tabs = nextTabs,
        activeTab = nextActiveTab,
    )
}

internal fun List<PaneTabState>.withMovedTab(
    activeTabId: String,
    tabId: String,
    targetIndex: Int,
): PaneTabStackUpdate? {
    val currentIndex = indexOfFirst { it.id == tabId }
    if (currentIndex == -1) return null

    val tab = this[currentIndex]
    val withoutTab = filterNot { it.id == tabId }
    val adjustedTargetIndex = if (currentIndex < targetIndex) targetIndex - 1 else targetIndex
    val insertIndex = adjustedTargetIndex.coerceIn(0, withoutTab.size)
    val nextTabs = withoutTab.toMutableList().apply { add(insertIndex, tab) }
    val activeTab = nextTabs.first { it.id == activeTabId }
    return PaneTabStackUpdate(
        tabs = nextTabs,
        activeTab = activeTab,
    )
}

internal fun List<PaneTabState>.withDetachedTab(
    activeTabId: String,
    tabId: String,
    replacementTab: PaneTabState?,
): PaneTabDetachUpdate? {
    val tabIndex = indexOfFirst { it.id == tabId }
    if (tabIndex == -1) return null

    val detachedTab = this[tabIndex]
    if (size == 1) {
        replacementTab ?: return null
        return PaneTabDetachUpdate(
            tabs = listOf(replacementTab),
            activeTab = replacementTab,
            detachedTabSnapshot = detachedTab.toTabSnapshot(),
        )
    }

    val nextTabs = filterNot { it.id == tabId }
    val nextActiveTab = if (activeTabId == tabId) {
        nextTabs.getOrNull(tabIndex.coerceAtMost(nextTabs.lastIndex)) ?: nextTabs.first()
    } else {
        nextTabs.first { it.id == activeTabId }
    }
    return PaneTabDetachUpdate(
        tabs = nextTabs,
        activeTab = nextActiveTab,
        detachedTabSnapshot = detachedTab.toTabSnapshot(),
    )
}

internal fun List<PaneTabState>.withAttachedTab(
    tab: PaneTabState,
    targetIndex: Int,
): PaneTabStackUpdate {
    val insertIndex = targetIndex.coerceIn(0, size)
    val nextTabs = toMutableList().apply {
        add(insertIndex, tab)
    }
    return PaneTabStackUpdate(
        tabs = nextTabs,
        activeTab = tab,
    )
}
