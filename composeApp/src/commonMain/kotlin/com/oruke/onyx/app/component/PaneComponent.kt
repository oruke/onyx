package com.oruke.onyx.app.component

import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.Value
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneInlineEditState
import com.oruke.onyx.core.model.PaneInspectorState
import com.oruke.onyx.core.model.PaneOperationFeedback
import com.oruke.onyx.core.model.PaneSessionSnapshot
import com.oruke.onyx.core.model.PaneStatusInfo
import com.oruke.onyx.core.model.TabSnapshot
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.ViewMode
import kotlinx.coroutines.flow.StateFlow

data class TabState(
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
    val pendingScrollToEntryId: String? = null,
)

data class PaneChromeState(
    val activeTabId: String,
    val inlineExpandedLocations: Set<String> = emptySet(),
    val inlineExpandedEntries: Map<String, InlineExpandedEntry> = emptyMap(),
)

data class PaneState(
    val paneId: PaneId,
    val chromeState: PaneChromeState,
    val activeTabState: TabState,
) {
    val activeTabId: String get() = chromeState.activeTabId
    val inlineExpandedLocations: Set<String> get() = chromeState.inlineExpandedLocations
    val inlineExpandedEntries: Map<String, InlineExpandedEntry> get() = chromeState.inlineExpandedEntries
    val location: String get() = activeTabState.location
    val canGoBack: Boolean get() = activeTabState.canGoBack
    val canGoForward: Boolean get() = activeTabState.canGoForward
    val detailsColumns: List<DetailsColumn> get() = activeTabState.detailsColumns
    val detailsColumnWeights: Map<DetailsColumn, Float> get() = activeTabState.detailsColumnWeights
    val detailsSort: DetailsSort get() = activeTabState.detailsSort
    val viewMode: ViewMode get() = activeTabState.viewMode
    val filterQuery: String get() = activeTabState.filterQuery
    val selectedEntryIds: Set<String> get() = activeTabState.selectedEntryIds
    val selectionAnchorId: String? get() = activeTabState.selectionAnchorId
    val selectionFocusId: String? get() = activeTabState.selectionFocusId
    val statusInfo: PaneStatusInfo get() = activeTabState.statusInfo
    val inlineEditState: PaneInlineEditState? get() = activeTabState.inlineEditState
    val inspectorState: PaneInspectorState get() = activeTabState.inspectorState
    val operationFeedback: PaneOperationFeedback? get() = activeTabState.operationFeedback
    val showHiddenItems: Boolean get() = activeTabState.showHiddenItems
    val hiddenColumns: Set<DetailsColumn> get() = activeTabState.hiddenColumns
    val galleryItemSizeDp: Int get() = activeTabState.galleryItemSizeDp
    val entriesState: PaneEntriesState get() = activeTabState.entriesState
    val pendingScrollToEntryId: String? get() = activeTabState.pendingScrollToEntryId
}

data class PaneTabState(
    val id: String,
    val title: String,
    val tabState: TabState,
    val allEntries: List<VFile>,
    val backStack: List<String>,
    val forwardStack: List<String>,
) {
    val location: String get() = tabState.location
    val canGoBack: Boolean get() = tabState.canGoBack
    val canGoForward: Boolean get() = tabState.canGoForward
    val detailsColumns: List<DetailsColumn> get() = tabState.detailsColumns
    val detailsColumnWeights: Map<DetailsColumn, Float> get() = tabState.detailsColumnWeights
    val detailsSort: DetailsSort get() = tabState.detailsSort
    val viewMode: ViewMode get() = tabState.viewMode
    val filterQuery: String get() = tabState.filterQuery
    val selectedEntryIds: Set<String> get() = tabState.selectedEntryIds
    val selectionAnchorId: String? get() = tabState.selectionAnchorId
    val selectionFocusId: String? get() = tabState.selectionFocusId
    val statusInfo: PaneStatusInfo get() = tabState.statusInfo
    val inlineEditState: PaneInlineEditState? get() = tabState.inlineEditState
    val inspectorState: PaneInspectorState get() = tabState.inspectorState
    val operationFeedback: PaneOperationFeedback? get() = tabState.operationFeedback
    val showHiddenItems: Boolean get() = tabState.showHiddenItems
    val hiddenColumns: Set<DetailsColumn> get() = tabState.hiddenColumns
    val galleryItemSizeDp: Int get() = tabState.galleryItemSizeDp
    val entriesState: PaneEntriesState get() = tabState.entriesState
    val pendingScrollToEntryId: String? get() = tabState.pendingScrollToEntryId

    fun withTabState(transform: (TabState) -> TabState): PaneTabState {
        return copy(tabState = transform(tabState))
    }
}

sealed interface PaneEntriesState {
    data object Idle : PaneEntriesState

    data object Loading : PaneEntriesState

    data class Ready(
        val entries: List<VFile>,
    ) : PaneEntriesState

    data class Failure(
        val error: PaneEntriesError,
    ) : PaneEntriesState
}

data class PaneEntriesError(
    val kind: PaneEntriesErrorKind,
    val detail: I18nMessage? = null,
) {
    companion object {
        fun loadingFailed(detail: String?): PaneEntriesError {
            return PaneEntriesError(
                kind = PaneEntriesErrorKind.LOAD_FAILED,
                detail = detail?.let { I18nMessage(MessageKey.MSG_STRING_LITERAL, it) },
            )
        }

        fun unknown(): PaneEntriesError {
            return PaneEntriesError(
                kind = PaneEntriesErrorKind.UNKNOWN,
                detail = I18nMessage(MessageKey.MSG_UNKNOWN_ERROR),
            )
        }
    }
}

enum class PaneEntriesErrorKind {
    LOAD_FAILED,
    UNKNOWN,
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
    val tabStack: Value<ChildStack<TabConfig, TabComponent>>

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

    fun detachTab(tabId: String): TabSnapshot?

    fun attachTab(
        tabSnapshot: TabSnapshot,
        targetIndex: Int,
    )

    fun restoreSession(snapshot: PaneSessionSnapshot)

    // ── 内联展开 ────────────────────────────────────────────────────────

    fun toggleInlineExpand(directoryLocation: String)

    /** UI 滚动到 pendingScrollToEntryId 后调用，清除待滚动标记 */
    fun consumePendingScroll()
}

interface EntryNameSuggestionService {
    suspend fun newFileName(): String

    suspend fun newDirectoryName(): String
}
