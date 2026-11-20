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

/**
 * 关闭除 [tabId] 外的全部其他标签，并将保留标签置为活动标签。
 *
 * @param tabId 要保留的目标标签 ID。
 * @return 存在其他可关闭标签时返回更新结果，否则返回 `null`。
 */
internal fun List<PaneTabState>.withClosedOtherTabs(
    tabId: String,
): PaneTabStackUpdate? {
    if (size <= 1) return null
    val kept = firstOrNull { it.id == tabId } ?: return null
    return PaneTabStackUpdate(
        tabs = listOf(kept),
        activeTab = kept,
    )
}

/**
 * 关闭位于 [tabId] 右侧的全部标签；若原活动标签位于右侧被关闭，则激活 [tabId]。
 *
 * @param activeTabId 当前活动标签 ID。
 * @param tabId 基准标签 ID。
 * @return 右侧存在可关闭标签时返回更新结果，否则返回 `null`。
 */
internal fun List<PaneTabState>.withClosedTabsToRight(
    activeTabId: String,
    tabId: String,
): PaneTabStackUpdate? {
    val tabIndex = indexOfFirst { it.id == tabId }
    if (tabIndex == -1 || tabIndex >= lastIndex) return null
    val nextTabs = subList(0, tabIndex + 1).toList()
    val nextActive = nextTabs.firstOrNull { it.id == activeTabId } ?: nextTabs.last()
    return PaneTabStackUpdate(
        tabs = nextTabs,
        activeTab = nextActive,
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
    val detachedTab = getOrNull(tabIndex) ?: return null
    return if (size == 1) {
        replacementTab?.let { replacement ->
            PaneTabDetachUpdate(
                tabs = listOf(replacement),
                activeTab = replacement,
                detachedTabSnapshot = detachedTab.toTabSnapshot(),
            )
        }
    } else {
        val nextTabs = filterNot { it.id == tabId }
        val nextActiveTab = if (activeTabId == tabId) {
            nextTabs.getOrNull(tabIndex.coerceAtMost(nextTabs.lastIndex)) ?: nextTabs.first()
        } else {
            nextTabs.first { it.id == activeTabId }
        }
        PaneTabDetachUpdate(
            tabs = nextTabs,
            activeTab = nextActiveTab,
            detachedTabSnapshot = detachedTab.toTabSnapshot(),
        )
    }
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
