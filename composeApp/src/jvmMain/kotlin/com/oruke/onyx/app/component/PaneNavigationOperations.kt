package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.PaneOperationFeedbackKind
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.vfs.archive.ArchiveService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 校验活动标签目录，并重试已降级的目录观察；已加载列表与浏览状态保持可用。 */
internal fun DefaultPaneComponent.refresh() {
    val tab = activeTab() ?: return
    if (com.oruke.onyx.app.filesystem.isComputerLocation(tab.location)) {
        loadTab(tab.id, tab.location)
        return
    }
    if (tabLoadJobs[tab.id]?.isActive == true) directories.refresh(tab.location)
    else loadTab(tab.id, tab.location)
}

/** 导航到活动标签的上一条历史位置。 */
internal fun DefaultPaneComponent.goBack() {
    val tab = captureActiveVisit() ?: return
    val previousLocation = tab.backStack.lastOrNull() ?: return
    val (restored, expansions) = navigationController.back(tab, mutableState.value.chromeState,
        pathService.title(previousLocation))
    pendingFocusEntryName.remove(tab.id)
    restoreExpansions(expansions)
    updateTab(tab.id) { restored }
    loadTab(tab.id, previousLocation)
}

/** 导航到活动标签的下一条历史位置。 */
internal fun DefaultPaneComponent.goForward() {
    val tab = captureActiveVisit() ?: return
    val nextLocation = tab.forwardStack.lastOrNull() ?: return
    val (restored, expansions) = navigationController.forward(tab, mutableState.value.chromeState,
        pathService.title(nextLocation))
    pendingFocusEntryName.remove(tab.id)
    restoreExpansions(expansions)
    updateTab(tab.id) { restored }
    loadTab(tab.id, nextLocation)
}

/** 导航到活动目录的父位置，并准备回焦当前目录。 */
internal fun DefaultPaneComponent.goUp() {
    val tab = activeTab() ?: return
    val parentLocation = pathService.navigationParent(tab.location) ?: return
    if (tab.backStack.lastOrNull() == parentLocation) {
        goBack()
        return
    }
    val focus = if (com.oruke.onyx.app.filesystem.isComputerLocation(parentLocation)) {
        pathService.normalizeLocation(tab.location)
    } else locationBaseName(tab.location)
    focus?.let { pendingFocusEntryName[tab.id] = it }
    navigateActiveTab(parentLocation, recordHistory = true)
}

/**
 * 打开指定目录位置。
 *
 * @param location 目标 VFS 位置。
 */
internal fun DefaultPaneComponent.openDirectory(location: String) {
    activeTab()?.let { tab ->
        pathService.directChildName(ancestor = location, descendant = tab.location)?.let { childName ->
            pendingFocusEntryName[tab.id] = childName
        }
    }
    navigateActiveTab(location, recordHistory = true)
}

/**
 * 打开指定目录，并在加载完成后聚焦选中指定名称的条目。
 *
 * @param location 目标目录 VFS 位置。
 * @param entryName 待聚焦条目的显示名称。
 */
internal fun DefaultPaneComponent.openDirectoryAndSelect(location: String, entryName: String) {
    activeTab()?.let { tab ->
        pendingFocusEntryName[tab.id] = entryName
    }
    navigateActiveTab(location, recordHistory = true)
}

/**
 * 根据条目类型打开目录、压缩包、图片或外部程序。
 *
 * @param entry 待打开条目。
 */
internal fun DefaultPaneComponent.openEntry(entry: VFile) {
    val tab = activeTab() ?: return
    clearInlineEdit(tab.id)
    if (entry.kind == VFileKind.DIRECTORY) {
        if (!entry.isBrowsableDirectory()) {
            updateFailure(
                tab.id,
                PaneOperationFeedbackKind.OPEN_FAILED,
                I18nMessage(MessageKey.MSG_DIRECTORY_UNAVAILABLE, entry.location),
            )
            return
        }
        openDirectory(entry.location)
        return
    }
    val insideArchive = ArchiveService.isArchiveLocation(entry.location)
    when {
        fileTypeService.isArchiveFileName(entry.name) && !insideArchive -> {
            openDirectory(ArchiveService.archiveLocation(entry.location))
            clearOperationFeedback(tab.id)
        }
        fileTypeService.isArchiveFileName(entry.name) -> Unit
        insideArchive && onOpenImageViewer != null && fileTypeService.isImageFileName(entry.name) -> {
            openArchiveImageEntry(entry, tab.id)
        }
        insideArchive -> openArchiveContentEntry(entry, tab.id)
        onOpenImageViewer != null && fileTypeService.isImageFileName(entry.name) -> openImageEntry(entry)
        else -> openExternalEntry(entry, tab.id)
    }
}

/**
 * 在图片查看器中打开图片并提供同目录图片序列。
 *
 * @param entry 待打开图片。
 */
private fun DefaultPaneComponent.openImageEntry(entry: VFile) {
    val allImages = currentVisibleEntries().filter { candidate ->
        candidate.kind == VFileKind.FILE && fileTypeService.isImageFileName(candidate.name)
    }
    onOpenImageViewer?.invoke(entry, allImages)
}

/**
 * 在图片查看器中打开压缩包内图片。
 *
 * 归档目录浏览不要求密码；只有真正读取图片内容前，才通过根组件复用解压密码对话框。
 *
 * @param entry 待打开的压缩包内图片。
 * @param tabId 当前标签 ID。
 * @return 无返回值。
 */
@Suppress("TooGenericExceptionCaught") // 组件边界需要将归档读取异常转换为面板可见失败状态。
private fun DefaultPaneComponent.openArchiveImageEntry(entry: VFile, tabId: String) {
    scope.launch {
        try {
            onPrepareArchiveContentAccess(entry)
            openImageEntry(entry)
            clearOperationFeedback(tabId)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            updateFailure(tabId, PaneOperationFeedbackKind.OPEN_FAILED, failure.toI18nMessage())
        }
    }
}

/**
 * 临时提取并打开压缩包内普通文件。
 *
 * 归档目录浏览不要求密码；只有真正读取文件内容前，才通过根组件复用解压密码对话框。
 *
 * @param entry 压缩包内条目。
 * @param tabId 当前标签 ID。
 */
@Suppress("TooGenericExceptionCaught")
private fun DefaultPaneComponent.openArchiveContentEntry(entry: VFile, tabId: String) {
    scope.launch {
        try {
            onPrepareArchiveContentAccess(entry)
            archiveEntryOpenService.openArchiveEntry(entry)
                .onSuccess { clearOperationFeedback(tabId) }
                .onFailure { failure ->
                    updateFailure(tabId, PaneOperationFeedbackKind.OPEN_FAILED, failure.toI18nMessage())
                }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            updateFailure(tabId, PaneOperationFeedbackKind.OPEN_FAILED, failure.toI18nMessage())
        }
    }
}

/**
 * 使用系统关联程序打开普通文件。
 *
 * @param entry 待打开文件。
 * @param tabId 当前标签 ID。
 */
private fun DefaultPaneComponent.openExternalEntry(entry: VFile, tabId: String) {
    scope.launch {
        externalOpenService.open(entry)
            .onSuccess { clearOperationFeedback(tabId) }
            .onFailure { failure ->
                updateFailure(tabId, PaneOperationFeedbackKind.OPEN_FAILED, failure.toI18nMessage())
            }
    }
}

/**
 * 导航活动标签并同步目录监听。
 *
 * @param location 目标 VFS 位置。
 * @param recordHistory 是否写入历史栈。
 */
private fun DefaultPaneComponent.navigateActiveTab(location: String, recordHistory: Boolean) {
    val tab = captureActiveVisit() ?: return
    val normalizedLocation = pathService.normalizeLocation(location)
    if (normalizedLocation == tab.location) {
        refresh()
        return
    }
    val destination = navigationController.open(tab, mutableState.value.chromeState,
        normalizedLocation, pathService.title(normalizedLocation), recordHistory)
    restoreExpansions(emptyMap())
    updateTab(tab.id) { destination }
    loadTab(tab.id, normalizedLocation)
}
