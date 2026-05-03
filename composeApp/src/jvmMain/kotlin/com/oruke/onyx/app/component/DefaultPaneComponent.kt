package com.oruke.onyx.app.component

import com.oruke.onyx.app.filesystem.ArchiveService
import com.oruke.onyx.app.filesystem.ExternalOpenService
import com.oruke.onyx.app.filesystem.FileCommandService
import com.oruke.onyx.app.filesystem.FileRepository
import com.oruke.onyx.app.filesystem.FileWatcher
import com.oruke.onyx.app.filesystem.TextClipboardService
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneInlineEditMode
import com.oruke.onyx.core.model.PaneInlineEditState
import com.oruke.onyx.core.model.PaneInspectorState
import com.oruke.onyx.core.model.PaneOperationFeedback
import com.oruke.onyx.core.model.PaneOperationFeedbackKind
import com.oruke.onyx.core.model.PaneSessionSnapshot
import com.oruke.onyx.core.model.PaneStatusInfo
import com.oruke.onyx.core.model.SortDirection
import com.oruke.onyx.core.model.TabSessionSnapshot
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.core.model.ViewMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.*
import kotlin.io.path.pathString

class DefaultPaneComponent(
    private val paneId: PaneId,
    initialLocation: String,
    private val fileRepository: FileRepository,
    private val fileCommandService: FileCommandService,
    private val textClipboardService: TextClipboardService,
    private val externalOpenService: ExternalOpenService,
    private val scope: CoroutineScope,
    private val initialViewMode: ViewMode = ViewMode.DETAILS,
    private val onOpenImageViewer: ((file: VFile, allImages: List<VFile>) -> Unit)? = null,
) : PaneComponent {
    private val mutableState = MutableStateFlow(
        createInitialState(
            initialLocation = initialLocation,
            defaultViewMode = initialViewMode,
        )
    )

    override val state: StateFlow<PaneState> = mutableState.asStateFlow()

    private val fileWatcher = FileWatcher()
    private var fileWatcherJob: Job? = null

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
        val nextBackStack = tab.backStack.dropLast(1)
        val nextForwardStack = tab.forwardStack + tab.location
        updateTab(tab.id) { currentTab ->
            currentTab.copy(
                location = previousLocation,
                title = locationTitle(previousLocation),
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
                title = locationTitle(nextLocation),
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
        val parentLocation = Path.of(mutableState.value.location).parent?.pathString ?: return
        openDirectory(parentLocation)
    }

    override fun openDirectory(location: String) {
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
                // 压缩包 → 以文件夹方式浏览
                if (ArchiveService.isArchive(entry.name)) {
                    openDirectory(ArchiveService.archiveLocation(entry.location))
                } else if (onOpenImageViewer != null && isImageFileName(entry.name)) {
                    val allImages = currentVisibleEntries()
                        .filter { it.kind == VFileKind.FILE && isImageFileName(it.name) }
                    onOpenImageViewer.invoke(entry, allImages)
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
        val targetEntryId = currentSelectionFocusId(entries) ?: return
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
        val targetEntryId = currentSelectionFocusId(entries) ?: return
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

        val currentAnchor = validEntryId(tab.selectionAnchorId, entries)
        val currentFocus = validEntryId(tab.selectionFocusId, entries)
        val nextSelection = when {
            range -> {
                val anchorId = currentAnchor ?: currentFocus ?: tab.selectedEntryIds.firstOrNull() ?: entryId
                buildRangeSelection(
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

        val currentIndex = entries.indexOfFirst { it.id == currentSelectionFocusId(entries) }
        val fallbackIndex = if (offset >= 0) 0 else entries.lastIndex
        val baseIndex = if (currentIndex == -1) fallbackIndex else currentIndex
        val nextIndex = (baseIndex + offset).coerceIn(0, entries.lastIndex)
        val nextEntryId = entries[nextIndex].id

        if (extendSelection) {
            val anchorId = validEntryId(tab.selectionAnchorId, entries)
                ?: currentSelectionFocusId(entries)
                ?: nextEntryId
            updateTab(tab.id) { currentTab ->
                currentTab.copy(
                    selectedEntryIds = buildRangeSelection(
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
            currentVisibleEntries().firstOrNull { it.id == currentSelectionFocusId(currentVisibleEntries()) } ?: return
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
        val tab = createTabState(normalizeLocation(location))
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
            .map { tabSnapshot -> tabSnapshot.toPaneTabState() }

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
        val state = mutableState.value
        if (directoryLocation in state.inlineExpandedLocations) {
            // 折叠：移除该 location 及其所有后代展开
            val toRemove = state.inlineExpandedLocations.filter { loc ->
                loc == directoryLocation || loc.startsWith("$directoryLocation/")
            }.toSet()
            mutableState.value = state.copy(
                inlineExpandedLocations = state.inlineExpandedLocations - toRemove,
                inlineExpandedEntries = state.inlineExpandedEntries - toRemove,
            )
        } else {
            // 展开：计算深度
            val depth = state.inlineExpandedEntries.values
                .firstOrNull { expanded ->
                    expanded.entries?.any { it.location == directoryLocation } == true
                }?.depth?.plus(1) ?: 1

            val loading = InlineExpandedEntry(
                parentLocation = directoryLocation,
                depth = depth,
                entries = null,
            )
            mutableState.value = state.copy(
                inlineExpandedLocations = state.inlineExpandedLocations + directoryLocation,
                inlineExpandedEntries = state.inlineExpandedEntries + (directoryLocation to loading),
            )
            loadInlineExpandChildren(directoryLocation, depth)
        }
    }

    private fun loadInlineExpandChildren(location: String, depth: Int) {
        scope.launch {
            fileRepository.list(location).fold(
                onSuccess = { entries ->
                    val state = mutableState.value
                    if (location !in state.inlineExpandedLocations) return@launch
                    val sorted = entries.sortedWith(
                        compareBy<VFile> { it.kind != VFileKind.DIRECTORY }
                            .thenBy { it.name.lowercase() }
                    )
                    mutableState.value = state.copy(
                        inlineExpandedEntries = state.inlineExpandedEntries + (
                            location to InlineExpandedEntry(
                                parentLocation = location,
                                depth = depth,
                                entries = sorted,
                            )
                        ),
                    )
                },
                onFailure = {
                    val state = mutableState.value
                    if (location !in state.inlineExpandedLocations) return@launch
                    mutableState.value = state.copy(
                        inlineExpandedEntries = state.inlineExpandedEntries + (
                            location to InlineExpandedEntry(
                                parentLocation = location,
                                depth = depth,
                                entries = emptyList(),
                                error = true,
                            )
                        ),
                    )
                },
            )
        }
    }

    // ── 面板目录树 ────────────────────────────────────────────────────────

    override fun toggleFolderTree() {
        val state = mutableState.value
        val nextVisible = !state.folderTreeVisible
        if (nextVisible && state.folderTreeState.roots.isEmpty()) {
            // 首次展开：初始化根节点
            initFolderTreeRoots()
        }
        mutableState.value = state.copy(folderTreeVisible = nextVisible)
    }

    override fun toggleFolderTreeNode(location: String) {
        val state = mutableState.value
        val node = findFolderTreeNode(state.folderTreeState.roots, location)
        if (node == null) return
        if (node.expanded) {
            // 折叠
            val updated = state.folderTreeState.updateNode(location) { it.copy(expanded = false) }
            mutableState.value = state.copy(folderTreeState = updated)
        } else {
            // 展开 + 加载
            val updated = state.folderTreeState.updateNode(location) {
                it.copy(expanded = true, loadState = PaneFolderTreeNodeLoadState.LOADING)
            }
            mutableState.value = state.copy(folderTreeState = updated)
            loadFolderTreeChildren(location)
        }
    }

    override fun retryFolderTreeNode(location: String) {
        val state = mutableState.value
        val updated = state.folderTreeState.updateNode(location) {
            it.copy(loadState = PaneFolderTreeNodeLoadState.LOADING)
        }
        mutableState.value = state.copy(folderTreeState = updated)
        loadFolderTreeChildren(location)
    }

    private fun initFolderTreeRoots() {
        val roots = FileSystems.getDefault().rootDirectories.map { root ->
            PaneFolderTreeNode(
                location = root.pathString,
                label = root.pathString,
                expanded = false,
                loadState = PaneFolderTreeNodeLoadState.IDLE,
            )
        }

        // 将面板当前 location 的祖先链自动展开
        val currentPath = Path.of(mutableState.value.location)
        val ancestorLocations = mutableListOf<String>()
        var p: Path? = currentPath
        while (p != null) {
            ancestorLocations.add(0, p.pathString)
            p = p.parent
        }

        var treeState = PaneFolderTreeState(roots = roots)

        // 自动展开到当前目录
        for (loc in ancestorLocations) {
            treeState = treeState.updateNode(loc) {
                it.copy(expanded = true, loadState = PaneFolderTreeNodeLoadState.LOADING)
            }
            loadFolderTreeChildren(loc)
        }

        mutableState.value = mutableState.value.copy(folderTreeState = treeState)
    }

    private fun loadFolderTreeChildren(location: String) {
        scope.launch {
            fileRepository.list(location).fold(
                onSuccess = { entries ->
                    val dirs = entries
                        .filter { it.kind == VFileKind.DIRECTORY }
                        .sortedBy { it.name.lowercase() }
                        .map { dir ->
                            PaneFolderTreeNode(
                                location = dir.location,
                                label = dir.name,
                                expanded = false,
                                loadState = PaneFolderTreeNodeLoadState.IDLE,
                            )
                        }
                    val state = mutableState.value
                    val updated = state.folderTreeState.updateNode(location) {
                        it.copy(
                            children = dirs,
                            loadState = PaneFolderTreeNodeLoadState.READY,
                        )
                    }
                    mutableState.value = state.copy(folderTreeState = updated)
                },
                onFailure = {
                    val state = mutableState.value
                    val updated = state.folderTreeState.updateNode(location) {
                        it.copy(loadState = PaneFolderTreeNodeLoadState.FAILURE)
                    }
                    mutableState.value = state.copy(folderTreeState = updated)
                },
            )
        }
    }

    private fun navigateActiveTab(
        location: String,
        recordHistory: Boolean,
    ) {
        val tab = activeTab() ?: return
        val normalizedLocation = normalizeLocation(location)
        if (normalizedLocation == tab.location) {
            refresh()
            return
        }

        val nextBackStack = if (recordHistory) tab.backStack + tab.location else tab.backStack
        val nextForwardStack = if (recordHistory) emptyList() else tab.forwardStack
        updateTab(tab.id) { currentTab ->
            currentTab.copy(
                location = normalizedLocation,
                title = locationTitle(normalizedLocation),
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
        mutableState.value = mutableState.value.copy(
            inlineExpandedLocations = emptySet(),
            inlineExpandedEntries = emptyMap(),
        )
        loadTab(tabId = tab.id, location = normalizedLocation)
        startWatching(normalizedLocation)
    }

    private fun loadTab(
        tabId: String,
        location: String,
    ) {
        scope.launch {
            val result = fileRepository.list(location)
            result.fold(
                onSuccess = { entries ->
                    updateTab(tabId) { currentTab ->
                        currentTab.copy(
                            allEntries = entries,
                            entriesState = PaneEntriesState.Ready(entries),
                        )
                    }
                },
                onFailure = { failure ->
                    updateTab(tabId) { currentTab ->
                        currentTab.copy(entriesState = PaneEntriesState.Failure(failure.message))
                    }
                },
            )
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
            location = normalizeLocation(initialLocation),
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
        return PaneTabState(
            id = UUID.randomUUID().toString(),
            title = locationTitle(location),
            location = location,
            canGoBack = false,
            canGoForward = false,
            detailsColumns = defaultDetailsColumns(),
            detailsColumnWeights = defaultDetailsColumnWeights(),
            detailsSort = DetailsSort(
                column = DetailsColumn.NAME,
                direction = SortDirection.ASCENDING,
            ),
            viewMode = defaultViewMode,
            filterQuery = "",
            selectedEntryIds = emptySet(),
            selectionAnchorId = null,
            selectionFocusId = null,
            statusInfo = PaneStatusInfo(),
            inlineEditState = null,
            inspectorState = PaneInspectorState(),
            operationFeedback = null,
            showHiddenItems = false,
            hiddenColumns = emptySet(),
            galleryItemSizeDp = 160,
            entriesState = PaneEntriesState.Idle,
            allEntries = emptyList(),
            backStack = emptyList(),
            forwardStack = emptyList(),
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
                transform(tab).withDerivedState().also { updated ->
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
            folderTreeVisible = state.folderTreeVisible,
            folderTreeState = state.folderTreeState,
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
                    collectVisibleEntries(
                        entries = entriesState.entries,
                        expandedLocations = state.inlineExpandedLocations,
                        expandedEntries = state.inlineExpandedEntries,
                    )
                }
            }
            else -> emptyList()
        }
    }

    /**
     * 递归收集所有可见条目（包括内联展开的子项）。
     */
    private fun collectVisibleEntries(
        entries: List<VFile>,
        expandedLocations: Set<String>,
        expandedEntries: Map<String, InlineExpandedEntry>,
    ): List<VFile> {
        return buildList {
            entries.forEach { entry ->
                add(entry)
                if (entry.location in expandedLocations) {
                    val expanded = expandedEntries[entry.location]
                    if (expanded?.entries != null) {
                        addAll(
                            collectVisibleEntries(
                                entries = expanded.entries,
                                expandedLocations = expandedLocations,
                                expandedEntries = expandedEntries,
                            )
                        )
                    }
                }
            }
        }
    }

    private fun currentSelectionFocusId(entries: List<VFile>): String? {
        val tab = activeTab() ?: return null
        return tab.selectionFocusId
            ?.takeIf { focusId -> entries.any { it.id == focusId } }
            ?: tab.selectionAnchorId
                ?.takeIf { anchorId -> entries.any { it.id == anchorId } }
            ?: tab.selectedEntryIds.firstOrNull()
    }

    private fun visibleSortedEntries(
        allEntries: List<VFile>,
        showHiddenItems: Boolean,
        sort: DetailsSort,
        filterQuery: String,
    ): List<VFile> {
        val visibleEntries = if (showHiddenItems) allEntries else allEntries.filterNot { it.hidden }
        val filteredEntries = if (filterQuery.isBlank()) {
            visibleEntries
        } else {
            val normalizedQuery = filterQuery.lowercase()
            visibleEntries.filter { entry ->
                entry.name.lowercase().contains(normalizedQuery)
            }
        }
        return sortEntries(filteredEntries, sort)
    }

    private fun PaneTabState.withDerivedState(): PaneTabState {
        val visibleEntries = visibleSortedEntries(
            allEntries = allEntries,
            showHiddenItems = showHiddenItems,
            sort = detailsSort,
            filterQuery = filterQuery,
        )
        // reconcileSelection 需要知道所有可见 ID（包括内联展开子项），
        // 否则展开子项的选中状态会被 intersect 过滤掉。
        val state = mutableState.value
        val allVisibleForSelection = if (state.inlineExpandedLocations.isEmpty()) {
            visibleEntries
        } else {
            collectVisibleEntries(
                entries = visibleEntries,
                expandedLocations = state.inlineExpandedLocations,
                expandedEntries = state.inlineExpandedEntries,
            )
        }
        val selection = reconcileSelection(
            entries = allVisibleForSelection,
            selectedEntryIds = selectedEntryIds,
            anchorId = selectionAnchorId,
            focusId = selectionFocusId,
        )
        val nextEntriesState = when (val currentEntriesState = entriesState) {
            is PaneEntriesState.Ready -> PaneEntriesState.Ready(visibleEntries)
            is PaneEntriesState.Failure -> currentEntriesState
            PaneEntriesState.Loading -> PaneEntriesState.Loading
            PaneEntriesState.Idle -> if (allEntries.isNotEmpty()) {
                PaneEntriesState.Ready(visibleEntries)
            } else {
                PaneEntriesState.Idle
            }
        }
        return copy(
            selectedEntryIds = selection.selectedEntryIds,
            selectionAnchorId = selection.anchorId,
            selectionFocusId = selection.focusId,
            statusInfo = buildStatusInfo(
                allEntries = allEntries,
                visibleEntries = visibleEntries,
                selectedEntryIds = selection.selectedEntryIds,
            ),
            entriesState = nextEntriesState,
        )
    }

    private fun buildStatusInfo(
        allEntries: List<VFile>,
        visibleEntries: List<VFile>,
        selectedEntryIds: Set<String>,
    ): PaneStatusInfo {
        val selectedEntries = visibleEntries.filter { entry -> selectedEntryIds.contains(entry.id) }
        return PaneStatusInfo(
            totalItemCount = allEntries.size,
            visibleItemCount = visibleEntries.size,
            directoryCount = visibleEntries.count { entry -> entry.kind == VFileKind.DIRECTORY },
            fileCount = visibleEntries.count { entry -> entry.kind == VFileKind.FILE },
            selectedCount = selectedEntries.size,
            selectedSizeBytes = selectedEntries.sumOf { entry -> entry.sizeBytes ?: 0L },
        )
    }

    private fun reconcileSelection(
        entries: List<VFile>,
        selectedEntryIds: Set<String>,
        anchorId: String?,
        focusId: String?,
    ): SelectionState {
        val visibleIds = entries.mapTo(mutableSetOf()) { it.id }
        // 允许空选择——用户清空选择后不应自动选中首项
        val nextSelectedEntryIds = selectedEntryIds.intersect(visibleIds)
        val nextAnchorId = anchorId?.takeIf { visibleIds.contains(it) }
        val nextFocusId = focusId?.takeIf { visibleIds.contains(it) } ?: nextAnchorId
        return SelectionState(
            selectedEntryIds = nextSelectedEntryIds,
            anchorId = nextAnchorId,
            focusId = nextFocusId,
        )
    }

    private fun validEntryId(
        entryId: String?,
        entries: List<VFile>,
    ): String? {
        return entryId?.takeIf { candidate -> entries.any { it.id == candidate } }
    }

    private fun buildRangeSelection(
        entries: List<VFile>,
        anchorId: String,
        targetId: String,
        additive: Boolean,
        existingSelection: Set<String>,
    ): Set<String> {
        val anchorIndex = entries.indexOfFirst { it.id == anchorId }.takeIf { it >= 0 } ?: return setOf(targetId)
        val targetIndex = entries.indexOfFirst { it.id == targetId }.takeIf { it >= 0 } ?: return setOf(anchorId)
        val range = entries
            .subList(minOf(anchorIndex, targetIndex), maxOf(anchorIndex, targetIndex) + 1)
            .mapTo(linkedSetOf()) { it.id }
        return if (additive) existingSelection + range else range
    }

    private fun sortEntries(
        entries: List<VFile>,
        sort: DetailsSort,
    ): List<VFile> {
        val comparator = when (sort.column) {
            DetailsColumn.NAME -> compareBy<VFile> { it.name.lowercase() }
            DetailsColumn.TYPE -> compareBy<VFile> {
                if (it.kind == VFileKind.DIRECTORY) "" else it.name.substringAfterLast('.', "").lowercase()
            }
            DetailsColumn.SIZE -> compareBy<VFile> { it.sizeBytes ?: -1L }
            DetailsColumn.MODIFIED -> compareBy<VFile> { it.modifiedAtEpochMillis ?: Long.MIN_VALUE }
        }

        val sorted = entries.sortedWith(
            compareByDescending<VFile> { it.kind == VFileKind.DIRECTORY }
                .then(comparator)
        )

        return when (sort.direction) {
            SortDirection.ASCENDING -> sorted
            SortDirection.DESCENDING -> {
                val directories = sorted.filter { it.kind == VFileKind.DIRECTORY }.reversed()
                val files = sorted.filter { it.kind == VFileKind.FILE }.reversed()
                directories + files
            }
        }
    }

    private fun normalizeLocation(location: String): String {
        return Path.of(location).normalize().toAbsolutePath().pathString
    }

    private data class SelectionState(
        val selectedEntryIds: Set<String>,
        val anchorId: String?,
        val focusId: String?,
    )
}

private const val MIN_DETAILS_COLUMN_WIDTH = 40f

private fun PaneTabState.toPaneState(
    paneId: PaneId,
    activeTabId: String,
    tabs: List<PaneTabState>,
    folderTreeVisible: Boolean = false,
    folderTreeState: PaneFolderTreeState = PaneFolderTreeState(),
    inlineExpandedLocations: Set<String> = emptySet(),
    inlineExpandedEntries: Map<String, InlineExpandedEntry> = emptyMap(),
): PaneState {
    return PaneState(
        paneId = paneId,
        activeTabId = activeTabId,
        tabs = tabs,
        location = location,
        canGoBack = canGoBack,
        canGoForward = canGoForward,
        detailsColumns = detailsColumns,
        detailsColumnWeights = detailsColumnWeights,
        detailsSort = detailsSort,
        viewMode = viewMode,
        filterQuery = filterQuery,
        selectedEntryIds = selectedEntryIds,
        selectionAnchorId = selectionAnchorId,
        selectionFocusId = selectionFocusId,
        statusInfo = statusInfo,
        inlineEditState = inlineEditState,
        inspectorState = inspectorState,
        operationFeedback = operationFeedback,
        showHiddenItems = showHiddenItems,
        hiddenColumns = hiddenColumns,
        galleryItemSizeDp = galleryItemSizeDp,
        entriesState = entriesState,
        folderTreeVisible = folderTreeVisible,
        folderTreeState = folderTreeState,
        inlineExpandedLocations = inlineExpandedLocations,
        inlineExpandedEntries = inlineExpandedEntries,
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

private fun TabSessionSnapshot.toPaneTabState(): PaneTabState {
    return PaneTabState(
        id = id,
        title = locationTitle(location),
        location = location,
        canGoBack = backStack.isNotEmpty(),
        canGoForward = forwardStack.isNotEmpty(),
        detailsColumns = detailsColumns,
        // 迁移旧的比例权重（所有值 < 2.0）为新的绝对 dp 宽度
        detailsColumnWeights = if (detailsColumnWeights.values.all { it < 2f }) {
            defaultDetailsColumnWeights()
        } else {
            detailsColumnWeights
        },
        detailsSort = detailsSort,
        viewMode = viewMode,
        filterQuery = filterQuery,
        selectedEntryIds = emptySet(),
        selectionAnchorId = null,
        selectionFocusId = null,
        statusInfo = PaneStatusInfo(),
        inlineEditState = null,
        inspectorState = PaneInspectorState(),
        operationFeedback = null,
        showHiddenItems = showHiddenItems,
        hiddenColumns = emptySet(),
        galleryItemSizeDp = 160,
        entriesState = PaneEntriesState.Idle,
        allEntries = emptyList(),
        backStack = backStack,
        forwardStack = forwardStack,
    )
}

private fun defaultDetailsColumns(): List<DetailsColumn> {
    return listOf(
        DetailsColumn.NAME,
        DetailsColumn.TYPE,
        DetailsColumn.SIZE,
        DetailsColumn.MODIFIED,
    )
}

private fun defaultDetailsColumnWeights(): Map<DetailsColumn, Float> {
    return mapOf(
        DetailsColumn.NAME to 300f,
        DetailsColumn.TYPE to 80f,
        DetailsColumn.SIZE to 100f,
        DetailsColumn.MODIFIED to 180f,
    )
}

private fun defaultDetailsColumnWidth(column: DetailsColumn): Float {
    return defaultDetailsColumnWeights()[column] ?: MIN_DETAILS_COLUMN_WIDTH
}

private fun locationTitle(location: String): String {
    val path = Path.of(location)
    return path.fileName?.pathString?.ifBlank { location } ?: path.pathString
}

private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "svg", "webp", "bmp", "ico", "tiff", "tif")

private fun isImageFileName(fileName: String): Boolean {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext in imageExtensions
}

// ── 面板目录树辅助函数 ──────────────────────────────────────────────────

private fun findFolderTreeNode(
    nodes: List<PaneFolderTreeNode>,
    location: String,
): PaneFolderTreeNode? {
    for (node in nodes) {
        if (node.location == location) return node
        val found = findFolderTreeNode(node.children, location)
        if (found != null) return found
    }
    return null
}

private fun PaneFolderTreeState.updateNode(
    location: String,
    transform: (PaneFolderTreeNode) -> PaneFolderTreeNode,
): PaneFolderTreeState {
    fun List<PaneFolderTreeNode>.update(): List<PaneFolderTreeNode> {
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
