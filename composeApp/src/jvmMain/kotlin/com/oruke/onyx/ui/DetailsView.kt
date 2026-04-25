package com.oruke.onyx.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.detailsColumnWeight
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
import onyx.composeapp.generated.resources.label_error_prefix
import onyx.composeapp.generated.resources.label_loading_entries
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed

// ── File entries ────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun PaneEntriesContent(
    viewMode: ViewMode,
    columns: List<DetailsColumn>,
    columnWeights: Map<DetailsColumn, Float>,
    sort: DetailsSort,
    selectedEntryIds: Set<String>,
    state: PaneEntriesState,
    paneActive: Boolean,
    contextMenuVisible: Boolean,
    onActivate: () -> Unit,
    onOpenEntry: (VFile) -> Unit,
    onToggleSort: (DetailsColumn) -> Unit,
    onResizeColumn: (DetailsColumn, DetailsColumn, Float) -> Unit,
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
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = stringResource(Res.string.label_empty_directory),
                        color = LocalOnyxPalette.current.mutedForeground,
                        fontSize = 12.sp
                    )
                }
                return
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // ── Column headers ─────────────────────────────────────
                if (viewMode == ViewMode.DETAILS) {
                    DetailsHeader(
                        columns = columns,
                        columnWeights = columnWeights,
                        sort = sort,
                        onToggleSort = onToggleSort,
                        onResizeColumn = onResizeColumn,
                    )
                }

                // ── File list ──────────────────────────────────────────
                if (viewMode == ViewMode.GALLERY) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(140.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
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
                                )
                            }
                        }
                        gridItemsIndexed(
                            items = state.entries,
                            key = { _, entry -> entry.id },
                        ) { _, entry ->
                            val isRenamingEntry =
                                inlineEditMode == PaneInlineEditMode.RENAME && inlineTargetEntryId == entry.id
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
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 4.dp),
                        userScrollEnabled = !contextMenuVisible,
                    ) {
                        if (shouldCreateInlineEntry) {
                            item(key = "inline-create") {
                                InlineEditEntryRow(
                                    columns = columns,
                                    columnWeights = columnWeights,
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
                                )
                            }
                        }
                        itemsIndexed(
                            items = state.entries,
                            key = { _, entry -> entry.id },
                        ) { index, entry ->
                            val isRenamingEntry = inlineEditMode == PaneInlineEditMode.RENAME &&
                                    inlineTargetEntryId == entry.id
                            if (isRenamingEntry) {
                                InlineEditEntryRow(
                                    columns = columns,
                                    columnWeights = columnWeights,
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
                                )
                            } else {
                                EntryRow(
                                    columns = columns,
                                    columnWeights = columnWeights,
                                    entry = entry,
                                    zebra = index % 2 == 1,
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
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun InlineEditEntryRow(
    columns: List<DetailsColumn>,
    columnWeights: Map<DetailsColumn, Float>,
    draftName: String,
    iconKey: org.jetbrains.jewel.ui.icon.IconKey,
    selected: Boolean,
    zebra: Boolean,
    onUpdateInlineEditDraft: (String) -> Unit,
    onConfirmInlineEdit: () -> Unit,
    onCancelInlineEdit: () -> Unit,
    onDismissContextMenu: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val rowBackground by animateColorAsState(
        targetValue = when {
            selected -> LocalOnyxPalette.current.selectionBackground
            zebra -> LocalOnyxPalette.current.surfaceVariant
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 120),
    )

    LaunchedEffect(draftName) {
        focusRequester.requestFocus()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground)
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .height(22.dp)
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
            when (column) {
                DetailsColumn.NAME -> {
                    Row(
                        modifier = Modifier.weight(detailsColumnWeight(columnWeights, column)),
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
                                .focusRequester(focusRequester),
                            textStyle = TextStyle(
                                fontSize = 12.sp,
                                color = if (selected) LocalOnyxPalette.current.selectionForeground else LocalOnyxPalette.current.foreground,
                            ),
                            singleLine = true,
                        )
                    }
                }

                DetailsColumn.SIZE -> {
                    Text(
                        text = "-",
                        modifier = Modifier.weight(detailsColumnWeight(columnWeights, column)),
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
                        modifier = Modifier.weight(detailsColumnWeight(columnWeights, column)),
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
                        modifier = Modifier.weight(detailsColumnWeight(columnWeights, column)),
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

@Composable
internal fun DetailsHeader(
    columns: List<DetailsColumn>,
    columnWeights: Map<DetailsColumn, Float>,
    sort: DetailsSort,
    onToggleSort: (DetailsColumn) -> Unit,
    onResizeColumn: (DetailsColumn, DetailsColumn, Float) -> Unit,
) {
    var headerWidthPx by remember { mutableStateOf(1) }
    val visibleColumns = remember(columns) { visibleDetailsColumns(columns) }
    val totalWeight = visibleColumns
        .sumOf { column -> detailsColumnWeight(columnWeights, column).toDouble() }
        .toFloat()
        .coerceAtLeast(1f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LocalOnyxPalette.current.headerBackground)
            .height(24.dp)
            .onSizeChanged { headerWidthPx = it.width.coerceAtLeast(1) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visibleColumns.forEachIndexed { index, column ->
            val nextColumn = visibleColumns.getOrNull(index + 1)
            when (column) {
                DetailsColumn.NAME -> SortHeaderCell(
                    text = stringResource(Res.string.label_column_name),
                    sortHint = sortHint(column, sort),
                    modifier = Modifier.weight(detailsColumnWeight(columnWeights, column)),
                    onClick = { onToggleSort(column) },
                )

                DetailsColumn.SIZE -> SortHeaderCell(
                    text = stringResource(Res.string.label_column_size),
                    sortHint = sortHint(column, sort),
                    modifier = Modifier.weight(detailsColumnWeight(columnWeights, column)),
                    onClick = { onToggleSort(column) },
                )

                DetailsColumn.MODIFIED -> SortHeaderCell(
                    text = stringResource(Res.string.label_column_modified),
                    sortHint = sortHint(column, sort),
                    modifier = Modifier.weight(detailsColumnWeight(columnWeights, column)),
                    onClick = { onToggleSort(column) },
                )
                DetailsColumn.TYPE -> SortHeaderCell(
                    text = stringResource(Res.string.label_column_type),
                    sortHint = sortHint(column, sort),
                    modifier = Modifier.weight(detailsColumnWeight(columnWeights, column)),
                    onClick = { onToggleSort(column) },
                )
            }
            if (nextColumn != null) {
                DetailsColumnResizeGap(
                    onResize = { deltaPx ->
                        onResizeColumn(column, nextColumn, deltaPx / headerWidthPx.toFloat() * totalWeight)
                    },
                )
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
                fontSize = 11.sp,
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
    columnWeights: Map<DetailsColumn, Float>,
    entry: VFile,
    zebra: Boolean,
    selected: Boolean,
    selectedEntryCount: Int,
    paneActive: Boolean,
    onActivate: () -> Unit,
    onOpenEntry: (VFile) -> Unit,
    onSelectEntry: (String, Boolean, Boolean) -> Unit,
    paneId: PaneId,
    fileDropTarget: FileDropTarget?,
    onStartFileDrag: (PaneId, FileTransferOperation) -> Unit,
    onFileDragPositionChange: (IntOffset) -> Unit,
    onFileDragEnd: (IntOffset?) -> Unit,
    onFileDropZoneChange: (FileDropZone) -> Unit,
    onShowContextMenu: (String, Boolean, IntOffset) -> Unit,
    onDismissContextMenu: () -> Unit,
) {
    var additiveSelection by remember { mutableStateOf(false) }
    var rangeSelection by remember { mutableStateOf(false) }
    var dragOperation by remember { mutableStateOf(FileTransferOperation.MOVE) }
    var dragPosition by remember { mutableStateOf<IntOffset?>(null) }
    var rowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val isDirectoryDropTarget = fileDropTarget?.directoryEntryId == entry.id
    val preserveMultiSelectionForDrag = selected && selectedEntryCount > 1
    val rowBackground by animateColorAsState(
        targetValue = when {
            isDirectoryDropTarget -> LocalOnyxPalette.current.rowHoverBackground
            selected && paneActive -> LocalOnyxPalette.current.selectionBackground
            selected && !paneActive -> LocalOnyxPalette.current.inactiveSelectionBackground
            zebra -> LocalOnyxPalette.current.surfaceVariant
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 120),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
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
                        if (!preserveMultiSelectionForDrag || additiveSelection || rangeSelection) {
                            onSelectEntry(entry.id, additiveSelection, rangeSelection)
                        }
                    }
                }
            }
            .pointerInput(entry.id, paneId) {
                detectDragGestures(
                    onDragStart = { offset ->
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
                    onActivate()
                    onDismissContextMenu()
                    onOpenEntry(entry)
                },
            )
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .height(22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DetailsColumnGap),
    ) {
        visibleDetailsColumns(columns).forEach { column ->
            when (column) {
                DetailsColumn.NAME -> {
                    Row(
                        modifier = Modifier
                            .weight(detailsColumnWeight(columnWeights, column))
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
                            fontSize = 12.sp,
                            color = LocalOnyxPalette.current.foreground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                DetailsColumn.SIZE -> {
                    Text(
                        text = formatFileSize(entry.sizeBytes),
                        modifier = Modifier.weight(detailsColumnWeight(columnWeights, column)),
                        fontSize = 12.sp,
                        color = LocalOnyxPalette.current.mutedForeground,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                DetailsColumn.MODIFIED -> {
                    Text(
                        text = formatModifiedTime(entry.modifiedAtEpochMillis),
                        modifier = Modifier.weight(detailsColumnWeight(columnWeights, column)),
                        fontSize = 12.sp,
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
                        modifier = Modifier.weight(detailsColumnWeight(columnWeights, column)),
                        fontSize = 12.sp,
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

