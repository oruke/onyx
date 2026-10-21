package com.oruke.onyx.app.component

/**
 * 将设备概览的选择命令交给专用 reducer，保留普通文件选择流程的边界。
 *
 * @param current 当前活动概览标签。
 * @param intent 待派发的面板事件。
 * @return 已处理选择命令时返回 true。
 */
internal fun DefaultPaneComponent.dispatchComputerSelectionIntent(
    current: PaneTabState,
    intent: PaneIntent,
): Boolean {
    val computer = current.entriesState as? ComputerEntriesState ?: return false
    val ids = computer.selectableIds()
    return when (intent) {
        is PaneIntent.SelectEntry ->
            true.also {
                pendingFocusEntryName.remove(current.id)
                updateTab(current.id) {
                    it.selectComputerEntry(intent.entryId, intent.additive, intent.range)
                }
            }
        is PaneIntent.SelectEntries ->
            true.also {
                pendingFocusEntryName.remove(current.id)
                updateTab(current.id) {
                    it.withSelectedEntryIds(intent.entryIds.intersect(ids.toSet()))
                }
            }
        is PaneIntent.MoveSelection ->
            true.also {
                pendingFocusEntryName.remove(current.id)
                updateTab(current.id) {
                    it.moveComputerSelection(intent.offset, intent.extendSelection)
                }
            }
        PaneIntent.SelectAll ->
            true.also {
                pendingFocusEntryName.remove(current.id)
                updateTab(current.id) { it.withSelectedEntryIds(ids.toSet()) }
            }
        else -> false
    }
}

/**
 * 直接从卷根向上进入概览时定位原设备；只消费当前访问的请求，用户主动选择后不再抢焦点。
 *
 * @param tabId 发起向上导航的标签身份。
 * @param visitId 首次设备结果所属访问身份。
 */
internal fun DefaultPaneComponent.focusComputerRoot(tabId: String, visitId: Long) {
    val current =
        activeTab()?.takeIf {
            it.id == tabId && it.tabState.browserState.visitId == visitId
        } ?: return
    val computer = current.entriesState as? ComputerEntriesState ?: return
    val location = pendingFocusEntryName.remove(tabId) ?: return
    val device =
        computer.devices.firstOrNull { device ->
            device.locations.any { pathService.normalizeLocation(it) == location }
        } ?: return
    updateTab(tabId) {
        it.selectComputerEntry(device.id, false, false).withTabState { state ->
            state.copy(pendingScrollToEntryId = device.id)
        }
    }
}

/**
 * 打开当前概览焦点位置，目录与设备分别通过各自统一入口处理。
 *
 * @param newTab 是否在新标签打开。
 */
internal fun DefaultPaneComponent.openComputerSelection(newTab: Boolean) {
    val current = activeTab() ?: return
    val id = current.selectionFocusId ?: current.selectedEntryIds.singleOrNull() ?: return
    val place =
        (current.entriesState as? ComputerEntriesState)?.places?.firstOrNull { it.location == id }
    if (place != null) {
        if (newTab) createTab(place.location) else openDirectory(place.location)
    } else performDeviceAction(id, newTab = newTab)
}
