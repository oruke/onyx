package com.oruke.onyx.app.component

import com.oruke.onyx.app.filesystem.OpenWithApp
import com.oruke.onyx.app.filesystem.ArchiveInfoRequest
import com.oruke.onyx.app.filesystem.ArchiveInfoResult
import com.oruke.onyx.app.filesystem.FileContextMenuCommand
import com.oruke.onyx.app.filesystem.FileContextMenuRequest
import com.oruke.onyx.app.filesystem.FileContextMenuSection
import com.oruke.onyx.app.filesystem.FileHashRequest
import com.oruke.onyx.app.filesystem.FileHashResult
import com.oruke.onyx.app.filesystem.PreviewTextRequest
import com.oruke.onyx.app.filesystem.PreviewTextResult
import com.oruke.onyx.app.filesystem.RemoteCredentialSavePolicy
import com.oruke.onyx.app.filesystem.SystemMenuAction
import com.oruke.onyx.app.filesystem.TransferConflictStrategy
import com.oruke.onyx.app.filesystem.VfsBreadcrumb
import com.oruke.onyx.app.filesystem.VfsConnectionTestRequest
import com.oruke.onyx.app.filesystem.VfsConnectionTestResult
import com.oruke.onyx.app.filesystem.VfsProtocol
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.ImageFitMode
import com.oruke.onyx.core.model.ImageViewerState
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.core.model.RemoteConnectionSavePolicy
import com.oruke.onyx.core.model.VFile
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.StateFlow

data class RootState(
    val layoutMode: PaneLayoutMode,
    val paneSplitFraction: Float,
    val activePane: PaneId,
    val primaryPane: PaneState,
    val secondaryPane: PaneState,
    val sidebarTreeState: SidebarTreeState,
    val settings: OnyxSettings,
    val sessionRestoreState: SessionRestoreState,
    val dialogState: RootDialogState?,
    val canPaste: Boolean,
    val tasks: List<BackgroundTask>,
    val showPreviewPane: Boolean = false,
    val searchState: SearchPanelState = SearchPanelState(),
    val operationHistoryState: OperationHistoryState = OperationHistoryState(),
)

/**
 * 文件操作历史的可用状态。
 *
 * @property canUndo 当前是否存在可撤销操作。
 * @property canRedo 当前是否存在可重做操作。
 */
data class OperationHistoryState(
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

data class SearchPanelState(
    val visible: Boolean = false,
    val paneId: PaneId = PaneId.PRIMARY,
    val rootLocation: String = "",
    val query: String = "",
    val status: SearchStatus = SearchStatus.IDLE,
    val results: List<VFile> = emptyList(),
    val scannedEntryCount: Int = 0,
    val limitReached: Boolean = false,
    val error: I18nMessage? = null,
)

enum class SearchStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class SidebarTreeState(
    val roots: List<SidebarTreeNode>,
)

data class SidebarTreeNode(
    val location: String,
    val label: String,
    val expanded: Boolean,
    val loadState: SidebarTreeNodeLoadState,
    val loadError: String? = null,
    val children: List<SidebarTreeNode> = emptyList(),
)

enum class SidebarTreeNodeLoadState {
    IDLE,
    LOADING,
    READY,
    FAILURE,
}

sealed interface SessionRestoreState {
    data object Loading : SessionRestoreState

    data object Ready : SessionRestoreState

    data class Failed(
        val reason: I18nMessage?,
    ) : SessionRestoreState
}

sealed interface RootDialogState {
    data class DeleteSelectionConfirmation(
        val moveToTrash: Boolean,
        val itemCount: Int,
        val trashUnavailable: Boolean,
    ) : RootDialogState

    data class ConflictResolution(
        val sourceName: String,
        val targetLocation: String,
        val operation: FileTransferOperation,
        val currentIndex: Int,
        val total: Int,
    ) : RootDialogState

    data class CreateDirectories(
        val paneId: PaneId,
        val location: String,
        val draft: String,
        val error: CreateDirectoriesDialogError? = null,
    ) : RootDialogState

    data class Settings(
        val draft: OnyxSettings,
        val remoteConnectionDraft: RemoteConnectionDraft = RemoteConnectionDraft(),
        val editingRemoteConnectionId: String? = null,
        val remoteConnectionTestState: RemoteConnectionTestState = RemoteConnectionTestState.Idle,
        val remoteConnectionError: RemoteConnectionDialogError? = null,
    ) : RootDialogState

    data class BatchRename(
        val paneId: PaneId,
        val entries: List<VFile>,
        val executing: Boolean = false,
        val progress: Float = 0f,
        val processedCount: Int = 0,
        val currentDetail: String = "",
        val completed: Boolean = false,
        val errorMessage: I18nMessage? = null,
    ) : RootDialogState

    /** 压缩包密码输入对话框 */
    data class ArchivePassword(
        val archiveName: String,
        val error: I18nMessage? = null,
    ) : RootDialogState

    data class RemoteCredentials(
        val paneId: PaneId,
        val protocol: VfsProtocol,
        val location: String,
        val draft: RemoteCredentialsDraft = RemoteCredentialsDraft(),
        val rejected: Boolean = false,
        val error: RemoteCredentialsDialogError? = null,
    ) : RootDialogState
}

enum class CreateDirectoriesDialogError {
    EMPTY_INPUT,
}

data class RemoteCredentialsDraft(
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val savePolicy: RemoteCredentialSavePolicy = RemoteCredentialSavePolicy.SESSION,
)

enum class RemoteCredentialsDialogError {
    USERNAME_EMPTY,
    SYSTEM_KEYRING_UNAVAILABLE,
}

data class RemoteConnectionDraft(
    val name: String = "",
    val protocol: RemoteConnectionProtocol = RemoteConnectionProtocol.SMB,
    val location: String = "",
    val username: String = "",
    val secret: String = "",
    val domain: String = "",
    val savePolicy: RemoteConnectionSavePolicy = RemoteConnectionSavePolicy.SESSION,
)

sealed interface RemoteConnectionTestState {
    data object Idle : RemoteConnectionTestState

    data object Testing : RemoteConnectionTestState

    data class Reachable(
        val capabilities: Set<String>,
    ) : RemoteConnectionTestState

    data class Failed(
        val reason: I18nMessage,
    ) : RemoteConnectionTestState
}

enum class RemoteConnectionDialogError {
    NAME_EMPTY,
    LOCATION_EMPTY,
    USERNAME_EMPTY,
    SYSTEM_KEYRING_UNAVAILABLE,
}

sealed interface RootIntent {
    data class SetLayoutMode(
        val mode: PaneLayoutMode,
    ) : RootIntent

    data class SetPaneSplitFraction(
        val fraction: Float,
    ) : RootIntent

    data object OpenSettings : RootIntent

    data class UpdateSettingsDraft(
        val draft: OnyxSettings,
    ) : RootIntent

    data object CleanupInvalidLocations : RootIntent

    data class UpdateRemoteConnectionDraft(
        val draft: RemoteConnectionDraft,
    ) : RootIntent

    data class EditRemoteConnection(
        val profile: RemoteConnectionProfile,
    ) : RootIntent

    data object NewRemoteConnection : RootIntent

    data object SaveRemoteConnectionDraft : RootIntent

    data object TestRemoteConnectionDraft : RootIntent

    data class DeleteRemoteConnection(
        val id: String,
    ) : RootIntent

    data class OpenRemoteConnection(
        val location: String,
    ) : RootIntent

    data class ActivatePane(
        val paneId: PaneId,
    ) : RootIntent

    data class UpdateSettings(
        val settings: OnyxSettings,
    ) : RootIntent

    data class OpenLocationInActivePane(
        val location: String,
    ) : RootIntent

    data class ToggleFavoriteLocation(
        val location: String,
    ) : RootIntent

    data class ToggleSidebarTreeNode(
        val location: String,
    ) : RootIntent

    data class RetrySidebarTreeNode(
        val location: String,
    ) : RootIntent

    data class BeginCreateDirectoriesInPane(
        val paneId: PaneId,
    ) : RootIntent

    data class UpdateCreateDirectoriesDraft(
        val draft: String,
    ) : RootIntent

    data object ConfirmDialog : RootIntent

    data object DismissDialog : RootIntent

    data class ResolveConflict(
        val strategy: TransferConflictStrategy,
        val applyToAll: Boolean,
    ) : RootIntent

    data class MoveTab(
        val sourcePaneId: PaneId,
        val tabId: String,
        val targetPaneId: PaneId,
        val targetIndex: Int,
    ) : RootIntent

    data object RefreshActivePane : RootIntent

    data object TogglePreviewPane : RootIntent

    data object ShowSearchPanel : RootIntent

    data object CloseSearchPanel : RootIntent

    data class UpdateSearchQuery(
        val query: String,
    ) : RootIntent

    data object ExecuteSearch : RootIntent

    data object CancelSearch : RootIntent

    data class OpenSearchResult(
        val entry: VFile,
    ) : RootIntent

    data class StageCopySelectedInPane(
        val paneId: PaneId,
    ) : RootIntent

    data class StageCutSelectedInPane(
        val paneId: PaneId,
    ) : RootIntent

    data class RequestPasteIntoPane(
        val paneId: PaneId,
    ) : RootIntent

    data class RequestTransferSelectedToDirectory(
        val sourcePaneId: PaneId,
        val targetDirectoryLocation: String,
        val operation: FileTransferOperation,
    ) : RootIntent

    data class RequestDeleteSelectedInPane(
        val paneId: PaneId,
    ) : RootIntent

    data class ExtractSelectedInPane(
        val paneId: PaneId,
    ) : RootIntent

    data class ExtractToDirectoryInPane(
        val paneId: PaneId,
    ) : RootIntent

    data class ExtractSmartInPane(
        val paneId: PaneId,
    ) : RootIntent

    data class SubmitArchivePassword(
        val password: String,
    ) : RootIntent

    data class UpdateRemoteCredentialsDraft(
        val draft: RemoteCredentialsDraft,
    ) : RootIntent

    data object SubmitRemoteCredentials : RootIntent

    data class BatchRenameInPane(
        val paneId: PaneId,
    ) : RootIntent

    data class ExecuteBatchRename(
        val paneId: PaneId,
        val renameMap: List<Pair<VFile, String>>,
    ) : RootIntent

    data class ResetBatchRenameForContinue(
        val paneId: PaneId,
    ) : RootIntent

    data class DismissTask(
        val taskId: String,
    ) : RootIntent

    data class CancelTask(
        val taskId: String,
    ) : RootIntent

    data class PauseTask(
        val taskId: String,
    ) : RootIntent

    data class ResumeTask(
        val taskId: String,
    ) : RootIntent

    data class RetryTask(
        val taskId: String,
    ) : RootIntent

    data object ClearAllTasks : RootIntent

    data object UndoLastFileOperation : RootIntent

    data object RedoLastFileOperation : RootIntent

    data class OpenImageViewer(
        val file: VFile,
        val allImages: List<VFile>,
    ) : RootIntent

    data object CloseImageViewer : RootIntent

    data object ImageViewerNext : RootIntent

    data object ImageViewerPrevious : RootIntent

    data class ImageViewerSetZoom(
        val factor: Float,
    ) : RootIntent

    data class ImageViewerSetFitMode(
        val mode: ImageFitMode,
    ) : RootIntent

    data class ImageViewerRotate(
        val clockwise: Boolean,
    ) : RootIntent

    data class OpenWithApp(
        val entry: VFile,
        val app: com.oruke.onyx.app.filesystem.OpenWithApp,
    ) : RootIntent

    data class OpenWithChooser(
        val entry: VFile,
    ) : RootIntent

    data class ExecuteSystemMenuAction(
        val action: SystemMenuAction,
        val entries: List<VFile>,
    ) : RootIntent

    data class ExecuteFileContextMenuCommand(
        val command: FileContextMenuCommand,
        val entries: List<VFile>,
    ) : RootIntent

    data class OpenTerminalAt(
        val location: String,
    ) : RootIntent
}

interface RootComponent {
    val state: StateFlow<RootState>
    val imageViewerState: StateFlow<ImageViewerState>
    val primaryPane: PaneComponent
    val secondaryPane: PaneComponent

    fun dispatch(intent: RootIntent)

    // ── 打开方式 ──────────────────────────────────────────────────────────

    suspend fun listOpenWithApps(entry: VFile): List<OpenWithApp>

    fun supportsOpenWith(entry: VFile): Boolean

    suspend fun listSystemMenuActions(entries: List<VFile>): List<SystemMenuAction>

    fun supportsContextMenuOpenWith(entry: VFile): Boolean

    suspend fun listContextMenuSections(request: FileContextMenuRequest): List<FileContextMenuSection>

    fun prepareExternalDrag(entries: List<VFile>): Boolean

    fun isArchiveFileName(fileName: String): Boolean

    fun isImageFileName(fileName: String): Boolean

    fun isTextPreviewFileName(fileName: String): Boolean

    fun locationLabel(location: String): String

    fun buildBreadcrumbs(location: String): List<VfsBreadcrumb>

    fun resolveTransferOperation(sourceLocation: String, targetLocation: String): FileTransferOperation

    suspend fun testRemoteConnection(request: VfsConnectionTestRequest): VfsConnectionTestResult

    suspend fun loadTextPreview(request: PreviewTextRequest): PreviewTextResult

    suspend fun readFileHash(request: FileHashRequest): FileHashResult

    suspend fun readArchiveInfo(request: ArchiveInfoRequest): ArchiveInfoResult

    suspend fun loadThumbnail(location: String, maxDimension: Int): ImageBitmap?

    suspend fun loadArchiveThumbnail(location: String, maxDimension: Int): ImageBitmap?

    suspend fun readImageSize(entry: VFile): IntSize?
}

fun RootComponent.setLayoutMode(mode: PaneLayoutMode) = dispatch(RootIntent.SetLayoutMode(mode))

fun RootComponent.setPaneSplitFraction(fraction: Float) = dispatch(RootIntent.SetPaneSplitFraction(fraction))

fun RootComponent.openSettings() = dispatch(RootIntent.OpenSettings)

fun RootComponent.updateSettingsDraft(draft: OnyxSettings) = dispatch(RootIntent.UpdateSettingsDraft(draft))

fun RootComponent.updateRemoteConnectionDraft(draft: RemoteConnectionDraft) =
    dispatch(RootIntent.UpdateRemoteConnectionDraft(draft))

fun RootComponent.editRemoteConnection(profile: RemoteConnectionProfile) =
    dispatch(RootIntent.EditRemoteConnection(profile))

fun RootComponent.newRemoteConnection() = dispatch(RootIntent.NewRemoteConnection)

fun RootComponent.saveRemoteConnectionDraft() = dispatch(RootIntent.SaveRemoteConnectionDraft)

fun RootComponent.testRemoteConnectionDraft() = dispatch(RootIntent.TestRemoteConnectionDraft)

fun RootComponent.deleteRemoteConnection(id: String) = dispatch(RootIntent.DeleteRemoteConnection(id))

fun RootComponent.openRemoteConnection(location: String) = dispatch(RootIntent.OpenRemoteConnection(location))

fun RootComponent.activatePane(paneId: PaneId) = dispatch(RootIntent.ActivatePane(paneId))

fun RootComponent.updateSettings(settings: OnyxSettings) = dispatch(RootIntent.UpdateSettings(settings))

fun RootComponent.openLocationInActivePane(location: String) = dispatch(RootIntent.OpenLocationInActivePane(location))

fun RootComponent.toggleFavoriteLocation(location: String) = dispatch(RootIntent.ToggleFavoriteLocation(location))

fun RootComponent.toggleSidebarTreeNode(location: String) = dispatch(RootIntent.ToggleSidebarTreeNode(location))

fun RootComponent.retrySidebarTreeNode(location: String) = dispatch(RootIntent.RetrySidebarTreeNode(location))

fun RootComponent.beginCreateDirectoriesInPane(paneId: PaneId) =
    dispatch(RootIntent.BeginCreateDirectoriesInPane(paneId))

fun RootComponent.updateCreateDirectoriesDraft(draft: String) =
    dispatch(RootIntent.UpdateCreateDirectoriesDraft(draft))

fun RootComponent.confirmDialog() = dispatch(RootIntent.ConfirmDialog)

fun RootComponent.dismissDialog() = dispatch(RootIntent.DismissDialog)

fun RootComponent.resolveConflict(
    strategy: TransferConflictStrategy,
    applyToAll: Boolean,
) = dispatch(RootIntent.ResolveConflict(strategy, applyToAll))

fun RootComponent.moveTab(
    sourcePaneId: PaneId,
    tabId: String,
    targetPaneId: PaneId,
    targetIndex: Int,
) = dispatch(RootIntent.MoveTab(sourcePaneId, tabId, targetPaneId, targetIndex))

fun RootComponent.refreshActivePane() = dispatch(RootIntent.RefreshActivePane)

fun RootComponent.togglePreviewPane() = dispatch(RootIntent.TogglePreviewPane)

fun RootComponent.showSearchPanel() = dispatch(RootIntent.ShowSearchPanel)

fun RootComponent.closeSearchPanel() = dispatch(RootIntent.CloseSearchPanel)

fun RootComponent.updateSearchQuery(query: String) = dispatch(RootIntent.UpdateSearchQuery(query))

fun RootComponent.executeSearch() = dispatch(RootIntent.ExecuteSearch)

fun RootComponent.cancelSearch() = dispatch(RootIntent.CancelSearch)

fun RootComponent.openSearchResult(entry: VFile) = dispatch(RootIntent.OpenSearchResult(entry))

fun RootComponent.stageCopySelectedInPane(paneId: PaneId) = dispatch(RootIntent.StageCopySelectedInPane(paneId))

fun RootComponent.stageCutSelectedInPane(paneId: PaneId) = dispatch(RootIntent.StageCutSelectedInPane(paneId))

fun RootComponent.requestPasteIntoPane(paneId: PaneId) = dispatch(RootIntent.RequestPasteIntoPane(paneId))

fun RootComponent.requestTransferSelectedToDirectory(
    sourcePaneId: PaneId,
    targetDirectoryLocation: String,
    operation: FileTransferOperation,
) = dispatch(RootIntent.RequestTransferSelectedToDirectory(sourcePaneId, targetDirectoryLocation, operation))

fun RootComponent.requestDeleteSelectedInPane(paneId: PaneId) = dispatch(RootIntent.RequestDeleteSelectedInPane(paneId))

fun RootComponent.extractSelectedInPane(paneId: PaneId) = dispatch(RootIntent.ExtractSelectedInPane(paneId))

fun RootComponent.extractToDirectoryInPane(paneId: PaneId) = dispatch(RootIntent.ExtractToDirectoryInPane(paneId))

fun RootComponent.extractSmartInPane(paneId: PaneId) = dispatch(RootIntent.ExtractSmartInPane(paneId))

fun RootComponent.submitArchivePassword(password: String) = dispatch(RootIntent.SubmitArchivePassword(password))

fun RootComponent.updateRemoteCredentialsDraft(draft: RemoteCredentialsDraft) =
    dispatch(RootIntent.UpdateRemoteCredentialsDraft(draft))

fun RootComponent.submitRemoteCredentials() = dispatch(RootIntent.SubmitRemoteCredentials)

fun RootComponent.batchRenameInPane(paneId: PaneId) = dispatch(RootIntent.BatchRenameInPane(paneId))

fun RootComponent.executeBatchRename(
    paneId: PaneId,
    renameMap: List<Pair<VFile, String>>,
) = dispatch(RootIntent.ExecuteBatchRename(paneId, renameMap))

fun RootComponent.resetBatchRenameForContinue(paneId: PaneId) =
    dispatch(RootIntent.ResetBatchRenameForContinue(paneId))

fun RootComponent.dismissTask(taskId: String) = dispatch(RootIntent.DismissTask(taskId))

fun RootComponent.cancelTask(taskId: String) = dispatch(RootIntent.CancelTask(taskId))

fun RootComponent.pauseTask(taskId: String) = dispatch(RootIntent.PauseTask(taskId))

fun RootComponent.resumeTask(taskId: String) = dispatch(RootIntent.ResumeTask(taskId))

fun RootComponent.retryTask(taskId: String) = dispatch(RootIntent.RetryTask(taskId))

fun RootComponent.clearAllTasks() = dispatch(RootIntent.ClearAllTasks)

fun RootComponent.undoLastFileOperation() = dispatch(RootIntent.UndoLastFileOperation)

fun RootComponent.redoLastFileOperation() = dispatch(RootIntent.RedoLastFileOperation)

fun RootComponent.openImageViewer(
    file: VFile,
    allImages: List<VFile>,
) = dispatch(RootIntent.OpenImageViewer(file, allImages))

fun RootComponent.closeImageViewer() = dispatch(RootIntent.CloseImageViewer)

fun RootComponent.imageViewerNext() = dispatch(RootIntent.ImageViewerNext)

fun RootComponent.imageViewerPrevious() = dispatch(RootIntent.ImageViewerPrevious)

fun RootComponent.imageViewerSetZoom(factor: Float) = dispatch(RootIntent.ImageViewerSetZoom(factor))

fun RootComponent.imageViewerSetFitMode(mode: ImageFitMode) = dispatch(RootIntent.ImageViewerSetFitMode(mode))

fun RootComponent.imageViewerRotate(clockwise: Boolean) = dispatch(RootIntent.ImageViewerRotate(clockwise))

fun RootComponent.openWithApp(
    entry: VFile,
    app: OpenWithApp,
) = dispatch(RootIntent.OpenWithApp(entry, app))

fun RootComponent.openWithChooser(entry: VFile) = dispatch(RootIntent.OpenWithChooser(entry))

fun RootComponent.executeSystemMenuAction(
    action: SystemMenuAction,
    entries: List<VFile>,
) = dispatch(RootIntent.ExecuteSystemMenuAction(action, entries))

fun RootComponent.executeFileContextMenuCommand(
    command: FileContextMenuCommand,
    entries: List<VFile>,
) = dispatch(RootIntent.ExecuteFileContextMenuCommand(command, entries))

fun RootComponent.openTerminalAt(location: String) = dispatch(RootIntent.OpenTerminalAt(location))
