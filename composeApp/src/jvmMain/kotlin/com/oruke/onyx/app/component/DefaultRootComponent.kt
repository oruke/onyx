package com.oruke.onyx.app.component

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.childContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.oruke.onyx.app.filesystem.ArchiveService
import com.oruke.onyx.app.filesystem.ArchiveEntryOpenService
import com.oruke.onyx.app.filesystem.ExternalOpenService
import com.oruke.onyx.app.filesystem.FileCommandService
import com.oruke.onyx.app.filesystem.FileRepository
import com.oruke.onyx.app.filesystem.FileTypeService
import com.oruke.onyx.app.filesystem.ImageMetadataService
import com.oruke.onyx.app.filesystem.OpenWithApp
import com.oruke.onyx.app.filesystem.OpenWithService
import com.oruke.onyx.app.filesystem.PreviewService
import com.oruke.onyx.app.filesystem.PreviewTextRequest
import com.oruke.onyx.app.filesystem.PreviewTextResult
import com.oruke.onyx.app.filesystem.RemoteAuthStore
import com.oruke.onyx.app.filesystem.RemoteCredentialSavePolicy
import com.oruke.onyx.app.filesystem.RemoteCredentialSaveResult
import com.oruke.onyx.app.filesystem.SessionRepository
import com.oruke.onyx.app.filesystem.SettingsRepository
import com.oruke.onyx.app.filesystem.TerminalLauncherService
import com.oruke.onyx.app.filesystem.TextClipboardService
import com.oruke.onyx.app.filesystem.ThumbnailService
import com.oruke.onyx.app.filesystem.TransferConflictStrategy
import com.oruke.onyx.app.filesystem.TrashService
import com.oruke.onyx.app.filesystem.VfsAuthContext
import com.oruke.onyx.app.filesystem.VfsBreadcrumb
import com.oruke.onyx.app.filesystem.VfsConnectionTestRequest
import com.oruke.onyx.app.filesystem.VfsConnectionTestResult
import com.oruke.onyx.app.filesystem.VfsConnectionTestService
import com.oruke.onyx.app.filesystem.VfsPathService
import com.oruke.onyx.app.filesystem.VfsProviderError
import com.oruke.onyx.app.filesystem.VfsProviderRegistry
import com.oruke.onyx.app.filesystem.VfsProtocol
import com.oruke.onyx.app.usecase.FileSearchEvent
import com.oruke.onyx.app.usecase.FileSearchRequest
import com.oruke.onyx.app.usecase.FileSearchUseCase
import com.oruke.onyx.core.model.AppSessionSnapshot
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.DeleteMode
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.ImageFitMode
import com.oruke.onyx.core.model.ImageViewerState
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.PaneSessionSnapshot
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.core.model.RemoteConnectionSavePolicy
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.util.UUID
import com.oruke.onyx.app.component.delegate.ArchiveActionDelegate
import com.oruke.onyx.app.component.delegate.ClipboardManager
import com.oruke.onyx.app.component.delegate.FileActionDelegate
import com.oruke.onyx.app.component.delegate.FileTransferDelegate
import com.oruke.onyx.app.component.delegate.ImageViewerController
import com.oruke.onyx.app.component.delegate.SessionManager
import com.oruke.onyx.app.component.delegate.SidebarDelegate
import com.oruke.onyx.app.component.delegate.TaskOrchestrator
import com.oruke.onyx.app.platform.ExternalDragHelper

@OptIn(kotlinx.coroutines.FlowPreview::class)
class DefaultRootComponent(
    componentContext: ComponentContext,
    private val fileRepository: FileRepository,
    private val fileCommandService: FileCommandService,
    private val textClipboardService: TextClipboardService,
    private val trashService: TrashService,
    private val externalOpenService: ExternalOpenService,
    private val settingsRepository: SettingsRepository,
    private val sessionRepository: SessionRepository,
    private val archiveService: ArchiveService,
    private val openWithService: OpenWithService,
    private val pathService: VfsPathService,
    private val entryNameSuggestionService: EntryNameSuggestionService,
    private val providerRegistry: VfsProviderRegistry,
    private val fileTypeService: FileTypeService,
    private val archiveEntryOpenService: ArchiveEntryOpenService,
    private val terminalLauncherService: TerminalLauncherService,
    private val previewService: PreviewService,
    private val thumbnailService: ThumbnailService,
    private val imageMetadataService: ImageMetadataService,
    private val connectionTestService: VfsConnectionTestService,
    private val remoteAuthStore: RemoteAuthStore,
    // ── Delegate ──────────────────────────────────────────────────────────
    private val taskOrchestrator: TaskOrchestrator,
    private val clipboardManager: ClipboardManager,
    private val imageViewerController: ImageViewerController,
    private val sessionManager: SessionManager,
) : RootComponent, ComponentContext by componentContext {

    // 生命周期绑定的 CoroutineScope — lifecycle.onDestroy 时自动取消
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
        lifecycle.doOnDestroy { s.cancel() }
    }

    override val primaryPane: PaneComponent = DefaultPaneComponent(
        componentContext = childContext("primaryPane"),
        paneId = PaneId.PRIMARY,
        initialLocation = fileRepository.defaultLocation(),
        fileRepository = fileRepository,
        fileCommandService = fileCommandService,
        textClipboardService = textClipboardService,
        externalOpenService = externalOpenService,
        pathService = pathService,
        entryNameSuggestionService = entryNameSuggestionService,
        fileTypeService = fileTypeService,
        archiveEntryOpenService = archiveEntryOpenService,
        onOpenImageViewer = { file, allImages -> openImageViewer(file, allImages) },
        onRemoteAuthenticationRequired = ::requestRemoteCredentials,
    )
    override val secondaryPane: PaneComponent = DefaultPaneComponent(
        componentContext = childContext("secondaryPane"),
        paneId = PaneId.SECONDARY,
        initialLocation = fileRepository.defaultLocation(),
        fileRepository = fileRepository,
        fileCommandService = fileCommandService,
        textClipboardService = textClipboardService,
        externalOpenService = externalOpenService,
        pathService = pathService,
        entryNameSuggestionService = entryNameSuggestionService,
        fileTypeService = fileTypeService,
        archiveEntryOpenService = archiveEntryOpenService,
        onOpenImageViewer = { file, allImages -> openImageViewer(file, allImages) },
        onRemoteAuthenticationRequired = ::requestRemoteCredentials,
    )

    private val layoutMode = MutableStateFlow(PaneLayoutMode.DUAL_VERTICAL)
    private val paneSplitFraction = MutableStateFlow(0.5f)
    private val activePane = MutableStateFlow(PaneId.PRIMARY)
    private val settings = MutableStateFlow(OnyxSettings())
    private val sessionRestoreState = MutableStateFlow<SessionRestoreState>(SessionRestoreState.Loading)
    private val dialogState = MutableStateFlow<RootDialogState?>(null)
    private val showPreviewPane = MutableStateFlow(false)
    override val imageViewerState: StateFlow<ImageViewerState> = imageViewerController.state
    private var persistenceReady = false

    // ── 业务委托 ──────────────────────────────────────────────────────────
    private val sidebarDelegate = SidebarDelegate(scope = scope, fileRepository = fileRepository)
    private val fileTransferDelegate = FileTransferDelegate(
        scope = scope,
        fileCommandService = fileCommandService,
        fileRepository = fileRepository,
        taskOrchestrator = taskOrchestrator,
        clipboardManager = clipboardManager,
        dialogState = dialogState,
        pathService = pathService,
        providerRegistry = providerRegistry,
        onRefreshAllPanes = ::refreshAllPanes,
    )
    private val archiveActionDelegate = ArchiveActionDelegate(
        scope = scope,
        archiveService = archiveService,
        taskOrchestrator = taskOrchestrator,
        dialogState = dialogState,
        onRefreshAllPanes = ::refreshAllPanes,
    )
    private val fileActionDelegate = FileActionDelegate(
        scope = scope,
        fileCommandService = fileCommandService,
        trashService = trashService,
        taskOrchestrator = taskOrchestrator,
        dialogState = dialogState,
        onRefreshAllPanes = ::refreshAllPanes,
        onRefreshPane = { paneId -> paneComponent(paneId).refresh() },
        getPaneState = ::paneState,
    )
    private val fileSearchUseCase = FileSearchUseCase(fileRepository)
    private val searchState = MutableStateFlow(
        SearchPanelState(rootLocation = fileRepository.defaultLocation()),
    )
    private var searchJob: Job? = null
    private var searchRunId: String? = null
    private val mutableState = MutableStateFlow(
        RootState(
            layoutMode = layoutMode.value,
            paneSplitFraction = paneSplitFraction.value,
            activePane = activePane.value,
            primaryPane = primaryPane.state.value,
            secondaryPane = secondaryPane.state.value,
            sidebarTreeState = sidebarDelegate.sidebarTreeState.value,
            settings = settings.value,
            sessionRestoreState = sessionRestoreState.value,
            dialogState = dialogState.value,
            canPaste = clipboardManager.canPaste,
            tasks = taskOrchestrator.tasks.value,
            showPreviewPane = showPreviewPane.value,
            searchState = searchState.value,
        )
    )

    override val state: StateFlow<RootState> = mutableState.asStateFlow()

    init {
        // 使用嵌套 combine 保持类型安全（每个 combine ≤ 5 参数）
        scope.launch {
            val layoutFlow = combine(
                layoutMode, paneSplitFraction, activePane, showPreviewPane,
            ) { mode, fraction, pane, preview ->
                LayoutSlice(mode, fraction, pane, preview)
            }
            val paneFlow = combine(
                primaryPane.state, secondaryPane.state,
            ) { primary, secondary -> primary to secondary }
            val contextFlow = combine(
                combine(
                    sidebarDelegate.sidebarTreeState, settings, sessionRestoreState,
                ) { sidebar, stgs, restore -> Triple(sidebar, stgs, restore) },
                combine(
                    dialogState, clipboardManager.clipboard, taskOrchestrator.tasks, searchState,
                ) { dialog, clipboard, taskList, search ->
                    RuntimeContextSlice(
                        dialogState = dialog,
                        canPaste = clipboard != null,
                        tasks = taskList,
                        searchState = search,
                    )
                },
            ) { (sidebar, stgs, restore), runtime ->
                ContextSlice(
                    sidebarTreeState = sidebar,
                    settings = stgs,
                    sessionRestoreState = restore,
                    dialogState = runtime.dialogState,
                    canPaste = runtime.canPaste,
                    tasks = runtime.tasks,
                    searchState = runtime.searchState,
                )
            }

            combine(layoutFlow, paneFlow, contextFlow) { layout, panes, context ->
                RootState(
                    layoutMode = layout.mode,
                    paneSplitFraction = layout.fraction,
                    activePane = layout.activePane,
                    primaryPane = panes.first,
                    secondaryPane = panes.second,
                    sidebarTreeState = context.sidebarTreeState,
                    settings = context.settings,
                    sessionRestoreState = context.sessionRestoreState,
                    dialogState = context.dialogState,
                    canPaste = context.canPaste,
                    tasks = context.tasks,
                    showPreviewPane = layout.showPreviewPane,
                    searchState = context.searchState,
                )
            }.collect { combinedState ->
                mutableState.value = combinedState
            }
        }

        scope.launch {
            combine(
                layoutMode,
                paneSplitFraction,
                activePane,
                primaryPane.state,
                secondaryPane.state,
                settings,
            ) { _ -> Unit }
                .debounce(500)
                .collect {
                    if (persistenceReady) {
                        persistCurrentState()
                    }
                }
        }

        scope.launch {
            restorePersistedState()
        }

        scope.launch {
            combine(primaryPane.state, secondaryPane.state) { primaryState, secondaryState ->
                listOf(primaryState.location, secondaryState.location)
            }.collect { locations ->
                if (persistenceReady) {
                    recordRecentLocations(*locations.toTypedArray())
                }
            }
        }

        scope.launch {
            combine(
                activePane,
                primaryPane.state,
                secondaryPane.state
            ) { currentActivePane, primaryState, secondaryState ->
                if (currentActivePane == PaneId.PRIMARY) primaryState.location else secondaryState.location
            }.collect { location ->
                sidebarDelegate.ensureLocationVisible(location)
            }
        }
    }

    override fun dispatch(intent: RootIntent) {
        when (intent) {
            is RootIntent.SetLayoutMode -> setLayoutMode(intent.mode)
            is RootIntent.SetPaneSplitFraction -> setPaneSplitFraction(intent.fraction)
            RootIntent.OpenSettings -> openSettings()
            is RootIntent.UpdateSettingsDraft -> updateSettingsDraft(intent.draft)
            is RootIntent.UpdateRemoteConnectionDraft -> updateRemoteConnectionDraft(intent.draft)
            is RootIntent.EditRemoteConnection -> editRemoteConnection(intent.profile)
            RootIntent.NewRemoteConnection -> newRemoteConnection()
            RootIntent.SaveRemoteConnectionDraft -> saveRemoteConnectionDraft()
            RootIntent.TestRemoteConnectionDraft -> testRemoteConnectionDraft()
            is RootIntent.DeleteRemoteConnection -> deleteRemoteConnection(intent.id)
            is RootIntent.OpenRemoteConnection -> openRemoteConnection(intent.location)
            is RootIntent.ActivatePane -> activatePane(intent.paneId)
            is RootIntent.UpdateSettings -> updateSettings(intent.settings)
            is RootIntent.OpenLocationInActivePane -> openLocationInActivePane(intent.location)
            is RootIntent.ToggleFavoriteLocation -> toggleFavoriteLocation(intent.location)
            is RootIntent.ToggleSidebarTreeNode -> toggleSidebarTreeNode(intent.location)
            is RootIntent.RetrySidebarTreeNode -> retrySidebarTreeNode(intent.location)
            is RootIntent.BeginCreateDirectoriesInPane -> beginCreateDirectoriesInPane(intent.paneId)
            is RootIntent.UpdateCreateDirectoriesDraft -> updateCreateDirectoriesDraft(intent.draft)
            RootIntent.ConfirmDialog -> confirmDialog()
            RootIntent.DismissDialog -> dismissDialog()
            is RootIntent.ResolveConflict -> resolveConflict(
                strategy = intent.strategy,
                applyToAll = intent.applyToAll,
            )
            is RootIntent.MoveTab -> moveTab(
                sourcePaneId = intent.sourcePaneId,
                tabId = intent.tabId,
                targetPaneId = intent.targetPaneId,
                targetIndex = intent.targetIndex,
            )
            RootIntent.RefreshActivePane -> refreshActivePane()
            RootIntent.TogglePreviewPane -> togglePreviewPane()
            RootIntent.ShowSearchPanel -> showSearchPanel()
            RootIntent.CloseSearchPanel -> closeSearchPanel()
            is RootIntent.UpdateSearchQuery -> updateSearchQuery(intent.query)
            RootIntent.ExecuteSearch -> executeSearch()
            RootIntent.CancelSearch -> cancelSearch()
            is RootIntent.OpenSearchResult -> openSearchResult(intent.entry)
            is RootIntent.StageCopySelectedInPane -> stageCopySelectedInPane(intent.paneId)
            is RootIntent.StageCutSelectedInPane -> stageCutSelectedInPane(intent.paneId)
            is RootIntent.RequestPasteIntoPane -> requestPasteIntoPane(intent.paneId)
            is RootIntent.RequestTransferSelectedToDirectory -> requestTransferSelectedToDirectory(
                sourcePaneId = intent.sourcePaneId,
                targetDirectoryLocation = intent.targetDirectoryLocation,
                operation = intent.operation,
            )
            is RootIntent.RequestDeleteSelectedInPane -> requestDeleteSelectedInPane(intent.paneId)
            is RootIntent.ExtractSelectedInPane -> extractSelectedInPane(intent.paneId)
            is RootIntent.ExtractToDirectoryInPane -> extractToDirectoryInPane(intent.paneId)
            is RootIntent.ExtractSmartInPane -> extractSmartInPane(intent.paneId)
            is RootIntent.SubmitArchivePassword -> submitArchivePassword(intent.password)
            is RootIntent.UpdateRemoteCredentialsDraft -> updateRemoteCredentialsDraft(intent.draft)
            RootIntent.SubmitRemoteCredentials -> submitRemoteCredentials()
            is RootIntent.BatchRenameInPane -> batchRenameInPane(intent.paneId)
            is RootIntent.ExecuteBatchRename -> executeBatchRename(
                paneId = intent.paneId,
                renameMap = intent.renameMap,
            )
            is RootIntent.ResetBatchRenameForContinue -> resetBatchRenameForContinue(intent.paneId)
            is RootIntent.DismissTask -> dismissTask(intent.taskId)
            is RootIntent.CancelTask -> cancelTask(intent.taskId)
            is RootIntent.PauseTask -> pauseTask(intent.taskId)
            is RootIntent.ResumeTask -> resumeTask(intent.taskId)
            is RootIntent.RetryTask -> retryTask(intent.taskId)
            RootIntent.ClearAllTasks -> clearAllTasks()
            is RootIntent.OpenImageViewer -> openImageViewer(
                file = intent.file,
                allImages = intent.allImages,
            )
            RootIntent.CloseImageViewer -> closeImageViewer()
            RootIntent.ImageViewerNext -> imageViewerNext()
            RootIntent.ImageViewerPrevious -> imageViewerPrevious()
            is RootIntent.ImageViewerSetZoom -> imageViewerSetZoom(intent.factor)
            is RootIntent.ImageViewerSetFitMode -> imageViewerSetFitMode(intent.mode)
            is RootIntent.ImageViewerRotate -> imageViewerRotate(intent.clockwise)
            is RootIntent.OpenWithApp -> openWithApp(
                entry = intent.entry,
                app = intent.app,
            )
            is RootIntent.OpenWithChooser -> openWithChooser(intent.entry)
            is RootIntent.OpenTerminalAt -> openTerminalAt(intent.location)
        }
    }

    fun setLayoutMode(mode: PaneLayoutMode) {
        layoutMode.value = mode
    }

    fun setPaneSplitFraction(fraction: Float) {
        paneSplitFraction.value = fraction.coerceIn(0.18f, 0.82f)
    }

    fun openSettings() {
        dialogState.value = RootDialogState.Settings(draft = settings.value)
    }

    fun updateSettingsDraft(draft: OnyxSettings) {
        val currentDialog = dialogState.value as? RootDialogState.Settings ?: return
        dialogState.value = currentDialog.copy(draft = draft)
    }

    fun updateRemoteConnectionDraft(draft: RemoteConnectionDraft) {
        val currentDialog = dialogState.value as? RootDialogState.Settings ?: return
        dialogState.value = currentDialog.copy(
            remoteConnectionDraft = draft,
            remoteConnectionError = null,
            remoteConnectionTestState = RemoteConnectionTestState.Idle,
        )
    }

    fun editRemoteConnection(profile: RemoteConnectionProfile) {
        val currentDialog = dialogState.value as? RootDialogState.Settings ?: return
        dialogState.value = currentDialog.copy(
            editingRemoteConnectionId = profile.id,
            remoteConnectionDraft = profile.toRemoteConnectionDraft(),
            remoteConnectionError = null,
            remoteConnectionTestState = RemoteConnectionTestState.Idle,
        )
    }

    fun newRemoteConnection() {
        val currentDialog = dialogState.value as? RootDialogState.Settings ?: return
        dialogState.value = currentDialog.copy(
            editingRemoteConnectionId = null,
            remoteConnectionDraft = RemoteConnectionDraft(),
            remoteConnectionError = null,
            remoteConnectionTestState = RemoteConnectionTestState.Idle,
        )
    }

    fun saveRemoteConnectionDraft() {
        val currentDialog = dialogState.value as? RootDialogState.Settings ?: return
        val draft = currentDialog.remoteConnectionDraft
        val name = draft.name.trim()
        val rawLocation = draft.location.trim()
        when {
            name.isEmpty() -> {
                dialogState.value = currentDialog.copy(remoteConnectionError = RemoteConnectionDialogError.NAME_EMPTY)
                return
            }

            rawLocation.isEmpty() -> {
                dialogState.value = currentDialog.copy(remoteConnectionError = RemoteConnectionDialogError.LOCATION_EMPTY)
                return
            }

            draft.hasCredentialInput() && draft.username.trim().isEmpty() -> {
                dialogState.value = currentDialog.copy(remoteConnectionError = RemoteConnectionDialogError.USERNAME_EMPTY)
                return
            }
        }

        val location = draft.normalizedLocation()
        val authContext = draft.toAuthContextOrNull()
        if (authContext != null) {
            val saveResult = remoteAuthStore.put(
                protocol = draft.protocol.toVfsProtocol(),
                location = location,
                authContext = authContext,
                savePolicy = draft.savePolicy.toRemoteCredentialSavePolicy(),
            )
            if (saveResult == RemoteCredentialSaveResult.UNSUPPORTED) {
                dialogState.value = currentDialog.copy(
                    remoteConnectionError = RemoteConnectionDialogError.SYSTEM_KEYRING_UNAVAILABLE,
                )
                return
            }
        }

        val profile = draft.toRemoteConnectionProfile(
            id = currentDialog.editingRemoteConnectionId ?: UUID.randomUUID().toString(),
            location = location,
        )
        val nextConnections = currentDialog.draft.remoteConnections
            .filterNot { existing -> existing.id == profile.id } + profile
        dialogState.value = currentDialog.copy(
            draft = currentDialog.draft.copy(remoteConnections = nextConnections),
            editingRemoteConnectionId = null,
            remoteConnectionDraft = RemoteConnectionDraft(),
            remoteConnectionError = null,
            remoteConnectionTestState = RemoteConnectionTestState.Idle,
        )
    }

    fun testRemoteConnectionDraft() {
        val currentDialog = dialogState.value as? RootDialogState.Settings ?: return
        val draft = currentDialog.remoteConnectionDraft
        val rawLocation = draft.location.trim()
        if (rawLocation.isEmpty()) {
            dialogState.value = currentDialog.copy(remoteConnectionError = RemoteConnectionDialogError.LOCATION_EMPTY)
            return
        }
        if (draft.hasCredentialInput() && draft.username.trim().isEmpty()) {
            dialogState.value = currentDialog.copy(remoteConnectionError = RemoteConnectionDialogError.USERNAME_EMPTY)
            return
        }

        val request = VfsConnectionTestRequest(
            protocol = draft.protocol.toVfsProtocol(),
            location = draft.normalizedLocation(),
            authContext = draft.toAuthContextOrNull() ?: VfsAuthContext.None,
        )
        dialogState.value = currentDialog.copy(
            remoteConnectionError = null,
            remoteConnectionTestState = RemoteConnectionTestState.Testing,
        )
        scope.launch {
            val testState = when (val result = connectionTestService.testConnection(request)) {
                is VfsConnectionTestResult.Reachable -> RemoteConnectionTestState.Reachable(
                    capabilities = result.capabilities.mapTo(sortedSetOf()) { capability -> capability.name },
                )

                is VfsConnectionTestResult.Failed -> RemoteConnectionTestState.Failed(
                    reason = I18nMessage(MessageKey.MSG_STRING_LITERAL, result.error.toConnectionMessage()),
                )
            }
            val latestDialog = dialogState.value as? RootDialogState.Settings ?: return@launch
            dialogState.value = latestDialog.copy(remoteConnectionTestState = testState)
        }
    }

    fun deleteRemoteConnection(id: String) {
        val currentDialog = dialogState.value as? RootDialogState.Settings ?: return
        val nextDraft = currentDialog.draft.copy(
            remoteConnections = currentDialog.draft.remoteConnections.filterNot { profile -> profile.id == id },
        )
        val editingId = currentDialog.editingRemoteConnectionId
        dialogState.value = currentDialog.copy(
            draft = nextDraft,
            editingRemoteConnectionId = editingId?.takeUnless { it == id },
            remoteConnectionDraft = if (editingId == id) RemoteConnectionDraft() else currentDialog.remoteConnectionDraft,
            remoteConnectionError = null,
            remoteConnectionTestState = RemoteConnectionTestState.Idle,
        )
    }

    fun openRemoteConnection(location: String) {
        dialogState.value = null
        openLocationInActivePane(location)
    }

    fun activatePane(paneId: PaneId) {
        activePane.value = paneId
    }

    fun updateSettings(settings: OnyxSettings) {
        this.settings.value = settings.sanitizeRootSettings()
    }

    fun openLocationInActivePane(location: String) {
        when (activePane.value) {
            PaneId.PRIMARY -> primaryPane.openDirectory(location)
            PaneId.SECONDARY -> secondaryPane.openDirectory(location)
        }
    }

    fun toggleFavoriteLocation(location: String) {
        val currentFavorites = settings.value.favoriteLocations
        updateSettings(
            settings.value.copy(
                favoriteLocations = if (currentFavorites.contains(location)) {
                    currentFavorites.filterNot { favoriteLocation -> favoriteLocation == location }
                } else {
                    currentFavorites + location
                },
            ),
        )
    }

    fun toggleSidebarTreeNode(location: String) = sidebarDelegate.toggleNode(location)

    fun retrySidebarTreeNode(location: String) = sidebarDelegate.retryNode(location)

    fun beginCreateDirectoriesInPane(paneId: PaneId) {
        dialogState.value = RootDialogState.CreateDirectories(
            paneId = paneId,
            location = paneState(paneId).location,
            draft = "",
        )
    }

    fun updateCreateDirectoriesDraft(draft: String) {
        val currentDialog = dialogState.value as? RootDialogState.CreateDirectories ?: return
        dialogState.value = currentDialog.copy(
            draft = draft,
            error = null,
        )
    }

    fun confirmDialog() {
        when (val currentDialog = dialogState.value) {
            is RootDialogState.DeleteSelectionConfirmation -> {
                dialogState.value = null
                val deleteRequest = fileActionDelegate.pendingDeleteRequest ?: return
                fileActionDelegate.clearPending()
                fileActionDelegate.executeDeleteRequest(deleteRequest)
            }

            is RootDialogState.CreateDirectories -> {
                val directoryPaths = parseDirectoryDraft(currentDialog.draft)
                if (directoryPaths.isEmpty()) {
                    dialogState.value = currentDialog.copy(error = CreateDirectoriesDialogError.EMPTY_INPUT)
                    return
                }
                dialogState.value = null
                fileActionDelegate.executeCreateDirectories(
                    paneId = currentDialog.paneId,
                    parentLocation = currentDialog.location,
                    paths = directoryPaths,
                )
            }

            is RootDialogState.Settings -> {
                dialogState.value = null
                updateSettings(currentDialog.draft)
            }

            else -> Unit
        }
    }

    fun dismissDialog() {
        fileActionDelegate.clearPending()
        fileTransferDelegate.clearPending()
        archiveActionDelegate.clearPending()
        dialogState.value = null
    }

    fun resolveConflict(
        strategy: TransferConflictStrategy,
        applyToAll: Boolean,
    ) = fileTransferDelegate.resolveConflict(strategy, applyToAll)

    fun moveTab(
        sourcePaneId: PaneId,
        tabId: String,
        targetPaneId: PaneId,
        targetIndex: Int,
    ) {
        if (sourcePaneId == targetPaneId) {
            paneComponent(sourcePaneId).moveTab(
                tabId = tabId,
                targetIndex = targetIndex,
            )
            activatePane(targetPaneId)
            return
        }

        val tabSnapshot = paneComponent(sourcePaneId).detachTab(tabId) ?: return
        paneComponent(targetPaneId).attachTab(
            tabSnapshot = tabSnapshot,
            targetIndex = targetIndex,
        )
        activatePane(targetPaneId)
    }

    fun refreshActivePane() {
        when (activePane.value) {
            PaneId.PRIMARY -> primaryPane.refresh()
            PaneId.SECONDARY -> secondaryPane.refresh()
        }
    }

    fun togglePreviewPane() {
        showPreviewPane.value = !showPreviewPane.value
    }

    fun showSearchPanel() {
        val paneId = activePane.value
        val location = paneState(paneId).location
        searchState.value = searchState.value.copy(
            visible = true,
            paneId = paneId,
            rootLocation = location,
            status = if (searchState.value.status == SearchStatus.RUNNING) {
                SearchStatus.RUNNING
            } else {
                SearchStatus.IDLE
            },
            error = null,
        )
    }

    fun closeSearchPanel() {
        val wasRunning = searchState.value.status == SearchStatus.RUNNING
        if (wasRunning) {
            searchRunId = null
            searchJob?.cancel()
            searchJob = null
        }
        searchState.value = searchState.value.copy(
            visible = false,
            status = if (wasRunning) SearchStatus.CANCELLED else searchState.value.status,
        )
    }

    fun updateSearchQuery(query: String) {
        if (query != searchState.value.query && searchState.value.status == SearchStatus.RUNNING) {
            cancelSearch()
        }
        searchState.value = searchState.value.copy(
            query = query,
            status = SearchStatus.IDLE,
            results = emptyList(),
            scannedEntryCount = 0,
            limitReached = false,
            error = null,
        )
    }

    fun executeSearch() {
        val current = searchState.value
        val query = current.query.trim()
        val paneId = current.paneId
        val rootLocation = current.rootLocation.ifBlank { paneState(paneId).location }
        searchJob?.cancel()
        searchJob = null

        if (query.isBlank()) {
            searchRunId = null
            searchState.value = current.copy(
                visible = true,
                rootLocation = rootLocation,
                status = SearchStatus.IDLE,
                results = emptyList(),
                scannedEntryCount = 0,
                limitReached = false,
                error = null,
            )
            return
        }

        val runId = UUID.randomUUID().toString()
        searchRunId = runId
        searchState.value = current.copy(
            visible = true,
            rootLocation = rootLocation,
            status = SearchStatus.RUNNING,
            results = emptyList(),
            scannedEntryCount = 0,
            limitReached = false,
            error = null,
        )

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                fileSearchUseCase.search(
                    FileSearchRequest(
                        rootLocation = rootLocation,
                        query = query,
                    )
                ).collect { event ->
                    if (searchRunId != runId) {
                        return@collect
                    }
                    reduceSearchEvent(event)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                if (searchRunId == runId) {
                    searchState.value = searchState.value.copy(
                        status = SearchStatus.FAILED,
                        error = failure.toSearchErrorMessage(),
                    )
                }
            } finally {
                if (searchRunId == runId) {
                    searchJob = null
                }
            }
        }
        searchJob = job
        job.start()
    }

    fun cancelSearch() {
        val wasRunning = searchState.value.status == SearchStatus.RUNNING
        searchRunId = null
        searchJob?.cancel()
        searchJob = null
        if (wasRunning) {
            searchState.value = searchState.value.copy(
                status = SearchStatus.CANCELLED,
                error = null,
            )
        }
    }

    fun openSearchResult(entry: VFile) {
        val paneId = searchState.value.paneId
        activatePane(paneId)
        paneComponent(paneId).openEntry(entry)
    }

    private fun reduceSearchEvent(event: FileSearchEvent) {
        when (event) {
            is FileSearchEvent.Progress -> {
                searchState.value = searchState.value.copy(
                    scannedEntryCount = event.scannedEntryCount,
                )
            }

            is FileSearchEvent.Results -> {
                searchState.value = searchState.value.copy(
                    status = SearchStatus.RUNNING,
                    results = event.entries,
                    scannedEntryCount = event.scannedEntryCount,
                    limitReached = event.limitReached,
                    error = null,
                )
            }

            is FileSearchEvent.Completed -> {
                searchState.value = searchState.value.copy(
                    status = SearchStatus.COMPLETED,
                    scannedEntryCount = event.scannedEntryCount,
                    limitReached = event.limitReached,
                    error = null,
                )
            }

            is FileSearchEvent.Failed -> {
                searchState.value = searchState.value.copy(
                    status = SearchStatus.FAILED,
                    scannedEntryCount = event.scannedEntryCount,
                    error = event.failure.toSearchErrorMessage(),
                )
            }
        }
    }

    fun stageCopySelectedInPane(paneId: PaneId) {
        clipboardManager.stageCopy(selectedEntriesInPane(paneId))
    }

    fun stageCutSelectedInPane(paneId: PaneId) {
        clipboardManager.stageCut(selectedEntriesInPane(paneId))
    }

    fun requestPasteIntoPane(paneId: PaneId) {
        val payload = clipboardManager.consume() ?: return
        val targetLocation = paneState(paneId).location
        fileTransferDelegate.requestTransferEntriesToDirectory(
            entries = payload.entries,
            targetDirectoryLocation = targetLocation,
            operation = when (payload.operation) {
                ClipboardManager.ClipboardOperation.COPY -> FileTransferOperation.COPY
                ClipboardManager.ClipboardOperation.CUT -> FileTransferOperation.MOVE
            },
            clearClipboardOnSuccess = payload.operation == ClipboardManager.ClipboardOperation.CUT,
        )
    }

    fun requestTransferSelectedToDirectory(
        sourcePaneId: PaneId,
        targetDirectoryLocation: String,
        operation: FileTransferOperation,
    ) {
        val entries = selectedEntriesInPane(sourcePaneId)
        if (entries.isEmpty()) {
            return
        }
        // 压缩包内条目 → 解压到目标目录（不能移动，只能解压）
        val archiveEntries = entries.filter { ArchiveService.parseArchiveLocation(it.location) != null }
        val localEntries = entries.filter { ArchiveService.parseArchiveLocation(it.location) == null }
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

    fun requestDeleteSelectedInPane(paneId: PaneId) {
        val selectedEntries = selectedEntriesInPane(paneId)
        if (selectedEntries.isEmpty()) {
            return
        }

        val moveToTrashPreferred = settings.value.deleteMode == DeleteMode.MOVE_TO_TRASH_PREFERRED
        val willMoveToTrash = moveToTrashPreferred && trashService.isSupported
        fileActionDelegate.setPendingDeleteRequest(
            FileActionDelegate.PendingDeleteRequest(
                paneId = paneId,
                entries = selectedEntries,
                moveToTrash = willMoveToTrash,
            )
        )
        dialogState.value = RootDialogState.DeleteSelectionConfirmation(
            moveToTrash = willMoveToTrash,
            itemCount = selectedEntries.size,
            trashUnavailable = moveToTrashPreferred && !trashService.isSupported,
        )
    }

    fun extractSelectedInPane(paneId: PaneId) {
        archiveActionDelegate.launchArchiveExtraction(
            selectedEntries = selectedEntriesInPane(paneId),
            currentLocation = paneState(paneId).location,
            taskTitle = I18nMessage(MessageKey.ACTION_EXTRACT_HERE),
        ) { entry, location, password ->
            archiveService.extract(
                archivePath = entry.location,
                targetDirectory = location,
                password = password,
            )
        }
    }

    fun extractToDirectoryInPane(paneId: PaneId) {
        archiveActionDelegate.launchArchiveExtraction(
            selectedEntries = selectedEntriesInPane(paneId),
            currentLocation = paneState(paneId).location,
            taskTitle = I18nMessage(MessageKey.ACTION_EXTRACT_TO_DIRECTORY),
        ) { entry, location, password ->
            archiveService.extractToDirectory(
                archivePath = entry.location,
                targetDirectory = location,
                password = password,
            )
        }
    }

    fun extractSmartInPane(paneId: PaneId) {
        archiveActionDelegate.launchArchiveExtraction(
            selectedEntries = selectedEntriesInPane(paneId),
            currentLocation = paneState(paneId).location,
            taskTitle = I18nMessage(MessageKey.ACTION_EXTRACT_SMART),
        ) { entry, location, password ->
            archiveService.extractSmart(
                archivePath = entry.location,
                targetDirectory = location,
                password = password,
            )
        }
    }

    fun submitArchivePassword(password: String) = archiveActionDelegate.submitArchivePassword(password)

    fun updateRemoteCredentialsDraft(draft: RemoteCredentialsDraft) {
        val currentDialog = dialogState.value as? RootDialogState.RemoteCredentials ?: return
        dialogState.value = currentDialog.copy(
            draft = draft,
            rejected = false,
            error = null,
        )
    }

    fun submitRemoteCredentials() {
        val currentDialog = dialogState.value as? RootDialogState.RemoteCredentials ?: return
        val username = currentDialog.draft.username.trim()
        if (username.isEmpty()) {
            dialogState.value = currentDialog.copy(error = RemoteCredentialsDialogError.USERNAME_EMPTY)
            return
        }

        val saveResult = remoteAuthStore.put(
            protocol = currentDialog.protocol,
            location = currentDialog.location,
            authContext = VfsAuthContext.UsernamePassword(
                username = username,
                password = currentDialog.draft.password,
                domain = currentDialog.draft.domain.trim().ifBlank { null },
            ),
            savePolicy = currentDialog.draft.savePolicy,
        )
        if (saveResult == RemoteCredentialSaveResult.UNSUPPORTED) {
            dialogState.value = currentDialog.copy(
                error = RemoteCredentialsDialogError.SYSTEM_KEYRING_UNAVAILABLE,
            )
            return
        }
        dialogState.value = null
        paneComponent(currentDialog.paneId).refresh()
    }

    fun batchRenameInPane(paneId: PaneId) {
        val selectedEntries = selectedEntriesInPane(paneId)
        if (selectedEntries.size < 2) return
        dialogState.value = RootDialogState.BatchRename(
            paneId = paneId,
            entries = selectedEntries,
        )
    }

    fun executeBatchRename(paneId: PaneId, renameMap: List<Pair<VFile, String>>) =
        fileActionDelegate.executeBatchRename(paneId, renameMap)

    fun resetBatchRenameForContinue(paneId: PaneId) =
        fileActionDelegate.resetBatchRenameForContinue(paneId)

    fun dismissTask(taskId: String) = taskOrchestrator.dismissTask(taskId)

    fun cancelTask(taskId: String) = taskOrchestrator.cancelTask(taskId)

    fun pauseTask(taskId: String) = taskOrchestrator.pauseTask(taskId)

    fun resumeTask(taskId: String) = taskOrchestrator.resumeTask(taskId)

    fun retryTask(taskId: String) = taskOrchestrator.retryTask(taskId)

    fun clearAllTasks() = taskOrchestrator.clearAllTasks()

    // ── 图片查看器 ────────────────────────────────────────────────────────────

    fun openImageViewer(file: VFile, allImages: List<VFile>) = imageViewerController.open(file, allImages)

    fun closeImageViewer() = imageViewerController.close()

    fun imageViewerNext() = imageViewerController.next()

    fun imageViewerPrevious() = imageViewerController.previous()

    fun imageViewerSetZoom(factor: Float) = imageViewerController.setZoom(factor)

    fun imageViewerSetFitMode(mode: ImageFitMode) = imageViewerController.setFitMode(mode)

    fun imageViewerRotate(clockwise: Boolean) = imageViewerController.rotate(clockwise)

    // ── 打开方式 ──────────────────────────────────────────────────────────

    override suspend fun listOpenWithApps(entry: VFile): List<OpenWithApp> {
        return openWithService.listApps(entry)
    }

    fun openWithApp(entry: VFile, app: OpenWithApp) {
        scope.launch {
            openWithService.openWith(entry, app)
        }
    }

    fun openWithChooser(entry: VFile) {
        scope.launch {
            openWithService.openWithChooser(entry)
        }
    }

    override fun prepareExternalDrag(entries: List<VFile>): Boolean {
        return ExternalDragHelper.preparePendingFiles(entries, archiveService)
    }

    override fun isArchiveFileName(fileName: String): Boolean {
        return fileTypeService.isArchiveFileName(fileName)
    }

    override fun isImageFileName(fileName: String): Boolean {
        return fileTypeService.isImageFileName(fileName)
    }

    override fun isTextPreviewFileName(fileName: String): Boolean {
        return fileTypeService.isTextPreviewFileName(fileName)
    }

    override fun locationLabel(location: String): String {
        return pathService.label(location)
    }

    override fun buildBreadcrumbs(location: String): List<VfsBreadcrumb> {
        return pathService.buildBreadcrumbs(location)
    }

    fun openTerminalAt(location: String) {
        scope.launch {
            terminalLauncherService.openTerminal(location)
        }
    }

    override fun resolveTransferOperation(sourceLocation: String, targetLocation: String): FileTransferOperation {
        return pathService.resolveTransferOperation(sourceLocation, targetLocation)
    }

    override suspend fun testRemoteConnection(request: VfsConnectionTestRequest): VfsConnectionTestResult {
        return connectionTestService.testConnection(request)
    }

    override suspend fun loadTextPreview(request: PreviewTextRequest): PreviewTextResult {
        return previewService.loadTextPreview(request)
    }

    override suspend fun loadThumbnail(location: String, maxDimension: Int): ImageBitmap? {
        return thumbnailService.loadThumbnail(location, maxDimension)
    }

    override suspend fun loadArchiveThumbnail(location: String, maxDimension: Int): ImageBitmap? {
        return thumbnailService.loadArchiveThumbnail(location, maxDimension)
    }

    override suspend fun readImageSize(entry: VFile): IntSize? {
        return imageMetadataService.readImageSize(entry)
    }

    private suspend fun restorePersistedState() {
        var restoreError: I18nMessage? = null
        settingsRepository.loadSettings().fold(
            onSuccess = { loadedSettings ->
                if (loadedSettings != null) {
                    settings.value = loadedSettings.copy(
                        uiScale = loadedSettings.uiScale.coerceIn(75, 200),
                    )
                }
            },
            onFailure = { failure ->
                restoreError = failure.message?.let { I18nMessage(MessageKey.MSG_STRING_LITERAL, it) }
                    ?: I18nMessage(MessageKey.MSG_LOAD_SETTINGS_FAILED)
            },
        )

        sessionRepository.loadSession().fold(
            onSuccess = { session ->
                if (session != null) {
                    applySession(session)
                } else {
                    layoutMode.value = settings.value.defaultLayoutMode
                    applyDefaultViewMode()
                }
            },
            onFailure = { failure ->
                restoreError = restoreError ?: failure.message?.let { I18nMessage(MessageKey.MSG_STRING_LITERAL, it) }
                    ?: I18nMessage(MessageKey.MSG_RESTORE_SESSION_FAILED)
                layoutMode.value = settings.value.defaultLayoutMode
                applyDefaultViewMode()
            },
        )

        sessionRestoreState.value = if (restoreError == null) {
            SessionRestoreState.Ready
        } else {
            SessionRestoreState.Failed(restoreError)
        }
        persistenceReady = true
        recordRecentLocations(
            primaryPane.state.value.location,
            secondaryPane.state.value.location,
        )
        persistCurrentState()
    }

    private fun applySession(snapshot: AppSessionSnapshot) {
        layoutMode.value = snapshot.layoutMode
        paneSplitFraction.value = snapshot.paneSplitFraction.coerceIn(0.18f, 0.82f)
        primaryPane.restoreSession(snapshot.primaryPane)
        secondaryPane.restoreSession(snapshot.secondaryPane)
        activePane.value = snapshot.activePane
    }

    private fun applyDefaultViewMode() {
        val defaultViewMode = settings.value.defaultViewMode
        primaryPane.setViewMode(defaultViewMode)
        secondaryPane.setViewMode(defaultViewMode)
    }

    private suspend fun persistCurrentState() {
        sessionManager.persist(settings.value, buildSessionSnapshot())
    }

    private fun buildSessionSnapshot(): AppSessionSnapshot {
        return AppSessionSnapshot(
            layoutMode = layoutMode.value,
            paneSplitFraction = paneSplitFraction.value,
            activePane = activePane.value,
            primaryPane = primaryPane.toPaneSessionSnapshot(),
            secondaryPane = secondaryPane.toPaneSessionSnapshot(),
        )
    }



    private fun parseDirectoryDraft(draft: String): List<String> {
        return draft
            .lines()
            .map { line -> line.trim() }
            .filter { line -> line.isNotBlank() }
            .distinct()
    }

    private fun recordRecentLocations(vararg locations: String) {
        val nextSettings = settings.value.recordRecentLocations(
            locations = locations.toList(),
            isArchiveLocation = ArchiveService::isArchiveLocation,
        )
        if (nextSettings != settings.value) {
            settings.value = nextSettings
        }
    }

    private fun requestRemoteCredentials(
        paneId: PaneId,
        error: VfsProviderError,
    ) {
        val protocol = error.protocol
        if (protocol != VfsProtocol.SMB && protocol != VfsProtocol.WEBDAV) {
            return
        }
        val location = error.location ?: paneState(paneId).location
        val rejected = error is VfsProviderError.AuthenticationRejected
        val currentDialog = dialogState.value as? RootDialogState.RemoteCredentials
        if (currentDialog != null &&
            currentDialog.paneId == paneId &&
            currentDialog.protocol == protocol &&
            currentDialog.location == location
        ) {
            if (rejected && !currentDialog.rejected) {
                dialogState.value = currentDialog.copy(rejected = true)
            }
            return
        }

        dialogState.value = RootDialogState.RemoteCredentials(
            paneId = paneId,
            protocol = protocol,
            location = location,
            rejected = rejected,
        )
    }


    private fun paneState(paneId: PaneId): PaneState {
        return when (paneId) {
            PaneId.PRIMARY -> primaryPane.state.value
            PaneId.SECONDARY -> secondaryPane.state.value
        }
    }

    private fun paneComponent(paneId: PaneId): PaneComponent {
        return when (paneId) {
            PaneId.PRIMARY -> primaryPane
            PaneId.SECONDARY -> secondaryPane
        }
    }

    private fun selectedEntriesInPane(paneId: PaneId): List<VFile> {
        val paneState = paneState(paneId)
        val topEntries = (paneState.entriesState as? PaneEntriesState.Ready)?.entries.orEmpty()
        // 同时包含内联展开的子项，否则选中子项时无法找到 VFile
        val allEntries = if (paneState.inlineExpandedLocations.isEmpty()) {
            topEntries
        } else {
            collectAllVisibleEntries(topEntries, paneState)
        }
        val selected = allEntries.filter { paneState.selectedEntryIds.contains(it.id) }
        if (selected.size <= 1) return selected

        // 去重：如果某个条目的祖先目录也被选中了，则排除它（复制父目录时已包含子项）
        val selectedLocations = selected
            .filter { it.kind == VFileKind.DIRECTORY }
            .mapTo(mutableSetOf()) { it.location }
        return selected.filter { entry ->
            val parent = entry.parentLocation ?: return@filter true
            // 逐级向上检查是否有祖先被选中
            !isAncestorSelected(parent, selectedLocations)
        }
    }

    private fun isAncestorSelected(location: String, selectedDirLocations: Set<String>): Boolean {
        var current: String? = location
        while (current != null) {
            if (current in selectedDirLocations) return true
            val lastSep = current.lastIndexOf('/')
            current = if (lastSep > 0) current.substring(0, lastSep) else null
        }
        return false
    }

    private fun collectAllVisibleEntries(
        entries: List<VFile>,
        paneState: PaneState,
    ): List<VFile> {
        return buildList {
            entries.forEach { entry ->
                add(entry)
                if (entry.location in paneState.inlineExpandedLocations) {
                    val expanded = paneState.inlineExpandedEntries[entry.location]
                    if (expanded?.entries != null) {
                        addAll(collectAllVisibleEntries(expanded.entries, paneState))
                    }
                }
            }
        }
    }


    private fun refreshAllPanes() {
        primaryPane.refresh()
        secondaryPane.refresh()
    }


}

private fun RemoteConnectionProfile.toRemoteConnectionDraft(): RemoteConnectionDraft {
    return RemoteConnectionDraft(
        name = name,
        protocol = protocol,
        location = location,
        username = username,
        domain = domain,
        savePolicy = savePolicy,
    )
}

private fun RemoteConnectionDraft.toRemoteConnectionProfile(
    id: String,
    location: String,
): RemoteConnectionProfile {
    return RemoteConnectionProfile(
        id = id,
        name = name.trim(),
        protocol = protocol,
        location = location,
        username = username.trim(),
        domain = domain.trim(),
        savePolicy = savePolicy,
    )
}

private fun RemoteConnectionDraft.hasCredentialInput(): Boolean {
    return username.isNotBlank() || secret.isNotBlank() || domain.isNotBlank()
}

private fun RemoteConnectionDraft.toAuthContextOrNull(): VfsAuthContext? {
    if (!hasCredentialInput()) return null
    return when (protocol) {
        RemoteConnectionProtocol.S3 -> VfsAuthContext.AwsCredentials(
            accessKeyId = username.trim(),
            secretAccessKey = secret,
            region = domain.trim().ifBlank { null },
        )

        RemoteConnectionProtocol.SMB,
        RemoteConnectionProtocol.WEBDAV,
        RemoteConnectionProtocol.WEBDAVS -> VfsAuthContext.UsernamePassword(
            username = username.trim(),
            password = secret,
            domain = domain.trim().ifBlank { null },
        )
    }
}

private fun RemoteConnectionDraft.normalizedLocation(): String {
    val trimmed = location.trim()
    val withScheme = if ("://" in trimmed) {
        trimmed
    } else {
        "${protocol.defaultScheme()}://${trimmed.trimStart('/')}"
    }
    return if (withScheme.contains('?') || withScheme.contains('#') || withScheme.endsWith('/')) {
        withScheme
    } else {
        "$withScheme/"
    }
}

private fun RemoteConnectionProtocol.defaultScheme(): String {
    return when (this) {
        RemoteConnectionProtocol.SMB -> "smb"
        RemoteConnectionProtocol.WEBDAV -> "webdav"
        RemoteConnectionProtocol.WEBDAVS -> "webdavs"
        RemoteConnectionProtocol.S3 -> "s3"
    }
}

private fun RemoteConnectionProtocol.toVfsProtocol(): VfsProtocol {
    return when (this) {
        RemoteConnectionProtocol.SMB -> VfsProtocol.SMB
        RemoteConnectionProtocol.WEBDAV,
        RemoteConnectionProtocol.WEBDAVS -> VfsProtocol.WEBDAV
        RemoteConnectionProtocol.S3 -> VfsProtocol.S3
    }
}

private fun RemoteConnectionSavePolicy.toRemoteCredentialSavePolicy(): RemoteCredentialSavePolicy {
    return when (this) {
        RemoteConnectionSavePolicy.DO_NOT_SAVE -> RemoteCredentialSavePolicy.DO_NOT_SAVE
        RemoteConnectionSavePolicy.SESSION -> RemoteCredentialSavePolicy.SESSION
        RemoteConnectionSavePolicy.SYSTEM_KEYRING -> RemoteCredentialSavePolicy.SYSTEM_KEYRING
    }
}

private fun VfsProviderError.toConnectionMessage(): String {
    return when (this) {
        is VfsProviderError.AuthenticationRequired -> "Authentication required"
        is VfsProviderError.AuthenticationRejected -> reason ?: "Authentication rejected"
        is VfsProviderError.PermissionDenied -> reason ?: "Permission denied"
        is VfsProviderError.NotFound -> "Location not found"
        is VfsProviderError.AlreadyExists -> "Location already exists"
        is VfsProviderError.NetworkFailure -> reason ?: "Network failure"
        is VfsProviderError.UnsupportedOperation -> "Protocol or capability is not supported"
        is VfsProviderError.CrossProviderTransferUnsupported -> "Cross-provider transfer is not supported"
    }
}

private fun Throwable.toSearchErrorMessage(): I18nMessage {
    val detail = message?.takeIf { it.isNotBlank() }
    return if (detail != null) {
        I18nMessage(MessageKey.MSG_STRING_LITERAL, detail)
    } else {
        I18nMessage(MessageKey.MSG_UNKNOWN_ERROR)
    }
}

private fun PaneComponent.toPaneSessionSnapshot(): PaneSessionSnapshot {
    return PaneSessionSnapshot(
        activeTabId = state.value.activeTabId,
        tabs = tabStack.value.items.map { child ->
            child.instance.state.value.toTabSnapshot()
        },
    )
}

/** combine 中间类型 — 布局相关状态切片 */
private data class LayoutSlice(
    val mode: PaneLayoutMode,
    val fraction: Float,
    val activePane: PaneId,
    val showPreviewPane: Boolean,
)

/** combine 中间类型 — 上下文状态切片 */
private data class ContextSlice(
    val sidebarTreeState: SidebarTreeState,
    val settings: OnyxSettings,
    val sessionRestoreState: SessionRestoreState,
    val dialogState: RootDialogState?,
    val canPaste: Boolean,
    val tasks: List<BackgroundTask>,
    val searchState: SearchPanelState,
)

/** combine 中间类型 — 运行时状态切片 */
private data class RuntimeContextSlice(
    val dialogState: RootDialogState?,
    val canPaste: Boolean,
    val tasks: List<BackgroundTask>,
    val searchState: SearchPanelState,
)
