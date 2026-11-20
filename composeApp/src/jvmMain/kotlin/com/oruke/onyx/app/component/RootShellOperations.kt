package com.oruke.onyx.app.component

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.filesystem.isSystemVirtualLocation
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.SidebarSectionKey
import com.oruke.onyx.ui.mergeEditableSettingsFrom
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import kotlinx.coroutines.launch

/**
 * 当前生效的视觉与展示设置：当设置窗口处于打开状态时实时返回未提交的草稿，使主窗口与各附属视图同步预览；
 * 关闭或取消设置窗口时自动回退至已持久化的 [RootState.settings]。
 */
internal val RootState.effectiveSettings: OnyxSettings
    get() = (dialogState as? RootDialogState.Settings)?.draft ?: settings

/** 立即将当前各状态流快照同步到公开根状态，避免多层 combine 异步通道延迟导致首帧主题或对话框状态不同步。 */
internal fun DefaultRootComponent.syncImmediateRootState() {
    mutableState.value = initialRootState()
}

/**
 * 切换布局，单栏模式同时激活唯一可见的主面板。
 * @param mode 新布局模式。
 */
internal fun DefaultRootComponent.setLayoutMode(mode: PaneLayoutMode) {
    layoutMode.value = mode
    // 单栏只渲染主面板，不能让视图切换和快捷键继续作用于已隐藏的次面板。
    if (mode == PaneLayoutMode.SINGLE) activePane.value = PaneId.PRIMARY
    syncImmediateRootState()
}

/** @param fraction 新面板分割比例。 */
internal fun DefaultRootComponent.setPaneSplitFraction(fraction: Float) {
    paneSplitFraction.value = fraction.coerceIn(MIN_PANE_SPLIT_FRACTION, MAX_PANE_SPLIT_FRACTION)
    syncImmediateRootState()
}

/** 打开设置对话框，同时保存打开瞬间的基准快照供脏态判断与三路字段合并。 */
internal fun DefaultRootComponent.openSettings() {
    val snapshot = settings.value
    dialogState.value = RootDialogState.Settings(draft = snapshot, initialDraft = snapshot)
    syncImmediateRootState()
}

/** @param draft 最新设置草稿。 */
internal fun DefaultRootComponent.updateSettingsDraft(draft: OnyxSettings) {
    val currentDialog = dialogState.value as? RootDialogState.Settings ?: return
    dialogState.value = currentDialog.copy(draft = draft)
    syncImmediateRootState()
}

/** 清理设置草稿中已经失效的位置。 */
internal fun DefaultRootComponent.cleanupInvalidLocations() {
    val currentDialog = dialogState.value as? RootDialogState.Settings ?: return
    dialogState.value = currentDialog.copy(
        draft = currentDialog.draft.cleanupInvalidLocations(pathService::isLocationAvailable),
    )
    syncImmediateRootState()
}

/** @param paneId 待激活面板。 */
internal fun DefaultRootComponent.activatePane(paneId: PaneId) {
    activePane.value = paneId
    syncImmediateRootState()
}

/** @param currentSettings 待同步到排序器、诊断日志与新建标签默认值的设置。 */
internal fun syncGlobalSortAndTabDefaults(currentSettings: OnyxSettings) {
    com.oruke.onyx.app.component.delegate.EntrySorter.defaultNaturalSortEnabled = currentSettings.naturalSortEnabled
    com.oruke.onyx.app.component.delegate.EntrySorter.defaultDirectoriesFirst = currentSettings.directoriesFirst
    defaultShowHiddenItemsForNewTabs = currentSettings.defaultShowHiddenItems
    OnyxLogger.verboseEnabled = currentSettings.verboseLoggingEnabled
}

/** @param nextSettings 待应用设置。 */
internal fun DefaultRootComponent.updateSettings(nextSettings: OnyxSettings) {
    val previousSettings = settings.value
    val sanitizedSettings = nextSettings.sanitizeRootSettings()
    val settingsChanged = sanitizedSettings != previousSettings
    settings.value = sanitizedSettings
    syncGlobalSortAndTabDefaults(sanitizedSettings)
    if (sanitizedSettings.naturalSortEnabled != previousSettings.naturalSortEnabled ||
        sanitizedSettings.directoriesFirst != previousSettings.directoriesFirst
    ) {
        primaryPane.recomputeAllTabsSorting()
        secondaryPane.recomputeAllTabsSorting()
    }
    synchronizeS3ConnectionConfigurations()
    if (settingsChanged && sessionRestoreState.value is SessionRestoreState.Failed) {
        // 恢复失败时禁止默认状态自动落盘；用户明确修改设置后才恢复持久化。
        persistenceReady = true
    }
    syncImmediateRootState()
}

/** @param location 待打开 VFS 位置。 */
internal fun DefaultRootComponent.openLocationInActivePane(location: String) {
    paneComponent(activePane.value).openDirectory(location)
}

/**
 * 在活动面板的新标签页中打开指定位置。
 *
 * @param location 目标目录位置。
 */
internal fun DefaultRootComponent.openLocationInNewTab(location: String) {
    paneComponent(activePane.value).createTab(location)
}

/**
 * 复制指定目录或文件路径到系统剪贴板。
 *
 * @param location 目标路径文本。
 */
internal fun DefaultRootComponent.copyLocationPath(location: String) {
    scope.launch {
        textClipboardService.copyText(location)
    }
}

/**
 * 使用系统默认外部应用打开指定文件（压缩包内条目会先安全解压到临时文件后再交由外部应用打开）。
 *
 * @param entry 目标文件条目。
 */
internal fun DefaultRootComponent.openExternalFile(entry: com.oruke.onyx.core.model.VFile) {
    scope.launch {
        if (com.oruke.onyx.vfs.archive.ArchiveService.isArchiveLocation(entry.location)) {
            archiveEntryOpenService.openArchiveEntry(entry)
        } else {
            externalOpenService.open(entry)
        }
    }
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

/** 发出窗口级聚焦当前活动面板地址栏信号，仅递增当前活动面板专属计数避免切面板误重放。 */
internal fun DefaultRootComponent.requestFocusActivePaneAddressBar() {
    val targetPane = activePane.value
    val current = addressBarFocusRequests.value
    addressBarFocusRequests.value = current + (targetPane to ((current[targetPane] ?: 0) + 1))
    syncImmediateRootState()
}

/** 切换右侧预览面板可见性；关闭时立即停止可能正在运行的音频试听。 */
internal fun DefaultRootComponent.togglePreviewPane() {
    val nextVisible = !showPreviewPane.value
    showPreviewPane.value = nextVisible
    if (!nextVisible) {
        audioPreviewPlayer.stop()
    }
    syncImmediateRootState()
}

/** 显式关闭右侧预览面板并停止音频试听。 */
internal fun DefaultRootComponent.closePreviewPane() {
    if (!showPreviewPane.value) return
    showPreviewPane.value = false
    audioPreviewPlayer.stop()
    syncImmediateRootState()
}

/** 调整并持久化右侧预览面板宽度（自动约束在允许范围内）。 */
internal fun DefaultRootComponent.setPreviewPaneWidth(widthDp: Int) {
    val clamped = widthDp.coerceIn(MIN_PREVIEW_PANE_WIDTH_DP, MAX_PREVIEW_PANE_WIDTH_DP)
    if (settings.value.previewPaneWidthDp == clamped) return
    updateSettings(settings.value.copy(previewPaneWidthDp = clamped))
}

/** 切换并持久化右侧面板模式（预览 / 元数据）；切离预览时停止音频试听。 */
internal fun DefaultRootComponent.setPreviewPaneMode(mode: com.oruke.onyx.core.model.PreviewPaneMode) {
    if (mode != com.oruke.onyx.core.model.PreviewPaneMode.PREVIEW) {
        audioPreviewPlayer.stop()
    }
    if (settings.value.previewPaneMode == mode) return
    updateSettings(settings.value.copy(previewPaneMode = mode))
}

/** @param paneId 新建目录目标面板。 */
internal fun DefaultRootComponent.beginCreateDirectoriesInPane(paneId: PaneId) {
    val location = paneState(paneId).location
    if (isSystemVirtualLocation(location)) return
    dialogState.value = RootDialogState.CreateDirectories(
        paneId = paneId,
        location = location,
        draft = "",
    )
    syncImmediateRootState()
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
        is RootDialogState.TrashPermanentDeleteConfirmation -> confirmTrashPermanentDeleteDialog(currentDialog)
        is RootDialogState.EmptyTrashConfirmation -> confirmEmptyTrashDialog(currentDialog)
        is RootDialogState.CreateDirectories -> confirmCreateDirectoriesDialog(currentDialog)
        is RootDialogState.CreateZipArchive -> confirmCreateZipArchive(currentDialog)
        is RootDialogState.Settings -> {
            dialogState.value = null
            val merged = settings.value.mergeEditableSettingsFrom(
                draft = currentDialog.draft,
                initialDraft = currentDialog.initialDraft,
            )
            updateSettings(merged)
        }

        else -> Unit
    }
    if (dialogState.value == null) {
        archiveActionDelegate.restorePendingPasswordDialogIfIdle()
        syncImmediateRootState()
    }
}

/** 按当前对话框类型精准关闭弹窗与清理对应委托状态，避免覆盖并发归档密码队列或误杀无关请求。 */
internal fun DefaultRootComponent.dismissDialog() {
    when (dialogState.value) {
        is RootDialogState.ArchivePassword -> {
            // 仅取消队首密码请求，由 ArchiveActionDelegate 推进至队列下一项或队列清空时自动置 null。
            archiveActionDelegate.clearPending()
        }

        is RootDialogState.DeleteSelectionConfirmation -> {
            fileActionDelegate.clearPending()
            dialogState.value = null
            archiveActionDelegate.restorePendingPasswordDialogIfIdle()
        }

        is RootDialogState.ConflictResolution -> {
            fileTransferDelegate.clearPending()
            dialogState.value = null
            archiveActionDelegate.restorePendingPasswordDialogIfIdle()
        }

        is RootDialogState.RemoteConnections -> {
            remoteConnectionManager.cancelConnectionTest()
            dialogState.value = null
            archiveActionDelegate.restorePendingPasswordDialogIfIdle()
        }

        else -> {
            dialogState.value = null
            archiveActionDelegate.restorePendingPasswordDialogIfIdle()
        }
    }
    syncImmediateRootState()
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
    val validation = validateCreateDirectoriesDraft(dialog.draft)
    if (validation.error != null) {
        dialogState.value = dialog.copy(error = validation.error)
        return
    }
    dialogState.value = null
    fileActionDelegate.executeCreateDirectories(dialog.location, validation.paths)
}

/** 多行新建目录预校验结果。 */
internal data class CreateDirectoriesValidationResult(
    /** 校验通过且去重后的相对目录路径列表。 */
    val paths: List<String>,
    /** 校验失败错误类型；通过时为 `null`。 */
    val error: CreateDirectoriesDialogError?,
)

/** 禁止出现在新目录段名中的非法字符集。 */
private val ILLEGAL_DIRECTORY_SEGMENT_CHARS = setOf('<', '>', ':', '"', '|', '?', '*')

/**
 * 解析并全行预校验多行新建目录草稿，拦截绝对路径、`..`/`.` 穿越段及非法文件名字符。
 *
 * @param draft 多行目录文本。
 * @return 包含合法路径列表或表单错误类型的校验结果。
 */
internal fun validateCreateDirectoriesDraft(draft: String): CreateDirectoriesValidationResult {
    val rawLines = draft.lines().map(String::trim).filter(String::isNotBlank)
    if (rawLines.isEmpty()) {
        return CreateDirectoriesValidationResult(emptyList(), CreateDirectoriesDialogError.EMPTY_INPUT)
    }
    val validPaths = linkedSetOf<String>()
    for (line in rawLines) {
        if (!isSafeRelativeDirectoryPath(line)) {
            return CreateDirectoriesValidationResult(emptyList(), CreateDirectoriesDialogError.INVALID_PATH)
        }
        val normalized = line.replace('\\', '/').trimEnd('/')
        validPaths += normalized
    }
    return CreateDirectoriesValidationResult(validPaths.toList(), error = null)
}

/** 校验单行相对目录路径是否安全合法。 */
private fun isSafeRelativeDirectoryPath(rawPath: String): Boolean {
    if (rawPath.startsWith('/') || rawPath.startsWith('\\')) return false
    // 拦截 Windows 盘符绝对路径（如 C:\foo 或 C:foo）与协议前缀（如 smb://）。
    if (rawPath.length >= 2 && rawPath[0].isLetter() && rawPath[1] == ':') return false
    if ("://" in rawPath) return false
    val normalized = rawPath.replace('\\', '/').trimEnd('/')
    if (normalized.isEmpty()) return false
    val segments = normalized.split('/')
    return segments.all { segment ->
        segment.isNotBlank() &&
            segment != "." &&
            segment != ".." &&
            !segment.endsWith('.') &&
            segment.none { ch -> ch in ILLEGAL_DIRECTORY_SEGMENT_CHARS || ch.code < 0x20 }
    }
}
