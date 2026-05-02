package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneInlineEditState
import com.oruke.onyx.core.model.PaneInspectorState
import com.oruke.onyx.core.model.PaneOperationFeedback
import com.oruke.onyx.core.model.PaneSessionSnapshot
import com.oruke.onyx.core.model.PaneStatusInfo
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.ViewMode
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
    val viewMode: ViewMode,
    val filterQuery: String,
    val selectedEntryIds: Set<String>,
    val selectionAnchorId: String?,
    val selectionFocusId: String?,
    val statusInfo: PaneStatusInfo,
    val inlineEditState: PaneInlineEditState?,
    val inspectorState: PaneInspectorState,
    val operationFeedback: PaneOperationFeedback?,
    val showHiddenItems: Boolean,
    val hiddenColumns: Set<DetailsColumn>,
    val galleryItemSizeDp: Int,
    val entriesState: PaneEntriesState,
    val folderTreeVisible: Boolean = false,
    val folderTreeState: PaneFolderTreeState = PaneFolderTreeState(),
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
    val viewMode: ViewMode,
    val filterQuery: String,
    val selectedEntryIds: Set<String>,
    val selectionAnchorId: String?,
    val selectionFocusId: String?,
    val statusInfo: PaneStatusInfo,
    val inlineEditState: PaneInlineEditState?,
    val inspectorState: PaneInspectorState,
    val operationFeedback: PaneOperationFeedback?,
    val showHiddenItems: Boolean,
    val hiddenColumns: Set<DetailsColumn>,
    val galleryItemSizeDp: Int,
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

// ── 面板目录树 ────────────────────────────────────────────────────────

data class PaneFolderTreeState(
    val roots: List<PaneFolderTreeNode> = emptyList(),
)

data class PaneFolderTreeNode(
    val location: String,
    val label: String,
    val expanded: Boolean = false,
    val loadState: PaneFolderTreeNodeLoadState = PaneFolderTreeNodeLoadState.IDLE,
    val children: List<PaneFolderTreeNode> = emptyList(),
)

enum class PaneFolderTreeNodeLoadState {
    IDLE,
    LOADING,
    READY,
    FAILURE,
}

interface PaneComponent {
    val state: StateFlow<PaneState>

    fun refresh()

    fun goBack()

    fun goForward()

    fun goUp()

    fun openDirectory(location: String)

    fun openEntry(entry: VFile)

    fun setViewMode(mode: ViewMode)

    fun setFilterQuery(query: String)

    fun toggleSort(column: DetailsColumn)

    fun toggleHiddenItems()

    fun toggleColumnVisibility(column: DetailsColumn)

    fun setGalleryItemSize(sizeDp: Int)

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

    /** 批量设置选中项（用于框选等场景） */
    fun selectEntries(entryIds: Set<String>)

    fun moveSelection(
        offset: Int,
        extendSelection: Boolean = false,
    )

    fun openSelectedEntry()

    fun beginRename()

    fun beginCreateFile()

    fun beginCreateDirectory()

    fun openSelectedInNewTab()

    fun copySelectedPaths()

    fun updateInlineEditDraft(draft: String)

    fun confirmInlineEdit()

    fun cancelInlineEdit()

    fun dismissOperationFeedback()


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

    fun restoreSession(snapshot: PaneSessionSnapshot)

    // ── 面板目录树 ────────────────────────────────────────────────────

    fun toggleFolderTree()

    fun toggleFolderTreeNode(location: String)

    fun retryFolderTreeNode(location: String)
}
