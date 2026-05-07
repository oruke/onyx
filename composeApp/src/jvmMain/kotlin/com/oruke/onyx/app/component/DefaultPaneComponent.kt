package com.oruke.onyx.app.component

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
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
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.*
import kotlin.io.path.pathString
import com.oruke.onyx.app.OnyxLogger

class DefaultPaneComponent(
    componentContext: ComponentContext,
    private val paneId: PaneId,
    initialLocation: String,
    private val fileRepository: FileRepository,
    private val fileCommandService: FileCommandService,
    private val textClipboardService: TextClipboardService,
    private val externalOpenService: ExternalOpenService,
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
        if (currentDirName != null && isParentOf(previousLocation, tab.location)) {
            pendingFocusEntryName[tab.id] = currentDirName
        }
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
        val tab = activeTab() ?: return
        val currentLocation = tab.location
        val parentLocation = if (ArchiveService.isArchiveLocation(currentLocation)) {
            ArchiveService.archiveParentLocation(currentLocation) ?: return
        } else {
            Path.of(currentLocation).parent?.pathString ?: return
        }
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
            val childName = childSegmentOnPath(ancestor = location, descendant = tab.location)
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
                if (ArchiveService.isArchive(entry.name)) {
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
                            val (archivePath, innerPath) = ArchiveService.parseArchiveLocation(entry.location)
                                ?: return@launch
                            val archiveService = ArchiveService()
                            val bytesResult = archiveService.extractToBytes(archivePath, innerPath)
                            val bytes = bytesResult.getOrNull()
                            if (bytes == null || bytes.isEmpty()) {
                                updateFailure(
                                    tabId = tab.id,
                                    kind = PaneOperationFeedbackKind.OPEN_FAILED,
                                    detail = I18nMessage(Res.string.msg_string_literal, "Extract failed"),
                                )
                                return@launch
                            }
                            // 写入临时文件
                            val tempDir = java.nio.file.Files.createTempDirectory("onyx-archive-")
                            val tempFile = tempDir.resolve(entry.name)
                            java.nio.file.Files.write(tempFile, bytes)
                            tempFile.toFile().deleteOnExit()
                            tempDir.toFile().deleteOnExit()

                            val tempVFile = entry.copy(location = tempFile.toString())
                            externalOpenService.open(tempVFile)
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
        if (ArchiveService.isArchiveLocation(location)) return location
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
        inlineExpandedLocations = inlineExpandedLocations,
        inlineExpandedEntries = inlineExpandedEntries,
        pendingScrollToEntryId = pendingScrollToEntryId,
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
    if (ArchiveService.isArchiveLocation(location)) {
        return ArchiveService.archiveLocationTitle(location)
    }
    val path = Path.of(location)
    return path.fileName?.pathString?.ifBlank { location } ?: path.pathString
}

private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "svg", "webp", "bmp", "ico", "tiff", "tif")

private fun isImageFileName(fileName: String): Boolean {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext in imageExtensions
}

/** Extract the last path segment (directory or file name) from a location. */
private fun locationBaseName(location: String): String? {
    if (ArchiveService.isArchiveLocation(location)) {
        return ArchiveService.archiveLocationTitle(location).takeIf { it.isNotBlank() }
    }
    return try {
        Path.of(location).fileName?.pathString?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}

/** Check if [parent] is the direct parent of [child]. */
private fun isParentOf(parent: String, child: String): Boolean {
    return try {
        val parentPath = Path.of(parent).toAbsolutePath().normalize()
        val childPath = Path.of(child).toAbsolutePath().normalize()
        childPath.parent == parentPath
    } catch (_: Exception) {
        false
    }
}

/**
 * If [ancestor] is a proper ancestor of [descendant], return the name of the
 * immediate child segment on the path from [ancestor] to [descendant].
 * e.g. ancestor="/a/b", descendant="/a/b/c/d" → "c"
 * Returns null if [ancestor] is not a proper ancestor of [descendant].
 */
private fun childSegmentOnPath(ancestor: String, descendant: String): String? {
    return try {
        val ancestorPath = Path.of(ancestor).toAbsolutePath().normalize()
        val descendantPath = Path.of(descendant).toAbsolutePath().normalize()
        if (!descendantPath.startsWith(ancestorPath) || descendantPath == ancestorPath) return null
        val relative = ancestorPath.relativize(descendantPath)
        relative.getName(0)?.pathString
    } catch (_: Exception) {
        null
    }
}
