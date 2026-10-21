package com.oruke.onyx.app.component

/**
 * 派发设备专用操作与选择，阻止概览设备进入普通文件编辑或传输路径。
 *
 * @param intent 当前面板业务事件。
 * @return 已由概览处理或拒绝时返回 true。
 */
internal fun DefaultPaneComponent.dispatchComputerIntent(intent: PaneIntent): Boolean {
    val current = activeTab() ?: return intent is PaneDeviceIntent
    if (current.entriesState !is ComputerEntriesState) return intent is PaneDeviceIntent
    return when (intent) {
        is PaneDeviceIntent.Open ->
            true.also { performDeviceAction(intent.deviceId, newTab = intent.newTab) }
        is PaneDeviceIntent.Execute ->
            true.also { performDeviceAction(intent.deviceId, intent.action) }
        PaneIntent.OpenSelectedEntry,
        PaneIntent.OpenSelectedInNewTab -> {
            openComputerSelection(intent == PaneIntent.OpenSelectedInNewTab)
            true
        }
        PaneIntent.BeginRename,
        PaneIntent.BeginCreateFile,
        PaneIntent.BeginCreateDirectory,
        is PaneIntent.ToggleInlineExpand -> true
        else -> dispatchComputerSelectionIntent(current, intent)
    }
}
