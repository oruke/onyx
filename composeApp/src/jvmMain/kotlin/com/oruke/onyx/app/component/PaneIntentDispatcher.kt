package com.oruke.onyx.app.component

/**
 * 按职责分类派发面板 intent，避免单个总路由随功能增长失控。
 *
 * @param intent 待处理 intent。
 */
internal fun DefaultPaneComponent.dispatchPaneIntent(intent: PaneIntent) {
    when {
        dispatchNavigationIntent(intent) -> Unit
        dispatchPresentationIntent(intent) -> Unit
        dispatchSelectionIntent(intent) -> Unit
        dispatchEditIntent(intent) -> Unit
        dispatchTabIntent(intent) -> Unit
        else -> error("Unsupported pane intent: $intent")
    }
}

/**
 * 派发目录导航与条目打开 intent。
 *
 * @param intent 待处理 intent。
 * @return 已处理时返回 `true`。
 */
private fun DefaultPaneComponent.dispatchNavigationIntent(intent: PaneIntent): Boolean {
    return when (intent) {
        PaneIntent.Refresh -> true.also { refresh() }
        PaneIntent.GoBack -> true.also { goBack() }
        PaneIntent.GoForward -> true.also { goForward() }
        PaneIntent.GoUp -> true.also { goUp() }
        is PaneIntent.OpenDirectory -> true.also { openDirectory(intent.location) }
        is PaneIntent.OpenEntry -> true.also { openEntry(intent.entry) }
        is PaneIntent.ToggleInlineExpand -> true.also { toggleInlineExpand(intent.directoryLocation) }
        else -> false
    }
}

/**
 * 派发视图、过滤、排序与反馈 intent。
 *
 * @param intent 待处理 intent。
 * @return 已处理时返回 `true`。
 */
private fun DefaultPaneComponent.dispatchPresentationIntent(intent: PaneIntent): Boolean {
    return when (intent) {
        is PaneIntent.SetViewMode -> { setViewMode(intent.mode); true }
        is PaneIntent.SetFilterQuery -> { setFilterQuery(intent.query); true }
        PaneIntent.ShowFilterInput -> { showFilterInput(); true }
        is PaneIntent.HideFilterInput -> { hideFilterInput(intent.clearQuery); true }
        PaneIntent.ShowCommandPalette -> { showCommandPalette(); true }
        PaneIntent.HideCommandPalette -> { hideCommandPalette(); true }
        is PaneIntent.ToggleSort -> { toggleSort(intent.column); true }
        PaneIntent.ToggleHiddenItems -> { toggleHiddenItems(); true }
        is PaneIntent.ToggleColumnVisibility -> { toggleColumnVisibility(intent.column); true }
        is PaneIntent.SetGalleryItemSize -> { setGalleryItemSize(intent.sizeDp); true }
        is PaneIntent.ResizeDetailsColumn -> {
            resizeDetailsColumn(intent.column, intent.nextColumn, intent.deltaWeight)
            true
        }
        PaneIntent.DismissOperationFeedback -> { dismissOperationFeedback(); true }
        is PaneIntent.ShowOperationFeedback -> { showOperationFeedback(intent.kind, intent.detail); true }
        PaneIntent.ConsumePendingScroll -> { consumePendingScroll(); true }
        else -> false
    }
}

/**
 * 派发选择和选择焦点 intent。
 *
 * @param intent 待处理 intent。
 * @return 已处理时返回 `true`。
 */
private fun DefaultPaneComponent.dispatchSelectionIntent(intent: PaneIntent): Boolean {
    return when (intent) {
        is PaneIntent.SelectEntry -> true.also { selectEntry(intent.entryId, intent.additive, intent.range) }
        is PaneIntent.SelectEntries -> true.also { selectEntries(intent.entryIds) }
        is PaneIntent.MoveSelection -> true.also { moveSelection(intent.offset, intent.extendSelection) }
        PaneIntent.OpenSelectedEntry -> true.also { openSelectedEntry() }
        PaneIntent.SelectAll -> true.also { selectAll() }
        PaneIntent.ClearSelection -> true.also { clearSelection() }
        else -> false
    }
}

/**
 * 派发内联编辑与路径复制 intent。
 *
 * @param intent 待处理 intent。
 * @return 已处理时返回 `true`。
 */
private fun DefaultPaneComponent.dispatchEditIntent(intent: PaneIntent): Boolean {
    return when (intent) {
        PaneIntent.BeginRename -> { beginRename(); true }
        PaneIntent.BeginCreateFile -> { beginCreateFile(); true }
        PaneIntent.BeginCreateDirectory -> { beginCreateDirectory(); true }
        PaneIntent.OpenSelectedInNewTab -> { openSelectedInNewTab(); true }
        PaneIntent.OpenSelectedInNewWindow -> { openSelectedInNewWindow(); true }
        PaneIntent.CopySelectedPaths -> { copySelectedPaths(); true }
        is PaneIntent.UpdateInlineEditDraft -> { updateInlineEditDraft(intent.draft); true }
        PaneIntent.ConfirmInlineEdit -> { confirmInlineEdit(); true }
        PaneIntent.CancelInlineEdit -> { cancelInlineEdit(); true }
        else -> false
    }
}


/**
 * 派发标签创建、切换、关闭与排序 intent。
 *
 * @param intent 待处理 intent。
 * @return 已处理时返回 `true`。
 */
private fun DefaultPaneComponent.dispatchTabIntent(intent: PaneIntent): Boolean {
    return when (intent) {
        is PaneIntent.CreateTab -> true.also { createTab(intent.location ?: state.value.location) }
        is PaneIntent.SelectTab -> true.also { selectTab(intent.tabId) }
        is PaneIntent.CloseTab -> true.also { closeTab(intent.tabId) }
        is PaneIntent.MoveTab -> true.also { moveTab(intent.tabId, intent.targetIndex) }
        else -> false
    }
}
