package com.oruke.onyx.app.component

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.childContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.oruke.onyx.app.filesystem.ArchiveService
import com.oruke.onyx.app.filesystem.ArchiveEntryOpenService
import com.oruke.onyx.app.filesystem.ArchiveFileTypeService
import com.oruke.onyx.app.filesystem.ExternalOpenService
import com.oruke.onyx.app.filesystem.FileCommandService
import com.oruke.onyx.app.filesystem.FileRepository
import com.oruke.onyx.app.filesystem.ImageMetadataService
import com.oruke.onyx.app.filesystem.OpenWithApp
import com.oruke.onyx.app.filesystem.OpenWithService
import com.oruke.onyx.app.filesystem.PreviewService
import com.oruke.onyx.app.filesystem.PreviewTextRequest
import com.oruke.onyx.app.filesystem.PreviewTextResult
import com.oruke.onyx.app.filesystem.SessionRepository
import com.oruke.onyx.app.filesystem.SettingsRepository
import com.oruke.onyx.app.filesystem.TerminalLauncherService
import com.oruke.onyx.app.filesystem.TextClipboardService
import com.oruke.onyx.app.filesystem.ThumbnailService
import com.oruke.onyx.app.filesystem.TransferConflictStrategy
import com.oruke.onyx.app.filesystem.TrashService
import com.oruke.onyx.app.filesystem.VfsBreadcrumb
import com.oruke.onyx.app.filesystem.VfsPathService
import com.oruke.onyx.core.model.AppSessionSnapshot
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.DeleteMode
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.ImageFitMode
import com.oruke.onyx.core.model.ImageViewerState
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.PaneSessionSnapshot
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_extract_here
import onyx.composeapp.generated.resources.action_extract_to_directory
import onyx.composeapp.generated.resources.action_extract_smart
import onyx.composeapp.generated.resources.msg_string_literal
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
    private val archiveFileTypeService: ArchiveFileTypeService,
    private val archiveEntryOpenService: ArchiveEntryOpenService,
    private val terminalLauncherService: TerminalLauncherService,
    private val previewService: PreviewService,
    private val thumbnailService: ThumbnailService,
    private val imageMetadataService: ImageMetadataService,
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
        archiveFileTypeService = archiveFileTypeService,
        archiveEntryOpenService = archiveEntryOpenService,
        onOpenImageViewer = { file, allImages -> openImageViewer(file, allImages) },
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
        archiveFileTypeService = archiveFileTypeService,
        archiveEntryOpenService = archiveEntryOpenService,
        onOpenImageViewer = { file, allImages -> openImageViewer(file, allImages) },
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
                    dialogState, clipboardManager.clipboard, taskOrchestrator.tasks,
                ) { dialog, clipboard, taskList -> Triple(dialog, clipboard, taskList) },
            ) { (sidebar, stgs, restore), (dialog, clipboard, taskList) ->
                ContextSlice(
                    sidebarTreeState = sidebar,
                    settings = stgs,
                    sessionRestoreState = restore,
                    dialogState = dialog,
                    canPaste = clipboard != null,
                    tasks = taskList,
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

    override fun setLayoutMode(mode: PaneLayoutMode) {
        layoutMode.value = mode
    }

    override fun setPaneSplitFraction(fraction: Float) {
        paneSplitFraction.value = fraction.coerceIn(0.18f, 0.82f)
    }

    override fun openSettings() {
        dialogState.value = RootDialogState.Settings(draft = settings.value)
    }

    override fun updateSettingsDraft(draft: OnyxSettings) {
        val currentDialog = dialogState.value as? RootDialogState.Settings ?: return
        dialogState.value = currentDialog.copy(draft = draft)
    }

    override fun activatePane(paneId: PaneId) {
        activePane.value = paneId
    }

    override fun updateSettings(settings: OnyxSettings) {
        this.settings.value = settings.sanitized()
    }

    override fun openLocationInActivePane(location: String) {
        when (activePane.value) {
            PaneId.PRIMARY -> primaryPane.openDirectory(location)
            PaneId.SECONDARY -> secondaryPane.openDirectory(location)
        }
    }

    override fun toggleFavoriteLocation(location: String) {
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

    override fun toggleSidebarTreeNode(location: String) = sidebarDelegate.toggleNode(location)

    override fun retrySidebarTreeNode(location: String) = sidebarDelegate.retryNode(location)

    override fun beginCreateDirectoriesInPane(paneId: PaneId) {
        dialogState.value = RootDialogState.CreateDirectories(
            paneId = paneId,
            location = paneState(paneId).location,
            draft = "",
        )
    }

    override fun updateCreateDirectoriesDraft(draft: String) {
        val currentDialog = dialogState.value as? RootDialogState.CreateDirectories ?: return
        dialogState.value = currentDialog.copy(
            draft = draft,
            error = null,
        )
    }

    override fun confirmDialog() {
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

    override fun dismissDialog() {
        fileActionDelegate.clearPending()
        fileTransferDelegate.clearPending()
        archiveActionDelegate.clearPending()
        dialogState.value = null
    }

    override fun resolveConflict(
        strategy: TransferConflictStrategy,
        applyToAll: Boolean,
    ) = fileTransferDelegate.resolveConflict(strategy, applyToAll)

    override fun moveTab(
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

        val tab = paneComponent(sourcePaneId).detachTab(tabId) ?: return
        paneComponent(targetPaneId).attachTab(
            tab = tab,
            targetIndex = targetIndex,
        )
        activatePane(targetPaneId)
    }

    override fun refreshActivePane() {
        when (activePane.value) {
            PaneId.PRIMARY -> primaryPane.refresh()
            PaneId.SECONDARY -> secondaryPane.refresh()
        }
    }

    override fun togglePreviewPane() {
        showPreviewPane.value = !showPreviewPane.value
    }

    override fun stageCopySelectedInPane(paneId: PaneId) {
        clipboardManager.stageCopy(selectedEntriesInPane(paneId))
    }

    override fun stageCutSelectedInPane(paneId: PaneId) {
        clipboardManager.stageCut(selectedEntriesInPane(paneId))
    }

    override fun requestPasteIntoPane(paneId: PaneId) {
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

    override fun requestTransferSelectedToDirectory(
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

    override fun requestDeleteSelectedInPane(paneId: PaneId) {
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

    override fun extractSelectedInPane(paneId: PaneId) {
        archiveActionDelegate.launchArchiveExtraction(
            selectedEntries = selectedEntriesInPane(paneId),
            currentLocation = paneState(paneId).location,
            taskTitle = I18nMessage(Res.string.action_extract_here),
        ) { entry, location, password ->
            archiveService.extract(
                archivePath = entry.location,
                targetDirectory = location,
                password = password,
            )
        }
    }

    override fun extractToDirectoryInPane(paneId: PaneId) {
        archiveActionDelegate.launchArchiveExtraction(
            selectedEntries = selectedEntriesInPane(paneId),
            currentLocation = paneState(paneId).location,
            taskTitle = I18nMessage(Res.string.action_extract_to_directory),
        ) { entry, location, password ->
            archiveService.extractToDirectory(
                archivePath = entry.location,
                targetDirectory = location,
                password = password,
            )
        }
    }

    override fun extractSmartInPane(paneId: PaneId) {
        archiveActionDelegate.launchArchiveExtraction(
            selectedEntries = selectedEntriesInPane(paneId),
            currentLocation = paneState(paneId).location,
            taskTitle = I18nMessage(Res.string.action_extract_smart),
        ) { entry, location, password ->
            archiveService.extractSmart(
                archivePath = entry.location,
                targetDirectory = location,
                password = password,
            )
        }
    }

    override fun submitArchivePassword(password: String) = archiveActionDelegate.submitArchivePassword(password)

    override fun batchRenameInPane(paneId: PaneId) {
        val selectedEntries = selectedEntriesInPane(paneId)
        if (selectedEntries.size < 2) return
        dialogState.value = RootDialogState.BatchRename(
            paneId = paneId,
            entries = selectedEntries,
        )
    }

    override fun executeBatchRename(paneId: PaneId, renameMap: List<Pair<VFile, String>>) =
        fileActionDelegate.executeBatchRename(paneId, renameMap)

    override fun resetBatchRenameForContinue(paneId: PaneId) =
        fileActionDelegate.resetBatchRenameForContinue(paneId)

    override fun dismissTask(taskId: String) = taskOrchestrator.dismissTask(taskId)

    override fun cancelTask(taskId: String) = taskOrchestrator.cancelTask(taskId)

    override fun pauseTask(taskId: String) = taskOrchestrator.pauseTask(taskId)

    override fun resumeTask(taskId: String) = taskOrchestrator.resumeTask(taskId)

    override fun clearAllTasks() = taskOrchestrator.clearAllTasks()

    // ── 图片查看器 ────────────────────────────────────────────────────────────

    override fun openImageViewer(file: VFile, allImages: List<VFile>) = imageViewerController.open(file, allImages)

    override fun closeImageViewer() = imageViewerController.close()

    override fun imageViewerNext() = imageViewerController.next()

    override fun imageViewerPrevious() = imageViewerController.previous()

    override fun imageViewerSetZoom(factor: Float) = imageViewerController.setZoom(factor)

    override fun imageViewerSetFitMode(mode: ImageFitMode) = imageViewerController.setFitMode(mode)

    override fun imageViewerRotate(clockwise: Boolean) = imageViewerController.rotate(clockwise)

    // ── 打开方式 ──────────────────────────────────────────────────────────

    override suspend fun listOpenWithApps(entry: VFile): List<OpenWithApp> {
        return openWithService.listApps(entry)
    }

    override fun openWithApp(entry: VFile, app: OpenWithApp) {
        scope.launch {
            openWithService.openWith(entry, app)
        }
    }

    override fun openWithChooser(entry: VFile) {
        scope.launch {
            openWithService.openWithChooser(entry)
        }
    }

    override fun prepareExternalDrag(entries: List<VFile>): Boolean {
        return ExternalDragHelper.preparePendingFiles(entries, archiveService)
    }

    override fun isArchiveFileName(fileName: String): Boolean {
        return archiveFileTypeService.isArchiveFileName(fileName)
    }

    override fun locationLabel(location: String): String {
        return pathService.label(location)
    }

    override fun buildBreadcrumbs(location: String): List<VfsBreadcrumb> {
        return pathService.buildBreadcrumbs(location)
    }

    override fun openTerminalAt(location: String) {
        scope.launch {
            terminalLauncherService.openTerminal(location)
        }
    }

    override fun resolveTransferOperation(sourceLocation: String, targetLocation: String): FileTransferOperation {
        return pathService.resolveTransferOperation(sourceLocation, targetLocation)
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
                restoreError = failure.message?.let { I18nMessage(Res.string.msg_string_literal, it) } ?: I18nMessage(
                    Res.string.msg_string_literal,
                    "Failed to load settings"
                )
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
                restoreError = restoreError ?: failure.message?.let { I18nMessage(Res.string.msg_string_literal, it) }
                        ?: I18nMessage(Res.string.msg_string_literal, "Failed to restore session")
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
            primaryPane = primaryPane.state.value.toSessionSnapshot(),
            secondaryPane = secondaryPane.state.value.toSessionSnapshot(),
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
        val normalizedLocations = locations
            .map { location -> location.trim() }
            .filter { location -> location.isNotEmpty() && !ArchiveService.isArchiveLocation(location) }
        if (normalizedLocations.isEmpty()) {
            return
        }
        val nextRecentLocations = buildList {
            addAll(normalizedLocations)
            addAll(settings.value.recentLocations)
        }.distinct().take(MaxRecentLocations)
        if (nextRecentLocations != settings.value.recentLocations) {
            settings.value = settings.value.copy(recentLocations = nextRecentLocations).sanitized()
        }
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

private fun PaneState.toSessionSnapshot(): PaneSessionSnapshot {
    return PaneSessionSnapshot(
        activeTabId = activeTabId,
        tabs = tabs.map { tab -> tab.toSessionSnapshot() },
    )
}

private fun OnyxSettings.sanitized(): OnyxSettings {
    return copy(
        uiScale = uiScale.coerceIn(75, 200),
        favoriteLocations = favoriteLocations.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .take(MaxFavoriteLocations),
        recentLocations = recentLocations.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .take(MaxRecentLocations),
    )
}

private const val MaxFavoriteLocations = 12
private const val MaxRecentLocations = 10

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
)
