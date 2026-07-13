package com.oruke.onyx.app.component

import com.oruke.onyx.vfs.api.OpenWithApp
import com.oruke.onyx.app.filesystem.ArchiveInfoRequest
import com.oruke.onyx.app.filesystem.ArchiveInfoResult
import com.oruke.onyx.vfs.api.FileContextMenuCommand
import com.oruke.onyx.vfs.api.FileContextMenuRequest
import com.oruke.onyx.vfs.api.FileContextMenuSection
import com.oruke.onyx.app.filesystem.FileHashRequest
import com.oruke.onyx.app.filesystem.FileHashResult
import com.oruke.onyx.app.filesystem.PreviewTextRequest
import com.oruke.onyx.app.filesystem.PreviewTextResult
import com.oruke.onyx.vfs.api.RemoteCredentialSavePolicy
import com.oruke.onyx.vfs.api.SystemMenuAction
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.app.filesystem.VfsBreadcrumb
import com.oruke.onyx.vfs.api.VfsConnectionTestRequest
import com.oruke.onyx.vfs.api.VfsConnectionTestResult
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.ImageFitMode
import com.oruke.onyx.core.model.ImageViewerState
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.PaneRoleState
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.core.model.RemoteConnectionSavePolicy
import com.oruke.onyx.core.model.S3ConnectionConfig
import com.oruke.onyx.core.model.VFile
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.StateFlow

internal data class RootState(
    val layoutMode: PaneLayoutMode,
    val paneSplitFraction: Float,
    val activePane: PaneId,
    val paneRoles: PaneRoleState = PaneRoleState.fromSource(activePane),
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
internal data class OperationHistoryState(
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

internal data class SearchPanelState(
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

internal enum class SearchStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

internal data class SidebarTreeState(
    val roots: List<SidebarTreeNode>,
)

internal data class SidebarTreeNode(
    val location: String,
    val label: String,
    val expanded: Boolean,
    val loadState: SidebarTreeNodeLoadState,
    val loadError: String? = null,
    val children: List<SidebarTreeNode> = emptyList(),
)

internal enum class SidebarTreeNodeLoadState {
    IDLE,
    LOADING,
    READY,
    FAILURE,
}

internal sealed interface SessionRestoreState {
    data object Loading : SessionRestoreState

    data object Ready : SessionRestoreState

    data class Failed(
        val reason: I18nMessage?,
    ) : SessionRestoreState
}

internal sealed interface RootDialogState {
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
    ) : RootDialogState

    /**
     * 网络位置管理窗口状态，独立于应用设置窗口保存远程连接配置。
     */
    data class RemoteConnections(
        val remoteConnectionDraft: RemoteConnectionDraft = RemoteConnectionDraft(),
        val editingRemoteConnectionId: String? = null,
        val remoteConnectionTestState: RemoteConnectionTestState = RemoteConnectionTestState.Idle,
        val remoteConnectionError: RemoteConnectionDialogError? = null,
        val saving: Boolean = false,
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
        val submitting: Boolean = false,
    ) : RootDialogState
}

internal enum class CreateDirectoriesDialogError {
    EMPTY_INPUT,
}

internal data class RemoteCredentialsDraft(
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val savePolicy: RemoteCredentialSavePolicy = RemoteCredentialSavePolicy.SESSION,
)

internal enum class RemoteCredentialsDialogError {
    USERNAME_EMPTY,
    SYSTEM_KEYRING_UNAVAILABLE,
    CREDENTIAL_SAVE_FAILED,
}

/** 网络位置编辑草稿，密钥仅保留在当前窗口内存中。 */
internal data class RemoteConnectionDraft(
    /** 用户可见连接名称。 */
    val name: String = "",
    /** 连接协议。 */
    val protocol: RemoteConnectionProtocol = RemoteConnectionProtocol.SMB,
    /** 远程位置。 */
    val location: String = "",
    /** 用户名或 S3 Access Key ID。 */
    val username: String = "",
    /** 密码或 S3 Secret Access Key。 */
    val secret: String = "",
    /** 用户是否实际修改过密钥字段，用于区分“保持原密码”和“写入空密码”。 */
    val secretChanged: Boolean = false,
    /** SMB 域。 */
    val domain: String = "",
    /** S3 兼容服务专属连接参数。 */
    val s3Config: S3ConnectionConfig = S3ConnectionConfig(),
    /** 凭据保存策略。 */
    val savePolicy: RemoteConnectionSavePolicy = RemoteConnectionSavePolicy.SESSION,
)

internal sealed interface RemoteConnectionTestState {
    data object Idle : RemoteConnectionTestState

    data object Testing : RemoteConnectionTestState

    data class Reachable(
        val capabilities: Set<String>,
    ) : RemoteConnectionTestState

    data class Failed(
        val reason: I18nMessage,
    ) : RemoteConnectionTestState
}

internal enum class RemoteConnectionDialogError {
    NAME_EMPTY,
    LOCATION_EMPTY,
    LOCATION_INVALID,
    S3_ENDPOINT_INVALID,
    S3_REGION_EMPTY,
    USERNAME_EMPTY,
    SECRET_EMPTY,
    SYSTEM_KEYRING_UNAVAILABLE,
    CREDENTIAL_SAVE_FAILED,
}

internal sealed interface RootIntent {
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

    data object OpenRemoteConnections : RootIntent

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

    data object OpenSearchResultsAsCollection : RootIntent

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

    data class RequestTransferSourceToDestination(
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
        val app: com.oruke.onyx.vfs.api.OpenWithApp,
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

/** 平台菜单、打开方式、拖放和连接测试能力。 */
internal interface RootPlatformServices {
    suspend fun listOpenWithApps(entry: VFile): List<OpenWithApp>

    fun supportsOpenWith(entry: VFile): Boolean

    suspend fun listSystemMenuActions(entries: List<VFile>): List<SystemMenuAction>

    fun supportsContextMenuOpenWith(entry: VFile): Boolean

    suspend fun listContextMenuSections(request: FileContextMenuRequest): List<FileContextMenuSection>

    fun prepareExternalDrag(entries: List<VFile>): Boolean

    suspend fun testRemoteConnection(request: VfsConnectionTestRequest): VfsConnectionTestResult
}

/** 路径展示、文件类型识别和预览读取能力。 */
internal interface RootPresentationServices {
    fun isArchiveFileName(fileName: String): Boolean

    fun isImageFileName(fileName: String): Boolean

    fun isTextPreviewFileName(fileName: String): Boolean

    fun locationLabel(location: String): String

    fun buildBreadcrumbs(location: String): List<VfsBreadcrumb>

    fun resolveTransferOperation(sourceLocation: String, targetLocation: String): FileTransferOperation

    suspend fun loadTextPreview(request: PreviewTextRequest): PreviewTextResult

    suspend fun readFileHash(request: FileHashRequest): FileHashResult

    suspend fun readArchiveInfo(request: ArchiveInfoRequest): ArchiveInfoResult

    suspend fun loadThumbnail(location: String, maxDimension: Int): ImageBitmap?

    suspend fun loadArchiveThumbnail(location: String, maxDimension: Int): ImageBitmap?

    suspend fun readImageSize(entry: VFile): IntSize?
}

/** 根组件状态、子面板和统一意图入口。 */
internal interface RootComponent : RootPlatformServices, RootPresentationServices {
    val state: StateFlow<RootState>
    val imageViewerState: StateFlow<ImageViewerState>
    val primaryPane: PaneComponent
    val secondaryPane: PaneComponent

    fun dispatch(intent: RootIntent)

}
