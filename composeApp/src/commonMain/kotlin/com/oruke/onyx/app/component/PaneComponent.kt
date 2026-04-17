package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.flow.StateFlow

data class PaneState(
    val paneId: PaneId,
    val activeTabId: String,
    val tabs: List<PaneTabState>,
    val location: String,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val detailsColumns: List<DetailsColumn>,
    val detailsColumnWeights: Map<DetailsColumn, Float>,
    val detailsSort: DetailsSort,
    val selectedEntryIds: Set<String>,
    val selectionAnchorId: String?,
    val selectionFocusId: String?,
    val showHiddenItems: Boolean,
    val entriesState: PaneEntriesState,
)

data class PaneTabState(
    val id: String,
    val title: String,
    val location: String,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val detailsColumns: List<DetailsColumn>,
    val detailsColumnWeights: Map<DetailsColumn, Float>,
    val detailsSort: DetailsSort,
    val selectedEntryIds: Set<String>,
    val selectionAnchorId: String?,
    val selectionFocusId: String?,
    val showHiddenItems: Boolean,
    val entriesState: PaneEntriesState,
    val allEntries: List<VFile>,
    val backStack: List<String>,
    val forwardStack: List<String>,
)

sealed interface PaneEntriesState {
    data object Idle : PaneEntriesState

    data object Loading : PaneEntriesState

    data class Ready(
        val entries: List<VFile>,
    ) : PaneEntriesState

    data class Failure(
        val reason: String?,
    ) : PaneEntriesState
}

interface PaneComponent {
    val state: StateFlow<PaneState>

    fun refresh()

    fun goBack()

    fun goForward()

    fun goUp()

    fun openDirectory(location: String)

    fun openEntry(entry: VFile)

    fun toggleSort(column: DetailsColumn)

    fun toggleHiddenItems()

    fun resizeDetailsColumn(
        column: DetailsColumn,
        nextColumn: DetailsColumn,
        deltaWeight: Float,
    )

    fun selectEntry(
        entryId: String,
        additive: Boolean = false,
        range: Boolean = false,
    )

    fun moveSelection(
        offset: Int,
        extendSelection: Boolean = false,
    )

    fun openSelectedEntry()

    fun selectAll()

    fun clearSelection()

    fun createTab(location: String = state.value.location)

    fun selectTab(tabId: String)

    fun closeTab(tabId: String)

    fun moveTab(
        tabId: String,
        targetIndex: Int,
    )

    fun detachTab(tabId: String): PaneTabState?

    fun attachTab(
        tab: PaneTabState,
        targetIndex: Int,
    )
}
