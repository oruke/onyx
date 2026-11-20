package com.oruke.onyx.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.core.model.PaneInlineEditMode
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 显示画廊网格、滚动条和一次性滚动定位。
 *
 * @param model 文件内容视图状态。
 * @param actions 文件内容交互回调。
 * @param thumbnails 缩略图服务。
 * @param ready 已加载的文件列表。
 * @param selection 框选状态。
 * @param dragActions 子条目拖拽回调。
 */
@Composable
internal fun GalleryEntriesGrid(
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    thumbnails: GalleryThumbnailServices,
    ready: PaneEntriesState.Ready,
    selection: PaneRubberBandState,
    dragActions: PaneEntriesChildDragActions,
) {
    val inlineEdit = model.inlineEditState
    val createInlineEntry = inlineEdit?.mode == PaneInlineEditMode.CREATE_FILE ||
            inlineEdit?.mode == PaneInlineEditMode.CREATE_DIRECTORY
    val entryIds = remember(ready.entries) { ready.entries.map { it.id } }
    val gridState = rememberPaneGridViewport(model, actions, entryIds, createInlineEntry)
    androidx.compose.runtime.DisposableEffect(selection, gridState) {
        val action: suspend (Float) -> Float = { delta -> gridState.scrollBy(delta) }
        selection.scrollByAction = action
        onDispose {
            if (selection.scrollByAction === action) selection.scrollByAction = null
        }
    }
    val canScrollVertically = gridState.canScrollForward || gridState.canScrollBackward
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val fallbackColumns = ((maxWidth - 8.dp) / model.galleryItemSizeDp.dp).toInt().coerceAtLeast(1)
        LaunchedEffect(gridState, fallbackColumns) {
            snapshotFlow {
                val layout = gridState.layoutInfo
                val measuredColumns = layout.visibleItemsInfo
                    .maxOfOrNull { info -> info.column + 1 }
                    ?.coerceAtLeast(1)
                    ?: fallbackColumns
                val fullyVisible = layout.visibleItemsInfo.count { info ->
                    info.offset.y >= layout.viewportStartOffset &&
                        info.offset.y + info.size.height <= layout.viewportEndOffset
                }
                measuredColumns to fullyVisible.coerceAtLeast(measuredColumns)
            }.distinctUntilChanged().collect { (columns, pageStep) ->
                actions.onViewportMetricsChanged(columns, pageStep)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(model.galleryItemSizeDp.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize().testTag("pane-entries-${model.paneId}"),
            contentPadding = PaddingValues(4.dp),
            userScrollEnabled = !model.contextMenuVisible,
        ) {
            if (createInlineEntry) {
                item(key = "inline-create") {
                    GalleryCreateEntry(model, actions, thumbnails, dragActions)
                }
            }
            gridItemsIndexed(ready.entries, key = { _, entry -> entry.id }) { _, entry ->
                GalleryFileEntry(model, actions, thumbnails, dragActions, selection, entry)
            }
        }
        if (canScrollVertically) {
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(gridState),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .testTag("pane-vertical-scrollbar-${model.paneId}")
                    .isolateScrollbarPointerEvents(
                        onActivate = actions.onActivate,
                        onDismissContextMenu = actions.onDismissContextMenu,
                    ),
            )
        }
    }
}

/**
 * 显示详情树列表、双向滚动条和一次性滚动定位。
 *
 * @param model 文件内容视图状态。
 * @param actions 文件内容交互回调。
 * @param ready 已加载的文件列表。
 * @param selection 框选状态。
 * @param horizontalScrollState 横向滚动状态。
 * @param dragActions 子条目拖拽回调。
 * @param minRowWidth 行内容需要撑满的最小视口宽度。
 */
@Composable
internal fun DetailsEntriesList(
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    ready: PaneEntriesState.Ready,
    selection: PaneRubberBandState,
    horizontalScrollState: androidx.compose.foundation.ScrollState,
    dragActions: PaneEntriesChildDragActions,
    minRowWidth: Dp = Dp.Unspecified,
) {
    val flattenedEntries = remember(
        ready.entries,
        model.inlineExpandedLocations,
        model.inlineExpandedEntries,
    ) {
        flattenEntries(ready.entries, model.inlineExpandedLocations, model.inlineExpandedEntries, depth = 0)
    }
    val createInlineEntry = model.inlineEditState?.mode == PaneInlineEditMode.CREATE_FILE ||
            model.inlineEditState?.mode == PaneInlineEditMode.CREATE_DIRECTORY
    val entryIds = remember(flattenedEntries) { flattenedEntries.map { it.entry.id } }
    val listState = rememberPaneListViewport(model, actions, entryIds, createInlineEntry, horizontalScrollState)
    androidx.compose.runtime.DisposableEffect(selection, listState) {
        val action: suspend (Float) -> Float = { delta -> listState.scrollBy(delta) }
        selection.scrollByAction = action
        onDispose {
            if (selection.scrollByAction === action) selection.scrollByAction = null
        }
    }
    val canScrollHorizontally = horizontalScrollState.canScrollForward || horizontalScrollState.canScrollBackward
    val canScrollVertically = listState.canScrollForward || listState.canScrollBackward
    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val fullyVisible = layout.visibleItemsInfo.count { info ->
                info.offset >= layout.viewportStartOffset &&
                    info.offset + info.size <= layout.viewportEndOffset
            }
            fullyVisible.coerceAtLeast(1)
        }.distinctUntilChanged().collect { pageStep ->
            actions.onViewportMetricsChanged(1, pageStep)
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (canScrollHorizontally) 10.dp else 0.dp)
                .testTag("pane-entries-${model.paneId}"),
            contentPadding = PaddingValues(bottom = 4.dp),
            userScrollEnabled = !model.contextMenuVisible,
        ) {
            if (createInlineEntry) {
                item(key = "inline-create") { DetailsCreateEntry(model, actions, horizontalScrollState, minRowWidth) }
            }
            itemsIndexed(flattenedEntries, key = { _, item -> item.entry.id }) { index, item ->
                DetailsFileEntry(model, actions, dragActions, selection, horizontalScrollState, index, item, minRowWidth)
            }
        }
        if (canScrollHorizontally) {
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(horizontalScrollState),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(end = if (canScrollVertically) 8.dp else 0.dp)
                    .testTag("pane-horizontal-scrollbar-${model.paneId}")
                    .isolateScrollbarPointerEvents(
                        onActivate = actions.onActivate,
                        onDismissContextMenu = actions.onDismissContextMenu,
                    ),
            )
        }
        if (canScrollVertically) {
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .padding(bottom = if (canScrollHorizontally) 8.dp else 0.dp)
                    .testTag("pane-vertical-scrollbar-${model.paneId}")
                    .isolateScrollbarPointerEvents(
                        onActivate = actions.onActivate,
                        onDismissContextMenu = actions.onDismissContextMenu,
                    ),
            )
        }
    }
}
