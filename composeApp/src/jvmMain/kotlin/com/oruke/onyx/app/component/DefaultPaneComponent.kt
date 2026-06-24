package com.oruke.onyx.app.component

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.navigate
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.component.delegate.SelectionReducer
import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.app.filesystem.ArchiveEntryOpenService
import com.oruke.onyx.vfs.api.ExternalOpenService
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.app.filesystem.FileTypeService
import com.oruke.onyx.vfs.api.TextClipboardService
import com.oruke.onyx.app.filesystem.VfsPathService
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneOperationFeedback
import com.oruke.onyx.core.model.PaneOperationFeedbackKind
import com.oruke.onyx.core.model.PaneSessionSnapshot
import com.oruke.onyx.core.model.TabSnapshot
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.core.model.ViewMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

internal class DefaultPaneComponent(
    componentContext: ComponentContext,
    private val paneId: PaneId,
    initialLocation: String,
    private val fileRepository: FileRepository,
    private val fileCommandService: FileCommandService,
    private val textClipboardService: TextClipboardService,
    private val externalOpenService: ExternalOpenService,
    private val pathService: VfsPathService,
    private val entryNameSuggestionService: EntryNameSuggestionService,
    private val fileTypeService: FileTypeService,
    private val archiveEntryOpenService: ArchiveEntryOpenService,
    private val initialViewMode: ViewMode = ViewMode.DETAILS,
    private val onOpenImageViewer: ((file: VFile, allImages: List<VFile>) -> Unit)? = null,
    private val onRemoteAuthenticationRequired: (PaneId, VfsProviderError) -> Unit = { _, _ -> },
    private val onFileRenamed: (source: VFile, renamed: VFile) -> Unit = { _, _ -> },
) : PaneComponent, ComponentContext by componentContext {

    // 生命周期绑定的 CoroutineScope — lifecycle.onDestroy 时自动取消
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
        lifecycle.doOnDestroy { s.cancel() }
    }

    private val initialTab = createTabState(
        location = pathService.normalizeLocation(initialLocation),
        defaultViewMode = initialViewMode,
    )

    private val mutableState = MutableStateFlow(
        initialTab.toPaneState(
            paneId = paneId,
            activeTabId = initialTab.id,
        )
    )

    override val state: StateFlow<PaneState> = mutableState.asStateFlow()
    private val mutableTabOrder = MutableStateFlow(listOf(initialTab.id))
    override val tabOrder: StateFlow<List<String>> = mutableTabOrder.asStateFlow()
    private val tabNavigation = StackNavigation<TabConfig>()
    override val tabStack: Value<ChildStack<TabConfig, TabComponent>> = childStack(
        source = tabNavigation,
        serializer = null,
        initialStack = { listOf(initialTab.toTabSnapshot().toTabConfig()) },
        key = "PaneTabStack-$paneId",
        childFactory = { config, childContext ->
            DefaultTabComponent(
                componentContext = childContext,
                config = config,
                pathService = pathService,
            )
        },
    )

    private val fileWatcherController = PaneFileWatcherController(
        scope = scope,
        onChanged = { location ->
            val tab = activeTab()
            if (tab != null && tab.location == location) {
                loadTab(tabId = tab.id, location = tab.location)
            }
        },
        onWatchDegraded = { location, failure ->
            val tab = activeTab()
            if (tab != null && tab.location == location) {
                updateFailure(
                    tabId = tab.id,
                    kind = PaneOperationFeedbackKind.WATCH_DEGRADED,
                    detail = failure.toI18nMessage(),
                )
            }
        },
    )

    /** 每个 tab 正在执行的加载 Job — 导航 / 刷新时先取消旧 Job，避免状态卡死。 */
    private val tabLoadJobs = mutableMapOf<String, Job>()

    /**
     * key = tabId, value = entry name to auto-select+scroll after load.
     * Consumed in [loadTab] on success.
     */
    private val pendingFocusEntryName = mutableMapOf<String, String>()

    init {
        refresh()
        fileWatcherController.start(mutableState.value.location)
    }

    override fun dispatch(intent: PaneIntent) {
        when (intent) {
            PaneIntent.Refresh -> refresh()
            PaneIntent.GoBack -> goBack()
            PaneIntent.GoForward -> goForward()
            PaneIntent.GoUp -> goUp()
            is PaneIntent.OpenDirectory -> openDirectory(intent.location)
            is PaneIntent.OpenEntry -> openEntry(intent.entry)
            is PaneIntent.SetViewMode -> setViewMode(intent.mode)
            is PaneIntent.SetFilterQuery -> setFilterQuery(intent.query)
            PaneIntent.ShowFilterInput -> showFilterInput()
            is PaneIntent.HideFilterInput -> hideFilterInput(intent.clearQuery)
            PaneIntent.ShowCommandPalette -> showCommandPalette()
            PaneIntent.HideCommandPalette -> hideCommandPalette()
            is PaneIntent.ToggleSort -> toggleSort(intent.column)
            PaneIntent.ToggleHiddenItems -> toggleHiddenItems()
            is PaneIntent.ToggleColumnVisibility -> toggleColumnVisibility(intent.column)
            is PaneIntent.SetGalleryItemSize -> setGalleryItemSize(intent.sizeDp)
            is PaneIntent.ResizeDetailsColumn -> resizeDetailsColumn(
                column = intent.column,
                nextColumn = intent.nextColumn,
                deltaWeight = intent.deltaWeight,
            )
            is PaneIntent.SelectEntry -> selectEntry(
                entryId = intent.entryId,
                additive = intent.additive,
                range = intent.range,
            )
            is PaneIntent.SelectEntries -> selectEntries(intent.entryIds)
            is PaneIntent.MoveSelection -> moveSelection(
                offset = intent.offset,
                extendSelection = intent.extendSelection,
            )
            PaneIntent.OpenSelectedEntry -> openSelectedEntry()
            PaneIntent.BeginRename -> beginRename()
            PaneIntent.BeginCreateFile -> beginCreateFile()
            PaneIntent.BeginCreateDirectory -> beginCreateDirectory()
            PaneIntent.OpenSelectedInNewTab -> openSelectedInNewTab()
            PaneIntent.CopySelectedPaths -> copySelectedPaths()
            is PaneIntent.UpdateInlineEditDraft -> updateInlineEditDraft(intent.draft)
            PaneIntent.ConfirmInlineEdit -> confirmInlineEdit()
            PaneIntent.CancelInlineEdit -> cancelInlineEdit()
            PaneIntent.DismissOperationFeedback -> dismissOperationFeedback()
            is PaneIntent.ShowOperationFeedback -> showOperationFeedback(intent.kind, intent.detail)
            PaneIntent.SelectAll -> selectAll()
            PaneIntent.ClearSelection -> clearSelection()
            is PaneIntent.CreateTab -> createTab(intent.location ?: state.value.location)
            is PaneIntent.SelectTab -> selectTab(intent.tabId)
            is PaneIntent.CloseTab -> closeTab(intent.tabId)
            is PaneIntent.MoveTab -> moveTab(
                tabId = intent.tabId,
                targetIndex = intent.targetIndex,
            )
            is PaneIntent.ToggleInlineExpand -> toggleInlineExpand(intent.directoryLocation)
            PaneIntent.ConsumePendingScroll -> consumePendingScroll()
        }
    }

    fun refresh() {
        val tab = activeTab() ?: return
        updateTab(tab.id) { currentTab ->
            currentTab.prepareForRefresh()
        }
        loadTab(tabId = tab.id, location = tab.location)
    }

    fun goBack() {
        val tab = activeTab() ?: return
        val previousLocation = tab.backStack.lastOrNull() ?: return
        // If going back to a parent, pre-set focus to current dir name
        val currentDirName = locationBaseName(tab.location)
        if (currentDirName != null && pathService.isDirectParent(previousLocation, tab.location)) {
            pendingFocusEntryName[tab.id] = currentDirName
        }
        updateTab(tab.id) { currentTab ->
            currentTab.navigateBackState(previousTitle = pathService.title(previousLocation))
        }
        loadTab(tabId = tab.id, location = previousLocation)
    }

    fun goForward() {
        val tab = activeTab() ?: return
        val nextLocation = tab.forwardStack.lastOrNull() ?: return
        updateTab(tab.id) { currentTab ->
            currentTab.navigateForwardState(nextTitle = pathService.title(nextLocation))
        }
        loadTab(tabId = tab.id, location = nextLocation)
    }

    fun goUp() {
        val tab = activeTab() ?: return
        val currentLocation = tab.location
        val parentLocation = pathService.parentLocation(currentLocation) ?: return
        val currentDirName = locationBaseName(currentLocation)
        if (currentDirName != null) {
            pendingFocusEntryName[tab.id] = currentDirName
        }
        navigateActiveTab(
            location = parentLocation,
            recordHistory = true,
        )
    }

    fun openDirectory(location: String) {
        val tab = activeTab()
        if (tab != null) {
            val childName = pathService.directChildName(ancestor = location, descendant = tab.location)
            if (childName != null) {
                pendingFocusEntryName[tab.id] = childName
            }
        }
        navigateActiveTab(
            location = location,
            recordHistory = true,
        )
    }

    fun openEntry(entry: VFile) {
        val tab = activeTab() ?: return
        clearInlineEdit(tab.id)
        when (entry.kind) {
            VFileKind.DIRECTORY -> {
                openDirectory(entry.location)
            }

            VFileKind.FILE -> {
                val isInsideArchive = ArchiveService.isArchiveLocation(entry.location)
                // 压缩包 → 以文件夹方式浏览
                if (fileTypeService.isArchiveFileName(entry.name)) {
                    if (isInsideArchive) {
                        // 嵌套压缩包：暂不支持，忽略
                    } else {
                        openDirectory(ArchiveService.archiveLocation(entry.location))
                    }
                } else if (onOpenImageViewer != null && fileTypeService.isImageFileName(entry.name)) {
                    val allImages = currentVisibleEntries()
                        .filter { it.kind == VFileKind.FILE && fileTypeService.isImageFileName(it.name) }
                    onOpenImageViewer.invoke(entry, allImages)
                } else if (isInsideArchive) {
                    // archive:// 内的普通文件 → 先提取到临时目录再外部打开
                    scope.launch {
                        try {
                            archiveEntryOpenService.openArchiveEntry(entry)
                                .onSuccess { clearOperationFeedback(tab.id) }
                                .onFailure { failure ->
                                    updateFailure(
                                        tabId = tab.id,
                                        kind = PaneOperationFeedbackKind.OPEN_FAILED,
                                        detail = failure.toI18nMessage(),
                                    )
                                }
                        } catch (e: Exception) {
                            updateFailure(
                                tabId = tab.id,
                                kind = PaneOperationFeedbackKind.OPEN_FAILED,
                                detail = e.toI18nMessage(),
                            )
                        }
                    }
                } else {
                    scope.launch {
                        externalOpenService.open(entry)
                            .onSuccess {
                                clearOperationFeedback(tab.id)
                            }
                            .onFailure { failure ->
                                updateFailure(
                                    tabId = tab.id,
                                    kind = PaneOperationFeedbackKind.OPEN_FAILED,
                                    detail = failure.toI18nMessage(),
                                )
                            }
                    }
                }
            }
        }
    }

    fun beginRename() {
        val tab = activeTab() ?: return
        if (tab.inlineEditState != null) {
            return
        }
        val entries = currentVisibleEntries()
        val targetEntryId = SelectionReducer.resolveSelectionFocusId(entries, tab.selectionFocusId, tab.selectionAnchorId, tab.selectedEntryIds) ?: return
        val targetEntry = entries.firstOrNull { it.id == targetEntryId } ?: return
        updateTab(tab.id) { currentTab ->
            currentTab.beginRenameInlineEdit(targetEntry)
        }
    }

    fun beginCreateFile() {
        val tab = activeTab() ?: return
        if (tab.inlineEditState != null) {
            return
        }
        scope.launch {
            val baseName = entryNameSuggestionService.newFileName()
            val nextName = tab.nextCreateName(baseName)
            updateTab(tab.id) { currentTab ->
                currentTab.beginCreateFileInlineEdit(nextName)
            }
        }
    }

    fun beginCreateDirectory() {
        val tab = activeTab() ?: return
        if (tab.inlineEditState != null) {
            return
        }
        scope.launch {
            val baseName = entryNameSuggestionService.newDirectoryName()
            val nextName = tab.nextCreateName(baseName)
            updateTab(tab.id) { currentTab ->
                currentTab.beginCreateDirectoryInlineEdit(nextName)
            }
        }
    }

    fun openSelectedInNewTab() {
        val tab = activeTab() ?: return
        val entries = currentVisibleEntries()
        val targetEntryId = SelectionReducer.resolveSelectionFocusId(entries, tab.selectionFocusId, tab.selectionAnchorId, tab.selectedEntryIds) ?: return
        val targetEntry = entries.firstOrNull { it.id == targetEntryId } ?: return
        if (targetEntry.kind != VFileKind.DIRECTORY) {
            return
        }
        clearInlineEdit(tab.id)
        createTab(targetEntry.location)
    }

    fun copySelectedPaths() {
        val tab = activeTab() ?: return
        val entries = currentVisibleEntries()
            .filter { entry -> tab.selectedEntryIds.contains(entry.id) }
        if (entries.isEmpty()) {
            return
        }
        scope.launch {
            textClipboardService.copyText(entries.joinToString(separator = "\n") { entry -> entry.location })
                .onSuccess {
                    clearOperationFeedback(tab.id)
                }
                .onFailure { failure ->
                    updateFailure(
                        tabId = tab.id,
                        kind = PaneOperationFeedbackKind.COPY_PATH_FAILED,
                        detail = failure.toI18nMessage(),
                    )
                }
        }
    }

    fun updateInlineEditDraft(draft: String) {
        val tab = activeTab() ?: return
        val inlineEditState = tab.inlineEditState
        if (inlineEditState == null || draft == inlineEditState.draftName) {
            return
        }
        updateTab(tab.id) { currentTab ->
            currentTab.withInlineEditDraft(draft)
        }
    }

    fun confirmInlineEdit() {
        val tab = activeTab() ?: return
        val result = tab.confirmInlineEditState(currentTabEntries(tab))
        if (result.tab != tab) {
            updateTab(tab.id) { result.tab }
        }

        when (val operation = result.operation) {
            is InlineEditOperation.Rename -> {
                scope.launch {
                    fileCommandService.rename(
                        entry = operation.entry,
                        targetName = operation.targetName,
                    ).onSuccess { renamedEntry ->
                        onFileRenamed(operation.entry, renamedEntry)
                        clearOperationFeedback(tab.id)
                        refreshActiveTab(tab.id)
                    }.onFailure { failure ->
                        updateFailure(
                            tabId = tab.id,
                            kind = PaneOperationFeedbackKind.RENAME_FAILED,
                            detail = failure.toI18nMessage(),
                        )
                    }
                }
            }

            is InlineEditOperation.CreateFile -> {
                scope.launch {
                    fileCommandService.createFile(
                        parentLocation = operation.parentLocation,
                        name = operation.name,
                    ).onSuccess {
                        clearOperationFeedback(tab.id)
                        refreshActiveTab(tab.id)
                    }.onFailure { failure ->
                        updateFailure(
                            tabId = tab.id,
                            kind = PaneOperationFeedbackKind.CREATE_FILE_FAILED,
                            detail = failure.toI18nMessage(),
                        )
                    }
                }
            }

            is InlineEditOperation.CreateDirectory -> {
                scope.launch {
                    fileCommandService.createDirectory(
                        parentLocation = operation.parentLocation,
                        name = operation.name,
                    ).onSuccess {
                        clearOperationFeedback(tab.id)
                        refreshActiveTab(tab.id)
                    }.onFailure { failure ->
                        updateFailure(
                            tabId = tab.id,
                            kind = PaneOperationFeedbackKind.CREATE_DIRECTORY_FAILED,
                            detail = failure.toI18nMessage(),
                        )
                    }
                }
            }

            null -> return
        }
    }

    fun cancelInlineEdit() {
        clearInlineEdit(activeTab()?.id ?: return)
    }

    fun selectEntry(
        entryId: String,
        additive: Boolean,
        range: Boolean,
    ) {
        val tab = activeTab() ?: return
        clearInlineEdit(tab.id)
        val entries = currentVisibleEntries()
        updateTab(tab.id) { currentTab ->
            currentTab.selectEntryState(
                entries = entries,
                entryId = entryId,
                additive = additive,
                range = range,
            )
        }
    }

    fun selectEntries(entryIds: Set<String>) {
        val tab = activeTab() ?: return
        updateTab(tab.id) { currentTab ->
            currentTab.withSelectedEntryIds(entryIds)
        }
    }

    fun moveSelection(
        offset: Int,
        extendSelection: Boolean,
    ) {
        val tab = activeTab() ?: return
        clearInlineEdit(tab.id)
        val entries = currentVisibleEntries()
        updateTab(tab.id) { currentTab ->
            currentTab.moveSelectionState(
                entries = entries,
                offset = offset,
                extendSelection = extendSelection,
            )
        }
    }

    fun openSelectedEntry() {
        val tab = activeTab() ?: return
        val selectedEntry = tab.resolveSelectionFocusEntry(currentVisibleEntries()) ?: return
        clearInlineEdit(tab.id)
        openEntry(selectedEntry)
    }

    fun selectAll() {
        val tab = activeTab() ?: return
        clearInlineEdit(tab.id)
        val entries = currentVisibleEntries()
        updateTab(tab.id) { currentTab ->
            currentTab.selectAllEntriesState(entries)
        }
    }

    fun clearSelection() {
        val tab = activeTab() ?: return
        clearInlineEdit(tab.id)
        updateTab(tab.id) { currentTab ->
            currentTab.clearSelectionState()
        }
    }

    fun createTab(location: String) {
        val tab = createTabState(pathService.normalizeLocation(location))
        val update = tabs().withCreatedTab(tab = tab)
        applyTabStackUpdate(update)
        loadTab(tabId = update.activeTab.id, location = update.activeTab.location)
    }

    fun selectTab(tabId: String) {
        val update = tabs().withSelectedTab(tabId = tabId) ?: return
        applyTabStackUpdate(update)
        if (update.activeTab.entriesState == PaneEntriesState.Idle) {
            loadTab(tabId = update.activeTab.id, location = update.activeTab.location)
        }
    }

    fun closeTab(tabId: String) {
        val update = tabs().withClosedTab(
            activeTabId = mutableState.value.activeTabId,
            tabId = tabId,
        ) ?: return
        applyTabStackUpdate(update)
        if (update.activeTab.entriesState == PaneEntriesState.Idle) {
            loadTab(tabId = update.activeTab.id, location = update.activeTab.location)
        }
    }

    fun moveTab(
        tabId: String,
        targetIndex: Int,
    ) {
        val update = tabs().withMovedTab(
            activeTabId = mutableState.value.activeTabId,
            tabId = tabId,
            targetIndex = targetIndex,
        ) ?: return
        applyTabStackUpdate(update)
    }

    override fun detachTab(tabId: String): TabSnapshot? {
        val currentTabs = tabs()
        val update = currentTabs.withDetachedTab(
            activeTabId = mutableState.value.activeTabId,
            tabId = tabId,
            replacementTab = if (currentTabs.size == 1) {
                createTabState(fileRepository.defaultLocation())
            } else {
                null
            },
        ) ?: return null
        applyTabStackUpdate(update)
        if (update.activeTab.entriesState == PaneEntriesState.Idle) {
            loadTab(tabId = update.activeTab.id, location = update.activeTab.location)
        }
        return update.detachedTabSnapshot
    }

    override fun attachTab(
        tabSnapshot: TabSnapshot,
        targetIndex: Int,
    ) {
        val restoredTab = tabSnapshot.toPaneTabState(pathService)
        val currentTabs = tabs()
        val uniqueTab = if (currentTabs.any { it.id == restoredTab.id }) {
            restoredTab.copy(id = UUID.randomUUID().toString())
        } else {
            restoredTab
        }
        val update = currentTabs.withAttachedTab(
            tab = uniqueTab,
            targetIndex = targetIndex,
        )
        applyTabStackUpdate(update)
        if (update.activeTab.entriesState == PaneEntriesState.Idle) {
            loadTab(tabId = update.activeTab.id, location = update.activeTab.location)
        }
    }

    override fun restoreSession(snapshot: PaneSessionSnapshot) {
        val restoredTabs = snapshot.tabs
            .ifEmpty { listOf(createTabState(fileRepository.defaultLocation()).toTabSnapshot()) }
            .map { tabSnapshot -> tabSnapshot.toPaneTabState(pathService) }

        val activeTabId = snapshot.activeTabId.takeIf { candidate ->
            restoredTabs.any { tab -> tab.id == candidate }
        } ?: restoredTabs.first().id
        val activeTab = restoredTabs.first { tab -> tab.id == activeTabId }
        navigateTabStack(
            tabs = restoredTabs,
            activeTabId = activeTabId,
        )
        applyActiveTab(
            activeTab = activeTab,
            activeTabId = activeTabId,
        )
        loadTab(tabId = activeTab.id, location = activeTab.location)
    }

    // ── 内联展开（文件列表树状展开） ──────────────────────────────────────

    fun toggleInlineExpand(directoryLocation: String) {
        val result = mutableState.value.toggleInlineExpandState(directoryLocation)
        mutableState.value = result.state
        result.loadRequest?.let { request ->
            loadInlineExpandChildren(request.location, request.depth)
        }
    }

    private fun loadInlineExpandChildren(location: String, depth: Int) {
        scope.launch {
            fileRepository.list(location).fold(
                onSuccess = { entries ->
                    mutableState.value = mutableState.value.withInlineExpandChildren(
                        location = location,
                        depth = depth,
                        entries = entries,
                    )
                },
                onFailure = {
                    mutableState.value = mutableState.value.withInlineExpandFailure(
                        location = location,
                        depth = depth,
                    )
                },
            )
        }
    }


    private fun navigateActiveTab(
        location: String,
        recordHistory: Boolean,
    ) {
        val tab = activeTab() ?: return
        val normalizedLocation = pathService.normalizeLocation(location)
        if (normalizedLocation == tab.location) {
            refresh()
            return
        }

        updateTab(tab.id) { currentTab ->
            currentTab.navigateToState(
                location = normalizedLocation,
                title = pathService.title(normalizedLocation),
                recordHistory = recordHistory,
            )
        }
        // 导航时清空内联展开状态
        mutableState.value = mutableState.value.clearInlineExpandState()
        loadTab(tabId = tab.id, location = normalizedLocation)
        fileWatcherController.start(normalizedLocation)
    }

    private fun loadTab(
        tabId: String,
        location: String,
    ) {
        // 取消该 tab 之前的加载（防止超时挂起导致后续操作全部卡死）
        tabLoadJobs[tabId]?.cancel()
        tabLoadJobs[tabId] = scope.launch {
            try {
                val result = fileRepository.list(location)
                // Stale guard：如果 tab 已经导航到别处，丢弃本次结果
                val currentTab = tab(tabId)
                if (currentTab == null || currentTab.location != location) return@launch

                result.fold(
                    onSuccess = { entries ->
                        val focusName = pendingFocusEntryName.remove(tabId)
                        val focusEntry = if (focusName != null) {
                            entries.firstOrNull { it.name == focusName }
                        } else null
                        updateTab(tabId) { tab ->
                            tab.withLoadedEntries(
                                entries = entries,
                                focusEntry = focusEntry,
                            )
                        }
                    },
                    onFailure = { failure ->
                        OnyxLogger.error("PaneComponent", "目录加载失败: $location", failure)
                        requestRemoteCredentialsIfSupported(failure)
                        pendingFocusEntryName.remove(tabId)
                        updateTab(tabId) { tab ->
                            tab.withLoadFailure(failure.toI18nMessage())
                        }
                    },
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                throw kotlinx.coroutines.CancellationException()
            } catch (e: Exception) {
                // 非预期异常 → 也要转为 Failure，不能让状态卡在 Loading
                OnyxLogger.error("PaneComponent", "目录加载异常: $location", e)
                requestRemoteCredentialsIfSupported(e)
                pendingFocusEntryName.remove(tabId)
                updateTab(tabId) { tab ->
                    tab.withLoadFailure(e.toI18nMessage())
                }
            } finally {
                tabLoadJobs.remove(tabId)
            }
        }
    }

    private fun refreshActiveTab(tabId: String) {
        updateTab(tabId) { currentTab ->
            currentTab.navigateToState(
                location = currentTab.location,
                title = currentTab.title,
                recordHistory = false,
            )
        }
        val tab = tab(tabId)
        if (tab != null) {
            loadTab(tabId = tab.id, location = tab.location)
        }
    }

    private fun requestRemoteCredentialsIfSupported(failure: Throwable) {
        val error = (failure as? VfsProviderException)?.error ?: return
        val authError = when (error) {
            is VfsProviderError.AuthenticationRejected -> error
            is VfsProviderError.AuthenticationRequired -> error
            else -> return
        }
        if (authError.protocol != VfsProtocol.SMB && authError.protocol != VfsProtocol.WEBDAV) {
            return
        }
        onRemoteAuthenticationRequired(paneId, authError)
    }

    private fun createTabState(
        location: String,
        defaultViewMode: ViewMode = initialViewMode,
    ): PaneTabState {
        return createDefaultPaneTabState(
            id = UUID.randomUUID().toString(),
            title = pathService.title(location),
            location = location,
            defaultViewMode = defaultViewMode,
        )
    }

    private fun clearInlineEdit(tabId: String) {
        updateTab(tabId) { currentTab ->
            currentTab.clearInlineEditState()
        }
    }

    internal fun clearOperationFeedback(tabId: String) {
        updateTab(tabId) { currentTab ->
            currentTab.withTabState { current -> current.copy(operationFeedback = null) }
        }
    }

    internal fun updateFailure(
        tabId: String,
        kind: PaneOperationFeedbackKind,
        detail: I18nMessage?,
    ) {
        updateTab(tabId) { currentTab ->
            currentTab.withTabState { current ->
                current.copy(
                    operationFeedback = PaneOperationFeedback(
                        kind = kind,
                        detail = detail,
                    )
                )
            }
        }
    }

    private fun currentTabEntries(tab: PaneTabState): List<VFile> {
        return when (val entriesState = tab.entriesState) {
            is PaneEntriesState.Ready -> entriesState.entries
            else -> emptyList()
        }
    }

    internal fun updateTab(
        tabId: String,
        transform: (PaneTabState) -> PaneTabState,
    ) {
        val state = mutableState.value
        var updatedTab: PaneTabState? = null
        tabs().forEach { tab ->
            if (tab.id == tabId) {
                transform(tab).withDerivedState(
                    inlineExpandedLocations = state.inlineExpandedLocations,
                    inlineExpandedEntries = state.inlineExpandedEntries,
                ).also { updated ->
                    updatedTab = updated
                }
            }
        }

        val updated = updatedTab ?: return
        updateTabComponentState(updated)
        if (state.activeTabId == tabId) {
            applyActiveTab(
                activeTab = updated,
                activeTabId = state.activeTabId,
                inlineExpandedLocations = state.inlineExpandedLocations,
                inlineExpandedEntries = state.inlineExpandedEntries,
            )
        }
    }

    private fun applyTabStackUpdate(update: PaneTabStackUpdate) {
        navigateTabStack(
            tabs = update.tabs,
            activeTabId = update.activeTab.id,
        )
        applyActiveTab(update.activeTab)
    }

    private fun applyTabStackUpdate(update: PaneTabDetachUpdate) {
        navigateTabStack(
            tabs = update.tabs,
            activeTabId = update.activeTab.id,
        )
        applyActiveTab(update.activeTab)
    }

    private fun navigateTabStack(
        tabs: List<PaneTabState>,
        activeTabId: String,
    ) {
        mutableTabOrder.value = tabs.map { tab -> tab.id }
        val configs = tabs.map { tab -> tab.toTabSnapshot().toTabConfig() }
        val activeIndex = configs.indexOfFirst { config -> config.id == activeTabId }
        val orderedConfigs = if (activeIndex in configs.indices && activeIndex != configs.lastIndex) {
            configs.toMutableList().apply {
                add(removeAt(activeIndex))
            }
        } else {
            configs
        }
        if (orderedConfigs.isNotEmpty()) {
            tabNavigation.navigate { orderedConfigs }
            tabs.forEach(::updateTabComponentState)
        }
    }

    private fun applyActiveTab(
        activeTab: PaneTabState,
        activeTabId: String = activeTab.id,
        inlineExpandedLocations: Set<String> = emptySet(),
        inlineExpandedEntries: Map<String, InlineExpandedEntry> = emptyMap(),
    ) {
        val chrome = mutableState.value.chromeState
        mutableState.value = activeTab.toPaneState(
            paneId = paneId,
            activeTabId = activeTabId,
            inlineExpandedLocations = inlineExpandedLocations,
            inlineExpandedEntries = inlineExpandedEntries,
            filterInputVisible = chrome.filterInputVisible,
            filterInputFocusRequestId = chrome.filterInputFocusRequestId,
            commandPaletteVisible = chrome.commandPaletteVisible,
        )
    }

    /**
     * 更新面板 chrome 状态，不触碰当前标签业务状态。
     *
     * @param transform 基于当前 chrome 状态生成下一状态的转换函数。
     * @return 无返回值。
     */
    internal fun updatePaneChrome(transform: (PaneChromeState) -> PaneChromeState) {
        val currentState = mutableState.value
        mutableState.value = currentState.copy(
            chromeState = transform(currentState.chromeState),
        )
    }

    private fun updateTabComponentState(tab: PaneTabState) {
        tabStack.value.items
            .firstOrNull { child -> child.configuration.id == tab.id }
            ?.instance
            ?.updateState(tab)
    }

    internal fun activeTab(): PaneTabState? {
        return tabStack.value.active.instance.state.value
    }

    private fun tab(tabId: String): PaneTabState? {
        return tabStack.value.items
            .firstOrNull { child -> child.configuration.id == tabId }
            ?.instance
            ?.state
            ?.value
    }

    private fun tabs(): List<PaneTabState> {
        return tabStatesInDisplayOrder()
    }

    private fun currentVisibleEntries(): List<VFile> {
        val state = mutableState.value
        return when (val entriesState = activeTab()?.entriesState) {
            is PaneEntriesState.Ready -> {
                if (state.inlineExpandedLocations.isEmpty()) {
                    entriesState.entries
                } else {
                    SelectionReducer.collectVisibleEntries(
                        entries = entriesState.entries,
                        expandedLocations = state.inlineExpandedLocations,
                        expandedEntries = state.inlineExpandedEntries,
                    )
                }
            }
            else -> emptyList()
        }
    }



    private fun locationBaseName(location: String): String? = pathService.baseName(location)
}
