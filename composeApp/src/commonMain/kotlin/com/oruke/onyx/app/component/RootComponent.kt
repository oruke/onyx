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
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.AppLocale
import com.oruke.onyx.core.model.BatchRenameDraft
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.ImageFitMode
import com.oruke.onyx.core.model.ImageViewerState
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.SidebarSectionKey
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.PaneRoleState
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.core.model.RemoteConnectionSavePolicy
import com.oruke.onyx.core.model.S3ConnectionConfig
import com.oruke.onyx.core.model.SearchScope
import com.oruke.onyx.core.model.SystemQuickAccessLocation
import com.oruke.onyx.core.model.VFile
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.StateFlow

internal data class RootState(
    /** 当前文件面板布局模式。 */
    val layoutMode: PaneLayoutMode,
    /** 双面板分隔位置比例。 */
    val paneSplitFraction: Float,
    /** 当前接收用户操作的面板。 */
    val activePane: PaneId,
    /** 主次面板当前角色关系。 */
    val paneRoles: PaneRoleState = PaneRoleState.fromSource(activePane),
    /** 主面板状态。 */
    val primaryPane: PaneState,
    /** 次面板状态。 */
    val secondaryPane: PaneState,
    /** 侧栏目录树状态。 */
    val sidebarTreeState: SidebarTreeState,
    /** 操作系统文件管理器提供的快速访问位置。 */
    val systemQuickAccessLocations: List<SystemQuickAccessLocation>,
    /** 当前应用设置。 */
    val settings: OnyxSettings,
    /** 会话恢复进度。 */
    val sessionRestoreState: SessionRestoreState,
    /** 当前根级对话框状态。 */
    val dialogState: RootDialogState?,
    /** 当前剪贴板是否包含可粘贴文件。 */
    val canPaste: Boolean,
    /** 后台任务快照。 */
    val tasks: List<BackgroundTask>,
    /** 是否显示预览面板。 */
    val showPreviewPane: Boolean = false,
    /** 文件搜索面板状态。 */
    val searchState: SearchPanelState = SearchPanelState(),
    /** 与完整搜索互相独立的快速打开状态。 */
    val quickOpenState: SearchPanelState = SearchPanelState(mode = SearchPanelMode.QUICK_OPEN),
    /** 可撤销与重做的文件操作历史状态。 */
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

    /** 批量重命名的规则草稿、预设与执行状态。 */
    data class BatchRename(
        /** 发起操作的文件面板。 */
        val paneId: PaneId,
        /** 当前待处理条目，保持组件给出的顺序。 */
        val entries: List<VFile>,
        /** 是否正在执行文件变更。 */
        val executing: Boolean = false,
        /** 已完成比例。 */
        val progress: Float = 0f,
        /** 已处理条目数量。 */
        val processedCount: Int = 0,
        /** 当前任务的进度详情。 */
        val currentDetail: String = "",
        /** 整批操作是否执行完成。 */
        val completed: Boolean = false,
        /** 文件执行失败信息，与表单校验错误区分。 */
        val errorMessage: I18nMessage? = null,
        /** 组件持有的表单草稿。 */
        val draft: BatchRenameDraft = BatchRenameDraft(),
        /** 组件规划后的完整名称预览。 */
        val renameResults: List<Pair<VFile, String>> = emptyList(),
        /** 预览校验失败，失败时禁止执行。 */
        val validationMessage: I18nMessage? = null,
        /** 当前选中的已保存预设。 */
        val selectedPresetId: String? = null,
        /** 保存或覆盖预设使用的名称草稿。 */
        val presetName: String = "",
    ) : RootDialogState

    /**
     * 创建 ZIP 压缩包对话框状态。
     *
     * @property paneId 发起压缩操作的面板标识。
     * @property location 新归档写入的父目录位置。
     * @property entries 待打包的文件或目录。
     * @property draft 用户输入的归档名称草稿。
     * @property error 当前名称校验错误。
     */
    data class CreateZipArchive(
        /** 发起压缩操作的面板标识。 */
        val paneId: PaneId,
        /** 新归档写入的父目录位置。 */
        val location: String,
        /** 待打包的文件或目录。 */
        val entries: List<VFile>,
        /** 用户输入的归档名称草稿。 */
        val draft: String,
        /** 当前名称校验错误。 */
        val error: CreateZipArchiveDialogError? = null,
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

/** ZIP 归档名称输入对话框的校验错误。 */
internal enum class CreateZipArchiveDialogError {
    /** 未输入归档名称。 */
    EMPTY_INPUT,

    /** 归档名称包含路径分隔符或跨平台非法字符。 */
    INVALID_NAME,
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

    /** 连接可用及已验证能力，供界面按当前语言展示。 */
    data class Reachable(
        /** 已验证的协议能力；由界面按当前语言生成展示文案。 */
        val capabilities: Set<VfsProviderCapability>,
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
    /** 在指定面板上下文中执行用户命令，菜单可提供显式目标条目。 */
    data class ExecuteUserCommand(
        /** 命令来源面板。 */
        val paneId: PaneId,
        /** 持久化命令标识。 */
        val id: String,
        /** 右键上下文目标；为空时使用面板当前选择。 */
        val entries: List<VFile>? = null,
    ) : RootIntent

    /** 更新组件批量重命名草稿并重新计算预览。 */
    data class UpdateBatchRenameDraft(
        /** 包含规则与序号输入的完整草稿。 */
        val draft: BatchRenameDraft,
    ) : RootIntent

    /** 选择一个已保存的批量重命名预设。 */
    data class LoadBatchRenamePreset(
        /** 要恢复到编辑器的预设标识。 */
        val id: String,
    ) : RootIntent

    /** 更新预设名称草稿。 */
    data class UpdateBatchRenamePresetName(
        /** 待保存的预设显示名称。 */
        val name: String,
    ) : RootIntent

    /** 将当前规则保存为预设或覆盖当前选中预设。 */
    data object SaveBatchRenamePreset : RootIntent

    /** 保留当前规则，清除选中记录以另存为独立预设。 */
    data object NewBatchRenamePreset : RootIntent

    /** 删除当前选中预设，保留正在编辑的规则。 */
    data object DeleteBatchRenamePreset : RootIntent
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

    /** 只更新指定窗口尺寸，不携带可能已经过时的整份设置。 */
    data class UpdateWindowSize(
        /** 本次调整的窗口。 */
        val windowKind: RootWindowKind,
        /** 窗口逻辑宽度。 */
        val width: Int,
        /** 窗口逻辑高度。 */
        val height: Int,
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

    data class ToggleSidebarSection(
        val section: SidebarSectionKey,
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

    data object ShowQuickOpen : RootIntent

    data object CloseQuickOpen : RootIntent

    /** 更新快速打开的查询，不触碰完整搜索。 */
    data class UpdateQuickOpenQuery(
        /** 快速打开的独立查询文本。 */
        val query: String,
    ) : RootIntent

    /** 执行快速打开查询。 */
    data object ExecuteQuickOpen : RootIntent

    /** 在快速打开的来源面板打开结果。 */
    data class OpenQuickOpenResult(
        /** 要通过来源面板打开的条目。 */
        val entry: VFile,
    ) : RootIntent

    /** 在快速打开的来源面板定位结果。 */
    data class OpenQuickOpenResultInFolder(
        /** 要在父目录定位并选中的条目。 */
        val entry: VFile,
    ) : RootIntent

    /** 更新快速打开的选中结果。 */
    data class SetQuickOpenSelectedIndex(
        /** 快速打开结果列表中的目标索引。 */
        val index: Int,
    ) : RootIntent

    /** 更新完整搜索的选中结果。 */
    data class SetSearchSelectedIndex(
        /** 完整搜索结果列表中的目标索引。 */
        val index: Int,
    ) : RootIntent

    /** 设置完整搜索的高级筛选区域可见性。 */
    data class SetSearchAdvancedFiltersExpanded(
        /** 高级条件区域是否展开。 */
        val expanded: Boolean,
    ) : RootIntent

    /** 明确允许或禁止当前搜索包含网络位置。 */
    data class SetSearchIncludeRemoteLocations(
        /** 是否明确允许本会话扫描网络位置。 */
        val include: Boolean,
    ) : RootIntent

    /** 显式把完整搜索绑定到当前活动目录。 */
    data object UseCurrentDirectoryForSearch : RootIntent

    data class UpdateSearchQuery(
        val query: String,
    ) : RootIntent

    data object ExecuteSearch : RootIntent

    data object CancelSearch : RootIntent

    data class OpenSearchResult(
        val entry: VFile,
    ) : RootIntent

    data object OpenSearchResultsAsCollection : RootIntent

    data class OpenSearchResultInFolder(
        val entry: VFile,
    ) : RootIntent

    data class UpdateSearchScope(
        val scope: SearchScope,
    ) : RootIntent

    data class UpdateSearchFilters(
        val filters: SearchFilters,
    ) : RootIntent

    data object ClearSearchHistory : RootIntent

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

    /**
     * 打开 ZIP 压缩包名称输入对话框。
     *
     * @property paneId 发起操作的面板标识。
     */
    data class BeginCreateZipArchiveInPane(
        /** 发起操作的面板标识。 */
        val paneId: PaneId,
    ) : RootIntent

    /**
     * 更新 ZIP 压缩包名称草稿。
     *
     * @property draft 用户输入的名称草稿。
     */
    data class UpdateZipArchiveNameDraft(
        /** 用户输入的名称草稿。 */
        val draft: String,
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

    /** 清理已成功或已取消的任务，保留失败记录与活动任务。 */
    data object ClearFinishedTasks : RootIntent

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
internal interface RootComponent : RootPlatformServices, RootPresentationServices, RootCustomizationServices {
    /** 应用级语言环境就绪信号，所有窗口只消费该信号。 */
    val resourceLocale: StateFlow<AppLocale>

    val state: StateFlow<RootState>
    val imageViewerState: StateFlow<ImageViewerState>
    val primaryPane: PaneComponent
    val secondaryPane: PaneComponent

    /** 所有文件管理器窗口共享的任务中心。 */
    val taskCenter: TaskCenterComponent

    fun dispatch(intent: RootIntent)

}
