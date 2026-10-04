package com.oruke.onyx.app.component

/**
 * 按职责分类派发根组件 intent。
 *
 * @param intent 待处理 intent。
 */
internal fun DefaultRootComponent.dispatchRootIntent(intent: RootIntent) {
    when {
        intent is RootIntent.UpdateWindowSize -> updateWindowSize(intent)
        intent is RootIntent.ExecuteUserCommand -> executeUserCommand(intent)
        dispatchBatchRenameDraftIntent(intent) -> Unit
        dispatchRootSettingsIntent(intent) -> Unit
        dispatchRootRemoteIntent(intent) -> Unit
        dispatchRootPaneIntent(intent) -> Unit
        dispatchRootDialogIntent(intent) -> Unit
        dispatchRootSearchIntent(intent) -> Unit
        dispatchRootQuickOpenIntent(intent) -> Unit
        dispatchRootSearchSettingsIntent(intent) -> Unit
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
        is RootIntent.SetLayoutMode -> {
            setLayoutMode(intent.mode); true
        }

        is RootIntent.SetPaneSplitFraction -> {
            setPaneSplitFraction(intent.fraction); true
        }

        RootIntent.OpenSettings -> {
            openSettings(); true
        }

        is RootIntent.UpdateSettingsDraft -> {
            updateSettingsDraft(intent.draft); true
        }

        RootIntent.CleanupInvalidLocations -> {
            cleanupInvalidLocations(); true
        }

        is RootIntent.UpdateSettings -> {
            updateSettings(intent.settings); true
        }

        is RootIntent.ToggleFavoriteLocation -> {
            toggleFavoriteLocation(intent.location); true
        }

        is RootIntent.ToggleSidebarSection -> {
            toggleSidebarSection(intent.section); true
        }

        RootIntent.TogglePreviewPane -> {
            togglePreviewPane(); true
        }

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
        RootIntent.OpenRemoteConnections -> {
            remoteConnectionManager.openRemoteConnections(); true
        }

        is RootIntent.UpdateRemoteConnectionDraft -> {
            remoteConnectionManager.updateRemoteConnectionDraft(intent.draft); true
        }

        is RootIntent.EditRemoteConnection -> {
            remoteConnectionManager.editRemoteConnection(intent.profile); true
        }

        RootIntent.NewRemoteConnection -> {
            remoteConnectionManager.newRemoteConnection(); true
        }

        RootIntent.SaveRemoteConnectionDraft -> {
            remoteConnectionManager.saveRemoteConnectionDraft(); true
        }

        RootIntent.TestRemoteConnectionDraft -> {
            remoteConnectionManager.testRemoteConnectionDraft(); true
        }

        is RootIntent.DeleteRemoteConnection -> {
            remoteConnectionManager.deleteRemoteConnection(intent.id); true
        }

        is RootIntent.OpenRemoteConnection -> {
            remoteConnectionManager.openRemoteConnection(intent.location); true
        }

        is RootIntent.UpdateRemoteCredentialsDraft -> {
            remoteConnectionManager.updateRemoteCredentialsDraft(intent.draft); true
        }

        RootIntent.SubmitRemoteCredentials -> {
            remoteConnectionManager.submitRemoteCredentials(); true
        }

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
        is RootIntent.ActivatePane -> {
            activatePane(intent.paneId); true
        }

        is RootIntent.OpenLocationInActivePane -> {
            openLocationInActivePane(intent.location); true
        }

        is RootIntent.ToggleSidebarTreeNode -> {
            toggleSidebarTreeNode(intent.location); true
        }

        is RootIntent.RetrySidebarTreeNode -> {
            retrySidebarTreeNode(intent.location); true
        }

        is RootIntent.MoveTab -> {
            moveTab(intent.sourcePaneId, intent.tabId, intent.targetPaneId, intent.targetIndex); true
        }

        RootIntent.RefreshActivePane -> {
            refreshActivePane(); true
        }

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
        is RootIntent.BeginCreateDirectoriesInPane -> {
            beginCreateDirectoriesInPane(intent.paneId); true
        }

        is RootIntent.UpdateCreateDirectoriesDraft -> {
            updateCreateDirectoriesDraft(intent.draft); true
        }

        RootIntent.ConfirmDialog -> {
            confirmDialog(); true
        }

        RootIntent.DismissDialog -> {
            dismissDialog(); true
        }

        is RootIntent.ResolveConflict -> {
            resolveConflict(intent.strategy, intent.applyToAll); true
        }

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
        RootIntent.ShowSearchPanel -> {
            searchDelegate.showSearchPanel(); true
        }

        RootIntent.CloseSearchPanel -> {
            searchDelegate.closeSearchPanel(); true
        }

        is RootIntent.UpdateSearchQuery -> {
            searchDelegate.updateSearchQuery(intent.query); true
        }

        RootIntent.ExecuteSearch -> {
            searchDelegate.executeSearch(); true
        }

        RootIntent.CancelSearch -> {
            searchDelegate.cancelSearch(); true
        }

        is RootIntent.OpenSearchResult -> {
            searchDelegate.openSearchResult(intent.entry); true
        }

        RootIntent.OpenSearchResultsAsCollection -> {
            searchDelegate.openSearchResultsAsCollection(); true
        }

        is RootIntent.OpenSearchResultInFolder -> {
            searchDelegate.openSearchResultInFolder(intent.entry); true
        }

        is RootIntent.UpdateSearchScope -> {
            searchDelegate.updateSearchScope(intent.scope); true
        }

        is RootIntent.UpdateSearchFilters -> {
            searchDelegate.updateSearchFilters(intent.filters); true
        }

        else -> false
    }
}

/**
 * 派发独立搜索窗口交互状态与搜索历史设置 intent。
 *
 * @param intent 待处理 intent。
 * @return 已处理时返回 `true`。
 */
private fun DefaultRootComponent.dispatchRootSearchSettingsIntent(intent: RootIntent): Boolean {
    return when (intent) {
        is RootIntent.SetSearchSelectedIndex -> {
            searchDelegate.setSelectedIndex(intent.index); true
        }

        is RootIntent.SetSearchAdvancedFiltersExpanded -> {
            searchDelegate.setAdvancedFiltersExpanded(intent.expanded)
            true
        }

        is RootIntent.SetSearchIncludeRemoteLocations -> {
            searchDelegate.setIncludeRemoteLocations(intent.include)
            true
        }

        RootIntent.UseCurrentDirectoryForSearch -> {
            searchDelegate.useCurrentDirectory(); true
        }

        RootIntent.ClearSearchHistory -> {
            clearSearchHistory(); true
        }

        else -> false
    }
}

/**
 * 仅向快速打开会话分发其查询和导航事件。
 *
 * @param intent 快速打开专用意图。
 * @return 是否已处理该意图。
 */
private fun DefaultRootComponent.dispatchRootQuickOpenIntent(intent: RootIntent): Boolean {
    return when (intent) {
        RootIntent.ShowQuickOpen -> {
            quickOpenDelegate.showQuickOpen(); true
        }

        RootIntent.CloseQuickOpen -> {
            quickOpenDelegate.closeSearchPanel(); true
        }

        is RootIntent.UpdateQuickOpenQuery -> {
            quickOpenDelegate.updateSearchQuery(intent.query); true
        }

        RootIntent.ExecuteQuickOpen -> {
            quickOpenDelegate.executeSearch(); true
        }

        is RootIntent.OpenQuickOpenResult -> {
            quickOpenDelegate.openSearchResult(intent.entry); true
        }

        is RootIntent.OpenQuickOpenResultInFolder -> {
            quickOpenDelegate.openSearchResultInFolder(intent.entry); true
        }

        is RootIntent.SetQuickOpenSelectedIndex -> {
            quickOpenDelegate.setSelectedIndex(intent.index); true
        }

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
        is RootIntent.StageCopySelectedInPane -> {
            stageCopySelectedInPane(intent.paneId); true
        }

        is RootIntent.StageCutSelectedInPane -> {
            stageCutSelectedInPane(intent.paneId); true
        }

        is RootIntent.RequestPasteIntoPane -> {
            requestPasteIntoPane(intent.paneId); true
        }

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

        is RootIntent.RequestDeleteSelectedInPane -> {
            requestDeleteSelectedInPane(intent.paneId); true
        }

        RootIntent.UndoLastFileOperation -> {
            fileOperationHistoryFacade.undoLastFileOperation(); true
        }

        RootIntent.RedoLastFileOperation -> {
            fileOperationHistoryFacade.redoLastFileOperation(); true
        }

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
        is RootIntent.ExtractSelectedInPane -> {
            extractSelectedInPane(intent.paneId); true
        }

        is RootIntent.ExtractToDirectoryInPane -> {
            extractToDirectoryInPane(intent.paneId); true
        }

        is RootIntent.ExtractSmartInPane -> {
            extractSmartInPane(intent.paneId); true
        }

        is RootIntent.BeginCreateZipArchiveInPane -> {
            beginCreateZipArchiveInPane(intent.paneId); true
        }

        is RootIntent.UpdateZipArchiveNameDraft -> {
            updateZipArchiveNameDraft(intent.draft); true
        }

        is RootIntent.SubmitArchivePassword -> {
            submitArchivePassword(intent.password); true
        }

        is RootIntent.BatchRenameInPane -> {
            batchRenameInPane(intent.paneId); true
        }

        is RootIntent.ExecuteBatchRename -> {
            executeBatchRename(intent.paneId, intent.renameMap); true
        }

        is RootIntent.ResetBatchRenameForContinue -> {
            resetBatchRenameForContinue(intent.paneId); true
        }

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
        is RootIntent.DismissTask -> {
            taskCenter.dispatch(TaskCenterIntent.Dismiss(intent.taskId)); true
        }

        is RootIntent.CancelTask -> {
            taskCenter.dispatch(TaskCenterIntent.Cancel(intent.taskId)); true
        }

        is RootIntent.PauseTask -> {
            taskCenter.dispatch(TaskCenterIntent.Pause(intent.taskId)); true
        }

        is RootIntent.ResumeTask -> {
            taskCenter.dispatch(TaskCenterIntent.Resume(intent.taskId)); true
        }

        is RootIntent.RetryTask -> {
            taskCenter.dispatch(TaskCenterIntent.Retry(intent.taskId)); true
        }

        RootIntent.ClearFinishedTasks -> {
            taskCenter.dispatch(TaskCenterIntent.ClearFinished); true
        }

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
        is RootIntent.OpenImageViewer -> {
            openImageViewer(intent.file, intent.allImages); true
        }

        RootIntent.CloseImageViewer -> {
            imageViewerController.close(); true
        }

        RootIntent.ImageViewerNext -> {
            imageViewerController.next(); true
        }

        RootIntent.ImageViewerPrevious -> {
            imageViewerController.previous(); true
        }

        is RootIntent.ImageViewerSetZoom -> {
            imageViewerController.setZoom(intent.factor); true
        }

        is RootIntent.ImageViewerSetFitMode -> {
            imageViewerController.setFitMode(intent.mode); true
        }

        is RootIntent.ImageViewerRotate -> {
            imageViewerController.rotate(intent.clockwise); true
        }

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
        is RootIntent.OpenWithApp -> {
            openWithApp(intent.entry, intent.app); true
        }

        is RootIntent.OpenWithChooser -> {
            openWithChooser(intent.entry); true
        }

        is RootIntent.ExecuteSystemMenuAction -> {
            executeSystemMenuAction(intent.action, intent.entries); true
        }

        is RootIntent.ExecuteFileContextMenuCommand -> {
            executeFileContextMenuCommand(intent.command, intent.entries); true
        }

        is RootIntent.OpenTerminalAt -> {
            openTerminalAt(intent.location); true
        }

        else -> false
    }
}
