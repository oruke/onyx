package com.oruke.onyx.app.component

import com.oruke.onyx.app.component.delegate.SelectionReducer
import com.oruke.onyx.core.model.PaneOperationFeedbackKind
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.shared.filesystem.toI18nMessage
import kotlinx.coroutines.launch

/** 开始重命名当前聚焦条目。 */
internal fun DefaultPaneComponent.beginRename() {
    val tab = activeTab() ?: return
    if (tab.inlineEditState != null) return
    val entries = currentVisibleEntries()
    val targetEntryId = SelectionReducer.resolveSelectionFocusId(
        entries,
        tab.selectionFocusId,
        tab.selectionAnchorId,
        tab.selectedEntryIds,
    ) ?: return
    val targetEntry = entries.firstOrNull { entry -> entry.id == targetEntryId } ?: return
    updateTab(tab.id) { currentTab -> currentTab.beginRenameInlineEdit(targetEntry) }
}

/** 开始在活动目录中内联新建文件。 */
internal fun DefaultPaneComponent.beginCreateFile() {
    val tab = activeTab() ?: return
    if (tab.inlineEditState != null) return
    scope.launch {
        val nextName = tab.nextCreateName(entryNameSuggestionService.newFileName())
        updateTab(tab.id) { currentTab -> currentTab.beginCreateFileInlineEdit(nextName) }
    }
}

/** 开始在活动目录中内联新建目录。 */
internal fun DefaultPaneComponent.beginCreateDirectory() {
    val tab = activeTab() ?: return
    if (tab.inlineEditState != null) return
    scope.launch {
        val nextName = tab.nextCreateName(entryNameSuggestionService.newDirectoryName())
        updateTab(tab.id) { currentTab -> currentTab.beginCreateDirectoryInlineEdit(nextName) }
    }
}

/**
 * 在新标签中打开当前聚焦目录。
 *
 * @return 无返回值。
 */
internal fun DefaultPaneComponent.openSelectedInNewTab() {
    val tab = activeTab() ?: return
    val targetEntry = selectedDirectoryForOpen() ?: return
    clearInlineEdit(tab.id)
    createTab(targetEntry.location)
}

/**
 * 在独立文件管理器窗口中打开当前聚焦目录。
 *
 * @return 无返回值。
 */
internal fun DefaultPaneComponent.openSelectedInNewWindow() {
    val tab = activeTab() ?: return
    val targetEntry = selectedDirectoryForOpen() ?: return
    clearInlineEdit(tab.id)
    onOpenDirectoryInNewWindow(targetEntry.location)
}

/**
 * 解析当前选择焦点对应的单个目录。
 *
 * @return 可在新标签或新窗口打开的目录；当前焦点不是目录时返回 `null`。
 */
private fun DefaultPaneComponent.selectedDirectoryForOpen(): VFile? {
    val tab = activeTab() ?: return null
    val entries = currentVisibleEntries()
    val targetEntryId = SelectionReducer.resolveSelectionFocusId(
        entries,
        tab.selectionFocusId,
        tab.selectionAnchorId,
        tab.selectedEntryIds,
    )
    return targetEntryId
        ?.let { id -> entries.firstOrNull { entry -> entry.id == id } }
        ?.takeIf(VFile::isBrowsableDirectory)
}

/** 将当前选中条目的 VFS 路径复制到系统文本剪贴板。 */
internal fun DefaultPaneComponent.copySelectedPaths() {
    val tab = activeTab() ?: return
    val entries = currentVisibleEntries().filter { entry -> entry.id in tab.selectedEntryIds }
    if (entries.isEmpty()) return
    scope.launch {
        textClipboardService.copyText(entries.joinToString(separator = "\n") { entry -> entry.location })
            .onSuccess { clearOperationFeedback(tab.id) }
            .onFailure { failure ->
                updateFailure(tab.id, PaneOperationFeedbackKind.COPY_PATH_FAILED, failure.toI18nMessage())
            }
    }
}

/**
 * 更新活动内联编辑草稿。
 *
 * @param draft 最新名称文本。
 */
internal fun DefaultPaneComponent.updateInlineEditDraft(draft: String) {
    val tab = activeTab() ?: return
    val inlineEditState = tab.inlineEditState
    if (inlineEditState == null || draft == inlineEditState.draftName) return
    updateTab(tab.id) { currentTab -> currentTab.withInlineEditDraft(draft) }
}

/** 校验并提交活动内联编辑操作。 */
internal fun DefaultPaneComponent.confirmInlineEdit() {
    val tab = activeTab() ?: return
    val result = tab.confirmInlineEditState(currentTabEntries(tab))
    if (result.tab != tab) updateTab(tab.id) { result.tab }
    executeInlineEditOperation(tab.id, result.operation)
}

/** 取消活动内联编辑。 */
internal fun DefaultPaneComponent.cancelInlineEdit() {
    activeTab()?.id?.let(::clearInlineEdit)
}

/**
 * 执行内联编辑产生的文件命令。
 *
 * @param tabId 当前标签 ID。
 * @param operation 待执行编辑命令。
 */
private fun DefaultPaneComponent.executeInlineEditOperation(tabId: String, operation: InlineEditOperation?) {
    when (operation) {
        is InlineEditOperation.Rename -> executeRename(tabId, operation)
        is InlineEditOperation.CreateFile -> executeCreateFile(tabId, operation)
        is InlineEditOperation.CreateDirectory -> executeCreateDirectory(tabId, operation)
        null -> Unit
    }
}

/**
 * 执行重命名命令并刷新标签。
 *
 * @param tabId 当前标签 ID。
 * @param operation 重命名操作。
 */
private fun DefaultPaneComponent.executeRename(tabId: String, operation: InlineEditOperation.Rename) {
    scope.launch {
        fileCommandService.rename(operation.entry, operation.targetName)
            .onSuccess { renamedEntry ->
                onFileRenamed(operation.entry, renamedEntry)
                clearOperationFeedback(tabId)
                refreshActiveTab(tabId)
            }
            .onFailure { failure ->
                updateFailure(tabId, PaneOperationFeedbackKind.RENAME_FAILED, failure.toI18nMessage())
            }
    }
}

/**
 * 执行新建文件命令并刷新标签。
 *
 * @param tabId 当前标签 ID。
 * @param operation 新建文件操作。
 */
private fun DefaultPaneComponent.executeCreateFile(tabId: String, operation: InlineEditOperation.CreateFile) {
    scope.launch {
        fileCommandService.createFile(operation.parentLocation, operation.name)
            .onSuccess {
                clearOperationFeedback(tabId)
                refreshActiveTab(tabId)
            }
            .onFailure { failure ->
                updateFailure(tabId, PaneOperationFeedbackKind.CREATE_FILE_FAILED, failure.toI18nMessage())
            }
    }
}

/**
 * 执行新建目录命令并刷新标签。
 *
 * @param tabId 当前标签 ID。
 * @param operation 新建目录操作。
 */
private fun DefaultPaneComponent.executeCreateDirectory(
    tabId: String,
    operation: InlineEditOperation.CreateDirectory,
) {
    scope.launch {
        fileCommandService.createDirectory(operation.parentLocation, operation.name)
            .onSuccess {
                clearOperationFeedback(tabId)
                refreshActiveTab(tabId)
            }
            .onFailure { failure ->
                updateFailure(tabId, PaneOperationFeedbackKind.CREATE_DIRECTORY_FAILED, failure.toI18nMessage())
            }
    }
}
