package com.oruke.onyx.app.component

import com.oruke.onyx.app.component.delegate.EntrySorter
import com.oruke.onyx.app.component.delegate.SelectionReducer
import com.oruke.onyx.app.filesystem.VfsPathService
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.PaneInspectorState
import com.oruke.onyx.core.model.PaneSessionSnapshot
import com.oruke.onyx.core.model.PaneStatusInfo
import com.oruke.onyx.core.model.SortDirection
import com.oruke.onyx.core.model.TabSnapshot
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.ViewMode

internal const val MIN_DETAILS_COLUMN_WIDTH = 40f

/** 文件名明细列默认宽度。 */
private const val DEFAULT_NAME_COLUMN_WIDTH = 300f

/** 文件类型明细列默认宽度。 */
private const val DEFAULT_TYPE_COLUMN_WIDTH = 80f

/** 文件大小明细列默认宽度。 */
private const val DEFAULT_SIZE_COLUMN_WIDTH = 100f

/** 修改时间明细列默认宽度。 */
private const val DEFAULT_MODIFIED_COLUMN_WIDTH = 180f

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
        DetailsColumn.NAME to DEFAULT_NAME_COLUMN_WIDTH,
        DetailsColumn.TYPE to DEFAULT_TYPE_COLUMN_WIDTH,
        DetailsColumn.SIZE to DEFAULT_SIZE_COLUMN_WIDTH,
        DetailsColumn.MODIFIED to DEFAULT_MODIFIED_COLUMN_WIDTH,
    )
}

internal fun defaultDetailsColumnWidth(column: DetailsColumn): Float {
    return defaultDetailsColumnWeights()[column] ?: MIN_DETAILS_COLUMN_WIDTH
}

internal fun PaneTabState.toPaneState(
    paneId: com.oruke.onyx.core.model.PaneId,
    activeTabId: String,
    inlineExpandedLocations: Set<String> = emptySet(),
    inlineExpandedEntries: Map<String, InlineExpandedEntry> = emptyMap(),
    filterInputVisible: Boolean = false,
    filterInputFocusRequestId: Int = 0,
    commandPaletteVisible: Boolean = false,
): PaneState {
    return PaneState(
        paneId = paneId,
        chromeState = PaneChromeState(
            activeTabId = activeTabId,
            inlineExpandedLocations = inlineExpandedLocations,
            inlineExpandedEntries = inlineExpandedEntries,
            filterInputVisible = filterInputVisible,
            filterInputFocusRequestId = filterInputFocusRequestId,
            commandPaletteVisible = commandPaletteVisible,
        ),
        activeTabState = tabState,
    )
}

internal fun PaneTabState.toTabSnapshot(): TabSnapshot {
    return TabSnapshot(
        id = id,
        location = location,
        detailsColumns = detailsColumns,
        detailsColumnWeights = detailsColumnWeights,
        detailsSort = detailsSort,
        showHiddenItems = showHiddenItems,
        viewMode = tabState.viewMode,
        computerViewMode = tabState.computerViewMode,
        filterQuery = filterQuery,
        backStack = backStack,
        forwardStack = forwardStack,
    )
}

/**
 * 将持久化标签快照恢复为运行时状态，并统一规范化当前位置和导航历史。
 *
 * @param pathService VFS 路径规范化服务。
 * @return 已恢复的标签运行时状态。
 */
internal fun TabSnapshot.toPaneTabState(pathService: VfsPathService): PaneTabState {
    val normalizedLocation = pathService.normalizeLocation(location)
    val normalizedBackStack = backStack.map(pathService::normalizeLocation)
    val normalizedForwardStack = forwardStack.map(pathService::normalizeLocation)
    return PaneTabState(
        id = id,
        title = pathService.title(normalizedLocation),
        tabState = TabState(
            location = normalizedLocation,
            canGoBack = normalizedBackStack.isNotEmpty(),
            canGoForward = normalizedForwardStack.isNotEmpty(),
            detailsColumns = detailsColumns,
            detailsColumnWeights = if (detailsColumnWeights.values.all { it < 2f }) {
                defaultDetailsColumnWeights()
            } else {
                detailsColumnWeights
            },
            detailsSort = detailsSort,
            viewMode = viewMode,
            computerViewMode = computerViewMode,
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
        backStack = normalizedBackStack,
        forwardStack = normalizedForwardStack,
    )
}

/**
 * 按视图配置派生可见条目、选择与统计；纯计算可以在后台线程执行。
 * @param inlineExpandedLocations 当前访问展开的目录。
 * @param inlineExpandedEntries 已加载的展开子项。
 * @param sortedEntries 内容和排序未变时复用的已有可见列表。
 * @param preserveOrder 是否在被动更新期间保护当前顺序，交互结束后恢复配置排序。
 * @return 校正后的标签状态。
 */
internal fun PaneTabState.withDerivedState(
    inlineExpandedLocations: Set<String>,
    inlineExpandedEntries: Map<String, InlineExpandedEntry>,
    sortedEntries: List<VFile>? = null,
    preserveOrder: Boolean = false,
): PaneTabState {
    if (entriesState is ComputerEntriesState) return withDerivedComputerState()
    // 历史缓存被淘汰时先保留其选择，真实目录返回后再校正，不能在 Loading 阶段清空。
    if (entriesState !is PaneEntriesState.Ready && allEntries.isEmpty()) return this
    val sorted = sortedEntries ?: EntrySorter.visibleSortedEntries(
        allEntries = allEntries,
        showHiddenItems = showHiddenItems,
        sort = detailsSort,
        filterQuery = filterQuery,
    )
    val previous = (entriesState as? PaneEntriesState.Ready)?.entries
    // 按下或滚动报告通常没有内容变化，直接复用顺序，避免每次手势都建立全目录身份索引。
    val visibleEntries = if (preserveOrder && previous != null && sorted !== previous) {
        preserveDirectoryEntryOrder(sorted, previous)
    } else sorted
    val allVisibleForSelection = if (inlineExpandedLocations.isEmpty()) {
        visibleEntries
    } else {
        SelectionReducer.collectVisibleEntries(
            entries = visibleEntries,
            expandedLocations = inlineExpandedLocations,
            expandedEntries = inlineExpandedEntries,
        )
    }
    val selection = SelectionReducer.reconcileSelection(
        entries = allVisibleForSelection,
        selectedEntryIds = selectedEntryIds,
        anchorId = selectionAnchorId,
        focusId = selectionFocusId,
    )
    val nextEntriesState = when (val currentEntriesState = entriesState) {
        is ComputerEntriesState -> currentEntriesState
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
            browserState = current.browserState.copy(directoryOrderDeferred = preserveOrder &&
                (current.browserState.directoryOrderDeferred || visibleEntries !== sorted)),
            selectedEntryIds = selection.selectedEntryIds,
            selectionAnchorId = selection.anchorId,
            selectionFocusId = selection.focusId,
            statusInfo = SelectionReducer.buildStatusInfo(
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
            refreshing = true,
            entriesState = if (current.entriesState is PaneEntriesState.Ready) current.entriesState
                else PaneEntriesState.Loading,
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

internal fun PaneTabState.withLoadFailure(reason: I18nMessage?): PaneTabState {
    return withTabState { current ->
        current.copy(entriesState = PaneEntriesState.Failure(PaneEntriesError.loadingFailed(reason)))
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
