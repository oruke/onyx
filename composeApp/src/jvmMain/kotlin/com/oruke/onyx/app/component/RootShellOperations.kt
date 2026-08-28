package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.SidebarSectionKey
import com.oruke.onyx.vfs.api.TransferConflictStrategy

/** @param mode 新布局模式。 */
internal fun DefaultRootComponent.setLayoutMode(mode: PaneLayoutMode) {
    layoutMode.value = mode
}

/** @param fraction 新面板分割比例。 */
internal fun DefaultRootComponent.setPaneSplitFraction(fraction: Float) {
    paneSplitFraction.value = fraction.coerceIn(MIN_PANE_SPLIT_FRACTION, MAX_PANE_SPLIT_FRACTION)
}

/** 打开设置对话框。 */
internal fun DefaultRootComponent.openSettings() {
    dialogState.value = RootDialogState.Settings(settings.value)
}

/** @param draft 最新设置草稿。 */
internal fun DefaultRootComponent.updateSettingsDraft(draft: OnyxSettings) {
    val currentDialog = dialogState.value as? RootDialogState.Settings ?: return
    dialogState.value = currentDialog.copy(draft = draft)
}

/** 清理设置草稿中已经失效的位置。 */
internal fun DefaultRootComponent.cleanupInvalidLocations() {
    val currentDialog = dialogState.value as? RootDialogState.Settings ?: return
    dialogState.value = currentDialog.copy(
        draft = currentDialog.draft.cleanupInvalidLocations(pathService::isLocationAvailable),
    )
}

/** @param paneId 待激活面板。 */
internal fun DefaultRootComponent.activatePane(paneId: PaneId) {
    activePane.value = paneId
}

/** @param nextSettings 待应用设置。 */
internal fun DefaultRootComponent.updateSettings(nextSettings: OnyxSettings) {
    val sanitizedSettings = nextSettings.sanitizeRootSettings()
    val settingsChanged = sanitizedSettings != settings.value
    settings.value = sanitizedSettings
    synchronizeS3ConnectionConfigurations()
    if (settingsChanged && sessionRestoreState.value is SessionRestoreState.Failed) {
        // 恢复失败时禁止默认状态自动落盘；用户明确修改设置后才恢复持久化。
        persistenceReady = true
    }
}

/** @param location 待打开 VFS 位置。 */
internal fun DefaultRootComponent.openLocationInActivePane(location: String) {
    paneComponent(activePane.value).openDirectory(location)
}

/** @param location 待切换收藏状态的位置。 */
internal fun DefaultRootComponent.toggleFavoriteLocation(location: String) {
    val currentFavorites = settings.value.favoriteLocations
    val nextFavorites = if (location in currentFavorites) {
        currentFavorites.filterNot { favorite -> favorite == location }
    } else {
        currentFavorites + location
    }
    updateSettings(settings.value.copy(favoriteLocations = nextFavorites))
}

/** @param location 待切换展开状态的侧栏位置。 */
internal fun DefaultRootComponent.toggleSidebarTreeNode(location: String) {
    sidebarDelegate.toggleNode(location)
}

/** @param height 新搜索抽屉高度比例（0.2f–0.8f），越界时收敛到合法范围。 */
internal fun DefaultRootComponent.setSearchDrawerHeight(height: Float) {
    updateSettings(settings.value.copy(searchDrawerHeight = height))
}

/** @param height 新任务中心抽屉高度比例（0.15f–0.6f），越界时收敛到合法范围。 */
internal fun DefaultRootComponent.setJobsDrawerHeight(height: Float) {
    updateSettings(settings.value.copy(jobsDrawerHeight = height))
}

/** 清空搜索历史。 */
internal fun DefaultRootComponent.clearSearchHistory() {
    updateSettings(settings.value.copy(searchHistory = emptyList()))
}

/** @param query 成功执行的查询文本，记录到搜索历史。 */
internal fun DefaultRootComponent.recordSearchHistory(query: String) {
    val next = settings.value.recordSearchQuery(query)
    if (next != settings.value) updateSettings(next)
}

/** @param section 待切换折叠状态的侧边栏分组。 */
internal fun DefaultRootComponent.toggleSidebarSection(section: SidebarSectionKey) {
    val collapsed = settings.value.collapsedSidebarSections
    val nextCollapsed = if (section in collapsed) collapsed - section else collapsed + section
    updateSettings(settings.value.copy(collapsedSidebarSections = nextCollapsed))
}

/** @param location 待重试加载的侧栏位置。 */
internal fun DefaultRootComponent.retrySidebarTreeNode(location: String) {
    sidebarDelegate.retryNode(location)
}

/**
 * 跨面板移动或在面板内重排标签。
 *
 * @param sourcePaneId 来源面板。
 * @param tabId 标签 ID。
 * @param targetPaneId 目标面板。
 * @param targetIndex 目标索引。
 */
internal fun DefaultRootComponent.moveTab(
    sourcePaneId: PaneId,
    tabId: String,
    targetPaneId: PaneId,
    targetIndex: Int,
) {
    if (sourcePaneId == targetPaneId) {
        paneComponent(sourcePaneId).moveTab(tabId, targetIndex)
        activatePane(targetPaneId)
        return
    }
    val tabSnapshot = paneComponent(sourcePaneId).detachTab(tabId) ?: return
    paneComponent(targetPaneId).attachTab(tabSnapshot, targetIndex)
    activatePane(targetPaneId)
}

/** 刷新活动面板。 */
internal fun DefaultRootComponent.refreshActivePane() {
    paneComponent(activePane.value).refresh()
}

/** 切换右侧预览面板可见性。 */
internal fun DefaultRootComponent.togglePreviewPane() {
    showPreviewPane.value = !showPreviewPane.value
}

/** @param paneId 新建目录目标面板。 */
internal fun DefaultRootComponent.beginCreateDirectoriesInPane(paneId: PaneId) {
    dialogState.value = RootDialogState.CreateDirectories(
        paneId = paneId,
        location = paneState(paneId).location,
        draft = "",
    )
}

/** @param draft 多目录路径草稿。 */
internal fun DefaultRootComponent.updateCreateDirectoriesDraft(draft: String) {
    val currentDialog = dialogState.value as? RootDialogState.CreateDirectories ?: return
    dialogState.value = currentDialog.copy(draft = draft, error = null)
}

/** 根据当前对话框类型提交删除、新建目录、创建 ZIP 或设置操作。 */
internal fun DefaultRootComponent.confirmDialog() {
    when (val currentDialog = dialogState.value) {
        is RootDialogState.DeleteSelectionConfirmation -> confirmDeleteDialog()
        is RootDialogState.CreateDirectories -> confirmCreateDirectoriesDialog(currentDialog)
        is RootDialogState.CreateZipArchive -> confirmCreateZipArchive(currentDialog)
        is RootDialogState.Settings -> {
            dialogState.value = null
            updateSettings(currentDialog.draft)
        }
        else -> Unit
    }
}

/** 关闭当前对话框并清除各委托的待处理请求。 */
internal fun DefaultRootComponent.dismissDialog() {
    fileActionDelegate.clearPending()
    fileTransferDelegate.clearPending()
    archiveActionDelegate.clearPending()
    dialogState.value = null
}

/**
 * 解决当前传输冲突。
 *
 * @param strategy 冲突策略。
 * @param applyToAll 是否应用到后续全部冲突。
 */
internal fun DefaultRootComponent.resolveConflict(strategy: TransferConflictStrategy, applyToAll: Boolean) {
    fileTransferDelegate.resolveConflict(strategy, applyToAll)
}

/** 提交待确认删除请求。 */
private fun DefaultRootComponent.confirmDeleteDialog() {
    dialogState.value = null
    val request = fileActionDelegate.pendingDeleteRequest ?: return
    fileActionDelegate.clearPending()
    fileActionDelegate.executeDeleteRequest(request)
}

/** @param dialog 新建多目录对话框。 */
private fun DefaultRootComponent.confirmCreateDirectoriesDialog(dialog: RootDialogState.CreateDirectories) {
    val directoryPaths = parseDirectoryDraft(dialog.draft)
    if (directoryPaths.isEmpty()) {
        dialogState.value = dialog.copy(error = CreateDirectoriesDialogError.EMPTY_INPUT)
        return
    }
    dialogState.value = null
    fileActionDelegate.executeCreateDirectories(dialog.paneId, dialog.location, directoryPaths)
}

/**
 * 解析多行目录草稿并去重。
 *
 * @param draft 多行目录文本。
 * @return 非空目录路径。
 */
private fun parseDirectoryDraft(draft: String): List<String> {
    return draft.lines().map(String::trim).filter(String::isNotBlank).distinct()
}
