package com.oruke.onyx.app.component

import com.arkivanov.decompose.router.stack.navigate
import com.oruke.onyx.app.component.delegate.SelectionReducer
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.PaneOperationFeedback
import com.oruke.onyx.core.model.PaneOperationFeedbackKind
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.ViewMode
import java.util.UUID

/**
 * 创建带默认视图状态的新标签。
 *
 * @param location 标签 VFS 位置。
 * @param defaultViewMode 默认视图模式。
 * @return 新标签状态。
 */
internal fun DefaultPaneComponent.createTabState(
    location: String,
    defaultViewMode: ViewMode = initialViewMode,
): PaneTabState {
    return createDefaultPaneTabState(
        id = UUID.randomUUID().toString(),
        title = pathService.title(location),
        location = location,
        defaultViewMode = defaultViewMode,
    )
}

/**
 * 清除指定标签内联编辑状态。
 *
 * @param tabId 标签 ID。
 */
internal fun DefaultPaneComponent.clearInlineEdit(tabId: String) {
    updateTab(tabId) { currentTab -> currentTab.clearInlineEditState() }
}

/**
 * 清除指定标签操作反馈。
 *
 * @param tabId 标签 ID。
 */
internal fun DefaultPaneComponent.clearOperationFeedback(tabId: String) {
    updateTab(tabId) { currentTab ->
        currentTab.withTabState { current -> current.copy(operationFeedback = null) }
    }
}

/**
 * 写入指定标签的失败反馈。
 *
 * @param tabId 标签 ID。
 * @param kind 反馈类型。
 * @param detail 本地化失败详情。
 */
internal fun DefaultPaneComponent.updateFailure(
    tabId: String,
    kind: PaneOperationFeedbackKind,
    detail: I18nMessage?,
) {
    updateTab(tabId) { currentTab ->
        currentTab.withTabState { current ->
            current.copy(operationFeedback = PaneOperationFeedback(kind, detail))
        }
    }
}

/**
 * 读取标签顶层已加载条目。
 *
 * @param tab 标签状态。
 * @return 已加载条目。
 */
internal fun DefaultPaneComponent.currentTabEntries(tab: PaneTabState): List<VFile> {
    return (tab.entriesState as? PaneEntriesState.Ready)?.entries.orEmpty()
}

/**
 * 更新指定标签并同步活动面板与 TabComponent 状态。
 *
 * @param tabId 标签 ID。
 * @param transform 标签状态转换。
 */
internal fun DefaultPaneComponent.updateTab(
    tabId: String,
    transform: (PaneTabState) -> PaneTabState,
) {
    val paneState = mutableState.value
    val currentTab = tabs().firstOrNull { tab -> tab.id == tabId } ?: return
    val updated = transform(currentTab).withDerivedState(
        inlineExpandedLocations = paneState.inlineExpandedLocations,
        inlineExpandedEntries = paneState.inlineExpandedEntries,
    )
    updateTabComponentState(updated)
    if (paneState.activeTabId == tabId) {
        applyActiveTab(
            activeTab = updated,
            activeTabId = paneState.activeTabId,
            inlineExpandedLocations = paneState.inlineExpandedLocations,
            inlineExpandedEntries = paneState.inlineExpandedEntries,
        )
    }
}

/**
 * 应用普通标签栈更新。
 *
 * @param update 标签栈更新结果。
 */
internal fun DefaultPaneComponent.applyTabStackUpdate(update: PaneTabStackUpdate) {
    navigateTabStack(update.tabs, update.activeTab.id)
    applyActiveTab(update.activeTab)
}

/**
 * 应用标签移出后的栈更新。
 *
 * @param update 标签移出更新结果。
 */
internal fun DefaultPaneComponent.applyTabStackUpdate(update: PaneTabDetachUpdate) {
    navigateTabStack(update.tabs, update.activeTab.id)
    applyActiveTab(update.activeTab)
}

/**
 * 同步标签显示顺序和 Decompose 子栈。
 *
 * @param tabs 标签状态列表。
 * @param activeTabId 活动标签 ID。
 */
internal fun DefaultPaneComponent.navigateTabStack(tabs: List<PaneTabState>, activeTabId: String) {
    mutableTabOrder.value = tabs.map { tab -> tab.id }
    val configs = tabs.map { tab -> tab.toTabSnapshot().toTabConfig() }
    val activeIndex = configs.indexOfFirst { config -> config.id == activeTabId }
    val orderedConfigs = if (activeIndex in configs.indices && activeIndex != configs.lastIndex) {
        configs.toMutableList().apply { add(removeAt(activeIndex)) }
    } else {
        configs
    }
    if (orderedConfigs.isNotEmpty()) {
        tabNavigation.navigate { orderedConfigs }
        tabs.forEach(::updateTabComponentState)
    }
}

/**
 * 将活动标签状态投影为面板公开状态。
 *
 * @param activeTab 活动标签。
 * @param activeTabId 活动标签 ID。
 * @param inlineExpandedLocations 已展开目录位置。
 * @param inlineExpandedEntries 已加载展开条目。
 */
internal fun DefaultPaneComponent.applyActiveTab(
    activeTab: PaneTabState,
    activeTabId: String = activeTab.id,
    inlineExpandedLocations: Set<String> = emptySet(),
    inlineExpandedEntries: Map<String, InlineExpandedEntry> = emptyMap(),
) {
    val chrome = mutableState.value.chromeState
    mutableState.value = activeTab.toPaneState(
        paneId = paneId,
        activeTabId = activeTabId,
        inlineExpandedLocations = inlineExpandedLocations,
        inlineExpandedEntries = inlineExpandedEntries,
        filterInputVisible = chrome.filterInputVisible,
        filterInputFocusRequestId = chrome.filterInputFocusRequestId,
        commandPaletteVisible = chrome.commandPaletteVisible,
    )
}

/**
 * 更新面板 chrome 状态，不触碰标签业务状态。
 *
 * @param transform chrome 状态转换。
 */
internal fun DefaultPaneComponent.updatePaneChrome(transform: (PaneChromeState) -> PaneChromeState) {
    val currentState = mutableState.value
    mutableState.value = currentState.copy(chromeState = transform(currentState.chromeState))
}

/**
 * 同步指定 TabComponent 的状态流。
 *
 * @param tab 最新标签状态。
 */
private fun DefaultPaneComponent.updateTabComponentState(tab: PaneTabState) {
    tabStack.value.items
        .firstOrNull { child -> child.configuration.id == tab.id }
        ?.instance
        ?.updateState(tab)
}

/**
 * 读取活动标签状态。
 *
 * @return 活动标签。
 */
internal fun DefaultPaneComponent.activeTab(): PaneTabState? {
    return tabStack.value.active.instance.state.value
}

/**
 * 按 ID 读取标签状态。
 *
 * @param tabId 标签 ID。
 * @return 匹配标签。
 */
internal fun DefaultPaneComponent.tab(tabId: String): PaneTabState? {
    return tabStack.value.items
        .firstOrNull { child -> child.configuration.id == tabId }
        ?.instance
        ?.state
        ?.value
}

/**
 * 按显示顺序读取全部标签。
 *
 * @return 标签状态列表。
 */
internal fun DefaultPaneComponent.tabs(): List<PaneTabState> = tabStatesInDisplayOrder()

/**
 * 读取包含树状展开子项的当前可见条目。
 *
 * @return 当前可见条目。
 */
internal fun DefaultPaneComponent.currentVisibleEntries(): List<VFile> {
    val state = mutableState.value
    val entries = (activeTab()?.entriesState as? PaneEntriesState.Ready)?.entries ?: return emptyList()
    return if (state.inlineExpandedLocations.isEmpty()) {
        entries
    } else {
        SelectionReducer.collectVisibleEntries(
            entries = entries,
            expandedLocations = state.inlineExpandedLocations,
            expandedEntries = state.inlineExpandedEntries,
        )
    }
}

/**
 * 读取位置末级名称。
 *
 * @param location VFS 位置。
 * @return 末级名称。
 */
internal fun DefaultPaneComponent.locationBaseName(location: String): String? = pathService.baseName(location)
