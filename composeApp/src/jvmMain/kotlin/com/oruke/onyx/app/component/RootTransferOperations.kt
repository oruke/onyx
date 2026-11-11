package com.oruke.onyx.app.component

import com.oruke.onyx.app.component.delegate.FileActionDelegate
import com.oruke.onyx.app.filesystem.isComputerLocation
import com.oruke.onyx.app.filesystem.isSystemVirtualLocation
import com.oruke.onyx.core.model.DeleteMode
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.isTrashLocation
import com.oruke.onyx.vfs.api.TrashMoveRecord
import com.oruke.onyx.vfs.archive.ArchiveService

/**
 * 将选中项传输到目录；压缩包内条目自动改为解压，目标为回收站时转为移入回收站。
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
    if (isTrashLocation(targetDirectoryLocation)) {
        if (operation == FileTransferOperation.MOVE) {
            requestDropEntriesToTrash(sourcePaneId)
        } else {
            showPaneOperationFailure(
                sourcePaneId,
                LocalizedException(I18nMessage(MessageKey.MSG_LOCATION_NOT_DIRECTORY, targetDirectoryLocation)),
            )
        }
        return
    }
    val sourceLocation = paneState(sourcePaneId).location
    if (isSystemVirtualLocation(sourceLocation)) return
    val sourceBaseLocation = sourceLocation.takeUnless(::isComputerLocation)
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
            sourceBaseLocation = sourceBaseLocation,
        )
    }
}

/** @param paneId 删除来源面板。 */
internal fun DefaultRootComponent.requestDeleteSelectedInPane(paneId: PaneId) {
    if (isTrashLocation(paneState(paneId).location)) {
        requestPermanentDeleteTrashItems(paneId)
        return
    }
    val selectedEntries = selectedEntriesInPane(paneId)
    if (selectedEntries.isEmpty()) return
    val moveToTrashPreferred = settings.value.deleteMode == DeleteMode.MOVE_TO_TRASH_PREFERRED
    val willMoveToTrash = moveToTrashPreferred && trashService.isSupported
    val trashUnavailable = moveToTrashPreferred && !trashService.isSupported
    val request = FileActionDelegate.PendingDeleteRequest(paneId, selectedEntries, willMoveToTrash)
    if (!settings.value.confirmBeforeDelete && !trashUnavailable) {
        fileActionDelegate.executeDeleteRequest(request)
        return
    }
    fileActionDelegate.setPendingDeleteRequest(request)
    dialogState.value = RootDialogState.DeleteSelectionConfirmation(
        moveToTrash = willMoveToTrash,
        itemCount = selectedEntries.size,
        trashUnavailable = trashUnavailable,
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
 * @param targetDirectoryLocation 根目标目录。
 * @param entryTargetDirectories 按条目 ID 指定的实际落盘父目录。
 * @param directorySteps 中间目录步骤。
 * @param createdDirectories 本次移动新建的中间目录。
 */
internal fun DefaultRootComponent.recordMoveOperation(
    entries: List<VFile>,
    targetDirectoryLocation: String,
    entryTargetDirectories: Map<String, String> = emptyMap(),
    directorySteps: List<com.oruke.onyx.shared.usecase.TransferDirectoryStep> = emptyList(),
    createdDirectories: List<VFile> = emptyList(),
) {
    fileOperationHistoryFacade.recordMoveOperation(
        entries = entries,
        targetDirectoryLocation = targetDirectoryLocation,
        entryTargetDirectories = entryTargetDirectories,
        directorySteps = directorySteps,
        createdDirectories = createdDirectories,
    )
}

/** @param records 回收站移动记录。 */
internal fun DefaultRootComponent.recordTrashDeleteOperation(records: List<TrashMoveRecord>) {
    fileOperationHistoryFacade.recordTrashDeleteOperation(records)
}

/**
 * 读取面板有效选中条目：在保留整目录选择能力的同时，对仅选中部分子项的内联展开父目录自动裁剪。
 *
 * @param paneId 面板 ID。
 * @return 去重与父子裁剪后的有效选中条目。
 */
internal fun DefaultRootComponent.selectedEntriesInPane(paneId: PaneId): List<VFile> {
    return paneState(paneId).effectiveSelectedEntries()
}
