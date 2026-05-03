package com.oruke.onyx.app.component

import com.oruke.onyx.app.filesystem.ArchiveService
import com.oruke.onyx.app.filesystem.ExternalOpenService
import com.oruke.onyx.app.filesystem.FileCommandService
import com.oruke.onyx.app.filesystem.FileRepository
import com.oruke.onyx.app.filesystem.SessionRepository
import com.oruke.onyx.app.filesystem.SettingsRepository
import com.oruke.onyx.app.filesystem.TextClipboardService
import com.oruke.onyx.app.filesystem.TransferConflictStrategy
import com.oruke.onyx.app.filesystem.TrashService
import com.oruke.onyx.core.model.AppSessionSnapshot
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.DeleteMode
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.ImageFitMode
import com.oruke.onyx.core.model.ImageViewerState
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.PaneSessionSnapshot
import com.oruke.onyx.core.model.TabSessionSnapshot
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_batch_rename
import onyx.composeapp.generated.resources.action_extract_here
import onyx.composeapp.generated.resources.msg_cancelled
import onyx.composeapp.generated.resources.msg_copied_items
import onyx.composeapp.generated.resources.msg_copy_failed
import onyx.composeapp.generated.resources.msg_copy_items
import onyx.composeapp.generated.resources.msg_create_folder_failed
import onyx.composeapp.generated.resources.msg_create_folders
import onyx.composeapp.generated.resources.msg_created_folders
import onyx.composeapp.generated.resources.msg_delete_failed
import onyx.composeapp.generated.resources.msg_delete_items
import onyx.composeapp.generated.resources.msg_deleted_items
import onyx.composeapp.generated.resources.msg_move_failed
import onyx.composeapp.generated.resources.msg_move_items
import onyx.composeapp.generated.resources.msg_moved_items
import onyx.composeapp.generated.resources.msg_string_literal
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.*

class DefaultRootComponent(
    private val scope: CoroutineScope,
    private val fileRepository: FileRepository,
    private val fileCommandService: FileCommandService,
    private val textClipboardService: TextClipboardService,
    private val trashService: TrashService,
    private val externalOpenService: ExternalOpenService,
    private val settingsRepository: SettingsRepository,
    private val sessionRepository: SessionRepository,
    private val archiveService: ArchiveService,
) : RootComponent {
    override val primaryPane: PaneComponent = DefaultPaneComponent(
        paneId = PaneId.PRIMARY,
        initialLocation = fileRepository.defaultLocation(),
        fileRepository = fileRepository,
        fileCommandService = fileCommandService,
        textClipboardService = textClipboardService,
        externalOpenService = externalOpenService,
        scope = scope,
        onOpenImageViewer = { file, allImages -> openImageViewer(file, allImages) },
    )
    override val secondaryPane: PaneComponent = DefaultPaneComponent(
        paneId = PaneId.SECONDARY,
        initialLocation = fileRepository.defaultLocation(),
        fileRepository = fileRepository,
        fileCommandService = fileCommandService,
        textClipboardService = textClipboardService,
        externalOpenService = externalOpenService,
        scope = scope,
        onOpenImageViewer = { file, allImages -> openImageViewer(file, allImages) },
    )

    private val layoutMode = MutableStateFlow(PaneLayoutMode.DUAL_VERTICAL)
    private val paneSplitFraction = MutableStateFlow(0.5f)
    private val activePane = MutableStateFlow(PaneId.PRIMARY)
    private val sidebarTreeState = MutableStateFlow(
        SidebarTreeState(
            roots = buildSidebarRootNodes(),
        )
    )
    private val settings = MutableStateFlow(OnyxSettings())
    private val sessionRestoreState = MutableStateFlow<SessionRestoreState>(SessionRestoreState.Loading)
    private val dialogState = MutableStateFlow<RootDialogState?>(null)
    private val clipboard = MutableStateFlow<ClipboardPayload?>(null)
    private val tasks = MutableStateFlow<List<BackgroundTask>>(emptyList())
    private val showPreviewPane = MutableStateFlow(false)
    override val imageViewerState = MutableStateFlow(ImageViewerState())
    private val taskJobs = mutableMapOf<String, Job>()
    private val taskPauseFlags = mutableMapOf<String, MutableStateFlow<Boolean>>()
    private var pendingDeleteRequest: PendingDeleteRequest? = null
    private var pendingTransferRequest: PendingTransferRequest? = null
    private val persistenceMutex = Mutex()
    private var persistenceReady = false
    private val mutableState = MutableStateFlow(
        RootState(
            layoutMode = layoutMode.value,
            paneSplitFraction = paneSplitFraction.value,
            activePane = activePane.value,
            primaryPane = primaryPane.state.value,
            secondaryPane = secondaryPane.state.value,
            sidebarTreeState = sidebarTreeState.value,
            settings = settings.value,
            sessionRestoreState = sessionRestoreState.value,
            dialogState = dialogState.value,
            canPaste = clipboard.value != null,
            tasks = tasks.value,
            showPreviewPane = showPreviewPane.value,
        )
    )

    override val state: StateFlow<RootState> = mutableState.asStateFlow()

    init {
        scope.launch {
            combine(
                layoutMode,
                paneSplitFraction,
                activePane,
                primaryPane.state,
                secondaryPane.state,
                sidebarTreeState,
                settings,
                sessionRestoreState,
                dialogState,
                clipboard,
                tasks,
                showPreviewPane,
            ) { values ->
                val currentLayoutMode = values[0] as PaneLayoutMode
                val currentPaneSplitFraction = values[1] as Float
                val currentActivePane = values[2] as PaneId
                val primaryState = values[3] as PaneState
                val secondaryState = values[4] as PaneState
                val currentSidebarTreeState = values[5] as SidebarTreeState
                val currentSettings = values[6] as OnyxSettings
                val currentSessionRestoreState = values[7] as SessionRestoreState
                val currentDialogState = values[8] as RootDialogState?
                val currentClipboard = values[9] as ClipboardPayload?

                @Suppress("UNCHECKED_CAST")
                val currentTasks = values[10] as List<BackgroundTask>
                val currentShowPreviewPane = values[11] as Boolean
                RootState(
                    layoutMode = currentLayoutMode,
                    paneSplitFraction = currentPaneSplitFraction,
                    activePane = currentActivePane,
                    primaryPane = primaryState,
                    secondaryPane = secondaryState,
                    sidebarTreeState = currentSidebarTreeState,
                    settings = currentSettings,
                    sessionRestoreState = currentSessionRestoreState,
                    dialogState = currentDialogState,
                    canPaste = currentClipboard != null,
                    tasks = currentTasks,
                    showPreviewPane = currentShowPreviewPane,
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
                ensureSidebarLocationVisible(location)
            }
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

    override fun toggleSidebarTreeNode(location: String) {
        val node = sidebarTreeState.value.findNode(location) ?: return
        val expand = !node.expanded
        sidebarTreeState.value = sidebarTreeState.value.updateNode(location) { currentNode ->
            currentNode.copy(expanded = expand)
        }
        if (expand && node.loadState != SidebarTreeNodeLoadState.READY) {
            scope.launch {
                loadSidebarChildren(location)
            }
        }
    }

    override fun retrySidebarTreeNode(location: String) {
        scope.launch {
            loadSidebarChildren(location, forceReload = true)
        }
    }

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
                val deleteRequest = pendingDeleteRequest ?: return
                pendingDeleteRequest = null
                executeDeleteRequest(deleteRequest)
            }

            is RootDialogState.CreateDirectories -> {
                val directoryPaths = parseDirectoryDraft(currentDialog.draft)
                if (directoryPaths.isEmpty()) {
                    dialogState.value = currentDialog.copy(error = CreateDirectoriesDialogError.EMPTY_INPUT)
                    return
                }
                dialogState.value = null
                executeCreateDirectories(
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
        pendingDeleteRequest = null
        pendingTransferRequest = null
        dialogState.value = null
    }

    override fun resolveConflict(
        strategy: TransferConflictStrategy,
        applyToAll: Boolean,
    ) {
        val pendingRequest = pendingTransferRequest ?: return
        val currentConflict = pendingRequest.conflictingEntries.getOrNull(pendingRequest.nextConflictIndex) ?: run {
            pendingTransferRequest = null
            dialogState.value = null
            return
        }
        val nextResolvedStrategies = pendingRequest.resolvedStrategies.toMutableMap().apply {
            put(currentConflict.id, strategy)
        }
        if (applyToAll) {
            pendingRequest.conflictingEntries
                .drop(pendingRequest.nextConflictIndex + 1)
                .forEach { entry ->
                    nextResolvedStrategies[entry.id] = strategy
                }
            pendingTransferRequest = null
            dialogState.value = null
            launchTransferTask(
                entries = pendingRequest.entries,
                targetDirectoryLocation = pendingRequest.targetDirectoryLocation,
                operation = pendingRequest.operation,
                clearClipboardOnSuccess = pendingRequest.clearClipboardOnSuccess,
                conflictStrategies = nextResolvedStrategies,
            )
            return
        }

        val nextConflictIndex = pendingRequest.nextConflictIndex + 1
        if (nextConflictIndex >= pendingRequest.conflictingEntries.size) {
            pendingTransferRequest = null
            dialogState.value = null
            launchTransferTask(
                entries = pendingRequest.entries,
                targetDirectoryLocation = pendingRequest.targetDirectoryLocation,
                operation = pendingRequest.operation,
                clearClipboardOnSuccess = pendingRequest.clearClipboardOnSuccess,
                conflictStrategies = nextResolvedStrategies,
            )
            return
        }

        val nextConflict = pendingRequest.conflictingEntries[nextConflictIndex]
        pendingTransferRequest = pendingRequest.copy(
            resolvedStrategies = nextResolvedStrategies,
            nextConflictIndex = nextConflictIndex,
        )
        dialogState.value = RootDialogState.ConflictResolution(
            sourceName = nextConflict.name,
            targetLocation = pendingRequest.targetDirectoryLocation,
            operation = pendingRequest.operation,
            currentIndex = nextConflictIndex + 1,
            total = pendingRequest.conflictingEntries.size,
        )
    }

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
        val entries = selectedEntriesInPane(paneId)
        if (entries.isEmpty()) {
            return
        }
        clipboard.value = ClipboardPayload(
            operation = ClipboardOperation.COPY,
            entries = entries,
        )
    }

    override fun stageCutSelectedInPane(paneId: PaneId) {
        val entries = selectedEntriesInPane(paneId)
        if (entries.isEmpty()) {
            return
        }
        clipboard.value = ClipboardPayload(
            operation = ClipboardOperation.CUT,
            entries = entries,
        )
    }

    override fun requestPasteIntoPane(paneId: PaneId) {
        val clipboardPayload = clipboard.value ?: return
        val targetLocation = paneState(paneId).location
        requestTransferEntriesToDirectory(
            entries = clipboardPayload.entries,
            targetDirectoryLocation = targetLocation,
            operation = when (clipboardPayload.operation) {
                ClipboardOperation.COPY -> FileTransferOperation.COPY
                ClipboardOperation.CUT -> FileTransferOperation.MOVE
            },
            clearClipboardOnSuccess = clipboardPayload.operation == ClipboardOperation.CUT,
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
        requestTransferEntriesToDirectory(
            entries = entries,
            targetDirectoryLocation = targetDirectoryLocation,
            operation = operation,
            clearClipboardOnSuccess = false,
        )
    }

    private fun requestTransferEntriesToDirectory(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        operation: FileTransferOperation,
        clearClipboardOnSuccess: Boolean,
    ) {
        if (operation == FileTransferOperation.MOVE && entries.all { it.parentLocation == targetDirectoryLocation }) {
            return
        }
        if (entries.any { entry -> targetDirectoryLocation.isSameOrChildOf(entry.location) }) {
            return
        }
        scope.launch {
            val conflictingEntries = detectConflictingEntries(
                entries = entries,
                targetDirectoryLocation = targetDirectoryLocation,
            )
            if (conflictingEntries.isNotEmpty()) {
                pendingTransferRequest = PendingTransferRequest(
                    entries = entries,
                    targetDirectoryLocation = targetDirectoryLocation,
                    operation = operation,
                    clearClipboardOnSuccess = clearClipboardOnSuccess,
                    conflictingEntries = conflictingEntries,
                    resolvedStrategies = emptyMap(),
                    nextConflictIndex = 0,
                )
                val firstConflict = conflictingEntries.first()
                dialogState.value = RootDialogState.ConflictResolution(
                    sourceName = firstConflict.name,
                    targetLocation = targetDirectoryLocation,
                    operation = operation,
                    currentIndex = 1,
                    total = conflictingEntries.size,
                )
                return@launch
            }
            launchTransferTask(
                entries = entries,
                targetDirectoryLocation = targetDirectoryLocation,
                operation = operation,
                clearClipboardOnSuccess = clearClipboardOnSuccess,
                conflictStrategies = emptyMap(),
            )
        }
    }

    private fun launchTransferTask(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        operation: FileTransferOperation,
        clearClipboardOnSuccess: Boolean,
        conflictStrategies: Map<String, TransferConflictStrategy>,
    ) {
        val taskId = UUID.randomUUID().toString()
        val taskKind = when (operation) {
            FileTransferOperation.COPY -> BackgroundTaskKind.COPY
            FileTransferOperation.MOVE -> BackgroundTaskKind.MOVE
        }
        val pauseFlag = MutableStateFlow(false)
        taskPauseFlags[taskId] = pauseFlag
        val startTime = System.currentTimeMillis()

        appendTask(
            BackgroundTask(
                id = taskId,
                kind = taskKind,
                title = when (operation) {
                    FileTransferOperation.COPY -> I18nMessage(Res.string.msg_copy_items, entries.size)
                    FileTransferOperation.MOVE -> I18nMessage(Res.string.msg_move_items, entries.size)
                },
                status = BackgroundTaskStatus.QUEUED,
                detail = I18nMessage(Res.string.msg_string_literal, targetDirectoryLocation),
                progress = 0f,
                totalCount = entries.size,
                startTimeMillis = startTime,
            )
        )

        val job = scope.launch {
            try {
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(
                        Res.string.msg_string_literal,
                        buildTransferTaskDetail(
                            entries = entries,
                            targetLocation = targetDirectoryLocation,
                        )
                    ),
                    progress = 0f,
                )

                entries.forEachIndexed { index, entry ->
                    ensureActive()
                    // 文件级暂停检测
                    while (pauseFlag.value) {
                        ensureActive()
                        delay(200)
                    }
                    updateTaskFields(taskId) { task ->
                        task.copy(currentFileName = entry.name)
                    }
                    val conflictStrategy = conflictStrategies[entry.id] ?: TransferConflictStrategy.KEEP_BOTH
                    val result = when (operation) {
                        FileTransferOperation.COPY -> {
                            fileCommandService.copy(
                                entries = listOf(entry),
                                targetDirectoryLocation = targetDirectoryLocation,
                                conflictStrategy = conflictStrategy,
                            )
                        }

                        FileTransferOperation.MOVE -> {
                            fileCommandService.move(
                                entries = listOf(entry),
                                targetDirectoryLocation = targetDirectoryLocation,
                                conflictStrategy = conflictStrategy,
                            )
                        }
                    }
                    result.getOrThrow()
                    updateTask(
                        taskId = taskId,
                        status = BackgroundTaskStatus.RUNNING,
                        detail = I18nMessage(
                            Res.string.msg_string_literal,
                            "${entry.name} → $targetDirectoryLocation"
                        ),
                        progress = (index + 1).toFloat() / entries.size,
                        processedCount = index + 1,
                    )
                }

                taskJobs.remove(taskId)
                taskPauseFlags.remove(taskId)
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = when (operation) {
                        FileTransferOperation.COPY ->
                            I18nMessage(Res.string.msg_copied_items, entries.size, targetDirectoryLocation)

                        FileTransferOperation.MOVE ->
                            I18nMessage(Res.string.msg_moved_items, entries.size, targetDirectoryLocation)
                    },
                    progress = 1f,
                    processedCount = entries.size,
                )
                if (clearClipboardOnSuccess) {
                    clipboard.value = null
                }
                refreshAllPanes()
                scheduleTaskAutoCleanup(taskId)
            } catch (_: CancellationException) {
                taskJobs.remove(taskId)
                taskPauseFlags.remove(taskId)
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(Res.string.msg_cancelled),
                    progress = null,
                )
                refreshAllPanes()
            } catch (failure: Throwable) {
                taskJobs.remove(taskId)
                taskPauseFlags.remove(taskId)
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = failure.message?.let { I18nMessage(Res.string.msg_string_literal, it) }
                        ?: when (operation) {
                            FileTransferOperation.COPY -> I18nMessage(Res.string.msg_copy_failed)
                            FileTransferOperation.MOVE -> I18nMessage(Res.string.msg_move_failed)
                    },
                    progress = null,
                )
                refreshAllPanes()
            }
        }
        taskJobs[taskId] = job
    }

    override fun requestDeleteSelectedInPane(paneId: PaneId) {
        val selectedEntries = selectedEntriesInPane(paneId)
        if (selectedEntries.isEmpty()) {
            return
        }

        val moveToTrashPreferred = settings.value.deleteMode == DeleteMode.MOVE_TO_TRASH_PREFERRED
        val willMoveToTrash = moveToTrashPreferred && trashService.isSupported
        pendingDeleteRequest = PendingDeleteRequest(
            paneId = paneId,
            entries = selectedEntries,
            moveToTrash = willMoveToTrash,
        )
        dialogState.value = RootDialogState.DeleteSelectionConfirmation(
            moveToTrash = willMoveToTrash,
            itemCount = selectedEntries.size,
            trashUnavailable = moveToTrashPreferred && !trashService.isSupported,
        )
    }

    override fun extractSelectedInPane(paneId: PaneId) {
        val selectedEntries = selectedEntriesInPane(paneId)
        if (selectedEntries.isEmpty()) return
        val currentLocation = paneState(paneId).location

        // 筛选出压缩文件
        val archiveEntries = selectedEntries.filter { entry ->
            entry.kind == VFileKind.FILE && ArchiveService.isArchive(entry.name)
        }
        if (archiveEntries.isEmpty()) return

        val taskId = UUID.randomUUID().toString()
        appendTask(
            BackgroundTask(
                id = taskId,
                kind = BackgroundTaskKind.EXTRACT,
                title = I18nMessage(Res.string.action_extract_here),
                status = BackgroundTaskStatus.QUEUED,
                detail = I18nMessage(Res.string.msg_string_literal, buildTaskDetail(archiveEntries)),
                progress = 0f,
                totalCount = archiveEntries.size,
                startTimeMillis = System.currentTimeMillis(),
            )
        )

        val job = scope.launch {
            try {
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(Res.string.msg_string_literal, buildTaskDetail(archiveEntries)),
                    progress = 0f,
                )
                archiveEntries.forEachIndexed { index, entry ->
                    ensureActive()
                    updateTask(
                        taskId = taskId,
                        status = BackgroundTaskStatus.RUNNING,
                        detail = I18nMessage(Res.string.msg_string_literal, entry.name),
                        progress = index.toFloat() / archiveEntries.size,
                    )
                    archiveService.extract(
                        archivePath = entry.location,
                        targetDirectory = currentLocation,
                    ).getOrThrow()
                }

                taskJobs.remove(taskId)
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = I18nMessage(Res.string.msg_string_literal, buildTaskDetail(archiveEntries)),
                    progress = 1f,
                    processedCount = archiveEntries.size,
                )
                refreshAllPanes()
                scheduleTaskAutoCleanup(taskId)
            } catch (_: CancellationException) {
                taskJobs.remove(taskId)
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(Res.string.msg_cancelled),
                )
            } catch (e: Throwable) {
                taskJobs.remove(taskId)
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = I18nMessage(Res.string.msg_string_literal, e.message ?: "Unknown error"),
                )
            }
        }
        taskJobs[taskId] = job
    }

    override fun batchRenameInPane(paneId: PaneId) {
        val selectedEntries = selectedEntriesInPane(paneId)
            .filter { it.kind == VFileKind.FILE }
        if (selectedEntries.size < 2) return
        dialogState.value = RootDialogState.BatchRename(
            paneId = paneId,
            entries = selectedEntries,
        )
    }

    override fun executeBatchRename(paneId: PaneId, renameMap: List<Pair<VFile, String>>) {
        dismissDialog()
        if (renameMap.isEmpty()) return

        val taskId = UUID.randomUUID().toString()
        appendTask(
            BackgroundTask(
                id = taskId,
                kind = BackgroundTaskKind.RENAME,
                title = I18nMessage(Res.string.action_batch_rename),
                status = BackgroundTaskStatus.QUEUED,
                detail = I18nMessage(Res.string.msg_string_literal, "${renameMap.size} files"),
                progress = 0f,
                totalCount = renameMap.size,
                startTimeMillis = System.currentTimeMillis(),
            )
        )

        val job = scope.launch {
            try {
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(Res.string.msg_string_literal, "Starting..."),
                    progress = 0f,
                )
                renameMap.forEachIndexed { index, (entry, newName) ->
                    ensureActive()
                    updateTask(
                        taskId = taskId,
                        status = BackgroundTaskStatus.RUNNING,
                        detail = I18nMessage(Res.string.msg_string_literal, "${entry.name} → $newName"),
                        progress = index.toFloat() / renameMap.size,
                    )
                    fileCommandService.rename(entry, newName).getOrThrow()
                }

                taskJobs.remove(taskId)
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = I18nMessage(Res.string.msg_string_literal, "${renameMap.size} files renamed"),
                    progress = 1f,
                    processedCount = renameMap.size,
                )
                refreshAllPanes()
                scheduleTaskAutoCleanup(taskId)
            } catch (_: CancellationException) {
                taskJobs.remove(taskId)
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(Res.string.msg_cancelled),
                )
            } catch (e: Throwable) {
                taskJobs.remove(taskId)
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = I18nMessage(Res.string.msg_string_literal, e.message ?: "Unknown error"),
                )
                refreshAllPanes()
            }
        }
        taskJobs[taskId] = job
    }

    private fun executeDeleteRequest(request: PendingDeleteRequest) {
        val selectedEntries = request.entries
        if (selectedEntries.isEmpty()) {
            return
        }

        val taskId = UUID.randomUUID().toString()
        appendTask(
            BackgroundTask(
                id = taskId,
                kind = BackgroundTaskKind.DELETE,
                title = I18nMessage(Res.string.msg_delete_items, selectedEntries.size),
                status = BackgroundTaskStatus.QUEUED,
                detail = I18nMessage(Res.string.msg_string_literal, paneState(request.paneId).location),
                progress = 0f,
                totalCount = selectedEntries.size,
                startTimeMillis = System.currentTimeMillis(),
            )
        )

        val job = scope.launch {
            try {
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(Res.string.msg_string_literal, buildTaskDetail(selectedEntries)),
                    progress = 0f,
                )

                selectedEntries.forEachIndexed { index, entry ->
                    ensureActive()
                    if (request.moveToTrash) {
                        trashService.moveToTrash(listOf(entry)).getOrThrow()
                    } else {
                        fileCommandService.delete(listOf(entry)).getOrThrow()
                    }
                    updateTask(
                        taskId = taskId,
                        status = BackgroundTaskStatus.RUNNING,
                        detail = I18nMessage(Res.string.msg_string_literal, entry.name),
                        progress = (index + 1).toFloat() / selectedEntries.size,
                    )
                }

                taskJobs.remove(taskId)
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = I18nMessage(Res.string.msg_deleted_items, selectedEntries.size),
                    progress = 1f,
                    processedCount = selectedEntries.size,
                )
                refreshAllPanes()
                scheduleTaskAutoCleanup(taskId)
            } catch (_: CancellationException) {
                taskJobs.remove(taskId)
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(Res.string.msg_cancelled),
                    progress = null,
                )
                refreshAllPanes()
            } catch (failure: Throwable) {
                taskJobs.remove(taskId)
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = failure.message?.let { I18nMessage(Res.string.msg_string_literal, it) }
                        ?: I18nMessage(Res.string.msg_delete_failed),
                    progress = null,
                )
                refreshAllPanes()
            }
        }
        taskJobs[taskId] = job
    }

    override fun dismissTask(taskId: String) {
        taskJobs.remove(taskId)?.cancel()
        taskPauseFlags.remove(taskId)
        tasks.value = tasks.value.filterNot { task -> task.id == taskId }
    }

    override fun cancelTask(taskId: String) {
        taskJobs[taskId]?.cancel()
    }

    override fun pauseTask(taskId: String) {
        taskPauseFlags[taskId]?.value = true
        updateTaskFields(taskId) { task ->
            task.copy(status = BackgroundTaskStatus.PAUSED)
        }
    }

    override fun resumeTask(taskId: String) {
        taskPauseFlags[taskId]?.value = false
        updateTaskFields(taskId) { task ->
            task.copy(status = BackgroundTaskStatus.RUNNING)
        }
    }

    override fun clearAllTasks() {
        taskJobs.values.forEach { job -> job.cancel() }
        taskJobs.clear()
        taskPauseFlags.clear()
        tasks.value = emptyList()
    }

    // ── 图片查看器 ────────────────────────────────────────────────────────────

    override fun openImageViewer(file: VFile, allImages: List<VFile>) {
        val index = allImages.indexOfFirst { it.id == file.id }.coerceAtLeast(0)
        imageViewerState.value = ImageViewerState(
            visible = true,
            currentFile = file,
            allImages = allImages,
            currentIndex = index,
            zoomFactor = 1f,
            fitMode = ImageFitMode.FIT_WINDOW,
            rotation = 0,
        )
    }

    override fun closeImageViewer() {
        imageViewerState.value = ImageViewerState()
    }

    override fun imageViewerNext() {
        val current = imageViewerState.value
        if (!current.visible || current.allImages.isEmpty()) return
        val nextIndex = (current.currentIndex + 1) % current.allImages.size
        val nextFile = current.allImages[nextIndex]
        imageViewerState.value = current.copy(
            currentIndex = nextIndex,
            currentFile = nextFile,
            zoomFactor = 1f,
            fitMode = ImageFitMode.FIT_WINDOW,
            rotation = 0,
        )
    }

    override fun imageViewerPrevious() {
        val current = imageViewerState.value
        if (!current.visible || current.allImages.isEmpty()) return
        val prevIndex = if (current.currentIndex <= 0) current.allImages.lastIndex else current.currentIndex - 1
        val prevFile = current.allImages[prevIndex]
        imageViewerState.value = current.copy(
            currentIndex = prevIndex,
            currentFile = prevFile,
            zoomFactor = 1f,
            fitMode = ImageFitMode.FIT_WINDOW,
            rotation = 0,
        )
    }

    override fun imageViewerSetZoom(factor: Float) {
        val current = imageViewerState.value
        if (!current.visible) return
        imageViewerState.value = current.copy(
            zoomFactor = factor.coerceIn(0.1f, 10f),
        )
    }

    override fun imageViewerSetFitMode(mode: ImageFitMode) {
        val current = imageViewerState.value
        if (!current.visible) return
        imageViewerState.value = current.copy(
            fitMode = mode,
            zoomFactor = 1f,
        )
    }

    override fun imageViewerRotate(clockwise: Boolean) {
        val current = imageViewerState.value
        if (!current.visible) return
        val delta = if (clockwise) 90 else -90
        imageViewerState.value = current.copy(
            rotation = (current.rotation + delta + 360) % 360,
        )
    }

    private fun scheduleTaskAutoCleanup(taskId: String) {
        scope.launch {
            delay(5000)
            tasks.value = tasks.value.filterNot { task -> task.id == taskId }
        }
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
            SessionRestoreState.Failed(restoreError!!)
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
        persistenceMutex.withLock {
            settingsRepository.saveSettings(settings.value)
            sessionRepository.saveSession(buildSessionSnapshot())
        }
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

    private suspend fun detectConflictingEntries(
        entries: List<VFile>,
        targetDirectoryLocation: String,
    ): List<VFile> {
        val existingNames = fileRepository.list(targetDirectoryLocation)
            .getOrDefault(emptyList())
            .mapTo(mutableSetOf()) { entry -> entry.name }
        return entries.filter { entry -> existingNames.contains(entry.name) }
    }

    private fun executeCreateDirectories(
        paneId: PaneId,
        parentLocation: String,
        paths: List<String>,
    ) {
        val taskId = UUID.randomUUID().toString()
        appendTask(
            BackgroundTask(
                id = taskId,
                kind = BackgroundTaskKind.COPY,
                title = I18nMessage(Res.string.msg_create_folders, paths.size),
                status = BackgroundTaskStatus.QUEUED,
                detail = I18nMessage(Res.string.msg_string_literal, parentLocation),
                progress = 0f,
                totalCount = paths.size,
                startTimeMillis = System.currentTimeMillis(),
            )
        )

        val job = scope.launch {
            try {
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(
                        Res.string.msg_string_literal,
                        paths.joinToString(limit = 3, truncated = " ...")
                    ),
                    progress = 0f,
                )
                paths.forEachIndexed { index, path ->
                    ensureActive()
                    fileCommandService.createDirectory(
                        parentLocation = parentLocation,
                        name = path,
                    ).getOrThrow()
                    updateTask(
                        taskId = taskId,
                        status = BackgroundTaskStatus.RUNNING,
                        detail = I18nMessage(Res.string.msg_string_literal, path),
                        progress = (index + 1).toFloat() / paths.size,
                    )
                }
                taskJobs.remove(taskId)
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = I18nMessage(Res.string.msg_created_folders, paths.size),
                    progress = 1f,
                    processedCount = paths.size,
                )
                paneComponent(paneId).refresh()
                scheduleTaskAutoCleanup(taskId)
            } catch (_: CancellationException) {
                taskJobs.remove(taskId)
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(Res.string.msg_cancelled),
                    progress = null,
                )
                paneComponent(paneId).refresh()
            } catch (failure: Throwable) {
                taskJobs.remove(taskId)
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = failure.message?.let { I18nMessage(Res.string.msg_string_literal, it) }
                        ?: I18nMessage(Res.string.msg_create_folder_failed),
                    progress = null,
                )
                paneComponent(paneId).refresh()
            }
        }
        taskJobs[taskId] = job
    }

    private fun parseDirectoryDraft(draft: String): List<String> {
        return draft
            .lines()
            .map { line -> line.trim() }
            .filter { line -> line.isNotBlank() }
            .distinct()
    }

    private fun buildSidebarRootNodes(): List<SidebarTreeNode> {
        return FileSystems.getDefault()
            .rootDirectories
            .map { rootPath -> rootPath.normalize().toAbsolutePath() }
            .distinct()
            .sortedBy { rootPath -> rootPath.toString() }
            .map { rootPath ->
                SidebarTreeNode(
                    location = rootPath.toString().ifBlank { "/" },
                    label = rootPath.toString().ifBlank { "/" },
                    expanded = false,
                    loadState = SidebarTreeNodeLoadState.IDLE,
                )
            }
    }

    private suspend fun ensureSidebarLocationVisible(location: String) {
        val normalizedLocation = normalizeTreeLocation(location) ?: return
        buildAncestorLocations(normalizedLocation)
            .dropLast(1)
            .forEach { ancestorLocation ->
                val node = sidebarTreeState.value.findNode(ancestorLocation) ?: return@forEach
                if (!node.expanded) {
                    sidebarTreeState.value = sidebarTreeState.value.updateNode(ancestorLocation) { currentNode ->
                        currentNode.copy(expanded = true)
                    }
                }
                if (node.loadState != SidebarTreeNodeLoadState.READY) {
                    loadSidebarChildren(ancestorLocation)
                }
            }
    }

    private suspend fun loadSidebarChildren(
        location: String,
        forceReload: Boolean = false,
    ) {
        val currentNode = sidebarTreeState.value.findNode(location) ?: return
        if (!forceReload && currentNode.loadState == SidebarTreeNodeLoadState.LOADING) {
            return
        }
        if (!forceReload && currentNode.loadState == SidebarTreeNodeLoadState.READY) {
            return
        }

        sidebarTreeState.value = sidebarTreeState.value.updateNode(location) { node ->
            node.copy(
                expanded = true,
                loadState = SidebarTreeNodeLoadState.LOADING,
                loadError = null,
            )
        }

        fileRepository.list(location).fold(
            onSuccess = { entries ->
                val children = entries
                    .filter { entry -> entry.kind == VFileKind.DIRECTORY }
                    .sortedBy { entry -> entry.name.lowercase() }
                    .map { entry ->
                        SidebarTreeNode(
                            location = entry.location,
                            label = entry.name,
                            expanded = false,
                            loadState = SidebarTreeNodeLoadState.IDLE,
                        )
                    }
                sidebarTreeState.value = sidebarTreeState.value.updateNode(location) { node ->
                    node.copy(
                        loadState = SidebarTreeNodeLoadState.READY,
                        loadError = null,
                        children = children,
                    )
                }
            },
            onFailure = { failure ->
                sidebarTreeState.value = sidebarTreeState.value.updateNode(location) { node ->
                    node.copy(
                        loadState = SidebarTreeNodeLoadState.FAILURE,
                        loadError = failure.message,
                    )
                }
            },
        )
    }

    private fun recordRecentLocations(vararg locations: String) {
        val normalizedLocations = locations
            .map { location -> location.trim() }
            .filter { location -> location.isNotEmpty() }
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

    private fun appendTask(task: BackgroundTask) {
        tasks.value = listOf(task) + tasks.value
    }

    private fun updateTask(
        taskId: String,
        status: BackgroundTaskStatus,
        detail: I18nMessage,
        progress: Float? = null,
        processedCount: Int? = null,
    ) {
        tasks.value = tasks.value.map { task ->
            if (task.id == taskId) {
                task.copy(
                    status = status,
                    detail = detail,
                    progress = progress,
                    processedCount = processedCount ?: task.processedCount,
                )
            } else {
                task
            }
        }
    }

    private fun updateTaskFields(taskId: String, transform: (BackgroundTask) -> BackgroundTask) {
        tasks.value = tasks.value.map { task ->
            if (task.id == taskId) transform(task) else task
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

    private fun buildTransferTaskDetail(
        entries: List<VFile>,
        targetLocation: String,
    ): String {
        return "${buildTaskDetail(entries)} -> $targetLocation"
    }

    private fun buildTaskDetail(entries: List<VFile>): String {
        val preview = entries.take(3).joinToString { it.name }
        return if (entries.size <= 3) preview else "$preview ..."
    }

    private fun refreshAllPanes() {
        primaryPane.refresh()
        secondaryPane.refresh()
    }

    private data class ClipboardPayload(
        val operation: ClipboardOperation,
        val entries: List<VFile>,
    )

    private data class PendingDeleteRequest(
        val paneId: PaneId,
        val entries: List<VFile>,
        val moveToTrash: Boolean,
    )

    private data class PendingTransferRequest(
        val entries: List<VFile>,
        val targetDirectoryLocation: String,
        val operation: FileTransferOperation,
        val clearClipboardOnSuccess: Boolean,
        val conflictingEntries: List<VFile>,
        val resolvedStrategies: Map<String, TransferConflictStrategy>,
        val nextConflictIndex: Int,
    )

    private enum class ClipboardOperation {
        COPY,
        CUT,
    }
}

private fun PaneState.toSessionSnapshot(): PaneSessionSnapshot {
    return PaneSessionSnapshot(
        activeTabId = activeTabId,
        tabs = tabs.map { tab -> tab.toSessionSnapshot() },
    )
}

private fun PaneTabState.toSessionSnapshot(): TabSessionSnapshot {
    return TabSessionSnapshot(
        id = id,
        location = location,
        detailsColumns = detailsColumns,
        detailsColumnWeights = detailsColumnWeights,
        detailsSort = detailsSort,
        showHiddenItems = showHiddenItems,
        viewMode = viewMode,
        filterQuery = filterQuery,
        backStack = backStack,
        forwardStack = forwardStack,
    )
}

private fun String.isSameOrChildOf(parentLocation: String): Boolean {
    val target = Path.of(this).normalize().toAbsolutePath()
    val parent = Path.of(parentLocation).normalize().toAbsolutePath()
    return target == parent || target.startsWith(parent)
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

private fun buildAncestorLocations(location: String): List<String> {
    val path = Path.of(location).normalize().toAbsolutePath()
    val chain = ArrayDeque<String>()
    var current: Path? = path
    while (current != null) {
        chain.addFirst(current.toString().ifBlank { "/" })
        current = current.parent
    }
    return chain.toList()
}

private fun normalizeTreeLocation(location: String): String? {
    return runCatching {
        Path.of(location).normalize().toAbsolutePath().toString().ifBlank { "/" }
    }.getOrNull()
}

private fun SidebarTreeState.findNode(location: String): SidebarTreeNode? {
    fun List<SidebarTreeNode>.search(): SidebarTreeNode? {
        for (node in this) {
            if (node.location == location) {
                return node
            }
            val nestedNode = node.children.search()
            if (nestedNode != null) {
                return nestedNode
            }
        }
        return null
    }

    return roots.search()
}

private fun SidebarTreeState.updateNode(
    location: String,
    transform: (SidebarTreeNode) -> SidebarTreeNode,
): SidebarTreeState {
    fun List<SidebarTreeNode>.update(): List<SidebarTreeNode> {
        return map { node ->
            when {
                node.location == location -> transform(node)
                node.children.isNotEmpty() -> node.copy(children = node.children.update())
                else -> node
            }
        }
    }

    return copy(roots = roots.update())
}

private const val MaxFavoriteLocations = 12
private const val MaxRecentLocations = 10
