package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.ImageFitMode
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.FileContextMenuCommand
import com.oruke.onyx.vfs.api.OpenWithApp
import com.oruke.onyx.vfs.api.SystemMenuAction

internal fun RootComponent.stageCopySelectedInPane(paneId: PaneId) =
    dispatch(RootIntent.StageCopySelectedInPane(paneId))

internal fun RootComponent.stageCutSelectedInPane(paneId: PaneId) = dispatch(RootIntent.StageCutSelectedInPane(paneId))

internal fun RootComponent.requestPasteIntoPane(paneId: PaneId) = dispatch(RootIntent.RequestPasteIntoPane(paneId))

internal fun RootComponent.requestTransferSelectedToDirectory(
    sourcePaneId: PaneId,
    targetDirectoryLocation: String,
    operation: FileTransferOperation,
) = dispatch(RootIntent.RequestTransferSelectedToDirectory(sourcePaneId, targetDirectoryLocation, operation))

internal fun RootComponent.requestTransferSourceToDestination(operation: FileTransferOperation) =
    dispatch(RootIntent.RequestTransferSourceToDestination(operation))

internal fun RootComponent.requestDeleteSelectedInPane(paneId: PaneId) =
    dispatch(RootIntent.RequestDeleteSelectedInPane(paneId))

internal fun RootComponent.extractSelectedInPane(paneId: PaneId) = dispatch(RootIntent.ExtractSelectedInPane(paneId))

internal fun RootComponent.extractToDirectoryInPane(paneId: PaneId) =
    dispatch(RootIntent.ExtractToDirectoryInPane(paneId))

internal fun RootComponent.extractSmartInPane(paneId: PaneId) = dispatch(RootIntent.ExtractSmartInPane(paneId))

internal fun RootComponent.submitArchivePassword(password: String) =
    dispatch(RootIntent.SubmitArchivePassword(password))

internal fun RootComponent.updateRemoteCredentialsDraft(draft: RemoteCredentialsDraft) =
    dispatch(RootIntent.UpdateRemoteCredentialsDraft(draft))

internal fun RootComponent.submitRemoteCredentials() = dispatch(RootIntent.SubmitRemoteCredentials)

internal fun RootComponent.batchRenameInPane(paneId: PaneId) = dispatch(RootIntent.BatchRenameInPane(paneId))

internal fun RootComponent.executeBatchRename(paneId: PaneId, renameMap: List<Pair<VFile, String>>) =
    dispatch(RootIntent.ExecuteBatchRename(paneId, renameMap))

internal fun RootComponent.resetBatchRenameForContinue(paneId: PaneId) =
    dispatch(RootIntent.ResetBatchRenameForContinue(paneId))

internal fun RootComponent.dismissTask(taskId: String) = dispatch(RootIntent.DismissTask(taskId))

internal fun RootComponent.cancelTask(taskId: String) = dispatch(RootIntent.CancelTask(taskId))

internal fun RootComponent.pauseTask(taskId: String) = dispatch(RootIntent.PauseTask(taskId))

internal fun RootComponent.resumeTask(taskId: String) = dispatch(RootIntent.ResumeTask(taskId))

internal fun RootComponent.retryTask(taskId: String) = dispatch(RootIntent.RetryTask(taskId))

internal fun RootComponent.clearAllTasks() = dispatch(RootIntent.ClearAllTasks)

internal fun RootComponent.undoLastFileOperation() = dispatch(RootIntent.UndoLastFileOperation)

internal fun RootComponent.redoLastFileOperation() = dispatch(RootIntent.RedoLastFileOperation)

internal fun RootComponent.openImageViewer(file: VFile, allImages: List<VFile>) =
    dispatch(RootIntent.OpenImageViewer(file, allImages))

internal fun RootComponent.closeImageViewer() = dispatch(RootIntent.CloseImageViewer)

internal fun RootComponent.imageViewerNext() = dispatch(RootIntent.ImageViewerNext)

internal fun RootComponent.imageViewerPrevious() = dispatch(RootIntent.ImageViewerPrevious)

internal fun RootComponent.imageViewerSetZoom(factor: Float) = dispatch(RootIntent.ImageViewerSetZoom(factor))

internal fun RootComponent.imageViewerSetFitMode(mode: ImageFitMode) = dispatch(RootIntent.ImageViewerSetFitMode(mode))

internal fun RootComponent.imageViewerRotate(clockwise: Boolean) = dispatch(RootIntent.ImageViewerRotate(clockwise))

internal fun RootComponent.openWithApp(entry: VFile, app: OpenWithApp) = dispatch(RootIntent.OpenWithApp(entry, app))

internal fun RootComponent.openWithChooser(entry: VFile) = dispatch(RootIntent.OpenWithChooser(entry))

internal fun RootComponent.executeSystemMenuAction(action: SystemMenuAction, entries: List<VFile>) =
    dispatch(RootIntent.ExecuteSystemMenuAction(action, entries))

internal fun RootComponent.executeFileContextMenuCommand(command: FileContextMenuCommand, entries: List<VFile>) =
    dispatch(RootIntent.ExecuteFileContextMenuCommand(command, entries))

internal fun RootComponent.openTerminalAt(location: String) = dispatch(RootIntent.OpenTerminalAt(location))
