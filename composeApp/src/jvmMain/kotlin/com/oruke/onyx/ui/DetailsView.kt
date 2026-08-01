package com.oruke.onyx.ui

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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
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
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.InlineExpandedEntry
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
import com.oruke.onyx.ui.theme.resolve
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed

/** 空状态图标的弱化透明度。 */
private const val EMPTY_STATE_ICON_ALPHA = 0.4f

/** 指针移动超过此距离平方后才开始拖拽或框选。 */
private const val DRAG_START_DISTANCE_SQUARED = 36f

/** 确认单击后延迟请求重命名的时间。 */
private const val RENAME_AFTER_CLICK_DELAY_MS = 300L

// ── File entries ────────────────────────────────────────────────────────────

/**
 * 文件内容区域的视图、选择、编辑与树形展开状态。
 */
internal data class PaneEntriesViewModel(
    /** 当前视图模式。 */
    val viewMode: ViewMode,
    /** 详情列顺序。 */
    val columns: List<DetailsColumn>,
    /** 详情列宽度。 */
    val columnWeights: Map<DetailsColumn, Float>,
    /** 隐藏的详情列。 */
    val hiddenColumns: Set<DetailsColumn>,
    /** 当前排序。 */
    val sort: DetailsSort,
    /** 当前选中条目标识。 */
    val selectedEntryIds: Set<String>,
    /** 文件列表加载状态。 */
    val entriesState: PaneEntriesState,
    /** 所属面板是否活动。 */
    val paneActive: Boolean,
    /** 条目菜单是否显示。 */
    val contextMenuVisible: Boolean,
    /** 所属面板标识。 */
    val paneId: PaneId,
    /** 当前文件放置目标。 */
    val fileDropTarget: FileDropTarget?,
    /** 当前内联编辑状态。 */
    val inlineEditState: PaneInlineEditState?,
    /** 画廊项边长。 */
    val galleryItemSizeDp: Int = 160,
    /** 当前树形展开位置。 */
    val inlineExpandedLocations: Set<String> = emptySet(),
    /** 已加载的树形展开子项。 */
    val inlineExpandedEntries: Map<String, InlineExpandedEntry> = emptyMap(),
    /** 待滚动到的条目标识。 */
    val pendingScrollToEntryId: String? = null,
)

/**
 * 文件内容区域的导航、选择、编辑、拖放和菜单回调。
 */
internal data class PaneEntriesActions(
    /** 激活所属面板。 */
    val onActivate: () -> Unit,
    /** 打开条目。 */
    val onOpenEntry: (VFile) -> Unit,
    /** 切换排序列。 */
    val onToggleSort: (DetailsColumn) -> Unit,
    /** 调整列宽。 */
    val onResizeColumn: (DetailsColumn, Float) -> Unit,
    /** 切换列可见性。 */
    val onToggleColumnVisibility: (DetailsColumn) -> Unit,
    /** 选择单个条目。 */
    val onSelectEntry: (String, Boolean, Boolean) -> Unit,
    /** 开始文件拖拽。 */
    val onStartFileDrag: (PaneId, FileTransferOperation) -> Unit,
    /** 更新文件拖拽位置。 */
    val onFileDragPositionChange: (IntOffset) -> Unit,
    /** 结束文件拖拽。 */
    val onFileDragEnd: (IntOffset?) -> Unit,
    /** 注册目录放置区域。 */
    val onFileDropZoneChange: (FileDropZone) -> Unit,
    /** 显示条目菜单。 */
    val onShowContextMenu: (String, Boolean, IntOffset) -> Unit,
    /** 关闭条目菜单。 */
    val onDismissContextMenu: () -> Unit,
    /** 更新内联编辑草稿。 */
    val onUpdateInlineEditDraft: (String) -> Unit,
    /** 确认内联编辑。 */
    val onConfirmInlineEdit: () -> Unit,
    /** 取消内联编辑。 */
    val onCancelInlineEdit: () -> Unit,
    /** 开始重命名。 */
    val onBeginRename: () -> Unit = {},
    /** 批量设置条目选择。 */
    val onSelectEntries: (Set<String>) -> Unit = {},
    /** 切换树形展开位置。 */
    val onToggleInlineExpand: (String) -> Unit = {},
    /** 消费一次滚动请求。 */
    val onConsumeScroll: () -> Unit = {},
    /** 显示空白区域菜单。 */
    val onBlankAreaContextMenu: (IntOffset) -> Unit = {},
)

/** 子条目组件使用的文件拖拽与框选回调。 */
private data class PaneEntriesChildDragActions(
    /** 开始文件拖拽。 */
    val onStartFileDrag: (PaneId, FileTransferOperation) -> Unit,
    /** 结束文件拖拽。 */
    val onFileDragEnd: (IntOffset?) -> Unit,
    /** 从未选中条目开始框选。 */
    val onStartRubberBand: (String, androidx.compose.ui.geometry.Offset, Boolean) -> Unit,
)

/** 保存文件内容区域框选、条目坐标与文件拖拽互斥状态。 */
private class PaneRubberBandState {
    /** 当前已布局条目的坐标。 */
    val itemCoordinates = mutableStateMapOf<String, LayoutCoordinates>()
    /** 框选起点。 */
    var start by mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)
    /** 框选终点。 */
    var end by mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)
    /** 是否正在框选。 */
    var active by mutableStateOf(false)
    /** 是否在原选择上追加。 */
    var additive by mutableStateOf(false)
    /** 框选开始时保留的基础选择。 */
    var baseSelection by mutableStateOf(emptySet<String>())
    /** 是否正在执行文件拖拽。 */
    var fileDragging by mutableStateOf(false)
    /** 空白区域按下位置。 */
    var pendingOrigin by mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)
    /** 内容容器布局坐标。 */
    var containerCoordinates by mutableStateOf<LayoutCoordinates?>(null)

    /** 结束框选并清理起止点。 */
    fun finishRubberBand() {
        active = false
        start = null
        end = null
    }

    /**
     * 计算当前框选矩形命中的条目标识。
     *
     * @param current 当前框选终点。
     * @return 与框选矩形相交的条目标识。
     */
    fun hitEntryIds(current: androidx.compose.ui.geometry.Offset): Set<String> {
        val origin = start ?: return emptySet()
        val container = containerCoordinates ?: return emptySet()
        val selectionRect = androidx.compose.ui.geometry.Rect(
            left = minOf(origin.x, current.x),
            top = minOf(origin.y, current.y),
            right = maxOf(origin.x, current.x),
            bottom = maxOf(origin.y, current.y),
        )
        return itemCoordinates.filter { (_, coordinates) ->
            coordinates.isAttached &&
                container.localBoundingBoxOf(coordinates, clipBounds = false).overlaps(selectionRect)
        }.keys
    }

    /**
     * 判断容器局部坐标是否命中任意文件项。
     *
     * @param position 容器局部坐标。
     * @return 命中已附加条目时返回 true。
     */
    fun hitsItem(position: androidx.compose.ui.geometry.Offset): Boolean {
        val container = containerCoordinates ?: return false
        return itemCoordinates.values.any { coordinates ->
            coordinates.isAttached &&
                container.localBoundingBoxOf(coordinates, clipBounds = false).contains(position)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun PaneEntriesContent(
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    thumbnails: GalleryThumbnailServices,
) {
    when (val entriesState = model.entriesState) {
        PaneEntriesState.Idle, PaneEntriesState.Loading -> PaneEntriesLoading()
        is PaneEntriesState.Failure -> PaneEntriesFailure(entriesState)

        is PaneEntriesState.Ready -> {
            ReadyPaneEntries(model, actions, thumbnails, entriesState)
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
    val rowBackground = when {
        selected -> LocalOnyxPalette.current.selectionBackground
        zebra -> LocalOnyxPalette.current.surfaceVariant
        else -> Color.Transparent
    }

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
                                        // 失焦提交当前草稿，贴近系统文件管理器的内联重命名行为。
                                        onConfirmInlineEdit()
                                    }
                                },
                            textStyle = TextStyle(
                                fontSize = 12.sp,
                                color = LocalOnyxPalette.current.foreground,
                            ),
                            singleLine = true,
                            cursorBrush = SolidColor(LocalOnyxPalette.current.foreground),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.CenterStart,
                                ) { innerTextField() }
                            },
                        )
                    }
                }

                DetailsColumn.SIZE,
                DetailsColumn.MODIFIED,
                DetailsColumn.TYPE -> InlineEditPlaceholderCell(colWidth)
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
    onResizeColumn: (DetailsColumn, Float) -> Unit,
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
            visibleColumns.forEach { column ->
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
                DetailsColumnResizeGap(
                    onResize = { deltaPx ->
                        val deltaDp = with(density) { deltaPx / this.density }
                        onResizeColumn(column, deltaDp)
                    },
                )
            }
        }

        DetailsColumnVisibilityMenu(
            visible = showColumnMenu,
            columns = columns,
            hiddenColumns = hiddenColumns,
            onDismiss = { showColumnMenu = false },
            onToggleColumnVisibility = onToggleColumnVisibility,
        )
    }
}

/**
 * 渲染明细列可见性弹出菜单。
 *
 * @param visible 是否展示菜单。
 * @param columns 全部明细列。
 * @param hiddenColumns 当前隐藏列。
 * @param onDismiss 关闭菜单回调。
 * @param onToggleColumnVisibility 切换列可见性回调。
 */
@Composable
private fun DetailsColumnVisibilityMenu(
    visible: Boolean,
    columns: List<DetailsColumn>,
    hiddenColumns: Set<DetailsColumn>,
    onDismiss: () -> Unit,
    onToggleColumnVisibility: (DetailsColumn) -> Unit,
) {
    if (visible) {
        androidx.compose.ui.window.Popup(onDismissRequest = onDismiss, alignment = Alignment.TopStart) {
            Column(
                modifier = Modifier
                    .background(LocalOnyxPalette.current.floatingSurface, RoundedCornerShape(4.dp))
                    .border(1.dp, LocalOnyxPalette.current.outline, RoundedCornerShape(4.dp))
                    .padding(4.dp)
                    .width(160.dp),
            ) {
                Text(
                    text = stringResource(Res.string.label_column_visibility),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LocalOnyxPalette.current.mutedForeground,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
                columns.forEach { column ->
                    DetailsColumnVisibilityRow(
                        column = column,
                        visible = column !in hiddenColumns,
                        onToggle = { onToggleColumnVisibility(column) },
                    )
                }
            }
        }
    }
}

/**
 * 渲染单个明细列可见性选项。
 *
 * @param column 明细列。
 * @param visible 当前是否可见。
 * @param onToggle 切换回调。
 */
@Composable
private fun DetailsColumnVisibilityRow(
    column: DetailsColumn,
    visible: Boolean,
    onToggle: () -> Unit,
) {
    val isName = column == DetailsColumn.NAME
    val label = when (column) {
        DetailsColumn.NAME -> stringResource(Res.string.label_column_name)
        DetailsColumn.TYPE -> stringResource(Res.string.label_column_type)
        DetailsColumn.SIZE -> stringResource(Res.string.label_column_size)
        DetailsColumn.MODIFIED -> stringResource(Res.string.label_column_modified)
    }
    val palette = LocalOnyxPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isName, onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (visible) "✓" else " ",
            fontSize = 12.sp,
            color = if (isName) palette.disabledForeground else palette.accent,
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (isName) palette.disabledForeground else palette.foreground,
        )
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

/**
 * 详情行的显示与树形展开状态。
 */
internal data class EntryRowState(
    /** 当前列顺序。 */
    val columns: List<DetailsColumn>,
    /** 各列宽度。 */
    val columnWidths: Map<DetailsColumn, Float>,
    /** 当前文件条目。 */
    val entry: VFile,
    /** 是否显示斑马纹。 */
    val zebra: Boolean,
    /** 当前条目是否选中。 */
    val selected: Boolean,
    /** 面板选中条目数量。 */
    val selectedEntryCount: Int,
    /** 所属面板是否活动。 */
    val paneActive: Boolean,
    /** 所属面板标识。 */
    val paneId: PaneId,
    /** 当前文件放置目标。 */
    val fileDropTarget: FileDropTarget?,
    /** 树形展开深度。 */
    val depth: Int = 0,
    /** 当前目录是否展开。 */
    val isExpanded: Boolean = false,
    /** 当前条目是否允许展开。 */
    val isExpandable: Boolean = false,
    /** 当前目录子项是否正在加载。 */
    val isExpandLoading: Boolean = false,
)

/**
 * 详情行的选择、拖放、菜单与树形展开回调。
 */
internal data class EntryRowActions(
    /** 激活所属面板。 */
    val onActivate: () -> Unit,
    /** 打开当前条目。 */
    val onOpenEntry: (VFile) -> Unit,
    /** 更新条目选择。 */
    val onSelectEntry: (String, Boolean, Boolean) -> Unit,
    /** 开始重命名。 */
    val onBeginRename: () -> Unit = {},
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
    /** 从条目区域开始框选。 */
    val onStartRubberBand: (String, androidx.compose.ui.geometry.Offset, Boolean) -> Unit = { _, _, _ -> },
    /** 切换目录树形展开状态。 */
    val onToggleInlineExpand: (() -> Unit)? = null,
)

/** 保存详情行单次组合周期内的选择、拖拽和命中坐标。 */
private class EntryRowInteractionState {
    /** 是否使用增量选择。 */
    var additiveSelection by mutableStateOf(false)
    /** 是否使用范围选择。 */
    var rangeSelection by mutableStateOf(false)
    /** 当前文件拖拽操作。 */
    var dragOperation by mutableStateOf(FileTransferOperation.MOVE)
    /** 当前文件拖拽窗口坐标。 */
    var dragPosition by mutableStateOf<IntOffset?>(null)
    /** 详情行布局坐标。 */
    var rowCoordinates by mutableStateOf<LayoutCoordinates?>(null)
    /** 名称区域布局坐标。 */
    var nameCoordinates by mutableStateOf<LayoutCoordinates?>(null)
    /** 展开箭头布局坐标。 */
    var expandCoordinates by mutableStateOf<LayoutCoordinates?>(null)
    /** 是否等待松开后收敛多选。 */
    var pendingDeselectOthers by mutableStateOf(false)
    /** 本次主键按下时当前条目是否已满足慢速重命名条件。 */
    var pressCanRename by mutableStateOf(false)
    /** 慢速重命名定时任务。 */
    var renameJob by mutableStateOf<Job?>(null)

    /**
     * 判断行内坐标是否命中展开箭头。
     *
     * @param position 行内指针坐标。
     * @return 命中已附加的展开箭头时返回 true。
     */
    fun isInExpandArrow(position: androidx.compose.ui.geometry.Offset): Boolean {
        val arrow = expandCoordinates ?: return false
        val row = rowCoordinates ?: return false
        return arrow.isAttached && row.localBoundingBoxOf(arrow, clipBounds = false).contains(position)
    }

    /**
     * 判断行内坐标是否位于名称区域，坐标不可用时保守返回 true。
     *
     * @param position 行内指针坐标。
     * @return 位于名称区域时返回 true。
     */
    fun isInNameArea(position: androidx.compose.ui.geometry.Offset): Boolean {
        val name = nameCoordinates ?: return true
        val row = rowCoordinates ?: return true
        return !name.isAttached || row.localBoundingBoxOf(name, clipBounds = false).contains(position)
    }

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
            kotlinx.coroutines.delay(RENAME_AFTER_CLICK_DELAY_MS)
            onBeginRename()
        }
    }
}

/** 显示文件列表加载状态。 */
@Composable
private fun PaneEntriesLoading() {
    Box(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = stringResource(Res.string.label_loading_entries),
            color = LocalOnyxPalette.current.mutedForeground,
            fontSize = 12.sp,
        )
    }
}

/**
 * 显示文件列表加载失败状态。
 *
 * @param state 加载失败状态。
 */
@Composable
private fun PaneEntriesFailure(state: PaneEntriesState.Failure) {
    val message = buildString {
        append(stringResource(Res.string.label_error_prefix))
        append(' ')
        append(state.error.detail?.resolve().orEmpty())
    }.trim()
    Box(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(message, color = LocalOnyxPalette.current.foreground, fontSize = 12.sp)
    }
}

/**
 * 显示已加载文件内容，并协调框选与子条目拖拽。
 *
 * @param model 文件内容视图状态。
 * @param actions 文件内容交互回调。
 * @param thumbnails 缩略图服务。
 * @param ready 已加载文件列表。
 */
@Composable
private fun ReadyPaneEntries(
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    thumbnails: GalleryThumbnailServices,
    ready: PaneEntriesState.Ready,
) {
    val createInlineEntry = model.inlineEditState?.mode == PaneInlineEditMode.CREATE_FILE ||
        model.inlineEditState?.mode == PaneInlineEditMode.CREATE_DIRECTORY
    if (ready.entries.isEmpty() && !createInlineEntry) {
        EmptyPaneEntries(actions)
        return
    }
    val selection = remember { PaneRubberBandState() }
    val childDragActions = paneChildDragActions(model, actions, selection)
    Box(
        modifier = Modifier.fillMaxSize().clipToBounds()
            .paneSelectionInteractions(model, actions, selection),
    ) {
        Box(modifier = Modifier.fillMaxSize().paneRubberBandTracker(actions, selection))
        PaneEntriesViews(model, actions, thumbnails, ready, selection, childDragActions)
        PaneRubberBandOverlay(selection)
    }
}

/**
 * 显示空目录状态并允许在空白区域打开菜单。
 *
 * @param actions 文件内容交互回调。
 */
@Composable
private fun EmptyPaneEntries(actions: PaneEntriesActions) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val position = event.changes.firstOrNull()?.position
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && position != null) {
                        actions.onActivate()
                        actions.onBlankAreaContextMenu(IntOffset(position.x.toInt(), position.y.toInt()))
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
                modifier = Modifier.alpha(EMPTY_STATE_ICON_ALPHA),
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
}

/**
 * 将内容区拖拽回调包装为带框选互斥状态的子条目回调。
 *
 * @param model 文件内容视图状态。
 * @param actions 文件内容交互回调。
 * @param selection 框选状态。
 * @return 子条目拖拽回调。
 */
private fun paneChildDragActions(
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    selection: PaneRubberBandState,
): PaneEntriesChildDragActions = PaneEntriesChildDragActions(
    onStartFileDrag = { paneId, operation ->
        selection.fileDragging = true
        selection.pendingOrigin = null
        actions.onStartFileDrag(paneId, operation)
    },
    onFileDragEnd = { position ->
        selection.fileDragging = false
        actions.onFileDragEnd(position)
    },
    onStartRubberBand = { _, windowPosition, additive ->
        val container = selection.containerCoordinates
        if (container != null) {
            val start = container.windowToLocal(windowPosition)
            selection.pendingOrigin = null
            selection.active = true
            selection.start = start
            selection.end = start
            selection.additive = additive
            selection.baseSelection = if (additive) model.selectedEntryIds else emptySet()
            actions.onActivate()
            actions.onDismissContextMenu()
            if (!additive) actions.onSelectEntries(emptySet())
        }
    },
)

/**
 * 显示详情表头及当前视图模式对应的文件列表。
 *
 * @param model 文件内容视图状态。
 * @param actions 文件内容交互回调。
 * @param thumbnails 缩略图服务。
 * @param ready 已加载文件列表。
 * @param selection 框选状态。
 * @param dragActions 子条目拖拽回调。
 */
@Composable
private fun PaneEntriesViews(
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    thumbnails: GalleryThumbnailServices,
    ready: PaneEntriesState.Ready,
    selection: PaneRubberBandState,
    dragActions: PaneEntriesChildDragActions,
) {
    val horizontalScrollState = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize()) {
        if (model.viewMode == ViewMode.DETAILS) {
            DetailsHeader(
                columns = model.columns,
                columnWidths = model.columnWeights,
                hiddenColumns = model.hiddenColumns,
                sort = model.sort,
                onToggleSort = actions.onToggleSort,
                onResizeColumn = actions.onResizeColumn,
                onToggleColumnVisibility = actions.onToggleColumnVisibility,
                scrollState = horizontalScrollState,
            )
            DetailsEntriesList(model, actions, ready, selection, horizontalScrollState, dragActions)
        } else {
            GalleryEntriesGrid(model, actions, thumbnails, ready, selection, dragActions)
        }
    }
}

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
private fun GalleryEntriesGrid(
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    thumbnails: GalleryThumbnailServices,
    ready: PaneEntriesState.Ready,
    selection: PaneRubberBandState,
    dragActions: PaneEntriesChildDragActions,
) {
    val gridState = rememberLazyGridState()
    val inlineEdit = model.inlineEditState
    val createInlineEntry = inlineEdit?.mode == PaneInlineEditMode.CREATE_FILE ||
        inlineEdit?.mode == PaneInlineEditMode.CREATE_DIRECTORY
    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(model.galleryItemSizeDp.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
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
        model.pendingScrollToEntryId?.let { entryId ->
            LaunchedEffect(entryId) {
                val offset = if (createInlineEntry) 1 else 0
                val index = ready.entries.indexOfFirst { it.id == entryId }
                if (index >= 0) gridState.animateScrollToItem(index + offset)
                actions.onConsumeScroll()
            }
        }
    }
}

/**
 * 显示画廊中的新建文件占位编辑项。
 *
 * @param model 文件内容视图状态。
 * @param actions 文件内容交互回调。
 * @param thumbnails 缩略图服务。
 * @param dragActions 子条目拖拽回调。
 */
@Composable
private fun GalleryCreateEntry(
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    thumbnails: GalleryThumbnailServices,
    dragActions: PaneEntriesChildDragActions,
) {
    GalleryItem(
        state = GalleryItemState(
            entry = null,
            draftName = model.inlineEditState?.draftName.orEmpty(),
            selected = false,
            selectedEntryCount = 0,
            paneActive = model.paneActive,
            paneId = model.paneId,
            fileDropTarget = model.fileDropTarget,
            sizeDp = model.galleryItemSizeDp,
        ),
        actions = galleryEntryActions(actions, dragActions, inlineEditing = true),
        thumbnails = thumbnails,
    )
}

/**
 * 显示画廊中的普通文件项并注册其框选坐标。
 *
 * @param model 文件内容视图状态。
 * @param actions 文件内容交互回调。
 * @param thumbnails 缩略图服务。
 * @param dragActions 子条目拖拽回调。
 * @param selection 框选状态。
 * @param entry 当前文件条目。
 */
@Composable
private fun GalleryFileEntry(
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    thumbnails: GalleryThumbnailServices,
    dragActions: PaneEntriesChildDragActions,
    selection: PaneRubberBandState,
    entry: VFile,
) {
    val editing = model.inlineEditState?.mode == PaneInlineEditMode.RENAME &&
        model.inlineEditState.targetEntryId == entry.id
    Box(modifier = Modifier.onGloballyPositioned { selection.itemCoordinates[entry.id] = it }) {
        GalleryItem(
            state = GalleryItemState(
                entry = entry,
                draftName = model.inlineEditState?.draftName?.takeIf { editing },
                selected = entry.id in model.selectedEntryIds,
                selectedEntryCount = model.selectedEntryIds.size,
                paneActive = model.paneActive,
                paneId = model.paneId,
                fileDropTarget = model.fileDropTarget,
                sizeDp = model.galleryItemSizeDp,
            ),
            actions = galleryEntryActions(actions, dragActions, editing),
            thumbnails = thumbnails,
        )
    }
}

/**
 * 将内容区回调适配为画廊项回调。
 *
 * @param actions 文件内容交互回调。
 * @param dragActions 子条目拖拽回调。
 * @param inlineEditing 当前条目是否正在内联编辑。
 * @return 画廊项交互回调。
 */
private fun galleryEntryActions(
    actions: PaneEntriesActions,
    dragActions: PaneEntriesChildDragActions,
    inlineEditing: Boolean,
): GalleryItemActions = GalleryItemActions(
    onActivate = actions.onActivate,
    onOpenEntry = actions.onOpenEntry,
    onSelectEntry = actions.onSelectEntry,
    onStartFileDrag = dragActions.onStartFileDrag,
    onFileDragPositionChange = actions.onFileDragPositionChange,
    onFileDragEnd = dragActions.onFileDragEnd,
    onFileDropZoneChange = actions.onFileDropZoneChange,
    onShowContextMenu = actions.onShowContextMenu,
    onDismissContextMenu = actions.onDismissContextMenu,
    onUpdateInlineEditDraft = actions.onUpdateInlineEditDraft.takeIf { inlineEditing },
    onConfirmInlineEdit = actions.onConfirmInlineEdit.takeIf { inlineEditing },
    onCancelInlineEdit = actions.onCancelInlineEdit.takeIf { inlineEditing },
    onStartRubberBand = dragActions.onStartRubberBand,
    onBeginRename = actions.onBeginRename,
)

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
private fun DetailsEntriesList(
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
    val listState = rememberLazyListState()
    val createInlineEntry = model.inlineEditState?.mode == PaneInlineEditMode.CREATE_FILE ||
        model.inlineEditState?.mode == PaneInlineEditMode.CREATE_DIRECTORY
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(bottom = 12.dp),
            contentPadding = PaddingValues(bottom = 4.dp),
            userScrollEnabled = !model.contextMenuVisible,
        ) {
            if (createInlineEntry) {
                item(key = "inline-create") { DetailsCreateEntry(model, actions, horizontalScrollState) }
            }
            itemsIndexed(flattenedEntries, key = { _, item -> "${item.entry.location}@${item.depth}" }) {
                    index, item ->
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
        model.pendingScrollToEntryId?.let { entryId ->
            LaunchedEffect(entryId) {
                val offset = if (createInlineEntry) 1 else 0
                val index = flattenedEntries.indexOfFirst { it.entry.id == entryId }
                if (index >= 0) listState.animateScrollToItem(index + offset)
                actions.onConsumeScroll()
            }
        }
    }
}

/**
 * 显示详情列表中的新建文件占位编辑行。
 *
 * @param model 文件内容视图状态。
 * @param actions 文件内容交互回调。
 * @param scrollState 横向滚动状态。
 */
@Composable
private fun DetailsCreateEntry(
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    scrollState: androidx.compose.foundation.ScrollState,
) {
    InlineEditEntryRow(
        columns = model.columns,
        columnWidths = model.columnWeights,
        draftName = model.inlineEditState?.draftName.orEmpty(),
        iconKey = if (model.inlineEditState?.mode == PaneInlineEditMode.CREATE_DIRECTORY) {
            AllIconsKeys.Nodes.Folder
        } else {
            AllIconsKeys.FileTypes.Any_type
        },
        selected = false,
        zebra = false,
        onUpdateInlineEditDraft = actions.onUpdateInlineEditDraft,
        onConfirmInlineEdit = actions.onConfirmInlineEdit,
        onCancelInlineEdit = actions.onCancelInlineEdit,
        onDismissContextMenu = actions.onDismissContextMenu,
        scrollState = scrollState,
    )
}

/**
 * 显示详情树中的普通行或重命名编辑行。
 *
 * @param model 文件内容视图状态。
 * @param actions 文件内容交互回调。
 * @param dragActions 子条目拖拽回调。
 * @param selection 框选状态。
 * @param scrollState 横向滚动状态。
 * @param index 扁平列表索引。
 * @param item 当前扁平树节点。
 */
@Composable
private fun DetailsFileEntry(
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    dragActions: PaneEntriesChildDragActions,
    selection: PaneRubberBandState,
    scrollState: androidx.compose.foundation.ScrollState,
    index: Int,
    item: FlattenedEntry,
) {
    val entry = item.entry
    val editing = model.inlineEditState?.mode == PaneInlineEditMode.RENAME &&
        model.inlineEditState.targetEntryId == entry.id
    Box(modifier = Modifier.onGloballyPositioned { selection.itemCoordinates[entry.id] = it }) {
        if (editing) {
            InlineEditEntryRow(
                columns = model.columns,
                columnWidths = model.columnWeights,
                draftName = model.inlineEditState.draftName,
                iconKey = if (entry.kind == VFileKind.DIRECTORY) {
                    AllIconsKeys.Nodes.Folder
                } else {
                    AllIconsKeys.FileTypes.Any_type
                },
                selected = entry.id in model.selectedEntryIds,
                zebra = index % 2 == 1,
                onUpdateInlineEditDraft = actions.onUpdateInlineEditDraft,
                onConfirmInlineEdit = actions.onConfirmInlineEdit,
                onCancelInlineEdit = actions.onCancelInlineEdit,
                onDismissContextMenu = actions.onDismissContextMenu,
                scrollState = scrollState,
            )
        } else {
            DetailsReadyEntryRow(model, actions, dragActions, scrollState, index, item)
        }
    }
}

/**
 * 显示未处于编辑状态的详情树文件行。
 *
 * @param model 文件内容视图状态。
 * @param actions 文件内容交互回调。
 * @param dragActions 子条目拖拽回调。
 * @param scrollState 横向滚动状态。
 * @param index 扁平列表索引。
 * @param item 当前扁平树节点。
 */
@Composable
private fun DetailsReadyEntryRow(
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    dragActions: PaneEntriesChildDragActions,
    scrollState: androidx.compose.foundation.ScrollState,
    index: Int,
    item: FlattenedEntry,
) {
    val entry = item.entry
    EntryRow(
        state = EntryRowState(
            columns = model.columns,
            columnWidths = model.columnWeights,
            entry = entry,
            zebra = index % 2 == 1,
            selected = entry.id in model.selectedEntryIds,
            selectedEntryCount = model.selectedEntryIds.size,
            paneActive = model.paneActive,
            paneId = model.paneId,
            fileDropTarget = model.fileDropTarget,
            depth = item.depth,
            isExpanded = item.isExpanded,
            isExpandable = item.isExpandable,
            isExpandLoading = item.isLoading,
        ),
        actions = EntryRowActions(
            onActivate = actions.onActivate,
            onOpenEntry = actions.onOpenEntry,
            onSelectEntry = actions.onSelectEntry,
            onBeginRename = actions.onBeginRename,
            onStartFileDrag = dragActions.onStartFileDrag,
            onFileDragPositionChange = actions.onFileDragPositionChange,
            onFileDragEnd = dragActions.onFileDragEnd,
            onFileDropZoneChange = actions.onFileDropZoneChange,
            onShowContextMenu = actions.onShowContextMenu,
            onDismissContextMenu = actions.onDismissContextMenu,
            onStartRubberBand = dragActions.onStartRubberBand,
            onToggleInlineExpand = { actions.onToggleInlineExpand(entry.location) }.takeIf { item.isExpandable },
        ),
        scrollState = scrollState,
    )
}

/**
 * 注册内容容器坐标并处理空白区按下、移动和松开。
 *
 * @param model 文件内容视图状态。
 * @param actions 文件内容交互回调。
 * @param selection 框选状态。
 * @return 添加框选交互后的修饰符。
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.paneSelectionInteractions(
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    selection: PaneRubberBandState,
): Modifier = onGloballyPositioned { selection.containerCoordinates = it }
    .onPointerEvent(PointerEventType.Press) { event ->
        handlePaneSelectionPress(event, actions, selection)
    }
    .onPointerEvent(PointerEventType.Move) { event ->
        handlePaneSelectionMove(event, model, actions, selection)
    }
    .onPointerEvent(PointerEventType.Release) { event ->
        handlePaneSelectionRelease(event, actions, selection)
    }

/**
 * 处理内容空白区按下并区分菜单、条目与框选起点。
 *
 * @param event 当前指针事件。
 * @param actions 文件内容交互回调。
 * @param selection 框选状态。
 */
private fun handlePaneSelectionPress(
    event: PointerEvent,
    actions: PaneEntriesActions,
    selection: PaneRubberBandState,
) {
    if (event.changes.any { it.isConsumed }) return
    val position = event.changes.firstOrNull()?.position ?: return
    val hitsItem = selection.hitsItem(position)
    if (event.buttons.isSecondaryPressed && !event.buttons.isPrimaryPressed && !hitsItem) {
        actions.onActivate()
        actions.onSelectEntries(emptySet())
        val windowPosition = selection.containerCoordinates?.localToWindow(position) ?: position
        actions.onBlankAreaContextMenu(IntOffset(windowPosition.x.toInt(), windowPosition.y.toInt()))
    } else if (event.buttons.isPrimaryPressed) {
        if (hitsItem) {
            selection.pendingOrigin = null
        } else {
            selection.pendingOrigin = position
            selection.active = false
            selection.fileDragging = false
        }
    }
}

/**
 * 处理空白区拖拽启动及框选矩形持续更新。
 *
 * @param event 当前指针事件。
 * @param model 文件内容视图状态。
 * @param actions 文件内容交互回调。
 * @param selection 框选状态。
 */
private fun handlePaneSelectionMove(
    event: PointerEvent,
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    selection: PaneRubberBandState,
) {
    if (selection.fileDragging || event.changes.any { it.isConsumed }) return
    val position = event.changes.firstOrNull()?.position ?: return
    val origin = selection.pendingOrigin
    if (origin != null && !selection.active) {
        val dx = position.x - origin.x
        val dy = position.y - origin.y
        if (dx * dx + dy * dy > DRAG_START_DISTANCE_SQUARED) {
            selection.active = true
            selection.start = origin
            selection.additive = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
            selection.baseSelection = if (selection.additive) model.selectedEntryIds else emptySet()
            actions.onActivate()
            actions.onDismissContextMenu()
            if (!selection.additive) actions.onSelectEntries(emptySet())
        }
    }
    if (selection.active) {
        selection.end = position
        event.changes.forEach { it.consume() }
        actions.onSelectEntries(selection.baseSelection + selection.hitEntryIds(position))
    }
}

/**
 * 处理空白区点击清空选择并结束框选。
 *
 * @param event 当前指针事件。
 * @param actions 文件内容交互回调。
 * @param selection 框选状态。
 */
private fun handlePaneSelectionRelease(
    event: PointerEvent,
    actions: PaneEntriesActions,
    selection: PaneRubberBandState,
) {
    val position = event.changes.firstOrNull()?.position
    val blankClick = !selection.active && selection.pendingOrigin != null
    if (blankClick && position != null && !selection.hitsItem(position)) {
        actions.onSelectEntries(emptySet())
    }
    selection.pendingOrigin = null
    selection.fileDragging = false
    if (selection.active) selection.finishRubberBand()
}

/**
 * 在条目触发框选后接管指针直到松开。
 *
 * @param actions 文件内容交互回调。
 * @param selection 框选状态。
 * @return 添加持续框选手势后的修饰符。
 */
private fun Modifier.paneRubberBandTracker(
    actions: PaneEntriesActions,
    selection: PaneRubberBandState,
): Modifier = if (!selection.active) {
    this
} else {
    pointerInput(Unit) {
        awaitPointerEventScope {
            var current = awaitFirstDown(requireUnconsumed = false)
            while (current.pressed) {
                selection.end = current.position
                actions.onSelectEntries(selection.baseSelection + selection.hitEntryIds(current.position))
                current = awaitDragOrCancellation(current.id) ?: break
                current.consume()
            }
            selection.finishRubberBand()
        }
    }
}

/**
 * 绘制当前框选矩形。
 *
 * @param selection 框选状态。
 */
@Composable
private fun PaneRubberBandOverlay(selection: PaneRubberBandState) {
    val start = selection.start ?: return
    val end = selection.end ?: return
    val left = minOf(start.x, end.x)
    val top = minOf(start.y, end.y)
    val width = maxOf(start.x, end.x) - left
    val height = maxOf(start.y, end.y) - top
    Box(
        modifier = Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(
                width = with(LocalDensity.current) { width.toDp() },
                height = with(LocalDensity.current) { height.toDp() },
            )
            .background(
                LocalOnyxPalette.current.selectionBackground.copy(alpha = 0.25f),
                RoundedCornerShape(2.dp),
            )
            .border(1.dp, LocalOnyxPalette.current.outline.copy(alpha = 0.6f), RoundedCornerShape(2.dp)),
    )
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun EntryRow(
    state: EntryRowState,
    actions: EntryRowActions,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
) {
    val interaction = remember(state.entry.id) { EntryRowInteractionState() }
    val selectedState = rememberUpdatedState(state.selected)
    val entryCountState = rememberUpdatedState(state.selectedEntryCount)
    val coroutineScope = rememberCoroutineScope()
    val rowBackground = entryRowBackground(state)

    Row(
        modifier = Modifier
            .horizontalScroll(scrollState)
            .entryRowPressInteractions(state, actions, interaction)
            .entryRowDragInteractions(state, actions, interaction, selectedState)
            .background(rowBackground)
            .combinedClickable(
                onClick = {
                    actions.onActivate()
                    val canRename = interaction.pressCanRename &&
                        selectedState.value && entryCountState.value == 1
                    if (canRename) {
                        interaction.scheduleRename(coroutineScope) {
                            if (selectedState.value && entryCountState.value == 1) actions.onBeginRename()
                        }
                    }
                },
                onDoubleClick = {
                    interaction.cancelRename()
                    actions.onActivate()
                    actions.onDismissContextMenu()
                    actions.onOpenEntry(state.entry)
                },
            )
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .height(LocalOnyxAppearance.current.listRowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DetailsColumnGap),
    ) {
        EntryRowCells(
            state = state,
            actions = actions,
            onNameCoordinates = { interaction.nameCoordinates = it },
            onExpandCoordinates = { interaction.expandCoordinates = it },
        )
    }
}

/**
 * 注册详情行坐标并处理左右键按下。
 *
 * @param state 详情行显示状态。
 * @param actions 详情行交互回调。
 * @param interaction 指针交互状态。
 * @return 添加按下交互后的修饰符。
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.entryRowPressInteractions(
    state: EntryRowState,
    actions: EntryRowActions,
    interaction: EntryRowInteractionState,
): Modifier = onGloballyPositioned { interaction.rowCoordinates = it }
    .onPointerEvent(PointerEventType.Press) { event ->
        handleEntryRowPress(event, state, actions, interaction)
    }

/**
 * 处理详情行按下事件中的选择、展开和菜单。
 *
 * @param event 当前指针事件。
 * @param state 详情行显示状态。
 * @param actions 详情行交互回调。
 * @param interaction 指针交互状态。
 */
private fun handleEntryRowPress(
    event: PointerEvent,
    state: EntryRowState,
    actions: EntryRowActions,
    interaction: EntryRowInteractionState,
) {
    val modified = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
    interaction.additiveSelection = modified
    interaction.rangeSelection = event.keyboardModifiers.isShiftPressed
    interaction.dragOperation = if (modified) FileTransferOperation.COPY else FileTransferOperation.MOVE
    val pointer = event.changes.firstOrNull()?.position ?: return
    if (event.buttons.isSecondaryPressed) {
        interaction.cancelRename()
        val windowPosition = interaction.rowCoordinates?.localToWindow(pointer) ?: pointer
        actions.onActivate()
        actions.onShowContextMenu(
            state.entry.id,
            state.selected,
            IntOffset(windowPosition.x.roundToInt(), windowPosition.y.roundToInt()),
        )
    } else if (event.buttons.isPrimaryPressed) {
        handleEntryRowPrimaryPress(state, actions, interaction, pointer)
    }
}

/**
 * 处理主键按下后的树形展开与选择。
 *
 * @param state 详情行显示状态。
 * @param actions 详情行交互回调。
 * @param interaction 指针交互状态。
 * @param pointer 行内指针坐标。
 */
private fun handleEntryRowPrimaryPress(
    state: EntryRowState,
    actions: EntryRowActions,
    interaction: EntryRowInteractionState,
    pointer: androidx.compose.ui.geometry.Offset,
) {
    interaction.cancelRename()
    interaction.pressCanRename = false
    val toggleExpand = actions.onToggleInlineExpand
    if (toggleExpand != null && interaction.isInExpandArrow(pointer)) {
        actions.onActivate()
        toggleExpand()
        return
    }
    actions.onActivate()
    actions.onDismissContextMenu()
    interaction.pendingDeselectOthers = false
    interaction.pressCanRename = state.selected && state.selectedEntryCount == 1 &&
        !interaction.additiveSelection && !interaction.rangeSelection
    when {
        interaction.additiveSelection || interaction.rangeSelection -> actions.onSelectEntry(
            state.entry.id,
            interaction.additiveSelection,
            interaction.rangeSelection,
        )
        state.selectedEntryCount > 1 -> interaction.pendingDeselectOthers = true
        else -> actions.onSelectEntry(state.entry.id, false, false)
    }
}

/**
 * 处理详情行文件拖拽、框选和松开后的选择收敛。
 *
 * @param state 详情行显示状态。
 * @param actions 详情行交互回调。
 * @param interaction 指针交互状态。
 * @param selectedState 最新选中状态。
 * @return 添加拖拽手势后的修饰符。
 */
private fun Modifier.entryRowDragInteractions(
    state: EntryRowState,
    actions: EntryRowActions,
    interaction: EntryRowInteractionState,
    selectedState: State<Boolean>,
): Modifier = pointerInput(state.entry.id, state.paneId) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (actions.onToggleInlineExpand != null && interaction.isInExpandArrow(down.position)) {
                waitForEntryPointerRelease()
            } else {
                trackEntryRowPointer(down.position, state, actions, interaction, selectedState.value)
            }
        }
    }
}

/** 等待展开箭头按下后的全部指针松开，避免误触文件拖拽。 */
private suspend fun AwaitPointerEventScope.waitForEntryPointerRelease() {
    while (true) {
        if (awaitPointerEvent().changes.none { it.pressed }) return
    }
}

/**
 * 跟踪一次详情行按下后的拖拽或框选过程。
 *
 * @param startPosition 初始行内坐标。
 * @param state 详情行显示状态。
 * @param actions 详情行交互回调。
 * @param interaction 指针交互状态。
 * @param selectedAtPress 按下瞬间的选中状态。
 */
private suspend fun AwaitPointerEventScope.trackEntryRowPointer(
    startPosition: androidx.compose.ui.geometry.Offset,
    state: EntryRowState,
    actions: EntryRowActions,
    interaction: EntryRowInteractionState,
    selectedAtPress: Boolean,
) {
    var fileDragStarted = false
    var tracking = true
    val inNameArea = interaction.isInNameArea(startPosition)
    while (tracking) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull()
        if (change == null) {
            tracking = false
        } else if (!change.pressed) {
            finishEntryRowPointer(state, actions, interaction, fileDragStarted)
            tracking = false
        } else {
            val progress = updateEntryRowPointer(
                event,
                change,
                startPosition,
                state,
                actions,
                interaction,
                selectedAtPress,
                inNameArea,
                fileDragStarted,
            )
            fileDragStarted = progress.fileDragStarted
            tracking = progress.continueTracking
        }
    }
}

/** 一次详情行指针移动处理后的拖拽和跟踪状态。 */
private data class EntryPointerProgress(
    /** 是否已开始文件拖拽。 */
    val fileDragStarted: Boolean,
    /** 是否继续等待后续指针事件。 */
    val continueTracking: Boolean,
)

/**
 * 处理详情行按住状态下的一次指针移动。
 *
 * @param event 当前指针事件。
 * @param change 当前主指针变更。
 * @param startPosition 初始行内坐标。
 * @param state 详情行显示状态。
 * @param actions 详情行交互回调。
 * @param interaction 指针交互状态。
 * @param selectedAtPress 按下瞬间是否选中。
 * @param inNameArea 按下位置是否在名称区域。
 * @param fileDragStarted 是否已开始文件拖拽。
 * @return 更新后的拖拽与跟踪状态。
 */
private fun updateEntryRowPointer(
    event: PointerEvent,
    change: PointerInputChange,
    startPosition: androidx.compose.ui.geometry.Offset,
    state: EntryRowState,
    actions: EntryRowActions,
    interaction: EntryRowInteractionState,
    selectedAtPress: Boolean,
    inNameArea: Boolean,
    fileDragStarted: Boolean,
): EntryPointerProgress {
    val dx = change.position.x - startPosition.x
    val dy = change.position.y - startPosition.y
    val crossedThreshold = dx * dx + dy * dy > DRAG_START_DISTANCE_SQUARED
    return when {
        fileDragStarted -> {
            change.consume()
            updateEntryRowFileDrag(actions, interaction, change.position)
            EntryPointerProgress(fileDragStarted = true, continueTracking = true)
        }
        !crossedThreshold -> EntryPointerProgress(fileDragStarted = false, continueTracking = true)
        selectedAtPress || inNameArea -> {
            prepareEntryRowDrag(interaction, change)
            if (!selectedAtPress) actions.onSelectEntry(state.entry.id, false, false)
            startEntryRowFileDrag(state, actions, interaction, change.position)
            EntryPointerProgress(fileDragStarted = true, continueTracking = true)
        }
        else -> {
            prepareEntryRowDrag(interaction, change)
            val modified = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
            val windowStart = interaction.rowCoordinates?.localToWindow(startPosition) ?: startPosition
            actions.onStartRubberBand(state.entry.id, windowStart, modified)
            EntryPointerProgress(fileDragStarted = false, continueTracking = false)
        }
    }
}

/**
 * 清理点击收敛与重命名计时，并消费开始拖拽的指针变更。
 *
 * @param interaction 指针交互状态。
 * @param change 越过拖拽阈值的指针变更。
 */
private fun prepareEntryRowDrag(interaction: EntryRowInteractionState, change: PointerInputChange) {
    interaction.pendingDeselectOthers = false
    interaction.cancelRename()
    change.consume()
}

/**
 * 开始详情行文件拖拽并上报首个窗口坐标。
 *
 * @param state 详情行显示状态。
 * @param actions 详情行交互回调。
 * @param interaction 指针交互状态。
 * @param position 当前行内坐标。
 */
private fun startEntryRowFileDrag(
    state: EntryRowState,
    actions: EntryRowActions,
    interaction: EntryRowInteractionState,
    position: androidx.compose.ui.geometry.Offset,
) {
    interaction.dragPosition = interaction.rowCoordinates?.localToWindow(position)?.toIntOffset()
    actions.onStartFileDrag(state.paneId, interaction.dragOperation)
    interaction.dragPosition?.let(actions.onFileDragPositionChange)
}

/**
 * 更新详情行文件拖拽窗口坐标。
 *
 * @param actions 详情行交互回调。
 * @param interaction 指针交互状态。
 * @param position 当前行内坐标。
 */
private fun updateEntryRowFileDrag(
    actions: EntryRowActions,
    interaction: EntryRowInteractionState,
    position: androidx.compose.ui.geometry.Offset,
) {
    interaction.dragPosition = interaction.rowCoordinates?.localToWindow(position)?.toIntOffset()
    interaction.dragPosition?.let(actions.onFileDragPositionChange)
}

/**
 * 完成文件拖拽，或在普通点击后收敛多选。
 *
 * @param state 详情行显示状态。
 * @param actions 详情行交互回调。
 * @param interaction 指针交互状态。
 * @param fileDragStarted 是否已开始文件拖拽。
 */
private fun finishEntryRowPointer(
    state: EntryRowState,
    actions: EntryRowActions,
    interaction: EntryRowInteractionState,
    fileDragStarted: Boolean,
) {
    if (fileDragStarted) {
        actions.onFileDragEnd(interaction.dragPosition)
        interaction.dragPosition = null
    } else if (interaction.pendingDeselectOthers) {
        actions.onSelectEntry(state.entry.id, false, false)
        interaction.pendingDeselectOthers = false
    }
}
