package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.PaneOperationFeedbackKind
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.vfs.archive.ArchiveService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 刷新活动标签目录。 */
internal fun DefaultPaneComponent.refresh() {
    val tab = activeTab() ?: return
    updateTab(tab.id) { currentTab -> currentTab.prepareForRefresh() }
    loadTab(tab.id, tab.location)
}

/** 导航到活动标签的上一条历史位置。 */
internal fun DefaultPaneComponent.goBack() {
    val tab = activeTab() ?: return
    val previousLocation = tab.backStack.lastOrNull() ?: return
    val currentDirName = locationBaseName(tab.location)
    if (currentDirName != null && pathService.isDirectParent(previousLocation, tab.location)) {
        pendingFocusEntryName[tab.id] = currentDirName
    }
    updateTab(tab.id) { currentTab ->
        currentTab.navigateBackState(previousTitle = pathService.title(previousLocation))
    }
    loadTab(tab.id, previousLocation)
}

/** 导航到活动标签的下一条历史位置。 */
internal fun DefaultPaneComponent.goForward() {
    val tab = activeTab() ?: return
    val nextLocation = tab.forwardStack.lastOrNull() ?: return
    updateTab(tab.id) { currentTab ->
        currentTab.navigateForwardState(nextTitle = pathService.title(nextLocation))
    }
    loadTab(tab.id, nextLocation)
}

/** 导航到活动目录的父位置，并准备回焦当前目录。 */
internal fun DefaultPaneComponent.goUp() {
    val tab = activeTab() ?: return
    val parentLocation = pathService.parentLocation(tab.location) ?: return
    locationBaseName(tab.location)?.let { name -> pendingFocusEntryName[tab.id] = name }
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
 * 根据条目类型打开目录、压缩包、图片或外部程序。
 *
 * @param entry 待打开条目。
 */
internal fun DefaultPaneComponent.openEntry(entry: VFile) {
    val tab = activeTab() ?: return
    clearInlineEdit(tab.id)
    if (entry.kind == VFileKind.DIRECTORY) {
        openDirectory(entry.location)
        return
    }
    val insideArchive = ArchiveService.isArchiveLocation(entry.location)
    when {
        fileTypeService.isArchiveFileName(entry.name) && !insideArchive -> {
            openDirectory(ArchiveService.archiveLocation(entry.location))
        }
        fileTypeService.isArchiveFileName(entry.name) -> Unit
        onOpenImageViewer != null && fileTypeService.isImageFileName(entry.name) -> openImageEntry(entry)
        insideArchive -> openArchiveContentEntry(entry, tab.id)
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
 * 临时提取并打开压缩包内普通文件。
 *
 * @param entry 压缩包内条目。
 * @param tabId 当前标签 ID。
 */
@Suppress("TooGenericExceptionCaught")
private fun DefaultPaneComponent.openArchiveContentEntry(entry: VFile, tabId: String) {
    scope.launch {
        try {
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
    val tab = activeTab() ?: return
    val normalizedLocation = pathService.normalizeLocation(location)
    if (normalizedLocation == tab.location) {
        refresh()
        return
    }
    updateTab(tab.id) { currentTab ->
        currentTab.navigateToState(
            location = normalizedLocation,
            title = pathService.title(normalizedLocation),
            recordHistory = recordHistory,
        )
    }
    mutableState.value = mutableState.value.clearInlineExpandState()
    loadTab(tab.id, normalizedLocation)
    fileWatcherController.start(normalizedLocation)
}
