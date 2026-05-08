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
import com.oruke.onyx.core.model.PaneInlineEditMode
import com.oruke.onyx.core.model.PaneInlineEditState
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

    override fun refresh() {
        val tab = activeTab() ?: return
        updateTab(tab.id) { currentTab ->
            currentTab.copy(
                inlineEditState = null,
                entriesState = PaneEntriesState.Loading,
            )
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
        val nextBackStack = tab.backStack.dropLast(1)
        val nextForwardStack = tab.forwardStack + tab.location
        updateTab(tab.id) { currentTab ->
            currentTab.copy(
                location = previousLocation,
                title = pathService.title(previousLocation),
                canGoBack = nextBackStack.isNotEmpty(),
                canGoForward = nextForwardStack.isNotEmpty(),
                selectedEntryIds = emptySet(),
                selectionAnchorId = null,
                selectionFocusId = null,
                entriesState = PaneEntriesState.Loading,
                allEntries = emptyList(),
                inlineEditState = null,
                backStack = nextBackStack,
                forwardStack = nextForwardStack,
            )
        }
        loadTab(tabId = tab.id, location = previousLocation)
    }

    override fun goForward() {
        val tab = activeTab() ?: return
        val nextLocation = tab.forwardStack.lastOrNull() ?: return
        val nextBackStack = tab.backStack + tab.location
        val nextForwardStack = tab.forwardStack.dropLast(1)
        updateTab(tab.id) { currentTab ->
            currentTab.copy(
                location = nextLocation,
                title = pathService.title(nextLocation),
                canGoBack = nextBackStack.isNotEmpty(),
                canGoForward = nextForwardStack.isNotEmpty(),
                selectedEntryIds = emptySet(),
                selectionAnchorId = null,
                selectionFocusId = null,
                entriesState = PaneEntriesState.Loading,
                allEntries = emptyList(),
                inlineEditState = null,
                backStack = nextBackStack,
                forwardStack = nextForwardStack,
            )
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
            currentTab.copy(
                inlineEditState = PaneInlineEditState(
                    mode = PaneInlineEditMode.RENAME,
                    targetEntryId = targetEntry.id,
                    draftName = targetEntry.name,
                ),
            )
        }
    }

    override fun beginCreateFile() {
        val tab = activeTab() ?: return
        if (tab.inlineEditState != null) {
            return
        }
        scope.launch {
            val baseName = org.jetbrains.compose.resources.getString(Res.string.action_new_file)
            val nextName = generateCreateName(tab, baseName)
            updateTab(tab.id) { currentTab ->
                currentTab.copy(
                    inlineEditState = PaneInlineEditState(
                        mode = PaneInlineEditMode.CREATE_FILE,
                        draftName = nextName,
                    ),
                )
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
            val nextName = generateCreateName(tab, baseName)
            updateTab(tab.id) { currentTab ->
                currentTab.copy(
                    inlineEditState = PaneInlineEditState(
                        mode = PaneInlineEditMode.CREATE_DIRECTORY,
                        draftName = nextName,
                    ),
                )
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
        if (tab.inlineEditState == null || draft == tab.inlineEditState.draftName) {
            return
        }
        updateTab(tab.id) { currentTab ->
            currentTab.copy(
                inlineEditState = currentTab.inlineEditState?.copy(draftName = draft),
            )
        }
    }

    override fun confirmInlineEdit() {
        val tab = activeTab() ?: return
        val inlineEdit = tab.inlineEditState ?: return
        if (tab.entriesState is PaneEntriesState.Failure) {
            clearInlineEdit(tab.id)
            return
        }
        val normalizedDraft = inlineEdit.draftName.trim()
        if (normalizedDraft.isBlank()) {
            clearInlineEdit(tab.id)
            return
        }

        when (inlineEdit.mode) {
            PaneInlineEditMode.RENAME -> {
                val targetEntry = currentTabEntries(tab)
                    .firstOrNull { it.id == inlineEdit.targetEntryId }
                    ?: run {
                        clearInlineEdit(tab.id)
                        return
                    }
                if (targetEntry.name == normalizedDraft) {
                    clearInlineEdit(tab.id)
                    return
                }
                scope.launch {
                    fileCommandService.rename(
                        entry = targetEntry,
                        targetName = normalizedDraft,
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

            PaneInlineEditMode.CREATE_FILE -> {
                scope.launch {
                    fileCommandService.createFile(
                        parentLocation = tab.location,
                        name = normalizedDraft,
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

            PaneInlineEditMode.CREATE_DIRECTORY -> {
                scope.launch {
                    fileCommandService.createDirectory(
                        parentLocation = tab.location,
                        name = normalizedDraft,
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
        updateTab(tab.id) { currentTab -> currentTab.copy(viewMode = mode) }
    }

    override fun setFilterQuery(query: String) {
        val tab = activeTab() ?: return
        updateTab(tab.id) { currentTab ->
            currentTab.copy(filterQuery = query.trim())
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
            currentTab.copy(
                detailsSort = nextSort,
            )
        }
    }

    override fun toggleHiddenItems() {
        val tab = activeTab() ?: return
        val showHiddenItems = !tab.showHiddenItems
        updateTab(tab.id) { currentTab ->
            currentTab.copy(
                showHiddenItems = showHiddenItems,
            )
        }
    }

    override fun toggleColumnVisibility(column: DetailsColumn) {
        // NAME 列不可隐藏
        if (column == DetailsColumn.NAME) return
        val tab = activeTab() ?: return
        updateTab(tab.id) { currentTab ->
            val hidden = currentTab.hiddenColumns.toMutableSet()
            if (column in hidden) hidden.remove(column) else hidden.add(column)
            currentTab.copy(hiddenColumns = hidden)
        }
    }

    override fun setGalleryItemSize(sizeDp: Int) {
        val tab = activeTab() ?: return
        val clamped = sizeDp.coerceIn(80, 320)
        updateTab(tab.id) { currentTab ->
            currentTab.copy(galleryItemSizeDp = clamped)
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
            currentTab.copy(
                detailsColumnWeights = currentTab.detailsColumnWeights + mapOf(
                    column to newWidth,
                )
            )
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
        if (entries.none { it.id == entryId }) {
            return
        }

        val currentAnchor = SelectionHelper.validEntryId(tab.selectionAnchorId, entries)
        val currentFocus = SelectionHelper.validEntryId(tab.selectionFocusId, entries)
        val nextSelection = when {
            range -> {
                val anchorId = currentAnchor ?: currentFocus ?: tab.selectedEntryIds.firstOrNull() ?: entryId
                SelectionHelper.buildRangeSelection(
                    entries = entries,
                    anchorId = anchorId,
                    targetId = entryId,
                    additive = additive,
                    existingSelection = tab.selectedEntryIds,
                )
            }

            additive -> {
                if (tab.selectedEntryIds.contains(entryId)) {
                    tab.selectedEntryIds - entryId
                } else {
                    tab.selectedEntryIds + entryId
                }
            }

            else -> setOf(entryId)
        }

        val finalSelection = nextSelection.ifEmpty { setOf(entryId) }
        updateTab(tab.id) { currentTab ->
            currentTab.copy(
                selectedEntryIds = finalSelection,
                selectionAnchorId = if (range) {
                    currentAnchor ?: currentFocus ?: entryId
                } else {
                    entryId
                },
                selectionFocusId = entryId,
            )
        }
    }

    override fun selectEntries(entryIds: Set<String>) {
        val tab = activeTab() ?: return
        updateTab(tab.id) { currentTab ->
            currentTab.copy(selectedEntryIds = entryIds)
        }
    }

    override fun moveSelection(
        offset: Int,
        extendSelection: Boolean,
    ) {
        val tab = activeTab() ?: return
        clearInlineEdit(tab.id)
        val entries = currentVisibleEntries()
        if (entries.isEmpty()) {
            return
        }

        val currentIndex = entries.indexOfFirst { it.id == SelectionHelper.resolveSelectionFocusId(entries, tab.selectionFocusId, tab.selectionAnchorId, tab.selectedEntryIds) }
        val fallbackIndex = if (offset >= 0) 0 else entries.lastIndex
        val baseIndex = if (currentIndex == -1) fallbackIndex else currentIndex
        val nextIndex = (baseIndex + offset).coerceIn(0, entries.lastIndex)
        val nextEntryId = entries[nextIndex].id

        if (extendSelection) {
            val anchorId = SelectionHelper.validEntryId(tab.selectionAnchorId, entries)
                ?: SelectionHelper.resolveSelectionFocusId(entries, tab.selectionFocusId, tab.selectionAnchorId, tab.selectedEntryIds)
                ?: nextEntryId
            updateTab(tab.id) { currentTab ->
                currentTab.copy(
                    selectedEntryIds = SelectionHelper.buildRangeSelection(
                        entries = entries,
                        anchorId = anchorId,
                        targetId = nextEntryId,
                        additive = false,
                        existingSelection = emptySet(),
                    ),
                    selectionAnchorId = anchorId,
                    selectionFocusId = nextEntryId,
                )
            }
        } else {
            updateTab(tab.id) { currentTab ->
                currentTab.copy(
                    selectedEntryIds = setOf(nextEntryId),
                    selectionAnchorId = nextEntryId,
                    selectionFocusId = nextEntryId,
                )
            }
        }
    }

    override fun openSelectedEntry() {
        val selectedEntry =
            currentVisibleEntries().let { vis -> vis.firstOrNull { it.id == SelectionHelper.resolveSelectionFocusId(vis, activeTab()?.selectionFocusId, activeTab()?.selectionAnchorId, activeTab()?.selectedEntryIds.orEmpty()) } } ?: return
        clearInlineEdit(activeTab()?.id ?: return)
        openEntry(selectedEntry)
    }

    override fun selectAll() {
        val tab = activeTab() ?: return
        clearInlineEdit(tab.id)
        val entries = currentVisibleEntries()
        if (entries.isEmpty()) {
            return
        }
        updateTab(tab.id) { currentTab ->
            currentTab.copy(
                selectedEntryIds = entries.mapTo(linkedSetOf()) { it.id },
                selectionAnchorId = entries.first().id,
                selectionFocusId = entries.first().id,
            )
        }
    }

    override fun clearSelection() {
        val tab = activeTab() ?: return
        clearInlineEdit(tab.id)
        updateTab(tab.id) { currentTab ->
            currentTab.copy(
                selectedEntryIds = emptySet(),
                selectionAnchorId = null,
                selectionFocusId = null,
            )
        }
    }

    override fun createTab(location: String) {
        val tab = createTabState(pathService.normalizeLocation(location))
        val nextTabs = mutableState.value.tabs + tab
        mutableState.value = tab.toPaneState(
            paneId = paneId,
            activeTabId = tab.id,
            tabs = nextTabs,
        )
        loadTab(tabId = tab.id, location = tab.location)
    }

    override fun selectTab(tabId: String) {
        val tab = mutableState.value.tabs.firstOrNull { it.id == tabId } ?: return
        mutableState.value = tab.toPaneState(
            paneId = paneId,
            activeTabId = tab.id,
            tabs = mutableState.value.tabs,
        )
        if (tab.entriesState == PaneEntriesState.Idle) {
            loadTab(tabId = tab.id, location = tab.location)
        }
    }

    override fun closeTab(tabId: String) {
        val state = mutableState.value
        if (state.tabs.size <= 1) {
            return
        }

        val tabIndex = state.tabs.indexOfFirst { it.id == tabId }
        if (tabIndex == -1) {
            return
        }

        val nextTabs = state.tabs.filterNot { it.id == tabId }
        val nextActiveTab = if (state.activeTabId == tabId) {
            nextTabs.getOrNull(tabIndex.coerceAtMost(nextTabs.lastIndex)) ?: nextTabs.first()
        } else {
            nextTabs.first { it.id == state.activeTabId }
        }
        mutableState.value = nextActiveTab.toPaneState(
            paneId = paneId,
            activeTabId = nextActiveTab.id,
            tabs = nextTabs,
        )
        if (nextActiveTab.entriesState == PaneEntriesState.Idle) {
            loadTab(tabId = nextActiveTab.id, location = nextActiveTab.location)
        }
    }

    override fun moveTab(
        tabId: String,
        targetIndex: Int,
    ) {
        val state = mutableState.value
        val currentIndex = state.tabs.indexOfFirst { it.id == tabId }
        if (currentIndex == -1) {
            return
        }

        val tab = state.tabs[currentIndex]
        val withoutTab = state.tabs.filterNot { it.id == tabId }
        val adjustedTargetIndex = if (currentIndex < targetIndex) targetIndex - 1 else targetIndex
        val insertIndex = adjustedTargetIndex.coerceIn(0, withoutTab.size)
        val nextTabs = withoutTab.toMutableList().apply { add(insertIndex, tab) }
        val activeTab = nextTabs.first { it.id == state.activeTabId }
        mutableState.value = activeTab.toPaneState(
            paneId = paneId,
            activeTabId = state.activeTabId,
            tabs = nextTabs,
        )
    }

    override fun detachTab(tabId: String): PaneTabState? {
        val state = mutableState.value
        val tabIndex = state.tabs.indexOfFirst { it.id == tabId }
        if (tabIndex == -1) {
            return null
        }

        val tab = state.tabs[tabIndex]
        if (state.tabs.size == 1) {
            val replacement = createTabState(fileRepository.defaultLocation())
            mutableState.value = replacement.toPaneState(
                paneId = paneId,
                activeTabId = replacement.id,
                tabs = listOf(replacement),
            )
            loadTab(tabId = replacement.id, location = replacement.location)
            return tab
        }

        val nextTabs = state.tabs.filterNot { it.id == tabId }
        val nextActiveTab = if (state.activeTabId == tabId) {
            nextTabs.getOrNull(tabIndex.coerceAtMost(nextTabs.lastIndex)) ?: nextTabs.first()
        } else {
            nextTabs.first { it.id == state.activeTabId }
        }
        mutableState.value = nextActiveTab.toPaneState(
            paneId = paneId,
            activeTabId = nextActiveTab.id,
            tabs = nextTabs,
        )
        if (nextActiveTab.entriesState == PaneEntriesState.Idle) {
            loadTab(tabId = nextActiveTab.id, location = nextActiveTab.location)
        }
        return tab
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
        val insertIndex = targetIndex.coerceIn(0, mutableState.value.tabs.size)
        val nextTabs = mutableState.value.tabs.toMutableList().apply {
            add(insertIndex, uniqueTab)
        }
        mutableState.value = uniqueTab.toPaneState(
            paneId = paneId,
            activeTabId = uniqueTab.id,
            tabs = nextTabs,
        )
        if (uniqueTab.entriesState == PaneEntriesState.Idle) {
            loadTab(tabId = uniqueTab.id, location = uniqueTab.location)
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
                currentTab.copy(pendingScrollToEntryId = null)
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

        val nextBackStack = if (recordHistory) tab.backStack + tab.location else tab.backStack
        val nextForwardStack = if (recordHistory) emptyList() else tab.forwardStack
        updateTab(tab.id) { currentTab ->
            currentTab.copy(
                location = normalizedLocation,
                title = pathService.title(normalizedLocation),
                canGoBack = nextBackStack.isNotEmpty(),
                canGoForward = nextForwardStack.isNotEmpty(),
                selectedEntryIds = emptySet(),
                selectionAnchorId = null,
                selectionFocusId = null,
                entriesState = PaneEntriesState.Loading,
                allEntries = emptyList(),
                inlineEditState = null,
                backStack = nextBackStack,
                forwardStack = nextForwardStack,
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
                            if (focusEntry != null) {
                                tab.copy(
                                    allEntries = entries,
                                    entriesState = PaneEntriesState.Ready(entries),
                                    selectedEntryIds = setOf(focusEntry.id),
                                    selectionAnchorId = focusEntry.id,
                                    selectionFocusId = focusEntry.id,
                                    pendingScrollToEntryId = focusEntry.id,
                                )
                            } else {
                                tab.copy(
                                    allEntries = entries,
                                    entriesState = PaneEntriesState.Ready(entries),
                                )
                            }
                        }
                    },
                    onFailure = { failure ->
                        OnyxLogger.error("PaneComponent", "目录加载失败: $location", failure)
                        pendingFocusEntryName.remove(tabId)
                        updateTab(tabId) { tab ->
                            tab.copy(entriesState = PaneEntriesState.Failure(failure.message))
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
                    tab.copy(entriesState = PaneEntriesState.Failure(e.message))
                }
            } finally {
                tabLoadJobs.remove(tabId)
            }
        }
    }

    private fun refreshActiveTab(tabId: String) {
        updateTab(tabId) { currentTab ->
            currentTab.copy(
                entriesState = PaneEntriesState.Loading,
                allEntries = emptyList(),
                selectedEntryIds = emptySet(),
                selectionAnchorId = null,
                selectionFocusId = null,
                inlineEditState = null,
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
            currentTab.copy(inlineEditState = null)
        }
    }

    private fun clearOperationFeedback(tabId: String) {
        updateTab(tabId) { currentTab ->
            currentTab.copy(operationFeedback = null)
        }
    }

    private fun updateFailure(
        tabId: String,
        kind: PaneOperationFeedbackKind,
        detail: I18nMessage?,
    ) {
        updateTab(tabId) { currentTab ->
            currentTab.copy(
                operationFeedback = PaneOperationFeedback(
                    kind = kind,
                    detail = detail,
                )
            )
        }
    }

    private fun currentTabEntries(tab: PaneTabState): List<VFile> {
        return when (val entriesState = tab.entriesState) {
            is PaneEntriesState.Ready -> entriesState.entries
            else -> emptyList()
        }
    }

    private fun generateCreateName(tab: PaneTabState, baseName: String): String {
        val existingNames = tab.allEntries.mapTo(mutableSetOf()) { it.name }
        var candidate = baseName
        var suffixIndex = 1
        while (candidate in existingNames) {
            candidate = "$baseName ($suffixIndex)"
            suffixIndex += 1
        }
        return candidate
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
