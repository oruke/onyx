package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.component.PaneContextMenuBuildInput
import com.oruke.onyx.app.component.PaneContextMenuModelBuilder
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.ui.theme.FileDropZone
import com.oruke.onyx.ui.theme.windowBounds
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider

/**
 * 渲染面板文件区域和可选检查器。
 *
 * @param runtime 面板运行时控制器。
 * @param paneDropBackground 面板作为放置目标时的背景色。
 * @param modifier 文件区域布局修饰符。
 */
@Composable
internal fun PaneFileArea(
    runtime: PaneSurfaceRuntime,
    paneDropBackground: Color,
    modifier: Modifier = Modifier,
) {
    val readyEntries = (runtime.state.entriesState as? PaneEntriesState.Ready)?.entries.orEmpty()
    val selectedEntries = readyEntries.filter { entry -> entry.id in runtime.state.selectedEntryIds }
    Row(modifier = modifier.fillMaxWidth().fillMaxHeight()) {
        PaneEntriesArea(runtime, readyEntries, paneDropBackground, Modifier.weight(1f))
        if (runtime.state.inspectorState.previewVisible || runtime.state.inspectorState.detailsVisible) {
            Divider(Orientation.Vertical, modifier = Modifier.fillMaxHeight().width(1.dp))
            PaneInspector(runtime, selectedEntries.singleOrNull())
        }
    }
}

/**
 * 渲染文件条目、过滤输入与右键菜单覆盖层。
 *
 * @param runtime 面板运行时控制器。
 * @param readyEntries 当前已加载条目。
 * @param paneDropBackground 面板放置目标背景色。
 * @param modifier 文件区域布局修饰符。
 */
@Composable
private fun PaneEntriesArea(
    runtime: PaneSurfaceRuntime,
    readyEntries: List<VFile>,
    paneDropBackground: Color,
    modifier: Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .onGloballyPositioned { coordinates ->
                runtime.updateFileDropZone(
                    FileDropZone(
                        paneId = runtime.state.paneId,
                        targetDirectoryLocation = runtime.state.location,
                        bounds = coordinates.windowBounds(),
                    )
                )
            }
            .background(paneDropBackground)
            .clickable(
                indication = null,
                interactionSource = interactionSource,
                onClick = {
                    runtime.model.onActivate()
                    runtime.ui.contextMenuVisible = false
                },
            ),
    ) {
        PaneEntriesContent(
            model = runtime.toPaneEntriesViewModel(),
            actions = runtime.toPaneEntriesActions(readyEntries),
            thumbnails = runtime.toGalleryThumbnailServices(),
        )
        PaneFilterOverlay(runtime)
        PaneContextMenuOverlay(runtime, readyEntries)
    }
}

/**
 * 把当前面板状态映射为条目视图模型。
 *
 * @return 文件条目渲染模型。
 */
private fun PaneSurfaceRuntime.toPaneEntriesViewModel(): PaneEntriesViewModel {
    return PaneEntriesViewModel(
        viewMode = state.viewMode,
        columns = state.detailsColumns,
        columnWeights = state.detailsColumnWeights,
        hiddenColumns = state.hiddenColumns,
        sort = state.detailsSort,
        selectedEntryIds = state.selectedEntryIds,
        entriesState = state.entriesState,
        paneActive = model.active,
        contextMenuVisible = ui.contextMenuVisible,
        paneId = state.paneId,
        fileDropTarget = dragBindings.fileDropTarget,
        inlineEditState = state.inlineEditState,
        galleryItemSizeDp = state.galleryItemSizeDp,
        inlineExpandedLocations = state.inlineExpandedLocations,
        inlineExpandedEntries = state.inlineExpandedEntries,
        pendingScrollToEntryId = state.pendingScrollToEntryId,
    )
}

/**
 * 构建文件条目视图动作，并把所有业务事件收敛到面板组件。
 *
 * @param readyEntries 当前已加载条目，用于构造右键菜单目标。
 * @return 文件条目动作集合。
 */
private fun PaneSurfaceRuntime.toPaneEntriesActions(readyEntries: List<VFile>): PaneEntriesActions {
    return PaneEntriesActions(
        onActivate = model.onActivate,
        onOpenEntry = { entry -> dispatch(PaneIntent.OpenEntry(entry)) },
        onToggleSort = { column -> dispatch(PaneIntent.ToggleSort(column)) },
        onResizeColumn = { column, nextColumn, deltaWeight ->
            dispatch(PaneIntent.ResizeDetailsColumn(column, nextColumn, deltaWeight))
        },
        onToggleColumnVisibility = { column -> dispatch(PaneIntent.ToggleColumnVisibility(column)) },
        onSelectEntry = { entryId, additive, range ->
            dispatch(PaneIntent.SelectEntry(entryId, additive, range))
        },
        onStartFileDrag = dragBindings.onFileDragStart,
        onFileDragPositionChange = dragBindings.onFileDragPositionChange,
        onFileDragEnd = dragBindings.onFileDragEnd,
        onFileDropZoneChange = ::updateFileDropZone,
        onUpdateInlineEditDraft = { draft -> dispatch(PaneIntent.UpdateInlineEditDraft(draft)) },
        onConfirmInlineEdit = { dispatch(PaneIntent.ConfirmInlineEdit) },
        onCancelInlineEdit = { dispatch(PaneIntent.CancelInlineEdit) },
        onShowContextMenu = { entryId, selected, position ->
            showEntryContextMenu(entryId, selected, position, readyEntries)
        },
        onDismissContextMenu = { ui.contextMenuVisible = false },
        onBeginRename = { dispatch(PaneIntent.BeginRename) },
        onSelectEntries = { entryIds -> dispatch(PaneIntent.SelectEntries(entryIds)) },
        onToggleInlineExpand = { location ->
            model.onActivate()
            dispatch(PaneIntent.ToggleInlineExpand(location))
        },
        onConsumeScroll = { dispatch(PaneIntent.ConsumePendingScroll) },
        onBlankAreaContextMenu = ::showBlankContextMenu,
    )
}

/**
 * 构建画廊缩略图服务。
 *
 * @return 当前面板可用的缩略图能力。
 */
private fun PaneSurfaceRuntime.toGalleryThumbnailServices(): GalleryThumbnailServices {
    return GalleryThumbnailServices(
        loadThumbnail = services.loadThumbnail,
        loadArchiveThumbnail = services.loadArchiveThumbnail,
        isImageFileName = model.actions.isImageFileName,
        isArchiveFileName = model.actions.isArchiveFileName,
    )
}

/**
 * 在文件区右上角渲染过滤输入覆盖层。
 *
 * @param runtime 面板运行时控制器。
 */
@Composable
private fun BoxScope.PaneFilterOverlay(runtime: PaneSurfaceRuntime) {
    val state = runtime.state
    val visible = state.filterInputVisible || state.filterQuery.isNotEmpty()
    if (!visible) return
    FloatingFilterInput(
        query = state.filterQuery,
        focusRequester = runtime.filterFocusRequester,
        focusRequestId = state.filterInputFocusRequestId,
        onQueryChange = { query -> runtime.dispatch(PaneIntent.SetFilterQuery(query)) },
        onFocusChanged = { focused ->
            runtime.ui.filterFocused = focused
            if (focused) runtime.model.onActivate()
        },
        onClose = runtime::closeFilterInput,
        modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
    )
}

/**
 * 渲染当前文件区右键菜单。
 *
 * @param runtime 面板运行时控制器。
 * @param readyEntries 当前已加载条目。
 */
@Composable
private fun BoxScope.PaneContextMenuOverlay(runtime: PaneSurfaceRuntime, readyEntries: List<VFile>) {
    if (!runtime.ui.contextMenuVisible) return
    val entries = readyEntries.filter { entry -> entry.id in runtime.ui.contextMenuEntryIds }
    val menuModel = PaneContextMenuModelBuilder.build(
        PaneContextMenuBuildInput(
            entries = entries,
            canPaste = runtime.model.canPaste,
            canUndo = runtime.model.actions.canUndo,
            canRedo = runtime.model.actions.canRedo,
            canExtractSelection = entries.any { entry ->
                entry.kind == VFileKind.FILE && runtime.model.actions.isArchiveFileName(entry.name)
            },
            contextMenuSections = runtime.ui.contextMenuSections,
        )
    )
    PaneContextMenu(
        anchorOffset = runtime.ui.contextMenuOffset,
        model = menuModel,
        onCommand = { command ->
            if (runtime.menuCommands.execute(command, entries, runtime.state.location)) {
                runtime.ui.contextMenuVisible = false
            }
        },
        commandShortcuts = runtime.model.commandShortcuts,
        onClose = { runtime.ui.contextMenuVisible = false },
    )
}

/**
 * 渲染文件检查器。
 *
 * @param runtime 面板运行时控制器。
 * @param entry 当前唯一选中的条目。
 */
@Composable
private fun PaneInspector(runtime: PaneSurfaceRuntime, entry: VFile?) {
    InspectorPanel(
        entry = entry,
        state = runtime.state.inspectorState,
        loadThumbnail = runtime.services.loadThumbnail,
        readFileHash = runtime.services.readFileHash,
        readArchiveInfo = runtime.services.readArchiveInfo,
        isImageFileName = runtime.model.actions.isImageFileName,
        isArchiveFileName = runtime.model.actions.isArchiveFileName,
    )
}
