package com.oruke.onyx.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.oruke.onyx.app.component.FileTransferOperation
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.ui.theme.FileDropTarget
import com.oruke.onyx.ui.theme.FileDropZone
import com.oruke.onyx.ui.theme.OnyxPalette
import com.oruke.onyx.ui.theme.toIntOffset
import com.oruke.onyx.ui.theme.windowBounds
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlin.math.roundToInt

// ── Gallery item (high-density grid) ────────────────────────────────────────

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun GalleryItem(
    entry: VFile?,
    draftName: String?,
    selected: Boolean,
    selectedEntryCount: Int,
    paneActive: Boolean,
    onActivate: () -> Unit,
    onOpenEntry: (VFile) -> Unit,
    onSelectEntry: (String, Boolean, Boolean) -> Unit,
    palette: OnyxPalette,
    paneId: PaneId,
    fileDropTarget: FileDropTarget?,
    onStartFileDrag: (PaneId, FileTransferOperation) -> Unit,
    onFileDragPositionChange: (IntOffset) -> Unit,
    onFileDragEnd: (IntOffset?) -> Unit,
    onFileDropZoneChange: (FileDropZone) -> Unit,
    onShowContextMenu: (String, Boolean, IntOffset) -> Unit,
    onDismissContextMenu: () -> Unit,
    onUpdateInlineEditDraft: ((String) -> Unit)? = null,
    onConfirmInlineEdit: (() -> Unit)? = null,
    onCancelInlineEdit: (() -> Unit)? = null,
) {
    var additiveSelection by remember { mutableStateOf(false) }
    var rangeSelection by remember { mutableStateOf(false) }
    var dragOperation by remember { mutableStateOf(FileTransferOperation.MOVE) }
    var dragPosition by remember { mutableStateOf<IntOffset?>(null) }
    var rowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val isDirectoryDropTarget = fileDropTarget?.directoryEntryId == entry?.id
    val preserveMultiSelectionForDrag = selected && selectedEntryCount > 1

    val isInlineEdit = draftName != null && onUpdateInlineEditDraft != null
    val focusRequester = remember { FocusRequester() }

    val itemBackground by animateColorAsState(
        targetValue = when {
            isDirectoryDropTarget -> palette.rowHoverBackground
            selected && paneActive -> palette.selectionBackground
            selected && !paneActive -> palette.inactiveSelectionBackground
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 120),
    )

    LaunchedEffect(isInlineEdit, draftName) {
        if (isInlineEdit) {
            focusRequester.requestFocus()
        }
    }

    Column(
        modifier = Modifier
            .padding(4.dp)
            .widthIn(min = 100.dp, max = 160.dp)
            .height(140.dp)
            .background(itemBackground, RoundedCornerShape(6.dp))
            .border(
                width = 1.dp,
                color = if (selected) palette.outline else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .onGloballyPositioned { coordinates ->
                rowCoordinates = coordinates
                if (entry?.kind == VFileKind.DIRECTORY) {
                    onFileDropZoneChange(
                        FileDropZone(
                            paneId = paneId,
                            targetDirectoryLocation = entry.location,
                            bounds = coordinates.windowBounds(),
                            directoryEntryId = entry.id,
                        )
                    )
                }
            }
            .onPointerEvent(PointerEventType.Press) { event ->
                if (entry == null) return@onPointerEvent
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
            .pointerInput(entry?.id, paneId) {
                if (entry == null) return@pointerInput
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
            .combinedClickable(
                onClick = { onActivate() },
                onDoubleClick = {
                    onActivate()
                    onDismissContextMenu()
                    if (entry != null) onOpenEntry(entry)
                },
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
    ) {
        val isImage = entry?.name?.lowercase()?.let {
            it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".gif") || it.endsWith(".webp") || it.endsWith(
                ".bmp"
            )
        } == true

        Box(
            modifier = Modifier.size(80.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isImage && entry != null) {
                AsyncImage(
                    model = entry.location,
                    contentDescription = entry.name,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                val iconKey =
                    if (entry?.kind == VFileKind.DIRECTORY) AllIconsKeys.Nodes.Folder else AllIconsKeys.FileTypes.Any_type
                Icon(
                    key = iconKey,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp)
                )
            }
        }

        if (isInlineEdit && draftName != null && onUpdateInlineEditDraft != null && onConfirmInlineEdit != null && onCancelInlineEdit != null) {
            BasicTextField(
                value = draftName,
                onValueChange = { onUpdateInlineEditDraft(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter -> {
                                onConfirmInlineEdit(); true
                            }

                            Key.Escape -> {
                                onCancelInlineEdit(); true
                            }

                            else -> false
                        }
                    },
                textStyle = TextStyle(
                    fontSize = 12.sp,
                    color = if (selected) palette.selectionForeground else palette.foreground,
                    textAlign = TextAlign.Center
                ),
                singleLine = true,
            )
        } else {
            Text(
                text = entry?.name ?: "",
                fontWeight = if (entry?.kind == VFileKind.DIRECTORY) FontWeight.Medium else FontWeight.Normal,
                fontSize = 12.sp,
                color = palette.foreground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                lineHeight = 14.sp
            )
        }
    }
}

