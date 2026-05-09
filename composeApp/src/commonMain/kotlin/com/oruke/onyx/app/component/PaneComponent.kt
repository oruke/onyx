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
    val inlineExpandedLocations: Set<String> = emptySet(),
    val inlineExpandedEntries: Map<String, InlineExpandedEntry> = emptyMap(),
    val pendingScrollToEntryId: String? = null,
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
    val pendingScrollToEntryId: String? = null,
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

// ── 内联展开（文件列表树状展开） ──────────────────────────────────────────

/**
 * 内联展开子项的加载状态。
 * [parentLocation] 是展开的目录 location，[depth] 是嵌套深度（从 1 开始），
 * [entries] 为 null 表示正在加载，非 null 表示已加载完成。
 */
data class InlineExpandedEntry(
    val parentLocation: String,
    val depth: Int,
    val entries: List<VFile>? = null,
    val error: Boolean = false,
)

sealed interface PaneIntent {
    data object Refresh : PaneIntent

    data object GoBack : PaneIntent

    data object GoForward : PaneIntent

    data object GoUp : PaneIntent

    data class OpenDirectory(
        val location: String,
    ) : PaneIntent

    data class OpenEntry(
        val entry: VFile,
    ) : PaneIntent

    data class SetViewMode(
        val mode: ViewMode,
    ) : PaneIntent

    data class SetFilterQuery(
        val query: String,
    ) : PaneIntent

    data class ToggleSort(
        val column: DetailsColumn,
    ) : PaneIntent

    data object ToggleHiddenItems : PaneIntent

    data class ToggleColumnVisibility(
        val column: DetailsColumn,
    ) : PaneIntent

    data class SetGalleryItemSize(
        val sizeDp: Int,
    ) : PaneIntent

    data class ResizeDetailsColumn(
        val column: DetailsColumn,
        val nextColumn: DetailsColumn,
        val deltaWeight: Float,
    ) : PaneIntent

    data class SelectEntry(
        val entryId: String,
        val additive: Boolean = false,
        val range: Boolean = false,
    ) : PaneIntent

    data class SelectEntries(
        val entryIds: Set<String>,
    ) : PaneIntent

    data class MoveSelection(
        val offset: Int,
        val extendSelection: Boolean = false,
    ) : PaneIntent

    data object OpenSelectedEntry : PaneIntent

    data object BeginRename : PaneIntent

    data object BeginCreateFile : PaneIntent

    data object BeginCreateDirectory : PaneIntent

    data object OpenSelectedInNewTab : PaneIntent

    data object CopySelectedPaths : PaneIntent

    data class UpdateInlineEditDraft(
        val draft: String,
    ) : PaneIntent

    data object ConfirmInlineEdit : PaneIntent

    data object CancelInlineEdit : PaneIntent

    data object DismissOperationFeedback : PaneIntent

    data object SelectAll : PaneIntent

    data object ClearSelection : PaneIntent

    data class CreateTab(
        val location: String? = null,
    ) : PaneIntent

    data class SelectTab(
        val tabId: String,
    ) : PaneIntent

    data class CloseTab(
        val tabId: String,
    ) : PaneIntent

    data class MoveTab(
        val tabId: String,
        val targetIndex: Int,
    ) : PaneIntent

    data class ToggleInlineExpand(
        val directoryLocation: String,
    ) : PaneIntent

    data object ConsumePendingScroll : PaneIntent
}


interface PaneComponent {
    val state: StateFlow<PaneState>

    fun dispatch(intent: PaneIntent)

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

    // ── 内联展开 ────────────────────────────────────────────────────────

    fun toggleInlineExpand(directoryLocation: String)

    /** UI 滚动到 pendingScrollToEntryId 后调用，清除待滚动标记 */
    fun consumePendingScroll()
}
