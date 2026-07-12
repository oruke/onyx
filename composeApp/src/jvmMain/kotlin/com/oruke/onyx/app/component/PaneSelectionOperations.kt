package com.oruke.onyx.app.component

/**
 * 选择单个条目，并支持追加或范围选择。
 *
 * @param entryId 条目 ID。
 * @param additive 是否追加选择。
 * @param range 是否从锚点范围选择。
 */
internal fun DefaultPaneComponent.selectEntry(entryId: String, additive: Boolean, range: Boolean) {
    val tab = activeTab() ?: return
    clearInlineEdit(tab.id)
    val entries = currentVisibleEntries()
    updateTab(tab.id) { currentTab ->
        currentTab.selectEntryState(entries, entryId, additive, range)
    }
}

/**
 * 直接替换当前选择集合。
 *
 * @param entryIds 条目 ID 集合。
 */
internal fun DefaultPaneComponent.selectEntries(entryIds: Set<String>) {
    val tab = activeTab() ?: return
    updateTab(tab.id) { currentTab -> currentTab.withSelectedEntryIds(entryIds) }
}

/**
 * 按当前可见顺序移动选择焦点。
 *
 * @param offset 移动偏移。
 * @param extendSelection 是否扩展选择。
 */
internal fun DefaultPaneComponent.moveSelection(offset: Int, extendSelection: Boolean) {
    val tab = activeTab() ?: return
    clearInlineEdit(tab.id)
    val entries = currentVisibleEntries()
    updateTab(tab.id) { currentTab ->
        currentTab.moveSelectionState(entries, offset, extendSelection)
    }
}

/** 打开当前选择焦点条目。 */
internal fun DefaultPaneComponent.openSelectedEntry() {
    val tab = activeTab() ?: return
    val selectedEntry = tab.resolveSelectionFocusEntry(currentVisibleEntries()) ?: return
    clearInlineEdit(tab.id)
    openEntry(selectedEntry)
}

/** 选择当前视图中的全部条目。 */
internal fun DefaultPaneComponent.selectAll() {
    val tab = activeTab() ?: return
    clearInlineEdit(tab.id)
    val entries = currentVisibleEntries()
    updateTab(tab.id) { currentTab -> currentTab.selectAllEntriesState(entries) }
}

/** 清空当前选择。 */
internal fun DefaultPaneComponent.clearSelection() {
    val tab = activeTab() ?: return
    clearInlineEdit(tab.id)
    updateTab(tab.id) { currentTab -> currentTab.clearSelectionState() }
}
