package com.oruke.onyx.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.core.model.PaneInlineEditMode

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
    Box(modifier = Modifier.fillMaxSize()) {
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
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(gridState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )

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
 */
@Composable
internal fun DetailsEntriesList(
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    ready: PaneEntriesState.Ready,
    selection: PaneRubberBandState,
    horizontalScrollState: androidx.compose.foundation.ScrollState,
    dragActions: PaneEntriesChildDragActions,
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
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(bottom = 12.dp).testTag("pane-entries-${model.paneId}"),
            contentPadding = PaddingValues(bottom = 4.dp),
            userScrollEnabled = !model.contextMenuVisible,
        ) {
            if (createInlineEntry) {
                item(key = "inline-create") { DetailsCreateEntry(model, actions, horizontalScrollState) }
            }
            itemsIndexed(flattenedEntries, key = { _, item -> item.entry.id }) { index, item ->
                DetailsFileEntry(model, actions, dragActions, selection, horizontalScrollState, index, item)
            }
        }
        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(horizontalScrollState),
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
        )
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )

    }
}
