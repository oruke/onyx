package com.oruke.onyx.app.component

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.component.delegate.SelectionHelper
import com.oruke.onyx.app.filesystem.ArchiveService
import com.oruke.onyx.app.filesystem.ArchiveEntryOpenService
import com.oruke.onyx.app.filesystem.ArchiveFileTypeService
import com.oruke.onyx.app.filesystem.ExternalOpenService
import com.oruke.onyx.app.filesystem.FileCommandService
import com.oruke.onyx.app.filesystem.FileRepository
import com.oruke.onyx.app.filesystem.FileWatcher
import com.oruke.onyx.app.filesystem.TextClipboardService
import com.oruke.onyx.app.filesystem.VfsPathService
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneOperationFeedback
import com.oruke.onyx.core.model.PaneOperationFeedbackKind
import com.oruke.onyx.core.model.PaneSessionSnapshot
import com.oruke.onyx.core.model.SortDirection
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_new_directory
import onyx.composeapp.generated.resources.action_new_file
import onyx.composeapp.generated.resources.msg_string_literal
import java.nio.file.Path
import java.util.*

class DefaultPaneComponent(
    componentContext: ComponentContext,
    private val paneId: PaneId,
    initialLocation: String,
    private val fileRepository: FileRepository,
    private val fileCommandService: FileCommandService,
    private val textClipboardService: TextClipboardService,
    private val externalOpenService: ExternalOpenService,
    private val pathService: VfsPathService,
    private val archiveFileTypeService: ArchiveFileTypeService,
    private val archiveEntryOpenService: ArchiveEntryOpenService,
    private val initialViewMode: ViewMode = ViewMode.DETAILS,
    private val onOpenImageViewer: ((file: VFile, allImages: List<VFile>) -> Unit)? = null,
) : PaneComponent, ComponentContext by componentContext {

    // 生命周期绑定的 CoroutineScope — lifecycle.onDestroy 时自动取消
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
        lifecycle.doOnDestroy { s.cancel() }
    }

    private val mutableState = MutableStateFlow(
        createInitialState(
            initialLocation = initialLocation,
            defaultViewMode = initialViewMode,
        )
    )

    override val state: StateFlow<PaneState> = mutableState.asStateFlow()

    private val fileWatcher = FileWatcher()
    private var fileWatcherJob: Job? = null

    /** 每个 tab 正在执行的加载 Job — 导航 / 刷新时先取消旧 Job，避免状态卡死。 */
    private val tabLoadJobs = mutableMapOf<String, Job>()

    /**
     * key = tabId, value = entry name to auto-select+scroll after load.
     * Consumed in [loadTab] on success.
     */
    private val pendingFocusEntryName = mutableMapOf<String, String>()

    init {
        refresh()
        startWatching(mutableState.value.location)
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

    override fun refresh() {
        val tab = activeTab() ?: return
        updateTab(tab.id) { currentTab ->
            currentTab.prepareForRefresh()
        }
        loadTab(tabId = tab.id, location = tab.location)
    }

    override fun goBack() {
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

    override fun goForward() {
        val tab = activeTab() ?: return
        val nextLocation = tab.forwardStack.lastOrNull() ?: return
        updateTab(tab.id) { currentTab ->
            currentTab.navigateForwardState(nextTitle = pathService.title(nextLocation))
        }
        loadTab(tabId = tab.id, location = nextLocation)
    }

    override fun goUp() {
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

    override fun openDirectory(location: String) {
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

    override fun openEntry(entry: VFile) {
        val tab = activeTab() ?: return
        clearInlineEdit(tab.id)
        when (entry.kind) {
            VFileKind.DIRECTORY -> {
                openDirectory(entry.location)
            }

            VFileKind.FILE -> {
                val isInsideArchive = ArchiveService.isArchiveLocation(entry.location)
                // 压缩包 → 以文件夹方式浏览
                if (archiveFileTypeService.isArchiveFileName(entry.name)) {
                    if (isInsideArchive) {
                        // 嵌套压缩包：暂不支持，忽略
                    } else {
                        openDirectory(ArchiveService.archiveLocation(entry.location))
                    }
                } else if (onOpenImageViewer != null && isImageFileName(entry.name)) {
                    val allImages = currentVisibleEntries()
                        .filter { it.kind == VFileKind.FILE && isImageFileName(it.name) }
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
                                        detail = failure.message?.let { I18nMessage(Res.string.msg_string_literal, it) },
                                    )
                                }
                        } catch (e: Exception) {
                            updateFailure(
                                tabId = tab.id,
                                kind = PaneOperationFeedbackKind.OPEN_FAILED,
                                detail = I18nMessage(Res.string.msg_string_literal, e.message ?: "Unknown error"),
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
                                    detail = failure.message?.let { I18nMessage(Res.string.msg_string_literal, it) },
                                )
                            }
                    }
                }
            }
        }
    }

    override fun beginRename() {
        val tab = activeTab() ?: return
        if (tab.inlineEditState != null) {
            return
        }
        val entries = currentVisibleEntries()
        val targetEntryId = SelectionHelper.resolveSelectionFocusId(entries, tab.selectionFocusId, tab.selectionAnchorId, tab.selectedEntryIds) ?: return
        val targetEntry = entries.firstOrNull { it.id == targetEntryId } ?: return
        updateTab(tab.id) { currentTab ->
            currentTab.beginRenameInlineEdit(targetEntry)
        }
    }

    override fun beginCreateFile() {
        val tab = activeTab() ?: return
        if (tab.inlineEditState != null) {
            return
        }
        scope.launch {
            val baseName = org.jetbrains.compose.resources.getString(Res.string.action_new_file)
            val nextName = tab.nextCreateName(baseName)
            updateTab(tab.id) { currentTab ->
                currentTab.beginCreateFileInlineEdit(nextName)
            }
        }
    }

    override fun beginCreateDirectory() {
        val tab = activeTab() ?: return
        if (tab.inlineEditState != null) {
            return
        }
        scope.launch {
            val baseName = org.jetbrains.compose.resources.getString(Res.string.action_new_directory)
            val nextName = tab.nextCreateName(baseName)
            updateTab(tab.id) { currentTab ->
                currentTab.beginCreateDirectoryInlineEdit(nextName)
            }
        }
    }

    override fun openSelectedInNewTab() {
        val tab = activeTab() ?: return
        val entries = currentVisibleEntries()
        val targetEntryId = SelectionHelper.resolveSelectionFocusId(entries, tab.selectionFocusId, tab.selectionAnchorId, tab.selectedEntryIds) ?: return
        val targetEntry = entries.firstOrNull { it.id == targetEntryId } ?: return
        if (targetEntry.kind != VFileKind.DIRECTORY) {
            return
        }
        clearInlineEdit(tab.id)
        createTab(targetEntry.location)
    }

    override fun copySelectedPaths() {
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
                        detail = failure.message?.let { I18nMessage(Res.string.msg_string_literal, it) },
                    )
                }
        }
    }

    override fun updateInlineEditDraft(draft: String) {
        val tab = activeTab() ?: return
        val inlineEditState = tab.inlineEditState
        if (inlineEditState == null || draft == inlineEditState.draftName) {
            return
        }
        updateTab(tab.id) { currentTab ->
            currentTab.withInlineEditDraft(draft)
        }
    }

    override fun confirmInlineEdit() {
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
                    ).onSuccess {
                        clearOperationFeedback(tab.id)
                        refreshActiveTab(tab.id)
                    }.onFailure { failure ->
                        updateFailure(
                            tabId = tab.id,
                            kind = PaneOperationFeedbackKind.RENAME_FAILED,
                            detail = failure.message?.let { I18nMessage(Res.string.msg_string_literal, it) },
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
                            detail = failure.message?.let { I18nMessage(Res.string.msg_string_literal, it) },
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
                            detail = failure.message?.let { I18nMessage(Res.string.msg_string_literal, it) },
                        )
                    }
                }
            }

            null -> return
        }
    }

    override fun cancelInlineEdit() {
        clearInlineEdit(activeTab()?.id ?: return)
    }

    override fun dismissOperationFeedback() {
        clearOperationFeedback(activeTab()?.id ?: return)
    }

    override fun setViewMode(mode: ViewMode) {
        val tab = activeTab() ?: return
        updateTab(tab.id) { currentTab ->
            currentTab.withTabState { current -> current.copy(viewMode = mode) }
        }
    }

    override fun setFilterQuery(query: String) {
        val tab = activeTab() ?: return
        updateTab(tab.id) { currentTab ->
            currentTab.withTabState { current -> current.copy(filterQuery = query.trim()) }
        }
    }

    override fun toggleSort(column: DetailsColumn) {
        val tab = activeTab() ?: return
        val currentSort = tab.detailsSort
        val nextSort = if (currentSort.column == column) {
            currentSort.copy(
                direction = when (currentSort.direction) {
                    SortDirection.ASCENDING -> SortDirection.DESCENDING
                    SortDirection.DESCENDING -> SortDirection.ASCENDING
                }
            )
        } else {
            DetailsSort(
                column = column,
                direction = SortDirection.ASCENDING,
            )
        }
        updateTab(tab.id) { currentTab ->
            currentTab.withTabState { current ->
                current.copy(
                    detailsSort = nextSort,
                )
            }
        }
    }

    override fun toggleHiddenItems() {
        val tab = activeTab() ?: return
        val showHiddenItems = !tab.showHiddenItems
        updateTab(tab.id) { currentTab ->
            currentTab.withTabState { current ->
                current.copy(
                    showHiddenItems = showHiddenItems,
                )
            }
        }
    }

    override fun toggleColumnVisibility(column: DetailsColumn) {
        // NAME 列不可隐藏
        if (column == DetailsColumn.NAME) return
        val tab = activeTab() ?: return
        updateTab(tab.id) { currentTab ->
            val hidden = currentTab.hiddenColumns.toMutableSet()
            if (column in hidden) hidden.remove(column) else hidden.add(column)
            currentTab.withTabState { current -> current.copy(hiddenColumns = hidden) }
        }
    }

    override fun setGalleryItemSize(sizeDp: Int) {
        val tab = activeTab() ?: return
        val clamped = sizeDp.coerceIn(80, 320)
        updateTab(tab.id) { currentTab ->
            currentTab.withTabState { current -> current.copy(galleryItemSizeDp = clamped) }
        }
    }

    override fun resizeDetailsColumn(
        column: DetailsColumn,
        nextColumn: DetailsColumn,
        deltaWeight: Float,
    ) {
        val tab = activeTab() ?: return
        val currentWidth = tab.detailsColumnWeights[column] ?: defaultDetailsColumnWidth(column)
        val newWidth = (currentWidth + deltaWeight).coerceAtLeast(MIN_DETAILS_COLUMN_WIDTH)
        updateTab(tab.id) { currentTab ->
            currentTab.withTabState { current ->
                current.copy(
                    detailsColumnWeights = current.detailsColumnWeights + mapOf(
                        column to newWidth,
                    )
                )
            }
        }
    }

    override fun selectEntry(
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

    override fun selectEntries(entryIds: Set<String>) {
        val tab = activeTab() ?: return
        updateTab(tab.id) { currentTab ->
            currentTab.withSelectedEntryIds(entryIds)
        }
    }

    override fun moveSelection(
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

    override fun openSelectedEntry() {
        val tab = activeTab() ?: return
        val selectedEntry = tab.resolveSelectionFocusEntry(currentVisibleEntries()) ?: return
        clearInlineEdit(tab.id)
        openEntry(selectedEntry)
    }

    override fun selectAll() {
        val tab = activeTab() ?: return
        clearInlineEdit(tab.id)
        val entries = currentVisibleEntries()
        updateTab(tab.id) { currentTab ->
            currentTab.selectAllEntriesState(entries)
        }
    }

    override fun clearSelection() {
        val tab = activeTab() ?: return
        clearInlineEdit(tab.id)
        updateTab(tab.id) { currentTab ->
            currentTab.clearSelectionState()
        }
    }

    override fun createTab(location: String) {
        val tab = createTabState(pathService.normalizeLocation(location))
        val update = mutableState.value.withCreatedTab(
            paneId = paneId,
            tab = tab,
        )
        mutableState.value = update.state
        loadTab(tabId = update.activeTab.id, location = update.activeTab.location)
    }

    override fun selectTab(tabId: String) {
        val update = mutableState.value.withSelectedTab(
            paneId = paneId,
            tabId = tabId,
        ) ?: return
        mutableState.value = update.state
        if (update.activeTab.entriesState == PaneEntriesState.Idle) {
            loadTab(tabId = update.activeTab.id, location = update.activeTab.location)
        }
    }

    override fun closeTab(tabId: String) {
        val update = mutableState.value.withClosedTab(
            paneId = paneId,
            tabId = tabId,
        ) ?: return
        mutableState.value = update.state
        if (update.activeTab.entriesState == PaneEntriesState.Idle) {
            loadTab(tabId = update.activeTab.id, location = update.activeTab.location)
        }
    }

    override fun moveTab(
        tabId: String,
        targetIndex: Int,
    ) {
        val update = mutableState.value.withMovedTab(
            paneId = paneId,
            tabId = tabId,
            targetIndex = targetIndex,
        ) ?: return
        mutableState.value = update.state
    }

    override fun detachTab(tabId: String): PaneTabState? {
        val state = mutableState.value
        val update = state.withDetachedTab(
            paneId = paneId,
            tabId = tabId,
            replacementTab = if (state.tabs.size == 1) {
                createTabState(fileRepository.defaultLocation())
            } else {
                null
            },
        ) ?: return null
        mutableState.value = update.state
        if (update.activeTab.entriesState == PaneEntriesState.Idle) {
            loadTab(tabId = update.activeTab.id, location = update.activeTab.location)
        }
        return update.detachedTab
    }

    override fun attachTab(
        tab: PaneTabState,
        targetIndex: Int,
    ) {
        val uniqueTab = if (mutableState.value.tabs.any { it.id == tab.id }) {
            tab.copy(id = UUID.randomUUID().toString())
        } else {
            tab
        }
        val update = mutableState.value.withAttachedTab(
            paneId = paneId,
            tab = uniqueTab,
            targetIndex = targetIndex,
        )
        mutableState.value = update.state
        if (update.activeTab.entriesState == PaneEntriesState.Idle) {
            loadTab(tabId = update.activeTab.id, location = update.activeTab.location)
        }
    }

    override fun restoreSession(snapshot: PaneSessionSnapshot) {
        val restoredTabs = snapshot.tabs
            .ifEmpty { listOf(createTabState(fileRepository.defaultLocation()).toSessionSnapshot()) }
            .map { tabSnapshot -> tabSnapshot.toPaneTabState(pathService) }

        val activeTabId = snapshot.activeTabId.takeIf { candidate ->
            restoredTabs.any { tab -> tab.id == candidate }
        } ?: restoredTabs.first().id
        val activeTab = restoredTabs.first { tab -> tab.id == activeTabId }
        mutableState.value = activeTab.toPaneState(
            paneId = paneId,
            activeTabId = activeTabId,
            tabs = restoredTabs,
        )
        loadTab(tabId = activeTab.id, location = activeTab.location)
    }

    // ── 内联展开（文件列表树状展开） ──────────────────────────────────────

    override fun toggleInlineExpand(directoryLocation: String) {
        val result = mutableState.value.toggleInlineExpandState(directoryLocation)
        mutableState.value = result.state
        result.loadRequest?.let { request ->
            loadInlineExpandChildren(request.location, request.depth)
        }
    }

    override fun consumePendingScroll() {
        val tab = activeTab() ?: return
        if (tab.pendingScrollToEntryId != null) {
            updateTab(tab.id) { currentTab ->
                currentTab.withTabState { current -> current.copy(pendingScrollToEntryId = null) }
            }
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
        startWatching(normalizedLocation)
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
                val currentTab = mutableState.value.tabs.firstOrNull { it.id == tabId }
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
                        pendingFocusEntryName.remove(tabId)
                        updateTab(tabId) { tab ->
                            tab.withLoadFailure(failure.message)
                        }
                    },
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                throw kotlinx.coroutines.CancellationException()
            } catch (e: Exception) {
                // 非预期异常 → 也要转为 Failure，不能让状态卡在 Loading
                OnyxLogger.error("PaneComponent", "目录加载异常: $location", e)
                pendingFocusEntryName.remove(tabId)
                updateTab(tabId) { tab ->
                    tab.withLoadFailure(e.message)
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
        val tab = mutableState.value.tabs.firstOrNull { it.id == tabId }
        if (tab != null) {
            loadTab(tabId = tab.id, location = tab.location)
        }
    }

    /**
     * 开始监听指定目录的文件变更，有变更时自动刷新。
     * 每次调用会取消前一个监听。
     */
    private fun startWatching(location: String) {
        fileWatcherJob?.cancel()
        // 压缩包内部无文件系统事件，跳过监听
        if (ArchiveService.isArchiveLocation(location)) return
        val path = try {
            Path.of(location)
        } catch (_: Exception) {
            return
        }
        if (!java.nio.file.Files.isDirectory(path)) return
        fileWatcherJob = fileWatcher.watch(path)
            .onEach {
                val tab = activeTab() ?: return@onEach
                if (tab.location == location) {
                    loadTab(tabId = tab.id, location = tab.location)
                }
            }
            .catch { /* 监听异常静默忽略 */ }
            .launchIn(scope)
    }

    private fun createInitialState(
        initialLocation: String,
        defaultViewMode: ViewMode,
    ): PaneState {
        val tab = createTabState(
            location = pathService.normalizeLocation(initialLocation),
            defaultViewMode = defaultViewMode,
        )
        return tab.toPaneState(
            paneId = paneId,
            activeTabId = tab.id,
            tabs = listOf(tab),
        )
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

    private fun clearOperationFeedback(tabId: String) {
        updateTab(tabId) { currentTab ->
            currentTab.withTabState { current -> current.copy(operationFeedback = null) }
        }
    }

    private fun updateFailure(
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

    private fun updateTab(
        tabId: String,
        transform: (PaneTabState) -> PaneTabState,
    ) {
        val state = mutableState.value
        var updatedActiveTab: PaneTabState? = null
        val nextTabs = state.tabs.map { tab ->
            if (tab.id == tabId) {
                transform(tab).withDerivedState(
                    inlineExpandedLocations = state.inlineExpandedLocations,
                    inlineExpandedEntries = state.inlineExpandedEntries,
                ).also { updated ->
                    if (state.activeTabId == tabId) {
                        updatedActiveTab = updated
                    }
                }
            } else {
                tab
            }
        }

        val activeTab = updatedActiveTab ?: nextTabs.firstOrNull { it.id == state.activeTabId } ?: return
        mutableState.value = activeTab.toPaneState(
            paneId = paneId,
            activeTabId = state.activeTabId,
            tabs = nextTabs,
            inlineExpandedLocations = state.inlineExpandedLocations,
            inlineExpandedEntries = state.inlineExpandedEntries,
        )
    }

    private fun activeTab(): PaneTabState? {
        return mutableState.value.tabs.firstOrNull { it.id == mutableState.value.activeTabId }
    }

    private fun currentVisibleEntries(): List<VFile> {
        val state = mutableState.value
        return when (val entriesState = activeTab()?.entriesState) {
            is PaneEntriesState.Ready -> {
                if (state.inlineExpandedLocations.isEmpty()) {
                    entriesState.entries
                } else {
                    SelectionHelper.collectVisibleEntries(
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

private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "svg", "webp", "bmp", "ico", "tiff", "tif")

private fun isImageFileName(fileName: String): Boolean {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext in imageExtensions
}
