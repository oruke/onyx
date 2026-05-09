package com.oruke.onyx.app.component

import com.oruke.onyx.app.component.delegate.EntrySorter
import com.oruke.onyx.app.component.delegate.SelectionHelper
import com.oruke.onyx.app.filesystem.VfsPathService
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.PaneInspectorState
import com.oruke.onyx.core.model.PaneSessionSnapshot
import com.oruke.onyx.core.model.PaneStatusInfo
import com.oruke.onyx.core.model.SortDirection
import com.oruke.onyx.core.model.TabSessionSnapshot
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.ViewMode

internal const val MIN_DETAILS_COLUMN_WIDTH = 40f

internal fun defaultDetailsColumns(): List<DetailsColumn> {
    return listOf(
        DetailsColumn.NAME,
        DetailsColumn.TYPE,
        DetailsColumn.SIZE,
        DetailsColumn.MODIFIED,
    )
}

internal fun defaultDetailsColumnWeights(): Map<DetailsColumn, Float> {
    return mapOf(
        DetailsColumn.NAME to 300f,
        DetailsColumn.TYPE to 80f,
        DetailsColumn.SIZE to 100f,
        DetailsColumn.MODIFIED to 180f,
    )
}

internal fun defaultDetailsColumnWidth(column: DetailsColumn): Float {
    return defaultDetailsColumnWeights()[column] ?: MIN_DETAILS_COLUMN_WIDTH
}

internal fun PaneTabState.toPaneState(
    paneId: com.oruke.onyx.core.model.PaneId,
    activeTabId: String,
    tabs: List<PaneTabState>,
    inlineExpandedLocations: Set<String> = emptySet(),
    inlineExpandedEntries: Map<String, InlineExpandedEntry> = emptyMap(),
): PaneState {
    return PaneState(
        paneId = paneId,
        chromeState = PaneChromeState(
            activeTabId = activeTabId,
            tabs = tabs,
            inlineExpandedLocations = inlineExpandedLocations,
            inlineExpandedEntries = inlineExpandedEntries,
        ),
        activeTabState = tabState,
    )
}

internal fun PaneTabState.toSessionSnapshot(): TabSessionSnapshot {
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

internal fun TabSessionSnapshot.toPaneTabState(pathService: VfsPathService): PaneTabState {
    return PaneTabState(
        id = id,
        title = pathService.title(location),
        tabState = TabState(
            location = location,
            canGoBack = backStack.isNotEmpty(),
            canGoForward = forwardStack.isNotEmpty(),
            detailsColumns = detailsColumns,
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
        ),
        allEntries = emptyList(),
        backStack = backStack,
        forwardStack = forwardStack,
    )
}

internal fun PaneTabState.withDerivedState(
    inlineExpandedLocations: Set<String>,
    inlineExpandedEntries: Map<String, InlineExpandedEntry>,
): PaneTabState {
    val visibleEntries = EntrySorter.visibleSortedEntries(
        allEntries = allEntries,
        showHiddenItems = showHiddenItems,
        sort = detailsSort,
        filterQuery = filterQuery,
    )
    val allVisibleForSelection = if (inlineExpandedLocations.isEmpty()) {
        visibleEntries
    } else {
        SelectionHelper.collectVisibleEntries(
            entries = visibleEntries,
            expandedLocations = inlineExpandedLocations,
            expandedEntries = inlineExpandedEntries,
        )
    }
    val selection = SelectionHelper.reconcileSelection(
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
    return withTabState { current ->
        current.copy(
            selectedEntryIds = selection.selectedEntryIds,
            selectionAnchorId = selection.anchorId,
            selectionFocusId = selection.focusId,
            statusInfo = SelectionHelper.buildStatusInfo(
                allEntries = allEntries,
                visibleEntries = visibleEntries,
                selectedEntryIds = selection.selectedEntryIds,
            ),
            entriesState = nextEntriesState,
        )
    }
}

internal fun PaneTabState.prepareForRefresh(): PaneTabState {
    return withTabState { current ->
        current.copy(
            inlineEditState = null,
            entriesState = PaneEntriesState.Loading,
        )
    }
}

internal fun PaneTabState.navigateBackState(previousTitle: String): PaneTabState {
    val previousLocation = backStack.last()
    val nextBackStack = backStack.dropLast(1)
    val nextForwardStack = forwardStack + location
    return navigateToLoadingLocation(
        location = previousLocation,
        title = previousTitle,
        backStack = nextBackStack,
        forwardStack = nextForwardStack,
    )
}

internal fun PaneTabState.navigateForwardState(nextTitle: String): PaneTabState {
    val nextLocation = forwardStack.last()
    val nextBackStack = backStack + location
    val nextForwardStack = forwardStack.dropLast(1)
    return navigateToLoadingLocation(
        location = nextLocation,
        title = nextTitle,
        backStack = nextBackStack,
        forwardStack = nextForwardStack,
    )
}

internal fun PaneTabState.navigateToState(
    location: String,
    title: String,
    recordHistory: Boolean,
): PaneTabState {
    val nextBackStack = if (recordHistory) backStack + this.location else backStack
    val nextForwardStack = if (recordHistory) emptyList() else forwardStack
    return navigateToLoadingLocation(
        location = location,
        title = title,
        backStack = nextBackStack,
        forwardStack = nextForwardStack,
    )
}

internal fun PaneTabState.withLoadedEntries(
    entries: List<VFile>,
    focusEntry: VFile?,
): PaneTabState {
    return if (focusEntry != null) {
        copy(
            allEntries = entries,
        ).withTabState { current ->
            current.copy(
                entriesState = PaneEntriesState.Ready(entries),
                selectedEntryIds = setOf(focusEntry.id),
                selectionAnchorId = focusEntry.id,
                selectionFocusId = focusEntry.id,
                pendingScrollToEntryId = focusEntry.id,
            )
        }
    } else {
        copy(
            allEntries = entries,
        ).withTabState { current ->
            current.copy(entriesState = PaneEntriesState.Ready(entries))
        }
    }
}

internal fun PaneTabState.withLoadFailure(reason: String?): PaneTabState {
    return withTabState { current ->
        current.copy(entriesState = PaneEntriesState.Failure(reason))
    }
}

private fun PaneTabState.navigateToLoadingLocation(
    location: String,
    title: String,
    backStack: List<String>,
    forwardStack: List<String>,
): PaneTabState {
    return copy(
        title = title,
        allEntries = emptyList(),
        backStack = backStack,
        forwardStack = forwardStack,
    ).withTabState { current ->
        current.copy(
            location = location,
            canGoBack = backStack.isNotEmpty(),
            canGoForward = forwardStack.isNotEmpty(),
            selectedEntryIds = emptySet(),
            selectionAnchorId = null,
            selectionFocusId = null,
            entriesState = PaneEntriesState.Loading,
            inlineEditState = null,
        )
    }
}

internal fun createDefaultPaneTabState(
    id: String,
    title: String,
    location: String,
    defaultViewMode: ViewMode,
): PaneTabState {
    return PaneTabState(
        id = id,
        title = title,
        tabState = TabState(
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
        ),
        allEntries = emptyList(),
        backStack = emptyList(),
        forwardStack = emptyList(),
    )
}
