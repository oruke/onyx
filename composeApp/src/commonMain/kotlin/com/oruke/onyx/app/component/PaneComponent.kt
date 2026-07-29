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
import com.oruke.onyx.core.model.PaneOperationFeedbackKind
import com.oruke.onyx.core.model.PaneSessionSnapshot
import com.oruke.onyx.core.model.PaneStatusInfo
import com.oruke.onyx.core.model.TabSnapshot
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.ViewMode
import kotlinx.coroutines.flow.StateFlow

internal data class TabState(
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

/**
 * 面板 chrome 层状态，描述不属于单个文件条目的交互外壳。
 *
 * @property activeTabId 当前活动标签页 ID。
 * @property inlineExpandedLocations 当前以内联树方式展开的目录位置。
 * @property inlineExpandedEntries 内联展开目录的子项缓存。
 * @property filterInputVisible 筛选输入框是否处于打开状态。
 * @property filterInputFocusRequestId 筛选输入框聚焦请求编号，每次显式打开递增。
 * @property commandPaletteVisible 命令面板是否处于打开状态。
 */
internal data class PaneChromeState(
    val activeTabId: String,
    val inlineExpandedLocations: Set<String> = emptySet(),
    val inlineExpandedEntries: Map<String, InlineExpandedEntry> = emptyMap(),
    val filterInputVisible: Boolean = false,
    val filterInputFocusRequestId: Int = 0,
    val commandPaletteVisible: Boolean = false,
)

internal data class PaneState(
    val paneId: PaneId,
    val chromeState: PaneChromeState,
    val activeTabState: TabState,
) {
    val activeTabId: String get() = chromeState.activeTabId
    val inlineExpandedLocations: Set<String> get() = chromeState.inlineExpandedLocations
    val inlineExpandedEntries: Map<String, InlineExpandedEntry> get() = chromeState.inlineExpandedEntries
    val filterInputVisible: Boolean get() = chromeState.filterInputVisible
    val filterInputFocusRequestId: Int get() = chromeState.filterInputFocusRequestId
    val commandPaletteVisible: Boolean get() = chromeState.commandPaletteVisible
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

internal data class PaneTabState(
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

internal sealed interface PaneEntriesState {
    data object Idle : PaneEntriesState

    data object Loading : PaneEntriesState

    data class Ready(
        val entries: List<VFile>,
    ) : PaneEntriesState

    data class Failure(
        val error: PaneEntriesError,
    ) : PaneEntriesState
}

internal data class PaneEntriesError(
    val kind: PaneEntriesErrorKind,
    val detail: I18nMessage? = null,
) {
    companion object {
        fun loadingFailed(detail: I18nMessage?): PaneEntriesError {
            return PaneEntriesError(
                kind = PaneEntriesErrorKind.LOAD_FAILED,
                detail = detail,
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

internal enum class PaneEntriesErrorKind {
    LOAD_FAILED,
    UNKNOWN,
}

// ── 内联展开（文件列表树状展开） ──────────────────────────────────────────

/**
 * 内联展开子项的加载状态。
 * [parentLocation] 是展开的目录 location，[depth] 是嵌套深度（从 1 开始），
 * [entries] 为 null 表示正在加载，非 null 表示已加载完成。
 */
internal data class InlineExpandedEntry(
    val parentLocation: String,
    val depth: Int,
    val entries: List<VFile>? = null,
    val error: Boolean = false,
)

internal sealed interface PaneIntent {
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

    data object ShowFilterInput : PaneIntent

    data class HideFilterInput(
        val clearQuery: Boolean,
    ) : PaneIntent

    data object ShowCommandPalette : PaneIntent

    data object HideCommandPalette : PaneIntent

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

    data object OpenSelectedInNewWindow : PaneIntent

    data object CopySelectedPaths : PaneIntent

    data class UpdateInlineEditDraft(
        val draft: String,
    ) : PaneIntent

    data object ConfirmInlineEdit : PaneIntent

    data object CancelInlineEdit : PaneIntent

    data object DismissOperationFeedback : PaneIntent

    data class ShowOperationFeedback(
        val kind: PaneOperationFeedbackKind,
        val detail: I18nMessage,
    ) : PaneIntent

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


internal interface PaneComponent {
    val state: StateFlow<PaneState>
    /** 视觉标签顺序；ChildStack 是导航栈顺序，active child 会被放到栈顶。 */
    val tabOrder: StateFlow<List<String>>
    /** 显示顺序的标签状态列表；导航或标签状态变化时同步更新，供标签栏订阅。 */
    val tabStates: StateFlow<List<PaneTabState>>
    val tabStack: Value<ChildStack<TabConfig, TabComponent>>

    fun dispatch(intent: PaneIntent)

    fun detachTab(tabId: String): TabSnapshot?

    fun attachTab(
        tabSnapshot: TabSnapshot,
        targetIndex: Int,
    )

    fun restoreSession(snapshot: PaneSessionSnapshot)
}

internal fun PaneComponent.tabStatesInDisplayOrder(): List<PaneTabState> {
    val children = tabStack.value.items
    val childrenById = children.associateBy { child -> child.configuration.id }
    val orderedIds = tabOrder.value.toSet()
    return tabOrder.value.mapNotNull { tabId ->
        childrenById[tabId]?.instance?.state?.value
    } + children
        .filterNot { child -> child.configuration.id in orderedIds }
        .map { child -> child.instance.state.value }
}

internal fun PaneComponent.refresh() = dispatch(PaneIntent.Refresh)

internal fun PaneComponent.goBack() = dispatch(PaneIntent.GoBack)

internal fun PaneComponent.goForward() = dispatch(PaneIntent.GoForward)

internal fun PaneComponent.goUp() = dispatch(PaneIntent.GoUp)

internal fun PaneComponent.openDirectory(location: String) = dispatch(PaneIntent.OpenDirectory(location))

internal fun PaneComponent.openEntry(entry: VFile) = dispatch(PaneIntent.OpenEntry(entry))

internal fun PaneComponent.setViewMode(mode: ViewMode) = dispatch(PaneIntent.SetViewMode(mode))

internal fun PaneComponent.setFilterQuery(query: String) = dispatch(PaneIntent.SetFilterQuery(query))

internal fun PaneComponent.showFilterInput() = dispatch(PaneIntent.ShowFilterInput)

internal fun PaneComponent.hideFilterInput(clearQuery: Boolean = true) =
    dispatch(PaneIntent.HideFilterInput(clearQuery))

internal fun PaneComponent.showCommandPalette() = dispatch(PaneIntent.ShowCommandPalette)

internal fun PaneComponent.hideCommandPalette() = dispatch(PaneIntent.HideCommandPalette)

internal fun PaneComponent.toggleSort(column: DetailsColumn) = dispatch(PaneIntent.ToggleSort(column))

internal fun PaneComponent.toggleHiddenItems() = dispatch(PaneIntent.ToggleHiddenItems)

internal fun PaneComponent.toggleColumnVisibility(column: DetailsColumn) =
    dispatch(PaneIntent.ToggleColumnVisibility(column))

internal fun PaneComponent.setGalleryItemSize(sizeDp: Int) = dispatch(PaneIntent.SetGalleryItemSize(sizeDp))

internal fun PaneComponent.resizeDetailsColumn(
    column: DetailsColumn,
    nextColumn: DetailsColumn,
    deltaWeight: Float,
) = dispatch(PaneIntent.ResizeDetailsColumn(column, nextColumn, deltaWeight))

internal fun PaneComponent.selectEntry(
    entryId: String,
    additive: Boolean = false,
    range: Boolean = false,
) = dispatch(PaneIntent.SelectEntry(entryId, additive, range))

internal fun PaneComponent.selectEntries(entryIds: Set<String>) = dispatch(PaneIntent.SelectEntries(entryIds))

internal fun PaneComponent.moveSelection(
    offset: Int,
    extendSelection: Boolean = false,
) = dispatch(PaneIntent.MoveSelection(offset, extendSelection))

internal fun PaneComponent.openSelectedEntry() = dispatch(PaneIntent.OpenSelectedEntry)

internal fun PaneComponent.beginRename() = dispatch(PaneIntent.BeginRename)

internal fun PaneComponent.beginCreateFile() = dispatch(PaneIntent.BeginCreateFile)

internal fun PaneComponent.beginCreateDirectory() = dispatch(PaneIntent.BeginCreateDirectory)

internal fun PaneComponent.openSelectedInNewTab() = dispatch(PaneIntent.OpenSelectedInNewTab)

internal fun PaneComponent.copySelectedPaths() = dispatch(PaneIntent.CopySelectedPaths)

internal fun PaneComponent.updateInlineEditDraft(draft: String) = dispatch(PaneIntent.UpdateInlineEditDraft(draft))

internal fun PaneComponent.confirmInlineEdit() = dispatch(PaneIntent.ConfirmInlineEdit)

internal fun PaneComponent.cancelInlineEdit() = dispatch(PaneIntent.CancelInlineEdit)

internal fun PaneComponent.dismissOperationFeedback() = dispatch(PaneIntent.DismissOperationFeedback)

internal fun PaneComponent.selectAll() = dispatch(PaneIntent.SelectAll)

internal fun PaneComponent.clearSelection() = dispatch(PaneIntent.ClearSelection)

internal fun PaneComponent.createTab(location: String = state.value.location) = dispatch(PaneIntent.CreateTab(location))

internal fun PaneComponent.selectTab(tabId: String) = dispatch(PaneIntent.SelectTab(tabId))

internal fun PaneComponent.closeTab(tabId: String) = dispatch(PaneIntent.CloseTab(tabId))

internal fun PaneComponent.moveTab(
    tabId: String,
    targetIndex: Int,
) = dispatch(PaneIntent.MoveTab(tabId, targetIndex))

internal fun PaneComponent.toggleInlineExpand(directoryLocation: String) =
    dispatch(PaneIntent.ToggleInlineExpand(directoryLocation))

internal fun PaneComponent.consumePendingScroll() = dispatch(PaneIntent.ConsumePendingScroll)

internal interface EntryNameSuggestionService {
    suspend fun newFileName(): String

    suspend fun newDirectoryName(): String
}
