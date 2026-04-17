package com.oruke.onyx.app.component

import com.oruke.onyx.app.filesystem.JvmLocalFileProvider
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.SortDirection
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.*
import kotlin.io.path.pathString

class DefaultPaneComponent(
    private val paneId: PaneId,
    initialLocation: String,
    private val localFileProvider: JvmLocalFileProvider,
    private val scope: CoroutineScope,
) : PaneComponent {
    private val mutableState = MutableStateFlow(createInitialState(initialLocation))

    override val state: StateFlow<PaneState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    override fun refresh() {
        val tab = activeTab() ?: return
        updateTab(tab.id) { currentTab -> currentTab.copy(entriesState = PaneEntriesState.Loading) }
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
        if (entry.kind == VFileKind.DIRECTORY) {
            openDirectory(entry.location)
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

        val sortedEntries = visibleSortedEntries(
            allEntries = tab.allEntries,
            showHiddenItems = tab.showHiddenItems,
            sort = nextSort,
        )
        val selection = reconcileSelection(
            entries = sortedEntries,
            selectedEntryIds = tab.selectedEntryIds,
            anchorId = tab.selectionAnchorId,
            focusId = tab.selectionFocusId,
        )
        updateTab(tab.id) { currentTab ->
            currentTab.copy(
                detailsSort = nextSort,
                selectedEntryIds = selection.selectedEntryIds,
                selectionAnchorId = selection.anchorId,
                selectionFocusId = selection.focusId,
                entriesState = PaneEntriesState.Ready(sortedEntries),
            )
        }
    }

    override fun toggleHiddenItems() {
        val tab = activeTab() ?: return
        val showHiddenItems = !tab.showHiddenItems
        val sortedEntries = visibleSortedEntries(
            allEntries = tab.allEntries,
            showHiddenItems = showHiddenItems,
            sort = tab.detailsSort,
        )
        val selection = reconcileSelection(
            entries = sortedEntries,
            selectedEntryIds = tab.selectedEntryIds,
            anchorId = tab.selectionAnchorId,
            focusId = tab.selectionFocusId,
        )
        updateTab(tab.id) { currentTab ->
            currentTab.copy(
                showHiddenItems = showHiddenItems,
                selectedEntryIds = selection.selectedEntryIds,
                selectionAnchorId = selection.anchorId,
                selectionFocusId = selection.focusId,
                entriesState = PaneEntriesState.Ready(sortedEntries),
            )
        }
    }

    override fun resizeDetailsColumn(
        column: DetailsColumn,
        nextColumn: DetailsColumn,
        deltaWeight: Float,
    ) {
        val tab = activeTab() ?: return
        val columnWeight = tab.detailsColumnWeights[column] ?: defaultDetailsColumnWeight(column)
        val nextColumnWeight = tab.detailsColumnWeights[nextColumn] ?: defaultDetailsColumnWeight(nextColumn)
        val combinedWeight = columnWeight + nextColumnWeight
        if (combinedWeight <= MIN_DETAILS_COLUMN_WEIGHT * 2) {
            return
        }

        val nextColumnWeightValue = (columnWeight + deltaWeight)
            .coerceIn(MIN_DETAILS_COLUMN_WEIGHT, combinedWeight - MIN_DETAILS_COLUMN_WEIGHT)
        val nextAdjacentWeightValue = combinedWeight - nextColumnWeightValue
        updateTab(tab.id) { currentTab ->
            currentTab.copy(
                detailsColumnWeights = currentTab.detailsColumnWeights + mapOf(
                    column to nextColumnWeightValue,
                    nextColumn to nextAdjacentWeightValue,
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

    override fun moveSelection(
        offset: Int,
        extendSelection: Boolean,
    ) {
        val tab = activeTab() ?: return
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
        openEntry(selectedEntry)
    }

    override fun selectAll() {
        val tab = activeTab() ?: return
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
            val replacement = createTabState(localFileProvider.defaultLocation())
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
                backStack = nextBackStack,
                forwardStack = nextForwardStack,
            )
        }
        loadTab(tabId = tab.id, location = normalizedLocation)
    }

    private fun loadTab(
        tabId: String,
        location: String,
    ) {
        scope.launch {
            val result = localFileProvider.list(location)
            result.fold(
                onSuccess = { entries ->
                    val tab = mutableState.value.tabs.firstOrNull { it.id == tabId }
                    if (tab != null) {
                        val sortedEntries = visibleSortedEntries(
                            allEntries = entries,
                            showHiddenItems = tab.showHiddenItems,
                            sort = tab.detailsSort,
                        )
                        val selection = reconcileSelection(
                            entries = sortedEntries,
                            selectedEntryIds = tab.selectedEntryIds,
                            anchorId = tab.selectionAnchorId,
                            focusId = tab.selectionFocusId,
                        )
                        updateTab(tabId) { currentTab ->
                            currentTab.copy(
                                allEntries = entries,
                                selectedEntryIds = selection.selectedEntryIds,
                                selectionAnchorId = selection.anchorId,
                                selectionFocusId = selection.focusId,
                                entriesState = PaneEntriesState.Ready(sortedEntries),
                            )
                        }
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

    private fun createInitialState(initialLocation: String): PaneState {
        val tab = createTabState(normalizeLocation(initialLocation))
        return tab.toPaneState(
            paneId = paneId,
            activeTabId = tab.id,
            tabs = listOf(tab),
        )
    }

    private fun createTabState(location: String): PaneTabState {
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
            selectedEntryIds = emptySet(),
            selectionAnchorId = null,
            selectionFocusId = null,
            showHiddenItems = false,
            entriesState = PaneEntriesState.Idle,
            allEntries = emptyList(),
            backStack = emptyList(),
            forwardStack = emptyList(),
        )
    }

    private fun updateTab(
        tabId: String,
        transform: (PaneTabState) -> PaneTabState,
    ) {
        val state = mutableState.value
        var updatedActiveTab: PaneTabState? = null
        val nextTabs = state.tabs.map { tab ->
            if (tab.id == tabId) {
                transform(tab).also { updated ->
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
        )
    }

    private fun activeTab(): PaneTabState? {
        return mutableState.value.tabs.firstOrNull { it.id == mutableState.value.activeTabId }
    }

    private fun currentVisibleEntries(): List<VFile> {
        return when (val entriesState = activeTab()?.entriesState) {
            is PaneEntriesState.Ready -> entriesState.entries
            else -> emptyList()
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
    ): List<VFile> {
        val visibleEntries = if (showHiddenItems) allEntries else allEntries.filterNot { it.hidden }
        return sortEntries(visibleEntries, sort)
    }

    private fun reconcileSelection(
        entries: List<VFile>,
        selectedEntryIds: Set<String>,
        anchorId: String?,
        focusId: String?,
    ): SelectionState {
        val visibleIds = entries.mapTo(mutableSetOf()) { it.id }
        val nextSelectedEntryIds = selectedEntryIds
            .intersect(visibleIds)
            .ifEmpty {
                entries.firstOrNull()?.let { setOf(it.id) } ?: emptySet()
            }
        val nextAnchorId = anchorId
            ?.takeIf { visibleIds.contains(it) }
            ?: nextSelectedEntryIds.firstOrNull()
        val nextFocusId = focusId
            ?.takeIf { visibleIds.contains(it) }
            ?: nextAnchorId
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
            DetailsColumn.TYPE -> compareBy<VFile> { it.kind.name }
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

private const val MIN_DETAILS_COLUMN_WEIGHT = 0.08f

private fun PaneTabState.toPaneState(
    paneId: PaneId,
    activeTabId: String,
    tabs: List<PaneTabState>,
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
        selectedEntryIds = selectedEntryIds,
        selectionAnchorId = selectionAnchorId,
        selectionFocusId = selectionFocusId,
        showHiddenItems = showHiddenItems,
        entriesState = entriesState,
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
        DetailsColumn.NAME to 0.58f,
        DetailsColumn.SIZE to 0.16f,
        DetailsColumn.MODIFIED to 0.26f,
    )
}

private fun defaultDetailsColumnWeight(column: DetailsColumn): Float {
    return defaultDetailsColumnWeights()[column] ?: MIN_DETAILS_COLUMN_WEIGHT
}

private fun locationTitle(location: String): String {
    val path = Path.of(location)
    return path.fileName?.pathString?.ifBlank { location } ?: path.pathString
}
