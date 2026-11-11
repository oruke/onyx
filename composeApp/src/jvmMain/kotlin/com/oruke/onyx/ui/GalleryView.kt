package com.oruke.onyx.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlin.math.roundToInt
import com.oruke.onyx.app.component.isBrowsableDirectory
import com.oruke.onyx.core.model.FileLabelStyle
import com.oruke.onyx.core.model.isTrashItemLocation
import com.oruke.onyx.ui.theme.LocalOnyxAppearance

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
    /** 是否排除文件扩展名选中。 */
    val selectNameWithoutExtension: Boolean = entry?.kind != VFileKind.DIRECTORY,
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
    /** 组件计算后的规则颜色。 */
    val labelStyle: FileLabelStyle? = null,
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

    /** 纯文件名文本标签布局坐标（限定慢速点击重命名命中区）。 */
    var renameLabelCoordinates by mutableStateOf<LayoutCoordinates?>(null)

    /** 是否等待松开后收敛多选。 */
    var pendingDeselectOthers by mutableStateOf(false)

    /** 统一的主键点击、双击打开与慢速重命名控制器。 */
    val clickController = FileEntryClickController()

    /** 内联编辑框是否曾获得焦点。 */
    var hasFocused by mutableStateOf(false)

    /**
     * 判断画廊项局部坐标是否精确命中底部文件名文本标签；坐标未就绪时保守返回 false。
     *
     * @param position 画廊项局部指针坐标。
     * @return 精确命中已附加文件名标签时返回 true。
     */
    fun isInRenameLabel(position: Offset): Boolean {
        val label = renameLabelCoordinates ?: return false
        val item = coordinates ?: return false
        return label.isAttached && item.isAttached &&
            item.localBoundingBoxOf(label, clipBounds = false).contains(position)
    }
}

/** 判断当前画廊项是否处于允许挂起慢速重命名的单选活动状态。 */
private fun GalleryItemState.canScheduleRename(inlineEditing: Boolean): Boolean {
    val activeSingleSelection = selected && selectedEntryCount == 1 && paneActive
    return activeSingleSelection && draftName == null && !inlineEditing
}

/** 判断画廊项动作集合是否已完备提供内联编辑回调。 */
private fun GalleryItemActions.supportsInlineEdit(draftName: String?): Boolean {
    val hasCallbacks = onUpdateInlineEditDraft != null && onConfirmInlineEdit != null
    return draftName != null && hasCallbacks && onCancelInlineEdit != null
}

/**
 * 显示画廊视图中的单个文件、文件夹或创建占位项。
 *
 * @param state 画廊项显示状态。
 * @param actions 画廊项交互回调。
 * @param thumbnails 缩略图与类型识别服务。
 */
@Composable
internal fun GalleryItem(
    state: GalleryItemState,
    actions: GalleryItemActions,
    thumbnails: GalleryThumbnailServices,
) {
    val density = LocalDensity.current
    val interaction = remember(state.entry?.id) { GalleryInteractionState() }
    val hoverInteractionSource = remember(state.entry?.id) { MutableInteractionSource() }
    val isHovered by hoverInteractionSource.collectIsHoveredAsState()
    val currentState = rememberUpdatedState(state)
    val currentActions = rememberUpdatedState(actions)
    val singleClickToOpenState = rememberUpdatedState(LocalOnyxAppearance.current.singleClickToOpen)
    val dragSlopSquaredState = rememberUpdatedState(
        with(density) { ENTRY_DRAG_SLOP_DP.dp.toPx().let { px -> px * px } },
    )
    val doubleClickSlopPxState = rememberUpdatedState(
        with(density) { ENTRY_DOUBLE_CLICK_SLOP_DP.dp.toPx() },
    )
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val canInlineEdit = actions.supportsInlineEdit(state.draftName)
    val canInlineEditState = rememberUpdatedState(canInlineEdit)
    val isDropTarget = state.fileDropTarget?.directoryEntryId == state.entry?.id
    val background = galleryItemBackground(state, isDropTarget, isHovered)
    val borderColor = galleryItemBorderColor(state.selected, isHovered)

    LaunchedEffect(canInlineEdit) {
        interaction.hasFocused = false
        if (canInlineEdit) focusRequester.requestFocus()
    }
    LaunchedEffect(state.selected, state.selectedEntryCount, state.paneActive, canInlineEdit) {
        if (!state.canScheduleRename(canInlineEdit)) {
            interaction.clickController.cancelRename()
        }
    }

    Column(
        modifier = Modifier
            .padding(4.dp)
            .width(state.sizeDp.dp)
            .height((state.sizeDp * GALLERY_ITEM_HEIGHT_FACTOR).dp)
            .hoverable(hoverInteractionSource)
            .background(background, RoundedCornerShape(6.dp))
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .galleryDropZoneRegistration(state, actions, interaction)
            .galleryPointerInteractions(
                entryId = state.entry?.id,
                paneId = state.paneId,
                currentState = currentState,
                currentActions = currentActions,
                interaction = interaction,
                canInlineEditState = canInlineEditState,
                singleClickToOpenState = singleClickToOpenState,
                dragSlopSquaredState = dragSlopSquaredState,
                doubleClickSlopPxState = doubleClickSlopPxState,
                coroutineScope = scope,
            )
            .galleryItemSemantics(state, actions, interaction, canInlineEdit, singleClickToOpenState)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        GalleryThumbnail(state.entry, state.sizeDp, thumbnails, Modifier.weight(1f).fillMaxWidth())
        GalleryItemName(state, actions, interaction, focusRequester, canInlineEdit)
    }
}

/**
 * 根据条目的选中和悬停状态计算边框颜色。
 */
@Composable
private fun galleryItemBorderColor(selected: Boolean, hovered: Boolean): Color = when {
    selected -> LocalOnyxPalette.current.outline
    hovered -> LocalOnyxPalette.current.outlineVariant
    else -> Color.Transparent
}

/**
 * 为画廊条目附加无障碍语义与无指针点击回退动作。
 */
private fun Modifier.galleryItemSemantics(
    state: GalleryItemState,
    actions: GalleryItemActions,
    interaction: GalleryInteractionState,
    canInlineEdit: Boolean,
    singleClickToOpenState: State<Boolean>,
): Modifier = semantics {
    selected = state.selected
    val entry = state.entry
    if (entry != null && !canInlineEdit) {
        onClick {
            interaction.clickController.onPointerDraggedOrCancelled()
            actions.onActivate()
            actions.onDismissContextMenu()
            if (singleClickToOpenState.value) {
                actions.onOpenEntry(entry)
            } else {
                actions.onSelectEntry(entry.id, false, false)
            }
            true
        }
    }
}

/**
 * 计算条目的选中、悬停或放置目标背景。
 *
 * @param state 画廊项显示状态。
 * @param isDropTarget 是否为当前目录放置目标。
 * @param hovered 当前鼠标是否悬停在该项上。
 * @return 当前应展示的背景色。
 */
@Composable
private fun galleryItemBackground(
    state: GalleryItemState,
    isDropTarget: Boolean,
    hovered: Boolean = false,
): Color {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    val labelBackground = state.labelStyle?.backgroundColorArgb
    return when {
        isDropTarget -> palette.rowHoverBackground
        state.selected && state.paneActive -> appearance.selectionBackground ?: palette.selectionBackground
        state.selected && hovered -> palette.rowHoverBackground
        state.selected -> appearance.inactiveSelectionBackground ?: palette.inactiveSelectionBackground
        hovered -> palette.rowHoverBackground
        labelBackground != null -> Color(labelBackground)
        else -> Color.Transparent
    }
}

/**
 * 注册画廊目录项的拖放区域坐标。
 */
private fun Modifier.galleryDropZoneRegistration(
    state: GalleryItemState,
    actions: GalleryItemActions,
    interaction: GalleryInteractionState,
): Modifier = onGloballyPositioned { coordinates ->
    interaction.coordinates = coordinates
    state.entry?.takeIf { it.isBrowsableDirectory() }?.let { entry ->
        actions.onFileDropZoneChange(
            FileDropZone(state.paneId, entry.location, coordinates.windowBounds(), entry.id),
        )
    }
}

/**
 * 在单一线性协程内顺序处理画廊项左右键按下、文件拖拽、框选、松开收敛及双击打开/慢速重命名。
 */
private fun Modifier.galleryPointerInteractions(
    entryId: String?,
    paneId: PaneId,
    currentState: State<GalleryItemState>,
    currentActions: State<GalleryItemActions>,
    interaction: GalleryInteractionState,
    canInlineEditState: State<Boolean>,
    singleClickToOpenState: State<Boolean>,
    dragSlopSquaredState: State<Float>,
    doubleClickSlopPxState: State<Float>,
    coroutineScope: CoroutineScope,
): Modifier = pointerInput(entryId, paneId) {
    if (entryId == null) return@pointerInput
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Press) continue
            val primaryPointer = handleGalleryPress(
                event = event,
                state = currentState.value,
                actions = currentActions.value,
                interaction = interaction,
                inlineEditing = canInlineEditState.value,
                doubleClickSlopPx = doubleClickSlopPxState.value,
            )
            if (primaryPointer != null) {
                trackGalleryPointer(
                    pointerId = primaryPointer.id,
                    startPosition = primaryPointer.position,
                    currentState = currentState,
                    currentActions = currentActions,
                    interaction = interaction,
                    selectedAtPress = currentState.value.selected,
                    canInlineEditState = canInlineEditState,
                    singleClickToOpenState = singleClickToOpenState,
                    dragSlopSquaredState = dragSlopSquaredState,
                    coroutineScope = coroutineScope,
                )
            }
        }
    }
}

/**
 * 处理画廊项按下事件中的选择和菜单；若为主键点击则返回对应指针变更供后续追踪。
 */
private fun handleGalleryPress(
    event: PointerEvent,
    state: GalleryItemState,
    actions: GalleryItemActions,
    interaction: GalleryInteractionState,
    inlineEditing: Boolean,
    doubleClickSlopPx: Float,
): PointerInputChange? {
    val entry = state.entry ?: return null
    if (inlineEditing) {
        interaction.clickController.onPointerDraggedOrCancelled()
        return null
    }
    val modified = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
    interaction.additiveSelection = modified
    interaction.rangeSelection = event.keyboardModifiers.isShiftPressed
    interaction.dragOperation = if (modified) FileTransferOperation.COPY else FileTransferOperation.MOVE
    val primaryChange = event.changes.firstOrNull() ?: return null
    val pointer = primaryChange.position
    return when {
        event.buttons.isSecondaryPressed -> {
            interaction.clickController.onPointerDraggedOrCancelled()
            val windowPosition = interaction.coordinates?.localToWindow(pointer) ?: pointer
            actions.onActivate()
            actions.onShowContextMenu(
                entry.id,
                state.selected,
                IntOffset(windowPosition.x.roundToInt(), windowPosition.y.roundToInt()),
            )
            null
        }

        event.isPrimaryPointerPress() ->
            handleGalleryPrimaryPress(state, actions, interaction, primaryChange, doubleClickSlopPx)

        else -> {
            interaction.clickController.onPointerDraggedOrCancelled()
            null
        }
    }
}

/**
 * 处理画廊主键按下后的连击记录与立即选择。
 */
private fun handleGalleryPrimaryPress(
    state: GalleryItemState,
    actions: GalleryItemActions,
    interaction: GalleryInteractionState,
    change: PointerInputChange,
    doubleClickSlopPx: Float,
): PointerInputChange {
    val entry = requireNotNull(state.entry)
    val wasActiveSingleSelection = state.paneActive && state.selected && state.selectedEntryCount == 1
    interaction.clickController.onPrimaryPress(
        FileEntryPressRequest(
            pressMillis = change.uptimeMillis,
            position = change.position,
            wasSingleSelectedBeforePress = wasActiveSingleSelection,
            additiveOrRange = interaction.additiveSelection || interaction.rangeSelection,
            inRenameLabel = interaction.isInRenameLabel(change.position),
            doubleClickSlopPx = doubleClickSlopPx,
        ),
    )
    actions.onActivate()
    actions.onDismissContextMenu()
    interaction.pendingDeselectOthers = false
    when {
        interaction.additiveSelection || interaction.rangeSelection -> actions.onSelectEntry(
            entry.id,
            interaction.additiveSelection,
            interaction.rangeSelection,
        )

        state.selected && state.selectedEntryCount > 1 -> interaction.pendingDeselectOthers = true
        else -> actions.onSelectEntry(entry.id, false, false)
    }
    return change
}

/** 一次画廊项指针移动处理后的拖拽与继续追踪状态。 */
private data class GalleryPointerProgress(
    val fileDragStarted: Boolean,
    val continueTracking: Boolean,
)

/**
 * 跟踪一次画廊项主键按下后的移动拖拽、框选或释放。
 */
private suspend fun AwaitPointerEventScope.trackGalleryPointer(
    pointerId: PointerId,
    startPosition: Offset,
    currentState: State<GalleryItemState>,
    currentActions: State<GalleryItemActions>,
    interaction: GalleryInteractionState,
    selectedAtPress: Boolean,
    canInlineEditState: State<Boolean>,
    singleClickToOpenState: State<Boolean>,
    dragSlopSquaredState: State<Float>,
    coroutineScope: CoroutineScope,
) {
    var fileDragStarted = false
    var tracking = true
    while (tracking) {
        val event = awaitPointerEvent()
        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
            interaction.clickController.onPointerDraggedOrCancelled()
            tracking = false
            continue
        }
        val change = event.changes.firstOrNull { it.id == pointerId } ?: event.changes.firstOrNull()
        if (change == null || canInlineEditState.value) {
            interaction.clickController.onPointerDraggedOrCancelled()
            tracking = false
        } else if (!change.pressed) {
            finishGalleryPointer(
                releaseChange = change,
                currentState = currentState,
                currentActions = currentActions,
                interaction = interaction,
                fileDragStarted = fileDragStarted,
                canInlineEditState = canInlineEditState,
                singleClickToOpen = singleClickToOpenState.value,
                coroutineScope = coroutineScope,
            )
            tracking = false
        } else {
            val progress = updateGalleryPointer(
                change = change,
                startPosition = startPosition,
                state = currentState.value,
                actions = currentActions.value,
                interaction = interaction,
                selectedAtPress = selectedAtPress,
                fileDragStarted = fileDragStarted,
                dragSlopSquared = dragSlopSquaredState.value,
            )
            fileDragStarted = progress.fileDragStarted
            tracking = progress.continueTracking
        }
    }
}

/**
 * 处理画廊项按住状态下的一次指针移动并识别是否启动文件拖拽或框选。
 */
private fun updateGalleryPointer(
    change: PointerInputChange,
    startPosition: Offset,
    state: GalleryItemState,
    actions: GalleryItemActions,
    interaction: GalleryInteractionState,
    selectedAtPress: Boolean,
    fileDragStarted: Boolean,
    dragSlopSquared: Float,
): GalleryPointerProgress {
    val entry = state.entry ?: return GalleryPointerProgress(fileDragStarted = false, continueTracking = false)
    val dx = change.position.x - startPosition.x
    val dy = change.position.y - startPosition.y
    val crossedThreshold = dx * dx + dy * dy > dragSlopSquared
    return when {
        fileDragStarted -> {
            change.consume()
            interaction.dragPosition = interaction.coordinates?.localToWindow(change.position)?.toIntOffset()
            interaction.dragPosition?.let(actions.onFileDragPositionChange)
            GalleryPointerProgress(fileDragStarted = true, continueTracking = true)
        }

        change.isConsumed -> {
            interaction.clickController.onPointerDraggedOrCancelled()
            GalleryPointerProgress(fileDragStarted = false, continueTracking = false)
        }

        !crossedThreshold -> GalleryPointerProgress(fileDragStarted = false, continueTracking = true)
        selectedAtPress -> {
            interaction.pendingDeselectOthers = false
            interaction.clickController.onPointerDraggedOrCancelled()
            change.consume()
            interaction.dragPosition = interaction.coordinates?.localToWindow(change.position)?.toIntOffset()
            actions.onStartFileDrag(state.paneId, interaction.dragOperation)
            interaction.dragPosition?.let(actions.onFileDragPositionChange)
            GalleryPointerProgress(fileDragStarted = true, continueTracking = true)
        }

        else -> {
            interaction.pendingDeselectOthers = false
            interaction.clickController.onPointerDraggedOrCancelled()
            change.consume()
            val start = interaction.coordinates?.localToWindow(startPosition) ?: startPosition
            actions.onStartRubberBand(entry.id, start, interaction.additiveSelection)
            GalleryPointerProgress(fileDragStarted = false, continueTracking = false)
        }
    }
}

/**
 * 处理画廊项主键释放事件，结束文件拖拽或派发最终的点击/打开/重命名动作。
 */
private fun finishGalleryPointer(
    releaseChange: PointerInputChange,
    currentState: State<GalleryItemState>,
    currentActions: State<GalleryItemActions>,
    interaction: GalleryInteractionState,
    fileDragStarted: Boolean,
    canInlineEditState: State<Boolean>,
    singleClickToOpen: Boolean,
    coroutineScope: CoroutineScope,
) {
    val state = currentState.value
    val actions = currentActions.value
    val entry = state.entry ?: return
    if (fileDragStarted) {
        actions.onFileDragEnd(interaction.dragPosition)
        interaction.dragPosition = null
        return
    }
    if (interaction.pendingDeselectOthers) {
        actions.onSelectEntry(entry.id, false, false)
        interaction.pendingDeselectOthers = false
    }
    val blockedByEditOrConsume = releaseChange.isConsumed || canInlineEditState.value || state.draftName != null
    if (blockedByEditOrConsume) {
        interaction.clickController.onPointerDraggedOrCancelled()
    } else {
        dispatchGalleryReleaseOutcome(
            entry = entry,
            releaseChange = releaseChange,
            currentState = currentState,
            currentActions = currentActions,
            interaction = interaction,
            canInlineEditState = canInlineEditState,
            singleClickToOpen = singleClickToOpen,
            coroutineScope = coroutineScope,
        )
    }
}

/**
 * 将画廊主键释放手势交由控制器决断并派发单击打开、双击打开或启动慢速重命名。
 */
private fun dispatchGalleryReleaseOutcome(
    entry: VFile,
    releaseChange: PointerInputChange,
    currentState: State<GalleryItemState>,
    currentActions: State<GalleryItemActions>,
    interaction: GalleryInteractionState,
    canInlineEditState: State<Boolean>,
    singleClickToOpen: Boolean,
    coroutineScope: CoroutineScope,
) {
    val state = currentState.value
    val actions = currentActions.value
    val outcome = interaction.clickController.onPrimaryRelease(
        FileEntryReleaseRequest(
            releaseMillis = releaseChange.uptimeMillis,
            inRenameLabel = interaction.isInRenameLabel(releaseChange.position),
            singleClickToOpen = singleClickToOpen,
            canStillRename = state.canScheduleRename(canInlineEditState.value),
        ),
    )
    when (outcome) {
        FileEntryReleaseOutcome.OPEN_SINGLE_CLICK,
        FileEntryReleaseOutcome.OPEN_DOUBLE_CLICK -> {
            interaction.clickController.cancelRename()
            actions.onActivate()
            actions.onDismissContextMenu()
            actions.onOpenEntry(entry)
        }

        FileEntryReleaseOutcome.SCHEDULE_RENAME -> {
            interaction.clickController.scheduleRename(coroutineScope) {
                if (currentState.value.canScheduleRename(canInlineEditState.value)) {
                    currentActions.value.onBeginRename()
                }
            }
        }

        FileEntryReleaseOutcome.NONE -> Unit
    }
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
    val canLoadRichThumbnail = entry != null && !isTrashItemLocation(entry.location)
    Box(modifier = modifier.clip(RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
        when {
            canLoadRichThumbnail && services.isImageFileName(entry.name) -> {
                ImageGalleryThumbnail(entry, sizeDp, services)
            }
            canLoadRichThumbnail && services.isArchiveFileName(entry.name) -> {
                ArchiveGalleryThumbnail(entry, sizeDp, services)
            }
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
    OnyxFileIcon(
        entry = entry,
        size = (sizeDp * GALLERY_FILE_ICON_SCALE).dp,
    )
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
            text = state.entry?.displayFileName(LocalOnyxAppearance.current.showFileExtensions).orEmpty(),
            modifier = Modifier.onGloballyPositioned { interaction.renameLabelCoordinates = it },
            fontWeight = if (state.entry?.kind == VFileKind.DIRECTORY) FontWeight.Medium else FontWeight.Normal,
            fontSize = 12.sp,
            color = state.labelStyle?.textColorArgb?.let(::Color)
                ?: LocalOnyxAppearance.current.fileNameColor
                ?: LocalOnyxPalette.current.foreground,
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
    val (fieldValue, onFieldValueChange) = rememberInlineEditFieldBinding(
        draftName = requireNotNull(state.draftName),
        selectWithoutExtension = state.selectNameWithoutExtension,
        onUpdateDraft = requireNotNull(actions.onUpdateInlineEditDraft),
    )
    BasicTextField(
        value = fieldValue,
        onValueChange = onFieldValueChange,
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
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) { innerTextField() }
        },
    )
}
