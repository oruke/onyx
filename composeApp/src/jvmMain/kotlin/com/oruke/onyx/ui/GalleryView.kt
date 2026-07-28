package com.oruke.onyx.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlin.math.roundToInt

/** 确认单击后延迟请求重命名的时间。 */
private const val RENAME_AFTER_CLICK_DELAY_MS = 300L

/** 画廊缩略图读取时请求的最大边长。 */
private const val GALLERY_THUMBNAIL_DIMENSION = 512

/** 画廊项高度相对宽度的比例。 */
private const val GALLERY_ITEM_HEIGHT_FACTOR = 1.15f

/** 普通图片读取期间占位图标相对项宽度的比例。 */
private const val GALLERY_PLACEHOLDER_ICON_SCALE = 0.2f

/** 普通文件与压缩包图标相对项宽度的比例。 */
private const val GALLERY_FILE_ICON_SCALE = 0.45f

/**
 * 单个画廊项的显示状态。
 */
internal data class GalleryItemState(
    /** 文件条目；创建文件占位项为 null。 */
    val entry: VFile?,
    /** 内联编辑草稿；非编辑状态为 null。 */
    val draftName: String?,
    /** 当前条目是否选中。 */
    val selected: Boolean,
    /** 当前面板选中条目数量。 */
    val selectedEntryCount: Int,
    /** 所属面板是否活动。 */
    val paneActive: Boolean,
    /** 所属面板标识。 */
    val paneId: PaneId,
    /** 当前文件放置目标。 */
    val fileDropTarget: FileDropTarget?,
    /** 画廊项边长设置。 */
    val sizeDp: Int,
)

/**
 * 单个画廊项的业务与交互回调。
 */
internal data class GalleryItemActions(
    /** 激活所属面板。 */
    val onActivate: () -> Unit,
    /** 打开文件条目。 */
    val onOpenEntry: (VFile) -> Unit,
    /** 更新条目选择。 */
    val onSelectEntry: (String, Boolean, Boolean) -> Unit,
    /** 开始文件拖拽。 */
    val onStartFileDrag: (PaneId, FileTransferOperation) -> Unit,
    /** 更新文件拖拽位置。 */
    val onFileDragPositionChange: (IntOffset) -> Unit,
    /** 结束文件拖拽。 */
    val onFileDragEnd: (IntOffset?) -> Unit,
    /** 注册目录放置区域。 */
    val onFileDropZoneChange: (FileDropZone) -> Unit,
    /** 显示条目右键菜单。 */
    val onShowContextMenu: (String, Boolean, IntOffset) -> Unit,
    /** 关闭右键菜单。 */
    val onDismissContextMenu: () -> Unit,
    /** 更新内联编辑草稿。 */
    val onUpdateInlineEditDraft: ((String) -> Unit)? = null,
    /** 确认内联编辑。 */
    val onConfirmInlineEdit: (() -> Unit)? = null,
    /** 取消内联编辑。 */
    val onCancelInlineEdit: (() -> Unit)? = null,
    /** 从未选中项开始框选。 */
    val onStartRubberBand: (String, Offset, Boolean) -> Unit = { _, _, _ -> },
    /** 开始重命名当前条目。 */
    val onBeginRename: () -> Unit = {},
)

/**
 * 画廊缩略图读取与类型识别服务。
 */
internal data class GalleryThumbnailServices(
    /** 读取普通图片缩略图。 */
    val loadThumbnail: suspend (String, Int) -> ImageBitmap?,
    /** 读取压缩包封面缩略图。 */
    val loadArchiveThumbnail: suspend (String, Int) -> ImageBitmap?,
    /** 判断文件名是否为图片。 */
    val isImageFileName: (String) -> Boolean,
    /** 判断文件名是否为压缩包。 */
    val isArchiveFileName: (String) -> Boolean,
)

/** 保存画廊项单次组合周期内的指针交互状态。 */
private class GalleryInteractionState {
    /** 是否使用增量选择。 */
    var additiveSelection by mutableStateOf(false)
    /** 是否使用范围选择。 */
    var rangeSelection by mutableStateOf(false)
    /** 当前拖拽操作。 */
    var dragOperation by mutableStateOf(FileTransferOperation.MOVE)
    /** 当前拖拽窗口坐标。 */
    var dragPosition by mutableStateOf<IntOffset?>(null)
    /** 当前条目的布局坐标。 */
    var coordinates by mutableStateOf<LayoutCoordinates?>(null)
    /** 是否等待松开后收敛多选。 */
    var pendingDeselectOthers by mutableStateOf(false)
    /** 本次主键按下时当前条目是否已满足慢速重命名条件。 */
    var pressCanRename by mutableStateOf(false)
    /** 慢速重命名定时任务。 */
    var renameJob by mutableStateOf<Job?>(null)
    /** 内联编辑框是否曾获得焦点。 */
    var hasFocused by mutableStateOf(false)

    /** 取消尚未触发的慢速重命名。 */
    fun cancelRename() {
        renameJob?.cancel()
        renameJob = null
    }

    /**
     * 确认单击后延迟请求重命名当前条目。
     *
     * @param scope 计时协程作用域。
     * @param onBeginRename 开始重命名回调。
     */
    fun scheduleRename(scope: CoroutineScope, onBeginRename: () -> Unit) {
        cancelRename()
        renameJob = scope.launch {
            delay(RENAME_AFTER_CLICK_DELAY_MS)
            onBeginRename()
        }
    }
}

/**
 * 显示画廊视图中的单个文件、文件夹或创建占位项。
 *
 * @param state 画廊项显示状态。
 * @param actions 画廊项交互回调。
 * @param thumbnails 缩略图与类型识别服务。
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun GalleryItem(
    state: GalleryItemState,
    actions: GalleryItemActions,
    thumbnails: GalleryThumbnailServices,
) {
    val interaction = remember(state.entry?.id) { GalleryInteractionState() }
    val selectedState = rememberUpdatedState(state.selected)
    val entryCountState = rememberUpdatedState(state.selectedEntryCount)
    val draftNameState = rememberUpdatedState(state.draftName)
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val canInlineEdit = state.draftName != null &&
        actions.onUpdateInlineEditDraft != null &&
        actions.onConfirmInlineEdit != null &&
        actions.onCancelInlineEdit != null
    val isDropTarget = state.fileDropTarget?.directoryEntryId == state.entry?.id
    val background by animateColorAsState(
        targetValue = galleryItemBackground(state, isDropTarget),
        animationSpec = tween(durationMillis = 120),
    )
    LaunchedEffect(canInlineEdit, state.draftName) {
        if (canInlineEdit) focusRequester.requestFocus()
    }
    Column(
        modifier = Modifier
            .padding(4.dp)
            .width(state.sizeDp.dp)
            .height((state.sizeDp * GALLERY_ITEM_HEIGHT_FACTOR).dp)
            .background(background, RoundedCornerShape(6.dp))
            .border(
                1.dp,
                if (state.selected) LocalOnyxPalette.current.outline else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .galleryPressInteractions(state, actions, interaction, canInlineEdit)
            .galleryDragInteractions(state, actions, interaction, selectedState)
            .combinedClickable(
                onClick = {
                    actions.onActivate()
                    val canRename = state.entry != null && !canInlineEdit &&
                        interaction.pressCanRename &&
                        selectedState.value && entryCountState.value == 1
                    if (canRename) {
                        interaction.scheduleRename(scope) {
                            if (selectedState.value && entryCountState.value == 1 && draftNameState.value == null) {
                                actions.onBeginRename()
                            }
                        }
                    }
                },
                onDoubleClick = {
                    interaction.cancelRename()
                    actions.onActivate()
                    actions.onDismissContextMenu()
                    state.entry?.let(actions.onOpenEntry)
                },
            )
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        GalleryThumbnail(state.entry, state.sizeDp, thumbnails, Modifier.weight(1f).fillMaxWidth())
        GalleryItemName(state, actions, interaction, focusRequester, canInlineEdit)
    }
}

/**
 * 计算条目的选中或放置目标背景。
 *
 * @param state 画廊项显示状态。
 * @param isDropTarget 是否为当前目录放置目标。
 * @return 当前应展示的背景色。
 */
@Composable
private fun galleryItemBackground(state: GalleryItemState, isDropTarget: Boolean): Color {
    val palette = LocalOnyxPalette.current
    return when {
        isDropTarget -> palette.rowHoverBackground
        state.selected && state.paneActive -> palette.selectionBackground
        state.selected -> palette.inactiveSelectionBackground
        else -> Color.Transparent
    }
}

/**
 * 注册目录放置区域并处理左右键按下。
 *
 * @param state 画廊项显示状态。
 * @param actions 画廊项交互回调。
 * @param interaction 指针交互状态。
 * @param inlineEditing 当前是否正在内联编辑。
 * @return 添加按下交互后的修饰符。
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.galleryPressInteractions(
    state: GalleryItemState,
    actions: GalleryItemActions,
    interaction: GalleryInteractionState,
    inlineEditing: Boolean,
): Modifier = onGloballyPositioned { coordinates ->
    interaction.coordinates = coordinates
    state.entry?.takeIf { it.kind == VFileKind.DIRECTORY }?.let { entry ->
        actions.onFileDropZoneChange(
            FileDropZone(state.paneId, entry.location, coordinates.windowBounds(), entry.id),
        )
    }
}.onPointerEvent(PointerEventType.Press) { event ->
    handleGalleryPress(event, state, actions, interaction, inlineEditing)
}

/**
 * 处理画廊项按下事件中的选择和菜单。
 *
 * @param event 当前指针事件。
 * @param state 画廊项显示状态。
 * @param actions 画廊项交互回调。
 * @param interaction 指针交互状态。
 * @param inlineEditing 当前是否正在内联编辑。
 */
private fun handleGalleryPress(
    event: PointerEvent,
    state: GalleryItemState,
    actions: GalleryItemActions,
    interaction: GalleryInteractionState,
    inlineEditing: Boolean,
) {
    val entry = state.entry ?: return
    if (inlineEditing) return
    val modified = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
    interaction.additiveSelection = modified
    interaction.rangeSelection = event.keyboardModifiers.isShiftPressed
    interaction.dragOperation = if (modified) FileTransferOperation.COPY else FileTransferOperation.MOVE
    val pointer = event.changes.firstOrNull()?.position ?: return
    if (event.buttons.isSecondaryPressed) {
        interaction.cancelRename()
        val windowPosition = interaction.coordinates?.localToWindow(pointer) ?: pointer
        actions.onActivate()
        actions.onShowContextMenu(
            entry.id,
            state.selected,
            IntOffset(windowPosition.x.roundToInt(), windowPosition.y.roundToInt()),
        )
    } else if (event.buttons.isPrimaryPressed) {
        handleGalleryPrimaryPress(state, actions, interaction)
    }
}

/**
 * 处理主键按下后的选择。
 *
 * @param state 画廊项显示状态。
 * @param actions 画廊项交互回调。
 * @param interaction 指针交互状态。
 */
private fun handleGalleryPrimaryPress(
    state: GalleryItemState,
    actions: GalleryItemActions,
    interaction: GalleryInteractionState,
) {
    val entry = requireNotNull(state.entry)
    actions.onActivate()
    actions.onDismissContextMenu()
    interaction.cancelRename()
    interaction.pressCanRename = state.selected && state.selectedEntryCount == 1 &&
        !interaction.additiveSelection && !interaction.rangeSelection
    interaction.pendingDeselectOthers = false
    when {
        interaction.additiveSelection || interaction.rangeSelection -> actions.onSelectEntry(
            entry.id,
            interaction.additiveSelection,
            interaction.rangeSelection,
        )
        state.selectedEntryCount > 1 -> interaction.pendingDeselectOthers = true
        else -> actions.onSelectEntry(entry.id, false, false)
    }
}

/**
 * 处理画廊项拖拽、框选以及松开后选择收敛。
 *
 * @param state 画廊项显示状态。
 * @param actions 画廊项交互回调。
 * @param interaction 指针交互状态。
 * @param selectedState 最新选中状态。
 * @return 添加拖拽手势后的修饰符。
 */
private fun Modifier.galleryDragInteractions(
    state: GalleryItemState,
    actions: GalleryItemActions,
    interaction: GalleryInteractionState,
    selectedState: State<Boolean>,
): Modifier = pointerInput(state.entry?.id, state.paneId) {
    val entry = state.entry ?: return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var fileDragStarted = false
        val selectedAtPress = selectedState.value
        val drag = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
        if (drag != null) {
            interaction.cancelRename()
            interaction.pendingDeselectOthers = false
            if (selectedAtPress) {
                fileDragStarted = true
                runGalleryFileDrag(drag, state, actions, interaction)
            } else {
                val start = interaction.coordinates?.localToWindow(down.position) ?: down.position
                actions.onStartRubberBand(entry.id, start, interaction.additiveSelection)
            }
        }
        if (!fileDragStarted && interaction.pendingDeselectOthers) {
            actions.onSelectEntry(entry.id, false, false)
            interaction.pendingDeselectOthers = false
        }
    }
}

/**
 * 从触发阈值后的变更开始持续执行文件拖拽。
 *
 * @param firstChange 首个越过拖拽阈值的指针变更。
 * @param state 画廊项显示状态。
 * @param actions 画廊项交互回调。
 * @param interaction 指针交互状态。
 */
private suspend fun AwaitPointerEventScope.runGalleryFileDrag(
    firstChange: PointerInputChange,
    state: GalleryItemState,
    actions: GalleryItemActions,
    interaction: GalleryInteractionState,
) {
    var current = firstChange
    interaction.dragPosition = interaction.coordinates?.localToWindow(current.position)?.toIntOffset()
    actions.onStartFileDrag(state.paneId, interaction.dragOperation)
    interaction.dragPosition?.let(actions.onFileDragPositionChange)
    while (current.pressed) {
        current = awaitDragOrCancellation(current.id) ?: break
        current.consume()
        interaction.dragPosition = interaction.coordinates?.localToWindow(current.position)?.toIntOffset()
        interaction.dragPosition?.let(actions.onFileDragPositionChange)
    }
    actions.onFileDragEnd(interaction.dragPosition)
    interaction.dragPosition = null
}

/**
 * 显示图片、压缩包封面或普通文件图标。
 *
 * @param entry 当前文件条目。
 * @param sizeDp 画廊项边长设置。
 * @param services 缩略图与类型识别服务。
 * @param modifier 缩略图区域修饰符。
 */
@Composable
private fun GalleryThumbnail(
    entry: VFile?,
    sizeDp: Int,
    services: GalleryThumbnailServices,
    modifier: Modifier,
) {
    Box(modifier = modifier.clip(RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
        when {
            entry != null && services.isImageFileName(entry.name) -> ImageGalleryThumbnail(entry, sizeDp, services)
            entry != null && services.isArchiveFileName(entry.name) -> ArchiveGalleryThumbnail(entry, sizeDp, services)
            else -> GalleryFallbackIcon(entry, sizeDp)
        }
    }
}

/**
 * 显示普通图片缩略图或占位图标。
 *
 * @param entry 图片条目。
 * @param sizeDp 画廊项边长设置。
 * @param services 缩略图服务。
 */
@Composable
private fun ImageGalleryThumbnail(entry: VFile, sizeDp: Int, services: GalleryThumbnailServices) {
    val (thumbnail, _) = rememberAsyncBitmap(entry.location, GALLERY_THUMBNAIL_DIMENSION, services.loadThumbnail)
    if (thumbnail != null) {
        GalleryBitmap(thumbnail, entry.name)
    } else {
        Icon(
            key = AllIconsKeys.FileTypes.Any_type,
            contentDescription = null,
            modifier = Modifier.size((sizeDp * GALLERY_PLACEHOLDER_ICON_SCALE).dp),
        )
    }
}

/**
 * 显示压缩包封面及格式标记，或压缩包图标。
 *
 * @param entry 压缩包条目。
 * @param sizeDp 画廊项边长设置。
 * @param services 缩略图服务。
 */
@Composable
private fun ArchiveGalleryThumbnail(entry: VFile, sizeDp: Int, services: GalleryThumbnailServices) {
    val (thumbnail, _) = rememberAsyncBitmap(
        entry.location,
        GALLERY_THUMBNAIL_DIMENSION,
        services.loadArchiveThumbnail,
    )
    if (thumbnail == null) {
        Icon(
            key = AllIconsKeys.FileTypes.Archive,
            contentDescription = null,
            modifier = Modifier.size((sizeDp * GALLERY_FILE_ICON_SCALE).dp),
        )
        return
    }
    Box(modifier = Modifier.fillMaxSize()) {
        GalleryBitmap(thumbnail, entry.name)
        Box(
            modifier = Modifier.fillMaxWidth().height(28.dp).align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))),
        )
        Text(
            text = entry.name.substringAfterLast('.', "").uppercase(),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            lineHeight = 11.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(3.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/**
 * 显示适应容器的高质量缩略图。
 *
 * @param bitmap 缩略图位图。
 * @param contentDescription 图片内容描述。
 */
@Composable
private fun GalleryBitmap(bitmap: ImageBitmap, contentDescription: String) {
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.High,
        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)),
    )
}

/**
 * 显示目录或普通文件的矢量图标。
 *
 * @param entry 当前文件条目。
 * @param sizeDp 画廊项边长设置。
 */
@Composable
private fun GalleryFallbackIcon(entry: VFile?, sizeDp: Int) {
    val iconKey = if (entry?.kind == VFileKind.DIRECTORY) AllIconsKeys.Nodes.Folder else fileIconKey(entry?.name)
    Icon(key = iconKey, contentDescription = null, modifier = Modifier.size((sizeDp * GALLERY_FILE_ICON_SCALE).dp))
}

/**
 * 显示文件名或内联编辑输入框。
 *
 * @param state 画廊项显示状态。
 * @param actions 画廊项交互回调。
 * @param interaction 指针交互状态。
 * @param focusRequester 内联编辑焦点请求器。
 * @param inlineEditing 当前是否正在内联编辑。
 */
@Composable
private fun GalleryItemName(
    state: GalleryItemState,
    actions: GalleryItemActions,
    interaction: GalleryInteractionState,
    focusRequester: FocusRequester,
    inlineEditing: Boolean,
) {
    if (inlineEditing) {
        GalleryInlineEditor(state, actions, interaction, focusRequester)
    } else {
        Text(
            text = state.entry?.name.orEmpty(),
            fontWeight = if (state.entry?.kind == VFileKind.DIRECTORY) FontWeight.Medium else FontWeight.Normal,
            fontSize = 12.sp,
            color = LocalOnyxPalette.current.foreground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 14.sp,
        )
    }
}

/**
 * 显示可用 Enter、Escape 和失焦提交的内联名称编辑器。
 *
 * @param state 画廊项显示状态。
 * @param actions 画廊项交互回调。
 * @param interaction 指针交互状态。
 * @param focusRequester 输入框焦点请求器。
 */
@Composable
private fun GalleryInlineEditor(
    state: GalleryItemState,
    actions: GalleryItemActions,
    interaction: GalleryInteractionState,
    focusRequester: FocusRequester,
) {
    val confirm = requireNotNull(actions.onConfirmInlineEdit)
    val cancel = requireNotNull(actions.onCancelInlineEdit)
    BasicTextField(
        value = requireNotNull(state.draftName),
        onValueChange = requireNotNull(actions.onUpdateInlineEditDraft),
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, LocalOnyxPalette.current.outline, RoundedCornerShape(2.dp))
            .background(LocalOnyxPalette.current.surface, RoundedCornerShape(2.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                if (focusState.isFocused) interaction.hasFocused = true else if (interaction.hasFocused) confirm()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter -> true.also { confirm() }
                    Key.Escape -> true.also { cancel() }
                    else -> false
                }
            },
        textStyle = TextStyle(
            fontSize = 12.sp,
            color = LocalOnyxPalette.current.foreground,
            textAlign = TextAlign.Center,
        ),
        singleLine = true,
        cursorBrush = SolidColor(LocalOnyxPalette.current.foreground),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { innerTextField() }
        },
    )
}
