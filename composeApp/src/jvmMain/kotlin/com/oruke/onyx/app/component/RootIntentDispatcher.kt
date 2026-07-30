package com.oruke.onyx.app.component

/**
 * 按职责分类派发根组件 intent。
 *
 * @param intent 待处理 intent。
 */
internal fun DefaultRootComponent.dispatchRootIntent(intent: RootIntent) {
    when {
        dispatchRootSettingsIntent(intent) -> Unit
        dispatchRootRemoteIntent(intent) -> Unit
        dispatchRootPaneIntent(intent) -> Unit
        dispatchRootDialogIntent(intent) -> Unit
        dispatchRootSearchIntent(intent) -> Unit
        dispatchRootTransferIntent(intent) -> Unit
        dispatchRootArchiveIntent(intent) -> Unit
        dispatchRootTaskIntent(intent) -> Unit
        dispatchRootImageIntent(intent) -> Unit
        dispatchRootPlatformIntent(intent) -> Unit
        else -> error("Unsupported root intent: $intent")
    }
}

/**
 * 派发布局与设置 intent。
 *
 * @param intent 待处理 intent。
 * @return 已处理时返回 `true`。
 */
private fun DefaultRootComponent.dispatchRootSettingsIntent(intent: RootIntent): Boolean {
    return when (intent) {
        is RootIntent.SetLayoutMode -> { setLayoutMode(intent.mode); true }
        is RootIntent.SetPaneSplitFraction -> { setPaneSplitFraction(intent.fraction); true }
        RootIntent.OpenSettings -> { openSettings(); true }
        is RootIntent.UpdateSettingsDraft -> { updateSettingsDraft(intent.draft); true }
        RootIntent.CleanupInvalidLocations -> { cleanupInvalidLocations(); true }
        is RootIntent.UpdateSettings -> { updateSettings(intent.settings); true }
        is RootIntent.ToggleFavoriteLocation -> { toggleFavoriteLocation(intent.location); true }
        RootIntent.TogglePreviewPane -> { togglePreviewPane(); true }
        else -> false
    }
}

/**
 * 派发远程连接配置和认证 intent。
 *
 * @param intent 待处理 intent。
 * @return 已处理时返回 `true`。
 */
private fun DefaultRootComponent.dispatchRootRemoteIntent(intent: RootIntent): Boolean {
    return when (intent) {
        RootIntent.OpenRemoteConnections -> { remoteConnectionManager.openRemoteConnections(); true }
        is RootIntent.UpdateRemoteConnectionDraft -> {
            remoteConnectionManager.updateRemoteConnectionDraft(intent.draft); true
        }
        is RootIntent.EditRemoteConnection -> { remoteConnectionManager.editRemoteConnection(intent.profile); true }
        RootIntent.NewRemoteConnection -> { remoteConnectionManager.newRemoteConnection(); true }
        RootIntent.SaveRemoteConnectionDraft -> { remoteConnectionManager.saveRemoteConnectionDraft(); true }
        RootIntent.TestRemoteConnectionDraft -> { remoteConnectionManager.testRemoteConnectionDraft(); true }
        is RootIntent.DeleteRemoteConnection -> { remoteConnectionManager.deleteRemoteConnection(intent.id); true }
        is RootIntent.OpenRemoteConnection -> { remoteConnectionManager.openRemoteConnection(intent.location); true }
        is RootIntent.UpdateRemoteCredentialsDraft -> {
            remoteConnectionManager.updateRemoteCredentialsDraft(intent.draft); true
        }
        RootIntent.SubmitRemoteCredentials -> { remoteConnectionManager.submitRemoteCredentials(); true }
        else -> false
    }
}

/**
 * 派发面板激活、位置、侧栏和标签 intent。
 *
 * @param intent 待处理 intent。
 * @return 已处理时返回 `true`。
 */
private fun DefaultRootComponent.dispatchRootPaneIntent(intent: RootIntent): Boolean {
    return when (intent) {
        is RootIntent.ActivatePane -> { activatePane(intent.paneId); true }
        is RootIntent.OpenLocationInActivePane -> { openLocationInActivePane(intent.location); true }
        is RootIntent.ToggleSidebarTreeNode -> { toggleSidebarTreeNode(intent.location); true }
        is RootIntent.RetrySidebarTreeNode -> { retrySidebarTreeNode(intent.location); true }
        is RootIntent.MoveTab -> {
            moveTab(intent.sourcePaneId, intent.tabId, intent.targetPaneId, intent.targetIndex); true
        }
        RootIntent.RefreshActivePane -> { refreshActivePane(); true }
        else -> false
    }
}

/**
 * 派发根对话框 intent。
 *
 * @param intent 待处理 intent。
 * @return 已处理时返回 `true`。
 */
private fun DefaultRootComponent.dispatchRootDialogIntent(intent: RootIntent): Boolean {
    return when (intent) {
        is RootIntent.BeginCreateDirectoriesInPane -> { beginCreateDirectoriesInPane(intent.paneId); true }
        is RootIntent.UpdateCreateDirectoriesDraft -> { updateCreateDirectoriesDraft(intent.draft); true }
        RootIntent.ConfirmDialog -> { confirmDialog(); true }
        RootIntent.DismissDialog -> { dismissDialog(); true }
        is RootIntent.ResolveConflict -> { resolveConflict(intent.strategy, intent.applyToAll); true }
        else -> false
    }
}

/**
 * 派发搜索面板 intent。
 *
 * @param intent 待处理 intent。
 * @return 已处理时返回 `true`。
 */
private fun DefaultRootComponent.dispatchRootSearchIntent(intent: RootIntent): Boolean {
    return when (intent) {
        RootIntent.ShowSearchPanel -> { searchDelegate.showSearchPanel(); true }
        RootIntent.CloseSearchPanel -> { searchDelegate.closeSearchPanel(); true }
        is RootIntent.UpdateSearchQuery -> { searchDelegate.updateSearchQuery(intent.query); true }
        RootIntent.ExecuteSearch -> { searchDelegate.executeSearch(); true }
        RootIntent.CancelSearch -> { searchDelegate.cancelSearch(); true }
        is RootIntent.OpenSearchResult -> { searchDelegate.openSearchResult(intent.entry); true }
        RootIntent.OpenSearchResultsAsCollection -> { searchDelegate.openSearchResultsAsCollection(); true }
        else -> false
    }
}

/**
 * 派发复制、移动、粘贴、删除和历史 intent。
 *
 * @param intent 待处理 intent。
 * @return 已处理时返回 `true`。
 */
private fun DefaultRootComponent.dispatchRootTransferIntent(intent: RootIntent): Boolean {
    return when (intent) {
        is RootIntent.StageCopySelectedInPane -> { stageCopySelectedInPane(intent.paneId); true }
        is RootIntent.StageCutSelectedInPane -> { stageCutSelectedInPane(intent.paneId); true }
        is RootIntent.RequestPasteIntoPane -> { requestPasteIntoPane(intent.paneId); true }
        is RootIntent.RequestTransferSelectedToDirectory -> {
            requestTransferSelectedToDirectory(
                intent.sourcePaneId,
                intent.targetDirectoryLocation,
                intent.operation,
            )
            true
        }
        is RootIntent.RequestTransferSourceToDestination -> {
            fileOperationHistoryFacade.requestTransferSourceToDestination(intent.operation); true
        }
        is RootIntent.RequestDeleteSelectedInPane -> { requestDeleteSelectedInPane(intent.paneId); true }
        RootIntent.UndoLastFileOperation -> { fileOperationHistoryFacade.undoLastFileOperation(); true }
        RootIntent.RedoLastFileOperation -> { fileOperationHistoryFacade.redoLastFileOperation(); true }
        else -> false
    }
}

/**
 * 派发压缩包和批量重命名 intent。
 *
 * @param intent 待处理 intent。
 * @return 已处理时返回 `true`。
 */
private fun DefaultRootComponent.dispatchRootArchiveIntent(intent: RootIntent): Boolean {
    return when (intent) {
        is RootIntent.ExtractSelectedInPane -> { extractSelectedInPane(intent.paneId); true }
        is RootIntent.ExtractToDirectoryInPane -> { extractToDirectoryInPane(intent.paneId); true }
        is RootIntent.ExtractSmartInPane -> { extractSmartInPane(intent.paneId); true }
        is RootIntent.BeginCreateZipArchiveInPane -> { beginCreateZipArchiveInPane(intent.paneId); true }
        is RootIntent.UpdateZipArchiveNameDraft -> { updateZipArchiveNameDraft(intent.draft); true }
        is RootIntent.SubmitArchivePassword -> { submitArchivePassword(intent.password); true }
        is RootIntent.BatchRenameInPane -> { batchRenameInPane(intent.paneId); true }
        is RootIntent.ExecuteBatchRename -> { executeBatchRename(intent.paneId, intent.renameMap); true }
        is RootIntent.ResetBatchRenameForContinue -> { resetBatchRenameForContinue(intent.paneId); true }
        else -> false
    }
}

/**
 * 派发后台任务 intent。
 *
 * @param intent 待处理 intent。
 * @return 已处理时返回 `true`。
 */
private fun DefaultRootComponent.dispatchRootTaskIntent(intent: RootIntent): Boolean {
    return when (intent) {
        is RootIntent.DismissTask -> { taskOrchestrator.dismissTask(intent.taskId); true }
        is RootIntent.CancelTask -> { taskOrchestrator.cancelTask(intent.taskId); true }
        is RootIntent.PauseTask -> { taskOrchestrator.pauseTask(intent.taskId); true }
        is RootIntent.ResumeTask -> { taskOrchestrator.resumeTask(intent.taskId); true }
        is RootIntent.RetryTask -> { taskOrchestrator.retryTask(intent.taskId); true }
        RootIntent.ClearAllTasks -> { taskOrchestrator.clearAllTasks(); true }
        else -> false
    }
}

/**
 * 派发图片查看器 intent。
 *
 * @param intent 待处理 intent。
 * @return 已处理时返回 `true`。
 */
private fun DefaultRootComponent.dispatchRootImageIntent(intent: RootIntent): Boolean {
    return when (intent) {
        is RootIntent.OpenImageViewer -> { openImageViewer(intent.file, intent.allImages); true }
        RootIntent.CloseImageViewer -> { imageViewerController.close(); true }
        RootIntent.ImageViewerNext -> { imageViewerController.next(); true }
        RootIntent.ImageViewerPrevious -> { imageViewerController.previous(); true }
        is RootIntent.ImageViewerSetZoom -> { imageViewerController.setZoom(intent.factor); true }
        is RootIntent.ImageViewerSetFitMode -> { imageViewerController.setFitMode(intent.mode); true }
        is RootIntent.ImageViewerRotate -> { imageViewerController.rotate(intent.clockwise); true }
        else -> false
    }
}

/**
 * 派发打开方式、系统菜单和终端 intent。
 *
 * @param intent 待处理 intent。
 * @return 已处理时返回 `true`。
 */
private fun DefaultRootComponent.dispatchRootPlatformIntent(intent: RootIntent): Boolean {
    return when (intent) {
        is RootIntent.OpenWithApp -> { openWithApp(intent.entry, intent.app); true }
        is RootIntent.OpenWithChooser -> { openWithChooser(intent.entry); true }
        is RootIntent.ExecuteSystemMenuAction -> {
            executeSystemMenuAction(intent.action, intent.entries); true
        }
        is RootIntent.ExecuteFileContextMenuCommand -> {
            executeFileContextMenuCommand(intent.command, intent.entries); true
        }
        is RootIntent.OpenTerminalAt -> { openTerminalAt(intent.location); true }
        else -> false
    }
}
