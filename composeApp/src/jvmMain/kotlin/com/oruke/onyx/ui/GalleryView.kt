package com.oruke.onyx.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable

import androidx.compose.foundation.gestures.awaitDragOrCancellation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.ui.theme.FileDropTarget
import com.oruke.onyx.ui.theme.FileDropZone
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.fileIconKey
import com.oruke.onyx.ui.theme.toIntOffset
import com.oruke.onyx.ui.theme.windowBounds
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlin.math.roundToInt
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

// ── Gallery item (high-density grid) ────────────────────────────────────────

/**
 * 画廊视图中的单个文件/文件夹展示项 (Gallery Item)。
 * 
 * 采用网格布局 (Grid) 呈现，重点突出文件的缩略图或图标。
 * - 如果是图片文件 (jpg, png, webp 等)，会使用 Coil 异步加载并居中裁剪显示。
 * - 否则显示高清 Jewel SVG 矢量图标。
 * - 包含复杂的鼠标交互（点击、双击、拖拽、右键菜单、框选）。
 */
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
    galleryItemSizeDp: Int = 160,
    onStartRubberBand: (String, androidx.compose.ui.geometry.Offset, Boolean) -> Unit = { _, _, _ -> },
    onBeginRename: () -> Unit = {},
    loadThumbnail: suspend (String, Int) -> ImageBitmap?,
    loadArchiveThumbnail: suspend (String, Int) -> ImageBitmap?,
    isImageFileName: (String) -> Boolean,
    isArchiveFileName: (String) -> Boolean,
) {
    var additiveSelection by remember { mutableStateOf(false) }
    val currentSelected by androidx.compose.runtime.rememberUpdatedState(selected)
    var rangeSelection by remember { mutableStateOf(false) }
    var dragOperation by remember { mutableStateOf(FileTransferOperation.MOVE) }
    var dragPosition by remember { mutableStateOf<IntOffset?>(null) }
    var rowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val isDirectoryDropTarget = fileDropTarget?.directoryEntryId == entry?.id
    var pendingDeselectOthers by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var renameTimerJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val canInlineEdit =
        draftName != null &&
            onUpdateInlineEditDraft != null &&
            onConfirmInlineEdit != null &&
            onCancelInlineEdit != null
    val focusRequester = remember { FocusRequester() }
    var hasFocused by remember { mutableStateOf(false) }

    val itemBackground by animateColorAsState(
        targetValue = when {
            isDirectoryDropTarget -> LocalOnyxPalette.current.rowHoverBackground
            selected && paneActive -> LocalOnyxPalette.current.selectionBackground
            selected && !paneActive -> LocalOnyxPalette.current.inactiveSelectionBackground
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 120),
    )

    LaunchedEffect(canInlineEdit, draftName) {
        if (canInlineEdit) {
            focusRequester.requestFocus()
        }
    }

    val itemWidth = galleryItemSizeDp.dp
    val itemHeight = (galleryItemSizeDp * 1.15f).dp
    val iconSize = (galleryItemSizeDp * 0.45f).dp
    val placeholderIconSize = (galleryItemSizeDp * 0.2f).dp

    Column(
        modifier = Modifier
            .padding(4.dp)
            .width(itemWidth)
            .height(itemHeight)
            .background(itemBackground, RoundedCornerShape(6.dp))
            .border(
                width = 1.dp,
                color = if (selected) LocalOnyxPalette.current.outline else Color.Transparent,
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
                // 内联编辑期间，不处理选中逻辑（避免 selectEntry 清除编辑状态）
                if (canInlineEdit) return@onPointerEvent
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
                        pendingDeselectOthers = false
                        if (additiveSelection || rangeSelection) {
                            onSelectEntry(entry.id, additiveSelection, rangeSelection)
                        } else if (selectedEntryCount > 1) {
                            pendingDeselectOthers = true
                        } else {
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
            .pointerInput(entry?.id, paneId) {
                if (entry == null) return@pointerInput
                val entryId = entry.id
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragStarted = false
                    val wasSelectedAtPress = currentSelected
                    val drag = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    if (drag != null) {
                        renameTimerJob?.cancel()
                        renameTimerJob = null
                        pendingDeselectOthers = false
                        if (!wasSelectedAtPress) {
                            // 未选中项拖拽 → 启动框选
                            val addSel = additiveSelection
                            val windowStartPos = rowCoordinates?.localToWindow(down.position) ?: down.position
                            onStartRubberBand(entryId, windowStartPos, addSel)
                            // 由父组件 overlay 接管后续拖拽跟踪
                        } else {
                            var current: PointerInputChange = drag
                            dragStarted = true
                            pendingDeselectOthers = false
                            dragPosition = rowCoordinates?.localToWindow(current.position)?.toIntOffset()
                            onStartFileDrag(paneId, dragOperation)
                            dragPosition?.let(onFileDragPositionChange)
                            while (current.pressed) {
                                val next = awaitDragOrCancellation(current.id) ?: break
                                current = next
                                current.consume()
                                dragPosition = rowCoordinates?.localToWindow(current.position)?.toIntOffset()
                                dragPosition?.let(onFileDragPositionChange)
                            }
                            onFileDragEnd(dragPosition)
                            dragPosition = null
                        }
                    }
                    if (!dragStarted && pendingDeselectOthers) {
                        onSelectEntry(entryId, false, false)
                        pendingDeselectOthers = false
                    }
                }
            }
            .combinedClickable(
                onClick = { onActivate() },
                onDoubleClick = {
                    renameTimerJob?.cancel()
                    renameTimerJob = null
                    onActivate()
                    onDismissContextMenu()
                    if (entry != null) onOpenEntry(entry)
                },
            ).padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
    ) {
        val currentEntry = entry

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (currentEntry != null && isImageFileName(currentEntry.name)) {
                val (thumbnail, _) = rememberAsyncBitmap(currentEntry.location, 512, loadThumbnail)
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail,
                        contentDescription = currentEntry.name,
                        contentScale = ContentScale.Fit,
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(4.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .clip(RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            key = AllIconsKeys.FileTypes.Any_type,
                            contentDescription = null,
                            modifier = Modifier.size(placeholderIconSize)
                        )
                    }
                }
            } else if (currentEntry != null && isArchiveFileName(currentEntry.name)) {
                val (archiveThumbnail, _) = rememberAsyncBitmap(currentEntry.location, 512, loadArchiveThumbnail)
                if (archiveThumbnail != null) {
                    // 缩略图 + 格式标签叠加
                    Box(modifier = Modifier.fillMaxSize()) {
                        Image(
                            bitmap = archiveThumbnail,
                            contentDescription = currentEntry.name,
                            contentScale = ContentScale.Fit,
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(4.dp))
                        )
                        // 底部渐变遮罩，增强标签可读性
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(28.dp)
                                .align(Alignment.BottomCenter)
                                .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
                                .background(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                                    )
                                )
                        )
                        // 右下角格式标签
                        val ext = currentEntry.name.substringAfterLast('.', "").uppercase()
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(3.dp)
                                .background(
                                    Color.Black.copy(alpha = 0.65f),
                                    RoundedCornerShape(3.dp),
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = ext,
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                lineHeight = 11.sp,
                            )
                        }
                    }
                } else {
                    Icon(
                        key = AllIconsKeys.FileTypes.Archive,
                        contentDescription = null,
                        modifier = Modifier.size(iconSize)
                    )
                }
            } else {
                val iconKey =
                    if (currentEntry?.kind == VFileKind.DIRECTORY) {
                        AllIconsKeys.Nodes.Folder
                    } else {
                        fileIconKey(currentEntry?.name)
                    }
                Icon(
                    key = iconKey,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize)
                )
            }
        }

        if (canInlineEdit) {
            val inlineDraftName = checkNotNull(draftName)
            val updateInlineEditDraft = checkNotNull(onUpdateInlineEditDraft)
            val confirmInlineEdit = checkNotNull(onConfirmInlineEdit)
            val cancelInlineEdit = checkNotNull(onCancelInlineEdit)
            BasicTextField(
                value = inlineDraftName,
                onValueChange = updateInlineEditDraft,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, LocalOnyxPalette.current.outline, RoundedCornerShape(2.dp))
                    .background(LocalOnyxPalette.current.surface, RoundedCornerShape(2.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            hasFocused = true
                        } else if (hasFocused) {
                            // 失焦时取消编辑，避免 Alt+Tab 等场景下误提交空名称
                            cancelInlineEdit()
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter -> {
                                confirmInlineEdit(); true
                            }

                            Key.Escape -> {
                                cancelInlineEdit(); true
                            }

                            else -> false
                        }
                    },
                textStyle = TextStyle(
                    fontSize = 12.sp,
                    color = LocalOnyxPalette.current.foreground,
                    textAlign = TextAlign.Center
                ),
                singleLine = true,
                cursorBrush = SolidColor(LocalOnyxPalette.current.foreground),
            )
        } else {
            Text(
                text = entry?.name ?: "",
                fontWeight = if (entry?.kind == VFileKind.DIRECTORY) FontWeight.Medium else FontWeight.Normal,
                fontSize = 12.sp,
                color = LocalOnyxPalette.current.foreground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                lineHeight = 14.sp
            )
        }
    }
}
