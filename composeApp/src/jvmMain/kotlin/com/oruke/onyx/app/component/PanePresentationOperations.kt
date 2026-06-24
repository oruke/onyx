package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.PaneOperationFeedbackKind
import com.oruke.onyx.core.model.ViewMode

internal fun DefaultPaneComponent.setViewMode(mode: ViewMode) {
    val tab = activeTab() ?: return
    updateTab(tab.id) { currentTab ->
        currentTab.withViewModeState(mode)
    }
}

internal fun DefaultPaneComponent.setFilterQuery(query: String) {
    val tab = activeTab() ?: return
    updateTab(tab.id) { currentTab ->
        currentTab.withFilterQueryState(query)
    }
}

/**
 * 打开面板内筛选输入框，并递增聚焦请求编号。
 *
 * @return 无返回值。
 */
internal fun DefaultPaneComponent.showFilterInput() {
    updatePaneChrome { chrome ->
        chrome.copy(
            filterInputVisible = true,
            filterInputFocusRequestId = chrome.filterInputFocusRequestId + 1,
        )
    }
}

/**
 * 关闭面板内筛选输入框，可按需清空当前标签的筛选条件。
 *
 * @param clearQuery 是否同时清空当前标签筛选文本。
 * @return 无返回值。
 */
internal fun DefaultPaneComponent.hideFilterInput(clearQuery: Boolean) {
    if (clearQuery) {
        setFilterQuery("")
    }
    updatePaneChrome { chrome ->
        chrome.copy(filterInputVisible = false)
    }
}

/**
 * 打开面板内命令面板。
 *
 * @return 无返回值。
 */
internal fun DefaultPaneComponent.showCommandPalette() {
    updatePaneChrome { chrome ->
        chrome.copy(commandPaletteVisible = true)
    }
}

/**
 * 关闭面板内命令面板。
 *
 * @return 无返回值。
 */
internal fun DefaultPaneComponent.hideCommandPalette() {
    updatePaneChrome { chrome ->
        chrome.copy(commandPaletteVisible = false)
    }
}

internal fun DefaultPaneComponent.toggleSort(column: DetailsColumn) {
    val tab = activeTab() ?: return
    updateTab(tab.id) { currentTab ->
        currentTab.withToggledSortState(column)
    }
}

internal fun DefaultPaneComponent.toggleHiddenItems() {
    val tab = activeTab() ?: return
    updateTab(tab.id) { currentTab ->
        currentTab.withToggledHiddenItemsState()
    }
}

internal fun DefaultPaneComponent.toggleColumnVisibility(column: DetailsColumn) {
    val tab = activeTab() ?: return
    updateTab(tab.id) { currentTab ->
        currentTab.withToggledColumnVisibilityState(column)
    }
}

internal fun DefaultPaneComponent.setGalleryItemSize(sizeDp: Int) {
    val tab = activeTab() ?: return
    updateTab(tab.id) { currentTab ->
        currentTab.withGalleryItemSizeState(sizeDp)
    }
}

internal fun DefaultPaneComponent.resizeDetailsColumn(
    column: DetailsColumn,
    nextColumn: DetailsColumn,
    deltaWeight: Float,
) {
    val tab = activeTab() ?: return
    updateTab(tab.id) { currentTab ->
        currentTab.withResizedDetailsColumnState(
            column = column,
            deltaWeight = deltaWeight,
        )
    }
}

internal fun DefaultPaneComponent.dismissOperationFeedback() {
    clearOperationFeedback(activeTab()?.id ?: return)
}

internal fun DefaultPaneComponent.showOperationFeedback(
    kind: PaneOperationFeedbackKind,
    detail: I18nMessage,
) {
    updateFailure(
        tabId = activeTab()?.id ?: return,
        kind = kind,
        detail = detail,
    )
}

internal fun DefaultPaneComponent.consumePendingScroll() {
    val tab = activeTab() ?: return
    if (tab.pendingScrollToEntryId != null) {
        updateTab(tab.id) { currentTab ->
            currentTab.withTabState { current -> current.copy(pendingScrollToEntryId = null) }
        }
    }
}
