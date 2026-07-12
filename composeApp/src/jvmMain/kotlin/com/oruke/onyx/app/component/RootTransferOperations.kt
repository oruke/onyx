package com.oruke.onyx.app.component

import com.oruke.onyx.app.component.delegate.ClipboardManager
import com.oruke.onyx.app.component.delegate.FileActionDelegate
import com.oruke.onyx.core.model.DeleteMode
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.TrashMoveRecord
import com.oruke.onyx.vfs.archive.ArchiveService

/** @param paneId 复制来源面板。 */
internal fun DefaultRootComponent.stageCopySelectedInPane(paneId: PaneId) {
    clipboardManager.stageCopy(selectedEntriesInPane(paneId))
}

/** @param paneId 剪切来源面板。 */
internal fun DefaultRootComponent.stageCutSelectedInPane(paneId: PaneId) {
    clipboardManager.stageCut(selectedEntriesInPane(paneId))
}

/** @param paneId 粘贴目标面板。 */
internal fun DefaultRootComponent.requestPasteIntoPane(paneId: PaneId) {
    val payload = clipboardManager.consume() ?: return
    fileTransferDelegate.requestTransferEntriesToDirectory(
        entries = payload.entries,
        targetDirectoryLocation = paneState(paneId).location,
        operation = when (payload.operation) {
            ClipboardManager.ClipboardOperation.COPY -> FileTransferOperation.COPY
            ClipboardManager.ClipboardOperation.CUT -> FileTransferOperation.MOVE
        },
        clearClipboardOnSuccess = payload.operation == ClipboardManager.ClipboardOperation.CUT,
    )
}

/**
 * 将选中项传输到目录；压缩包内条目自动改为解压。
 *
 * @param sourcePaneId 来源面板。
 * @param targetDirectoryLocation 目标目录位置。
 * @param operation 复制或移动操作。
 */
internal fun DefaultRootComponent.requestTransferSelectedToDirectory(
    sourcePaneId: PaneId,
    targetDirectoryLocation: String,
    operation: FileTransferOperation,
) {
    val entries = selectedEntriesInPane(sourcePaneId)
    if (entries.isEmpty()) return
    val archiveEntries = entries.filter { entry -> ArchiveService.parseArchiveLocation(entry.location) != null }
    val localEntries = entries.filter { entry -> ArchiveService.parseArchiveLocation(entry.location) == null }
    if (archiveEntries.isNotEmpty()) {
        archiveActionDelegate.launchArchiveExtractToDirectory(archiveEntries, targetDirectoryLocation)
    }
    if (localEntries.isNotEmpty()) {
        fileTransferDelegate.requestTransferEntriesToDirectory(
            entries = localEntries,
            targetDirectoryLocation = targetDirectoryLocation,
            operation = operation,
            clearClipboardOnSuccess = false,
        )
    }
}

/** @param paneId 删除来源面板。 */
internal fun DefaultRootComponent.requestDeleteSelectedInPane(paneId: PaneId) {
    val selectedEntries = selectedEntriesInPane(paneId)
    if (selectedEntries.isEmpty()) return
    val moveToTrashPreferred = settings.value.deleteMode == DeleteMode.MOVE_TO_TRASH_PREFERRED
    val willMoveToTrash = moveToTrashPreferred && trashService.isSupported
    fileActionDelegate.setPendingDeleteRequest(
        FileActionDelegate.PendingDeleteRequest(paneId, selectedEntries, willMoveToTrash)
    )
    dialogState.value = RootDialogState.DeleteSelectionConfirmation(
        moveToTrash = willMoveToTrash,
        itemCount = selectedEntries.size,
        trashUnavailable = moveToTrashPreferred && !trashService.isSupported,
    )
}

/**
 * 记录单文件重命名历史。
 *
 * @param source 原条目。
 * @param renamed 重命名后条目。
 */
internal fun DefaultRootComponent.recordRenameOperation(source: VFile, renamed: VFile) {
    fileOperationHistoryFacade.recordRenameOperation(source, renamed)
}

/** @param renameMap 批量重命名原条目与目标名称。 */
internal fun DefaultRootComponent.recordBatchRenameOperation(renameMap: List<Pair<VFile, String>>) {
    fileOperationHistoryFacade.recordBatchRenameOperation(renameMap)
}

/**
 * 记录移动操作历史。
 *
 * @param entries 被移动条目。
 * @param targetDirectoryLocation 目标目录。
 */
internal fun DefaultRootComponent.recordMoveOperation(entries: List<VFile>, targetDirectoryLocation: String) {
    fileOperationHistoryFacade.recordMoveOperation(entries, targetDirectoryLocation)
}

/** @param records 回收站移动记录。 */
internal fun DefaultRootComponent.recordTrashDeleteOperation(records: List<TrashMoveRecord>) {
    fileOperationHistoryFacade.recordTrashDeleteOperation(records)
}

/** @param paneId 面板 ID。 @return 对应面板状态。 */
internal fun DefaultRootComponent.paneState(paneId: PaneId): PaneState {
    return when (paneId) {
        PaneId.PRIMARY -> primaryPane.state.value
        PaneId.SECONDARY -> secondaryPane.state.value
    }
}

/** @param paneId 面板 ID。 @return 对应面板组件。 */
internal fun DefaultRootComponent.paneComponent(paneId: PaneId): PaneComponent {
    return when (paneId) {
        PaneId.PRIMARY -> primaryPane
        PaneId.SECONDARY -> secondaryPane
    }
}

/**
 * 读取面板选中条目，并排除已由选中祖先目录覆盖的子项。
 *
 * @param paneId 面板 ID。
 * @return 去重后的选中条目。
 */
internal fun DefaultRootComponent.selectedEntriesInPane(paneId: PaneId): List<VFile> {
    val state = paneState(paneId)
    val topEntries = (state.entriesState as? PaneEntriesState.Ready)?.entries.orEmpty()
    val allEntries = if (state.inlineExpandedLocations.isEmpty()) {
        topEntries
    } else {
        collectAllVisibleEntries(topEntries, state)
    }
    val selected = allEntries.filter { entry -> entry.id in state.selectedEntryIds }
    if (selected.size <= 1) return selected
    val selectedDirectories = selected.filter { entry -> entry.kind == VFileKind.DIRECTORY }
        .mapTo(mutableSetOf()) { entry -> entry.location }
    return selected.filter { entry ->
        val parent = entry.parentLocation ?: return@filter true
        !isAncestorSelected(parent, selectedDirectories)
    }
}

/**
 * 判断给定位置的任一祖先是否已选中。
 *
 * @param location 起始父位置。
 * @param selectedDirectoryLocations 已选中目录位置。
 * @return 存在已选祖先时返回 `true`。
 */
private fun isAncestorSelected(location: String, selectedDirectoryLocations: Set<String>): Boolean {
    var current: String? = location
    while (current != null) {
        if (current in selectedDirectoryLocations) return true
        val lastSeparator = current.lastIndexOf('/')
        current = if (lastSeparator > 0) current.substring(0, lastSeparator) else null
    }
    return false
}

/**
 * 递归收集顶层与内联展开条目。
 *
 * @param entries 当前层条目。
 * @param paneState 面板状态。
 * @return 当前可见条目。
 */
private fun collectAllVisibleEntries(entries: List<VFile>, paneState: PaneState): List<VFile> {
    return buildList {
        entries.forEach { entry ->
            add(entry)
            if (entry.location in paneState.inlineExpandedLocations) {
                paneState.inlineExpandedEntries[entry.location]?.entries?.let { children ->
                    addAll(collectAllVisibleEntries(children, paneState))
                }
            }
        }
    }
}

/** 刷新主次两个面板。 */
internal fun DefaultRootComponent.refreshAllPanes() {
    primaryPane.refresh()
    secondaryPane.refresh()
}
