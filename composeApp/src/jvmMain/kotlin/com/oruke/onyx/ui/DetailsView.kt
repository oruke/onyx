package com.oruke.onyx.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
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
                Text(
                    text = "${stringResource(Res.string.label_error_prefix)} ${state.reason.orEmpty()}".trim(),
                    color = LocalOnyxPalette.current.foreground,
                    fontSize = 12.sp,
                )
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
                    modifier = Modifier.fillMaxSize().padding(24.dp),
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
            val itemBoundsMap = remember { mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
            var rubberBandStart by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
            var rubberBandEnd by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
            var containerCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

            Box(
                modifier = Modifier.fillMaxSize()
                    .clipToBounds()
                    .onGloballyPositioned { containerCoordinates = it }
                    .pointerInput(state.entries) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                if (down.type != PointerEventType.Press) continue
                                val change = down.changes.firstOrNull() ?: continue
                                if (!down.buttons.isPrimaryPressed) continue
                                // 仅在空白区域开始框选：检查按下位置是否在任一 item 上
                                val hitPos = change.position
                                val hitItem = itemBoundsMap.any { (_, bounds) -> bounds.contains(hitPos) }
                                if (hitItem) continue
                                // 按下在空白区域 → 开始框选
                                onActivate()
                                onDismissContextMenu()
                                rubberBandStart = hitPos
                                rubberBandEnd = hitPos
                                onSelectEntries(emptySet())

                                // 拖拽循环
                                while (true) {
                                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    val currentChange = event.changes.firstOrNull() ?: break
                                    if (event.type == PointerEventType.Move) {
                                        rubberBandEnd = currentChange.position
                                        currentChange.consume()
                                        // 计算框选矩形
                                        val start = rubberBandStart ?: break
                                        val selRect = androidx.compose.ui.geometry.Rect(
                                            left = minOf(start.x, rubberBandEnd!!.x),
                                            top = minOf(start.y, rubberBandEnd!!.y),
                                            right = maxOf(start.x, rubberBandEnd!!.x),
                                            bottom = maxOf(start.y, rubberBandEnd!!.y),
                                        )
                                        val hitIds = itemBoundsMap
                                            .filter { (_, bounds) -> bounds.overlaps(selRect) }
                                            .keys
                                            .toSet()
                                        onSelectEntries(hitIds)
                                    }
                                    if (event.type == PointerEventType.Release || !currentChange.pressed) {
                                        rubberBandStart = null
                                        rubberBandEnd = null
                                        break
                                    }
                                }
                            }
                        }
                    },
            ) {
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
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(galleryItemSizeDp.dp),
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
                                        onStartFileDrag = onStartFileDrag,
                                        onFileDragPositionChange = onFileDragPositionChange,
                                        onFileDragEnd = onFileDragEnd,
                                        onFileDropZoneChange = onFileDropZoneChange,
                                        onShowContextMenu = onShowContextMenu,
                                        onDismissContextMenu = onDismissContextMenu,
                                        onUpdateInlineEditDraft = onUpdateInlineEditDraft,
                                        onConfirmInlineEdit = onConfirmInlineEdit,
                                        onCancelInlineEdit = onCancelInlineEdit,
                                        galleryItemSizeDp = galleryItemSizeDp,
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
                                    containerCoordinates?.let { parent ->
                                        val local = parent.localBoundingBoxOf(coords, clipBounds = false)
                                        itemBoundsMap[entry.id] = local
                                    }
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
                                        onStartFileDrag = onStartFileDrag,
                                        onFileDragPositionChange = onFileDragPositionChange,
                                        onFileDragEnd = onFileDragEnd,
                                        onFileDropZoneChange = onFileDropZoneChange,
                                        onShowContextMenu = onShowContextMenu,
                                        onDismissContextMenu = onDismissContextMenu,
                                        onUpdateInlineEditDraft = if (isRenamingEntry) onUpdateInlineEditDraft else null,
                                        onConfirmInlineEdit = if (isRenamingEntry) onConfirmInlineEdit else null,
                                        onCancelInlineEdit = if (isRenamingEntry) onCancelInlineEdit else null,
                                        galleryItemSizeDp = galleryItemSizeDp,
                                    )
                                }
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
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
                                    items = state.entries,
                                    key = { _, entry -> entry.id },
                                ) { index, entry ->
                                    val isRenamingEntry = inlineEditMode == PaneInlineEditMode.RENAME &&
                                            inlineTargetEntryId == entry.id
                                    Box(modifier = Modifier.onGloballyPositioned { coords ->
                                        containerCoordinates?.let { parent ->
                                            val local = parent.localBoundingBoxOf(coords, clipBounds = false)
                                            itemBoundsMap[entry.id] = local
                                        }
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
                                                onStartFileDrag = onStartFileDrag,
                                                onFileDragPositionChange = onFileDragPositionChange,
                                                onFileDragEnd = onFileDragEnd,
                                                onFileDropZoneChange = onFileDropZoneChange,
                                                onShowContextMenu = onShowContextMenu,
                                                onDismissContextMenu = onDismissContextMenu,
                                                scrollState = horizontalScrollState,
                                            )
                                        }
                                    }
                                }
                            }
                            HorizontalScrollbar(
                                adapter = rememberScrollbarAdapter(horizontalScrollState),
                                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                            )
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
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
) {
    var additiveSelection by remember { mutableStateOf(false) }
    var rangeSelection by remember { mutableStateOf(false) }
    var dragOperation by remember { mutableStateOf(FileTransferOperation.MOVE) }
    var dragPosition by remember { mutableStateOf<IntOffset?>(null) }
    var rowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val isDirectoryDropTarget = fileDropTarget?.directoryEntryId == entry.id
    val preserveMultiSelectionForDrag = selected && selectedEntryCount > 1
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
                        onActivate()
                        onDismissContextMenu()
                        // 慢速双击重命名逻辑：仅当已经是单选 + 当前项已选中 + 无修饰键时启动定时器
                        val wasSingleSelected = selected && selectedEntryCount == 1 &&
                                !additiveSelection && !rangeSelection
                        if (!preserveMultiSelectionForDrag || additiveSelection || rangeSelection) {
                            onSelectEntry(entry.id, additiveSelection, rangeSelection)
                        }
                        if (wasSingleSelected && !additiveSelection && !rangeSelection) {
                            // 启动 500ms 慢速重命名定时器
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
                detectDragGestures(
                    onDragStart = { offset ->
                        renameTimerJob?.cancel()
                        renameTimerJob = null
                        dragPosition = rowCoordinates?.localToWindow(offset)?.toIntOffset()
                        onStartFileDrag(paneId, dragOperation)
                        dragPosition?.let(onFileDragPositionChange)
                    },
                    onDragCancel = {
                        dragPosition = null
                        onFileDragEnd(null)
                    },
                    onDragEnd = {
                        onFileDragEnd(dragPosition)
                        dragPosition = null
                    },
                    onDrag = { change, _ ->
                        dragPosition = rowCoordinates?.localToWindow(change.position)?.toIntOffset()
                        dragPosition?.let(onFileDragPositionChange)
                    },
                )
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
                        modifier = Modifier
                            .width(colWidth)
                            .onGloballyPositioned { coordinates ->
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
                            modifier = Modifier.weight(1f),
                            fontWeight = if (entry.kind == VFileKind.DIRECTORY) FontWeight.Medium else FontWeight.Normal,
                            fontSize = LocalOnyxAppearance.current.listFontSize,
                            color = LocalOnyxPalette.current.foreground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
