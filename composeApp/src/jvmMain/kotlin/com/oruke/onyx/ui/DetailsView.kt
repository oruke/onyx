package com.oruke.onyx.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitDragOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.FileTransferOperation
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneInlineEditMode
import com.oruke.onyx.core.model.PaneInlineEditState
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.ui.theme.DetailsColumnGap
import com.oruke.onyx.ui.theme.FileDropTarget
import com.oruke.onyx.ui.theme.FileDropZone
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.detailsColumnWidth
import com.oruke.onyx.ui.theme.fileIconKey
import com.oruke.onyx.ui.theme.formatFileSize
import com.oruke.onyx.ui.theme.formatModifiedTime
import com.oruke.onyx.ui.theme.horizontalResizePointerIcon
import com.oruke.onyx.ui.theme.orEmpty
import com.oruke.onyx.ui.theme.sortHint
import com.oruke.onyx.ui.theme.toIntOffset
import com.oruke.onyx.ui.theme.visibleDetailsColumns
import com.oruke.onyx.ui.theme.windowBounds
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_column_modified
import onyx.composeapp.generated.resources.label_column_name
import onyx.composeapp.generated.resources.label_column_size
import onyx.composeapp.generated.resources.label_column_type
import onyx.composeapp.generated.resources.label_directory_badge
import onyx.composeapp.generated.resources.label_empty_directory
import onyx.composeapp.generated.resources.label_empty_directory_hint
import onyx.composeapp.generated.resources.label_error_prefix
import onyx.composeapp.generated.resources.label_column_visibility
import onyx.composeapp.generated.resources.label_loading_entries
import onyx.composeapp.generated.resources.action_refresh_active
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed

// ── File entries ────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun PaneEntriesContent(
    viewMode: ViewMode,
    columns: List<DetailsColumn>,
    columnWeights: Map<DetailsColumn, Float>,
    hiddenColumns: Set<DetailsColumn>,
    sort: DetailsSort,
    selectedEntryIds: Set<String>,
    state: PaneEntriesState,
    paneActive: Boolean,
    contextMenuVisible: Boolean,
    onActivate: () -> Unit,
    onOpenEntry: (VFile) -> Unit,
    onToggleSort: (DetailsColumn) -> Unit,
    onResizeColumn: (DetailsColumn, DetailsColumn, Float) -> Unit,
    onToggleColumnVisibility: (DetailsColumn) -> Unit,
    onSelectEntry: (String, Boolean, Boolean) -> Unit,
    paneId: PaneId,
    fileDropTarget: FileDropTarget?,
    onStartFileDrag: (PaneId, FileTransferOperation) -> Unit,
    onFileDragPositionChange: (IntOffset) -> Unit,
    onFileDragEnd: (IntOffset?) -> Unit,
    onFileDropZoneChange: (FileDropZone) -> Unit,
    onShowContextMenu: (String, Boolean, IntOffset) -> Unit,
    onDismissContextMenu: () -> Unit,
    inlineEditState: PaneInlineEditState?,
    onUpdateInlineEditDraft: (String) -> Unit,
    onConfirmInlineEdit: () -> Unit,
    onCancelInlineEdit: () -> Unit,
    onBeginRename: () -> Unit = {},
    galleryItemSizeDp: Int = 160,
    onSelectEntries: (Set<String>) -> Unit = {},
    inlineExpandedLocations: Set<String> = emptySet(),
    inlineExpandedEntries: Map<String, com.oruke.onyx.app.component.InlineExpandedEntry> = emptyMap(),
    onToggleInlineExpand: (String) -> Unit = {},
    pendingScrollToEntryId: String? = null,
    onConsumeScroll: () -> Unit = {},
    onBlankAreaContextMenu: (IntOffset) -> Unit = {},
    onRetry: () -> Unit = {},
    loadThumbnail: suspend (String, Int) -> ImageBitmap?,
    loadArchiveThumbnail: suspend (String, Int) -> ImageBitmap?,
) {
    when (state) {
        PaneEntriesState.Idle, PaneEntriesState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = stringResource(Res.string.label_loading_entries),
                    color = LocalOnyxPalette.current.mutedForeground,
                    fontSize = 12.sp
                )
            }
        }

        is PaneEntriesState.Failure -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${stringResource(Res.string.label_error_prefix)} ${state.reason.orEmpty()}".trim(),
                        color = LocalOnyxPalette.current.foreground,
                        fontSize = 12.sp,
                    )
                    Text(
                        text = stringResource(Res.string.action_refresh_active),
                        color = LocalOnyxPalette.current.accent,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { onRetry() }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }

        is PaneEntriesState.Ready -> {
            val shouldCreateInlineEntry = inlineEditState?.let {
                it.mode == PaneInlineEditMode.CREATE_FILE || it.mode == PaneInlineEditMode.CREATE_DIRECTORY
            } == true
            val inlineEditMode = inlineEditState?.mode
            val inlineEditDraftName = inlineEditState?.draftName.orEmpty()
            val inlineTargetEntryId = inlineEditState?.targetEntryId

            if (state.entries.isEmpty() && !shouldCreateInlineEntry) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Press &&
                                        event.buttons.isSecondaryPressed
                                    ) {
                                        val pos = event.changes.firstOrNull()?.position
                                        if (pos != null) {
                                            onActivate()
                                            onBlankAreaContextMenu(IntOffset(pos.x.toInt(), pos.y.toInt()))
                                        }
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            key = AllIconsKeys.Nodes.Folder,
                            contentDescription = null,
                            modifier = Modifier.alpha(0.4f),
                        )
                        Text(
                            text = stringResource(Res.string.label_empty_directory),
                            color = LocalOnyxPalette.current.mutedForeground,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(Res.string.label_empty_directory_hint),
                            color = LocalOnyxPalette.current.disabledForeground,
                            fontSize = 11.sp,
                        )
                    }
                }
                return
            }

            // ── 框选状态 ──
            val itemCoordsMap = remember { mutableStateMapOf<String, LayoutCoordinates>() }
            var rubberBandStart by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
            var rubberBandEnd by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
            var isRubberBanding by remember { mutableStateOf(false) }
            var addRubberBandSelection by remember { mutableStateOf(false) }
            var rubberBandBaseSelection by remember { mutableStateOf(emptySet<String>()) }
            var isFileDragging by remember { mutableStateOf(false) }
            var pendingDragOrigin by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
            var containerCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

            // 子组件通过此回调同步通知 parent 文件拖拽开始/结束
            val wrappedOnStartFileDrag: (PaneId, FileTransferOperation) -> Unit = { pid, op ->
                isFileDragging = true
                pendingDragOrigin = null
                onStartFileDrag(pid, op)
            }
            val wrappedOnFileDragEnd: (IntOffset?) -> Unit = { pos ->
                isFileDragging = false
                onFileDragEnd(pos)
            }
            // 子组件通过此回调通知容器从文件行启动框选（坐标为窗口坐标）
            val wrappedOnStartRubberBand: (String, androidx.compose.ui.geometry.Offset, Boolean) -> Unit =
                { entryId, windowPos, addSelection ->
                    val container = containerCoordinates
                    if (container != null) {
                        val containerPos = container.windowToLocal(windowPos)
                        pendingDragOrigin = null
                        isRubberBanding = true
                        rubberBandStart = containerPos
                        rubberBandEnd = containerPos
                        addRubberBandSelection = addSelection
                        rubberBandBaseSelection = if (addSelection) selectedEntryIds else emptySet()
                        onActivate()
                        onDismissContextMenu()
                        if (!addSelection) {
                            onSelectEntries(emptySet())
                        }
                    }
                }

            Box(
                modifier = Modifier.fillMaxSize()
                    .clipToBounds()
                    .onGloballyPositioned { containerCoordinates = it }
                    .onPointerEvent(PointerEventType.Press) { event ->
                        if (event.changes.any { it.isConsumed }) return@onPointerEvent
                        val pos = event.changes.firstOrNull()?.position ?: return@onPointerEvent

                        // hit-test: 判断是否点击在文件项上
                        val container = containerCoordinates
                        val hitItem = if (container != null) {
                            itemCoordsMap.any { (_, coords) ->
                                coords.isAttached &&
                                    container.localBoundingBoxOf(coords, clipBounds = false).contains(pos)
                            }
                        } else false

                        // 右键点击空白区：显示空白区右键菜单
                        if (!event.buttons.isPrimaryPressed && event.buttons.isSecondaryPressed && !hitItem) {
                            onActivate()
                            onSelectEntries(emptySet())
                            val windowPos = container?.localToWindow(pos)
                            val offset = if (windowPos != null) {
                                IntOffset(windowPos.x.toInt(), windowPos.y.toInt())
                            } else {
                                IntOffset(pos.x.toInt(), pos.y.toInt())
                            }
                            onBlankAreaContextMenu(offset)
                            return@onPointerEvent
                        }

                        if (!event.buttons.isPrimaryPressed) return@onPointerEvent

                        if (hitItem) {
                            // 点击在文件项上 → 不设置 pendingDragOrigin，让子组件全权处理
                            pendingDragOrigin = null
                        } else {
                            // 点击在空白区域 → parent 负责框选
                            pendingDragOrigin = pos
                            isRubberBanding = false
                            isFileDragging = false
                        }
                    }
                    // Move: 检测拖拽（空白区域直接启动，文件行区域由 onStartRubberBand 回调触发后继续跟踪）
                    .onPointerEvent(PointerEventType.Move) { event ->
                        if (isFileDragging) return@onPointerEvent
                        if (event.changes.any { it.isConsumed }) return@onPointerEvent

                        val pos = event.changes.firstOrNull()?.position ?: return@onPointerEvent

                        // 空白区域拖拽：由 pendingDragOrigin 启动
                        val origin = pendingDragOrigin
                        if (origin != null && !isRubberBanding) {
                            val dx = pos.x - origin.x
                            val dy = pos.y - origin.y
                            if (dx * dx + dy * dy > 36f) {
                                isRubberBanding = true
                                rubberBandStart = origin
                                addRubberBandSelection = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                                rubberBandBaseSelection = if (addRubberBandSelection) selectedEntryIds else emptySet()
                                onActivate()
                                onDismissContextMenu()
                                if (!addRubberBandSelection) {
                                    onSelectEntries(emptySet())
                                }
                            }
                        }

                        // 框选进行中（无论是空白区域启动还是子组件回调启动）：持续更新
                        if (isRubberBanding) {
                            val rbOrigin = rubberBandStart ?: return@onPointerEvent
                            rubberBandEnd = pos
                            event.changes.forEach { it.consume() }
                            val container = containerCoordinates ?: return@onPointerEvent
                            val selRect = androidx.compose.ui.geometry.Rect(
                                left = minOf(rbOrigin.x, pos.x),
                                top = minOf(rbOrigin.y, pos.y),
                                right = maxOf(rbOrigin.x, pos.x),
                                bottom = maxOf(rbOrigin.y, pos.y),
                            )
                            val hitIds = itemCoordsMap
                                .filter { (_, coords) ->
                                    coords.isAttached &&
                                        container.localBoundingBoxOf(coords, clipBounds = false).overlaps(selRect)
                                }
                                .keys
                                .toSet()
                            onSelectEntries(rubberBandBaseSelection + hitIds)
                        }
                    }
                    // Release: 结束
                    .onPointerEvent(PointerEventType.Release) { event ->
                        val pos = event.changes.firstOrNull()?.position
                        val container = containerCoordinates
                        if (!isRubberBanding && pendingDragOrigin != null && pos != null && container != null) {
                            // 这是一次点击（不是拖拽）：若 release 位置不在任何 item 上，清空选择
                            val hitItem = itemCoordsMap.entries.find { (_, coords) ->
                                coords.isAttached && container.localBoundingBoxOf(coords, clipBounds = false).contains(pos)
                            }
                            if (hitItem == null) {
                                onSelectEntries(emptySet())
                            }
                        }
                        pendingDragOrigin = null
                        isFileDragging = false
                        if (isRubberBanding) {
                            isRubberBanding = false
                            rubberBandStart = null
                            rubberBandEnd = null
                        }
                    },
            ) {
                // 框选跟踪覆盖层：从文件行触发框选时接管后续拖拽
                Box(
                    modifier = Modifier.fillMaxSize().then(
                        if (isRubberBanding) {
                            Modifier.pointerInput(Unit) {
                                awaitPointerEventScope {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    var current = down
                                    while (current.pressed) {
                                        rubberBandEnd = current.position
                                        val selRect = androidx.compose.ui.geometry.Rect(
                                            left = minOf(rubberBandStart!!.x, current.position.x),
                                            top = minOf(rubberBandStart!!.y, current.position.y),
                                            right = maxOf(rubberBandStart!!.x, current.position.x),
                                            bottom = maxOf(rubberBandStart!!.y, current.position.y),
                                        )
                                        val hitIds = itemCoordsMap
                                            .filter { (_, coords) ->
                                                coords.isAttached &&
                                                    containerCoordinates?.localBoundingBoxOf(coords, clipBounds = false)
                                                        ?.overlaps(selRect) == true
                                            }
                                            .keys
                                            .toSet()
                                        onSelectEntries(rubberBandBaseSelection + hitIds)
                                        val next = awaitDragOrCancellation(current.id) ?: break
                                        current = next
                                        current.consume()
                                    }
                                    isRubberBanding = false
                                    rubberBandStart = null
                                    rubberBandEnd = null
                                }
                            }
                        } else Modifier
                    )
                )
                Column(modifier = Modifier.fillMaxSize()) {
                    val horizontalScrollState = rememberScrollState()

                    // ── Column headers ─────────────────────────────────────
                    if (viewMode == ViewMode.DETAILS) {
                        DetailsHeader(
                            columns = columns,
                            columnWidths = columnWeights,
                            hiddenColumns = hiddenColumns,
                            sort = sort,
                            onToggleSort = onToggleSort,
                            onResizeColumn = onResizeColumn,
                            onToggleColumnVisibility = onToggleColumnVisibility,
                            scrollState = horizontalScrollState,
                        )
                    }

                    // ── File list ──────────────────────────────────────────
                    if (viewMode == ViewMode.GALLERY) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            val gridState = rememberLazyGridState()
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(galleryItemSizeDp.dp),
                                state = gridState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(4.dp),
                                userScrollEnabled = !contextMenuVisible,
                            ) {
                                if (shouldCreateInlineEntry) {
                                    item(
                                        key = "inline-create",
                                        span = { androidx.compose.foundation.lazy.grid.GridItemSpan(1) }) {
                                        GalleryItem(
                                            entry = null,
                                            draftName = inlineEditDraftName,
                                            selected = false,
                                            selectedEntryCount = 0,
                                            paneActive = paneActive,
                                            onActivate = onActivate,
                                            onOpenEntry = onOpenEntry,
                                            onSelectEntry = onSelectEntry,
                                            paneId = paneId,
                                            fileDropTarget = fileDropTarget,
                                            onStartFileDrag = wrappedOnStartFileDrag,
                                            onFileDragPositionChange = onFileDragPositionChange,
                                            onFileDragEnd = wrappedOnFileDragEnd,
                                            onFileDropZoneChange = onFileDropZoneChange,
                                            onShowContextMenu = onShowContextMenu,
                                            onDismissContextMenu = onDismissContextMenu,
                                            onUpdateInlineEditDraft = onUpdateInlineEditDraft,
                                            onConfirmInlineEdit = onConfirmInlineEdit,
                                            onCancelInlineEdit = onCancelInlineEdit,
                                            galleryItemSizeDp = galleryItemSizeDp,
                                            onStartRubberBand = wrappedOnStartRubberBand,
                                            loadThumbnail = loadThumbnail,
                                            loadArchiveThumbnail = loadArchiveThumbnail,
                                        )
                                    }
                                }
                                gridItemsIndexed(
                                    items = state.entries,
                                    key = { _, entry -> entry.id },
                                ) { _, entry ->
                                    val isRenamingEntry =
                                        inlineEditMode == PaneInlineEditMode.RENAME && inlineTargetEntryId == entry.id
                                    Box(modifier = Modifier.onGloballyPositioned { coords ->
                                        itemCoordsMap[entry.id] = coords
                                    }) {
                                        GalleryItem(
                                            entry = entry,
                                            draftName = if (isRenamingEntry) inlineEditDraftName else null,
                                            selected = selectedEntryIds.contains(entry.id),
                                            selectedEntryCount = selectedEntryIds.size,
                                            paneActive = paneActive,
                                            onActivate = onActivate,
                                            onOpenEntry = onOpenEntry,
                                            onSelectEntry = onSelectEntry,
                                            paneId = paneId,
                                            fileDropTarget = fileDropTarget,
                                            onStartFileDrag = wrappedOnStartFileDrag,
                                            onFileDragPositionChange = onFileDragPositionChange,
                                            onFileDragEnd = wrappedOnFileDragEnd,
                                            onFileDropZoneChange = onFileDropZoneChange,
                                            onShowContextMenu = onShowContextMenu,
                                            onDismissContextMenu = onDismissContextMenu,
                                            onUpdateInlineEditDraft = if (isRenamingEntry) onUpdateInlineEditDraft else null,
                                            onConfirmInlineEdit = if (isRenamingEntry) onConfirmInlineEdit else null,
                                            onCancelInlineEdit = if (isRenamingEntry) onCancelInlineEdit else null,
                                            galleryItemSizeDp = galleryItemSizeDp,
                                            onStartRubberBand = wrappedOnStartRubberBand,
                                            onBeginRename = onBeginRename,
                                            loadThumbnail = loadThumbnail,
                                            loadArchiveThumbnail = loadArchiveThumbnail,
                                        )
                                    }
                                }
                            }
                            VerticalScrollbar(
                                adapter = rememberScrollbarAdapter(gridState),
                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            )
                            // Auto-scroll to focused entry on navigation (one-shot)
                            if (pendingScrollToEntryId != null) {
                                LaunchedEffect(pendingScrollToEntryId) {
                                    val offset = if (shouldCreateInlineEntry) 1 else 0
                                    val index = state.entries.indexOfFirst { it.id == pendingScrollToEntryId }
                                    if (index >= 0) {
                                        gridState.animateScrollToItem(index + offset)
                                    }
                                    onConsumeScroll()
                                }
                            }
                        }
                    } else {
                        // DETAILS 模式：支持内联展开的扁平化树列表
                        val flattenedEntries = remember(
                            state.entries, inlineExpandedLocations, inlineExpandedEntries,
                        ) {
                            flattenEntries(
                                entries = state.entries,
                                expandedLocations = inlineExpandedLocations,
                                expandedEntries = inlineExpandedEntries,
                                depth = 0,
                            )
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            val listState = rememberLazyListState()
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize()
                                    .padding(bottom = 12.dp),
                                contentPadding = PaddingValues(bottom = 4.dp),
                                userScrollEnabled = !contextMenuVisible,
                            ) {
                                if (shouldCreateInlineEntry) {
                                    item(key = "inline-create") {
                                        InlineEditEntryRow(
                                            columns = columns,
                                            columnWidths = columnWeights,
                                            draftName = inlineEditDraftName,
                                            iconKey = if (inlineEditMode == PaneInlineEditMode.CREATE_DIRECTORY) {
                                                AllIconsKeys.Nodes.Folder
                                            } else {
                                                AllIconsKeys.FileTypes.Any_type
                                            },
                                            selected = false,
                                            zebra = false,
                                            onUpdateInlineEditDraft = onUpdateInlineEditDraft,
                                            onConfirmInlineEdit = onConfirmInlineEdit,
                                            onCancelInlineEdit = onCancelInlineEdit,
                                            onDismissContextMenu = onDismissContextMenu,
                                            scrollState = horizontalScrollState,
                                        )
                                    }
                                }
                                itemsIndexed(
                                    items = flattenedEntries,
                                    key = { _, item -> "${item.entry.location}@${item.depth}" },
                                ) { index, flatItem ->
                                    val entry = flatItem.entry
                                    val isRenamingEntry = inlineEditMode == PaneInlineEditMode.RENAME &&
                                            inlineTargetEntryId == entry.id
                                    Box(modifier = Modifier.onGloballyPositioned { coords ->
                                        itemCoordsMap[entry.id] = coords
                                    }) {
                                        if (isRenamingEntry) {
                                            InlineEditEntryRow(
                                                columns = columns,
                                                columnWidths = columnWeights,
                                                draftName = inlineEditDraftName,
                                                iconKey = if (entry.kind == VFileKind.DIRECTORY) {
                                                    AllIconsKeys.Nodes.Folder
                                                } else {
                                                    AllIconsKeys.FileTypes.Any_type
                                                },
                                                selected = selectedEntryIds.contains(entry.id),
                                                zebra = index % 2 == 1,
                                                onUpdateInlineEditDraft = onUpdateInlineEditDraft,
                                                onConfirmInlineEdit = onConfirmInlineEdit,
                                                onCancelInlineEdit = onCancelInlineEdit,
                                                onDismissContextMenu = onDismissContextMenu,
                                                scrollState = horizontalScrollState,
                                            )
                                        } else {
                                            EntryRow(
                                                columns = columns,
                                                columnWidths = columnWeights,
                                                entry = entry,
                                                zebra = index % 2 == 1,
                                                selected = selectedEntryIds.contains(entry.id),
                                                selectedEntryCount = selectedEntryIds.size,
                                                paneActive = paneActive,
                                                onActivate = onActivate,
                                                onOpenEntry = onOpenEntry,
                                                onSelectEntry = onSelectEntry,
                                                onBeginRename = onBeginRename,
                                                paneId = paneId,
                                                fileDropTarget = fileDropTarget,
                                                onStartFileDrag = wrappedOnStartFileDrag,
                                                onFileDragPositionChange = onFileDragPositionChange,
                                                onFileDragEnd = wrappedOnFileDragEnd,
                                                onFileDropZoneChange = onFileDropZoneChange,
                                                onShowContextMenu = onShowContextMenu,
                                                onDismissContextMenu = onDismissContextMenu,
                                                onStartRubberBand = wrappedOnStartRubberBand,
                                                scrollState = horizontalScrollState,
                                                depth = flatItem.depth,
                                                isExpanded = flatItem.isExpanded,
                                                isExpandable = flatItem.isExpandable,
                                                isExpandLoading = flatItem.isLoading,
                                                onToggleInlineExpand = if (flatItem.isExpandable) {
                                                    { onToggleInlineExpand(entry.location) }
                                                } else null,
                                            )
                                        }
                                    }
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
                            // Auto-scroll to focused entry on navigation (one-shot)
                            if (pendingScrollToEntryId != null) {
                                LaunchedEffect(pendingScrollToEntryId) {
                                    val offset = if (shouldCreateInlineEntry) 1 else 0
                                    val index = flattenedEntries.indexOfFirst { it.entry.id == pendingScrollToEntryId }
                                    if (index >= 0) {
                                        listState.animateScrollToItem(index + offset)
                                    }
                                    onConsumeScroll()
                                }
                            }
                        }
                    }
                }

                // ── 橡皮筋选择框绘制 ──
                val rbStart = rubberBandStart
                val rbEnd = rubberBandEnd
                if (rbStart != null && rbEnd != null) {
                    val left = minOf(rbStart.x, rbEnd.x)
                    val top = minOf(rbStart.y, rbEnd.y)
                    val w = maxOf(rbStart.x, rbEnd.x) - left
                    val h = maxOf(rbStart.y, rbEnd.y) - top
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                            .size(
                                width = with(LocalDensity.current) { w.toDp() },
                                height = with(LocalDensity.current) { h.toDp() },
                            )
                            .background(
                                LocalOnyxPalette.current.selectionBackground.copy(alpha = 0.25f),
                                RoundedCornerShape(2.dp),
                            )
                            .border(
                                1.dp,
                                LocalOnyxPalette.current.outline.copy(alpha = 0.6f),
                                RoundedCornerShape(2.dp),
                            )
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun InlineEditEntryRow(
    columns: List<DetailsColumn>,
    columnWidths: Map<DetailsColumn, Float>,
    draftName: String,
    iconKey: org.jetbrains.jewel.ui.icon.IconKey,
    selected: Boolean,
    zebra: Boolean,
    onUpdateInlineEditDraft: (String) -> Unit,
    onConfirmInlineEdit: () -> Unit,
    onCancelInlineEdit: () -> Unit,
    onDismissContextMenu: () -> Unit,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
) {
    val focusRequester = remember { FocusRequester() }
    var hasFocused by remember { mutableStateOf(false) }
    val rowBackground by animateColorAsState(
        targetValue = when {
            selected -> LocalOnyxPalette.current.selectionBackground
            zebra -> LocalOnyxPalette.current.surfaceVariant
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 120),
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Row(
        modifier = Modifier
            .horizontalScroll(scrollState)
            .background(rowBackground)
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .height(IntrinsicSize.Min)
            .onPointerEvent(PointerEventType.Press) {
                if (it.buttons.isSecondaryPressed) {
                    onDismissContextMenu()
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter -> {
                        onConfirmInlineEdit()
                        true
                    }

                    Key.Escape -> {
                        onCancelInlineEdit()
                        true
                    }

                    else -> false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DetailsColumnGap),
    ) {
        visibleDetailsColumns(columns).forEach { column ->
            val colWidth = detailsColumnWidth(columnWidths, column).dp
            when (column) {
                DetailsColumn.NAME -> {
                    Row(
                        modifier = Modifier.width(colWidth).height(22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(key = iconKey, contentDescription = null)
                        BasicTextField(
                            value = draftName,
                            onValueChange = {
                                onUpdateInlineEditDraft(it)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .border(1.dp, LocalOnyxPalette.current.outline, RoundedCornerShape(2.dp))
                                .background(LocalOnyxPalette.current.surface, RoundedCornerShape(2.dp))
                                .padding(horizontal = 4.dp)
                                .focusRequester(focusRequester)
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        hasFocused = true
                                    } else if (hasFocused) {
                                        // 失焦时取消编辑，避免 Alt+Tab 等场景下误提交空名称
                                        onCancelInlineEdit()
                                    }
                                },
                            textStyle = TextStyle(
                                fontSize = 12.sp,
                                color = LocalOnyxPalette.current.foreground,
                            ),
                            singleLine = true,
                            cursorBrush = SolidColor(LocalOnyxPalette.current.foreground),
                        )
                    }
                }

                DetailsColumn.SIZE -> {
                    Text(
                        text = "-",
                        modifier = Modifier.width(colWidth),
                        fontSize = 12.sp,
                        color = LocalOnyxPalette.current.mutedForeground,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                DetailsColumn.MODIFIED -> {
                    Text(
                        text = "-",
                        modifier = Modifier.width(colWidth),
                        fontSize = 12.sp,
                        color = LocalOnyxPalette.current.mutedForeground,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DetailsColumn.TYPE -> {
                    Text(
                        text = "-",
                        modifier = Modifier.width(colWidth),
                        fontSize = 12.sp,
                        color = LocalOnyxPalette.current.mutedForeground,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ── Details header ──────────────────────────────────────────────────────────

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DetailsHeader(
    columns: List<DetailsColumn>,
    columnWidths: Map<DetailsColumn, Float>,
    hiddenColumns: Set<DetailsColumn>,
    sort: DetailsSort,
    onToggleSort: (DetailsColumn) -> Unit,
    onResizeColumn: (DetailsColumn, DetailsColumn, Float) -> Unit,
    onToggleColumnVisibility: (DetailsColumn) -> Unit,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
) {
    val density = LocalDensity.current
    val visibleColumns = remember(columns, hiddenColumns) { visibleDetailsColumns(columns, hiddenColumns) }
    var showColumnMenu by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .background(LocalOnyxPalette.current.headerBackground)
                .height(LocalOnyxAppearance.current.headerHeight)
                .padding(horizontal = 8.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { showColumnMenu = true },
                    )
                }
                .onPointerEvent(PointerEventType.Press) {
                    // 右键点击打开列可见性菜单
                    if (it.buttons.isSecondaryPressed) {
                        showColumnMenu = true
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            visibleColumns.forEachIndexed { index, column ->
                val nextColumn = visibleColumns.getOrNull(index + 1)
                val colWidth = detailsColumnWidth(columnWidths, column).dp
                when (column) {
                    DetailsColumn.NAME -> SortHeaderCell(
                        text = stringResource(Res.string.label_column_name),
                        sortHint = sortHint(column, sort),
                        modifier = Modifier.width(colWidth),
                        onClick = { onToggleSort(column) },
                    )

                    DetailsColumn.SIZE -> SortHeaderCell(
                        text = stringResource(Res.string.label_column_size),
                        sortHint = sortHint(column, sort),
                        modifier = Modifier.width(colWidth),
                        onClick = { onToggleSort(column) },
                    )

                    DetailsColumn.MODIFIED -> SortHeaderCell(
                        text = stringResource(Res.string.label_column_modified),
                        sortHint = sortHint(column, sort),
                        modifier = Modifier.width(colWidth),
                        onClick = { onToggleSort(column) },
                    )
                    DetailsColumn.TYPE -> SortHeaderCell(
                        text = stringResource(Res.string.label_column_type),
                        sortHint = sortHint(column, sort),
                        modifier = Modifier.width(colWidth),
                        onClick = { onToggleSort(column) },
                    )
                }
                if (nextColumn != null) {
                    DetailsColumnResizeGap(
                        onResize = { deltaPx ->
                            val deltaDp = with(density) { deltaPx / this.density }
                            onResizeColumn(column, nextColumn, deltaDp)
                        },
                    )
                }
            }
        }

        // 列可见性右键菜单
        if (showColumnMenu) {
            androidx.compose.ui.window.Popup(
                onDismissRequest = { showColumnMenu = false },
                alignment = Alignment.TopStart,
            ) {
                Column(
                    modifier = Modifier
                        .background(LocalOnyxPalette.current.floatingSurface, RoundedCornerShape(4.dp))
                        .border(1.dp, LocalOnyxPalette.current.outline, RoundedCornerShape(4.dp))
                        .padding(4.dp)
                        .width(160.dp),
                ) {
                    // 菜单标题
                    Text(
                        text = stringResource(Res.string.label_column_visibility),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = LocalOnyxPalette.current.mutedForeground,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    columns.forEach { column ->
                        val isVisible = column !in hiddenColumns
                        val isName = column == DetailsColumn.NAME
                        val label = when (column) {
                            DetailsColumn.NAME -> stringResource(Res.string.label_column_name)
                            DetailsColumn.TYPE -> stringResource(Res.string.label_column_type)
                            DetailsColumn.SIZE -> stringResource(Res.string.label_column_size)
                            DetailsColumn.MODIFIED -> stringResource(Res.string.label_column_modified)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isName) {
                                    onToggleColumnVisibility(column)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = if (isVisible) "✓" else " ",
                                fontSize = 12.sp,
                                color = if (isName) LocalOnyxPalette.current.disabledForeground
                                        else LocalOnyxPalette.current.accent,
                            )
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                color = if (isName) LocalOnyxPalette.current.disabledForeground
                                        else LocalOnyxPalette.current.foreground,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SortHeaderCell(
    text: String,
    sortHint: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    textAlign: TextAlign = TextAlign.Start,
) {
    Box(modifier = modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (textAlign == TextAlign.End) Arrangement.End else Arrangement.Start,
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
                fontSize = LocalOnyxAppearance.current.headerFontSize,
                color = LocalOnyxPalette.current.mutedForeground,
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sortHint != null) {
                Icon(
                    key = if (sortHint == "▲") AllIconsKeys.General.ArrowUp else AllIconsKeys.General.ArrowDown,
                    contentDescription = sortHint,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
internal fun DetailsColumnResizeGap(
    onResize: (Float) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(DetailsColumnGap)
            .pointerHoverIcon(horizontalResizePointerIcon())
            .pointerInput(Unit) {
                detectDragGestures { _, dragAmount ->
                    onResize(dragAmount.x)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(LocalOnyxPalette.current.outlineVariant),
        )
    }
}

// ── Entry row (high-density, single-line) ───────────────────────────────────

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun EntryRow(
    columns: List<DetailsColumn>,
    columnWidths: Map<DetailsColumn, Float>,
    entry: VFile,
    zebra: Boolean,
    selected: Boolean,
    selectedEntryCount: Int,
    paneActive: Boolean,
    onActivate: () -> Unit,
    onOpenEntry: (VFile) -> Unit,
    onSelectEntry: (String, Boolean, Boolean) -> Unit,
    onBeginRename: () -> Unit = {},
    paneId: PaneId,
    fileDropTarget: FileDropTarget?,
    onStartFileDrag: (PaneId, FileTransferOperation) -> Unit,
    onFileDragPositionChange: (IntOffset) -> Unit,
    onFileDragEnd: (IntOffset?) -> Unit,
    onFileDropZoneChange: (FileDropZone) -> Unit,
    onShowContextMenu: (String, Boolean, IntOffset) -> Unit,
    onDismissContextMenu: () -> Unit,
    onStartRubberBand: (String, androidx.compose.ui.geometry.Offset, Boolean) -> Unit = { _, _, _ -> },
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    depth: Int = 0,
    isExpanded: Boolean = false,
    isExpandable: Boolean = false,
    isExpandLoading: Boolean = false,
    onToggleInlineExpand: (() -> Unit)? = null,
) {
    var additiveSelection by remember { mutableStateOf(false) }
    // 用 rememberUpdatedState 捕获最新的 selected 值，避免 pointerInput 重启
    val currentSelected by androidx.compose.runtime.rememberUpdatedState(selected)
    var rangeSelection by remember { mutableStateOf(false) }
    var dragOperation by remember { mutableStateOf(FileTransferOperation.MOVE) }
    var dragPosition by remember { mutableStateOf<IntOffset?>(null) }
    var rowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var nameAreaCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var expandArrowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val isDirectoryDropTarget = fileDropTarget?.directoryEntryId == entry.id
    var pendingDeselectOthers by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var renameTimerJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val appearance = LocalOnyxAppearance.current
    val rowBackground by animateColorAsState(
        targetValue = when {
            isDirectoryDropTarget -> LocalOnyxPalette.current.rowHoverBackground
            selected && paneActive -> LocalOnyxPalette.current.selectionBackground
            selected && !paneActive -> LocalOnyxPalette.current.inactiveSelectionBackground
            zebra && appearance.zebraStripeEnabled -> appearance.zebraStripeColor ?: LocalOnyxPalette.current.surfaceVariant
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 120),
    )

    // 判断 Row 本地坐标是否落在展开箭头区域
    fun isInExpandArrow(positionInRow: androidx.compose.ui.geometry.Offset): Boolean {
        val arrowCoords = expandArrowCoords ?: return false
        val rowCoords = rowCoordinates ?: return false
        if (!arrowCoords.isAttached) return false
        val arrowRect = rowCoords.localBoundingBoxOf(arrowCoords, clipBounds = false)
        return arrowRect.contains(positionInRow)
    }

    Row(
        modifier = Modifier
            .horizontalScroll(scrollState)
            .onGloballyPositioned { coordinates ->
                rowCoordinates = coordinates
            }
            .onPointerEvent(PointerEventType.Press) { event ->
                additiveSelection = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                rangeSelection = event.keyboardModifiers.isShiftPressed
                dragOperation = if (event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed) {
                    FileTransferOperation.COPY
                } else {
                    FileTransferOperation.MOVE
                }
                val pointerPosition = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                when {
                    event.buttons.isSecondaryPressed -> {
                        renameTimerJob?.cancel()
                        renameTimerJob = null
                        val windowPosition = rowCoordinates?.localToWindow(pointerPosition) ?: pointerPosition
                        onActivate()
                        onShowContextMenu(
                            entry.id,
                            selected,
                            IntOffset(windowPosition.x.roundToInt(), windowPosition.y.roundToInt()),
                        )
                    }

                    event.buttons.isPrimaryPressed -> {
                        // 检测是否点击在展开箭头区域
                        if (onToggleInlineExpand != null && isInExpandArrow(pointerPosition)) {
                            onActivate()
                            onToggleInlineExpand()
                            return@onPointerEvent
                        }

                        onActivate()
                        onDismissContextMenu()
                        pendingDeselectOthers = false

                        if (additiveSelection || rangeSelection) {
                            // 修饰键点击：立即处理（Ctrl切换/Shift范围）
                            onSelectEntry(entry.id, additiveSelection, rangeSelection)
                        } else if (selectedEntryCount > 1) {
                            // 多选中无修饰键点击：延迟到 release 再取消其它选中
                            pendingDeselectOthers = true
                        } else {
                            // 单选或无选中：立即选中
                            onSelectEntry(entry.id, false, false)
                        }

                        // 慢速双击重命名逻辑
                        val canRename = selected && selectedEntryCount == 1 &&
                                !additiveSelection && !rangeSelection
                        if (canRename) {
                            renameTimerJob?.cancel()
                            renameTimerJob = coroutineScope.launch {
                                kotlinx.coroutines.delay(500)
                                onBeginRename()
                            }
                        } else {
                            renameTimerJob?.cancel()
                            renameTimerJob = null
                        }
                    }
                }
            }
            .pointerInput(entry.id, paneId) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startPos = down.position
                        var dragStarted = false
                        // 在按下瞬间捕获选中状态（避免 onPointerEvent(Press) 先选中后导致误判）
                        val wasSelectedAtPress = currentSelected

                        // 判断按下位置是否在名称列范围内
                        val nameCoords = nameAreaCoords
                        val rowCoords = rowCoordinates
                        val inNameColumn = if (nameCoords != null && rowCoords != null && nameCoords.isAttached) {
                            val nameRect = rowCoords.localBoundingBoxOf(nameCoords, clipBounds = false)
                            nameRect.contains(startPos)
                        } else {
                            // 若无法判定范围，保守回退为名称列行为
                            true
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                if (dragStarted) {
                                    onFileDragEnd(dragPosition)
                                    dragPosition = null
                                } else if (pendingDeselectOthers) {
                                    onSelectEntry(entry.id, false, false)
                                    pendingDeselectOthers = false
                                }
                                break
                            }

                            val dx = change.position.x - startPos.x
                            val dy = change.position.y - startPos.y
                            if (!dragStarted && dx * dx + dy * dy > 36f) {
                                dragStarted = true
                                pendingDeselectOthers = false
                                renameTimerJob?.cancel()
                                renameTimerJob = null
                                change.consume()

                                if (wasSelectedAtPress || inNameColumn) {
                                    // 按下时已选中 或 名称列内拖拽 → 文件拖动
                                    if (!wasSelectedAtPress) {
                                        // 未选中但在名称列：自动选中再拖拽（Dolphin 行为）
                                        onSelectEntry(entry.id, false, false)
                                    }
                                    dragPosition = rowCoordinates?.localToWindow(change.position)?.toIntOffset()
                                    onStartFileDrag(paneId, dragOperation)
                                    dragPosition?.let(onFileDragPositionChange)
                                } else {
                                    // 按下时未选中 + 非名称列拖拽 → 启动框选，退出循环让 overlay 接管
                                    val addSel = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                                    val windowStartPos = rowCoordinates?.localToWindow(startPos) ?: startPos
                                    onStartRubberBand(entry.id, windowStartPos, addSel)
                                    break
                                }
                            } else if (dragStarted) {
                                change.consume()
                                if (wasSelectedAtPress || inNameColumn) {
                                    // 文件拖动：持续更新位置
                                    dragPosition = rowCoordinates?.localToWindow(change.position)?.toIntOffset()
                                    dragPosition?.let(onFileDragPositionChange)
                                }
                            }
                        }
                    }
                }
            }
            .background(rowBackground)
            .combinedClickable(
                onClick = {
                    onActivate()
                },
                onDoubleClick = {
                    // 双击时取消重命名定时器
                    renameTimerJob?.cancel()
                    renameTimerJob = null
                    onActivate()
                    onDismissContextMenu()
                    // 双击箭头区域不导航
                    if (onToggleInlineExpand != null) {
                        // 双击时无法精确获取位置，但 Press 已处理了箭头逻辑
                        // 这里仍然走导航逻辑（因为快速双击箭头的场景极少）
                    }
                    onOpenEntry(entry)
                },
            )
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .height(LocalOnyxAppearance.current.listRowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DetailsColumnGap),
    ) {
        visibleDetailsColumns(columns).forEach { column ->
            val colWidth = detailsColumnWidth(columnWidths, column).dp
            when (column) {
                DetailsColumn.NAME -> {
                    Row(
                        modifier = Modifier.width(colWidth),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 缩进
                        if (depth > 0) {
                            Spacer(Modifier.width((depth * 20).dp))
                        }

                        // 展开/折叠箭头
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .onGloballyPositioned { coordinates ->
                                    expandArrowCoords = coordinates
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                isExpandLoading -> {
                                    Text(
                                        text = "…",
                                        fontSize = 10.sp,
                                        color = LocalOnyxPalette.current.disabledForeground,
                                    )
                                }
                                isExpandable -> {
                                    Icon(
                                        key = if (isExpanded) AllIconsKeys.General.ArrowDown
                                              else AllIconsKeys.General.ArrowRight,
                                        contentDescription = null,
                                        modifier = Modifier.size(10.dp),
                                    )
                                }
                                else -> {
                                    // 文件：占位保持对齐
                                }
                            }
                        }

                        Spacer(Modifier.width(2.dp))

                        Row(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .onGloballyPositioned { coordinates ->
                                    nameAreaCoords = coordinates
                                    if (entry.kind == VFileKind.DIRECTORY) {
                                        onFileDropZoneChange(
                                            FileDropZone(
                                                paneId = paneId,
                                                targetDirectoryLocation = entry.location,
                                                bounds = coordinates.windowBounds(),
                                                directoryEntryId = entry.id,
                                            )
                                        )
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                key = if (entry.kind == VFileKind.DIRECTORY) AllIconsKeys.Nodes.Folder else fileIconKey(entry.name),
                                contentDescription = null,
                            )
                            Text(
                                text = entry.name,
                                fontWeight = if (entry.kind == VFileKind.DIRECTORY) FontWeight.Medium else FontWeight.Normal,
                                fontSize = LocalOnyxAppearance.current.listFontSize,
                                color = LocalOnyxPalette.current.foreground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                DetailsColumn.SIZE -> {
                    Text(
                        text = formatFileSize(entry.sizeBytes),
                        modifier = Modifier.width(colWidth),
                        fontSize = LocalOnyxAppearance.current.listFontSize,
                        color = LocalOnyxPalette.current.mutedForeground,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                DetailsColumn.MODIFIED -> {
                    Text(
                        text = formatModifiedTime(entry.modifiedAtEpochMillis),
                        modifier = Modifier.width(colWidth),
                        fontSize = LocalOnyxAppearance.current.listFontSize,
                        color = LocalOnyxPalette.current.mutedForeground,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DetailsColumn.TYPE -> {
                    val typeText = if (entry.kind == VFileKind.DIRECTORY) {
                        stringResource(Res.string.label_directory_badge)
                    } else {
                        val ext = entry.name.substringAfterLast('.', "")
                        ext.ifEmpty { "-" }
                    }
                    Text(
                        text = typeText,
                        modifier = Modifier.width(colWidth),
                        fontSize = LocalOnyxAppearance.current.listFontSize,
                        color = LocalOnyxPalette.current.mutedForeground,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ── 内联展开扁平化 ──────────────────────────────────────────────────────

internal data class FlattenedEntry(
    val entry: VFile,
    val depth: Int,
    val isExpanded: Boolean,
    val isExpandable: Boolean,
    val isLoading: Boolean,
)

/**
 * 将 entries + 展开子项递归扁平化为带 depth 的列表。
 */
internal fun flattenEntries(
    entries: List<VFile>,
    expandedLocations: Set<String>,
    expandedEntries: Map<String, com.oruke.onyx.app.component.InlineExpandedEntry>,
    depth: Int,
): List<FlattenedEntry> {
    return buildList {
        entries.forEach { entry ->
            val isExpandable = entry.kind == VFileKind.DIRECTORY
            val isExpanded = entry.location in expandedLocations
            val expandData = expandedEntries[entry.location]
            val isLoading = isExpanded && expandData?.entries == null

            add(
                FlattenedEntry(
                    entry = entry,
                    depth = depth,
                    isExpanded = isExpanded,
                    isExpandable = isExpandable,
                    isLoading = isLoading,
                )
            )

            // 递归展开子项
            if (isExpanded && expandData?.entries != null) {
                addAll(
                    flattenEntries(
                        entries = expandData.entries,
                        expandedLocations = expandedLocations,
                        expandedEntries = expandedEntries,
                        depth = depth + 1,
                    )
                )
            }
        }
    }
}
