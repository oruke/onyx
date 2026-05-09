package com.oruke.onyx.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.oruke.onyx.app.component.FileTransferOperation
import com.oruke.onyx.app.component.PaneComponent
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.app.filesystem.VfsBreadcrumb
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.app.filesystem.OpenWithApp
import com.oruke.onyx.ui.theme.FileDropTarget
import com.oruke.onyx.ui.theme.FileDropZone
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.TabDropZone
import com.oruke.onyx.ui.theme.windowBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_filter
import onyx.composeapp.generated.resources.action_go_back
import onyx.composeapp.generated.resources.action_go_forward
import onyx.composeapp.generated.resources.action_go_home
import onyx.composeapp.generated.resources.action_go_up
import onyx.composeapp.generated.resources.action_refresh_active
import onyx.composeapp.generated.resources.action_toggle_favorite
import onyx.composeapp.generated.resources.action_toggle_hidden_files
import onyx.composeapp.generated.resources.label_filter_placeholder
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// ── Pane surface ───────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun PaneSurface(
    state: PaneState,
    active: Boolean,
    component: PaneComponent,
    actions: PaneActions,
    modifier: Modifier = Modifier,
    onActivate: () -> Unit,
    canPaste: Boolean,
    favoriteLocations: List<String>,
    // ── 拖放相关 ──
    onDropTab: (PaneId, String, IntOffset) -> Unit,
    onTabDragPositionChange: (IntOffset) -> Unit,
    onTabDragEnd: () -> Unit,
    onTabDropZoneChange: (PaneId, TabDropZone) -> Unit,
    tabDropIndicatorIndex: Int?,
    onFileDragStart: (PaneId, FileTransferOperation) -> Unit,
    onFileDragPositionChange: (IntOffset) -> Unit,
    onFileDragEnd: (IntOffset?) -> Unit,
    onFileDropZoneChange: (FileDropZone) -> Unit,
    fileDropTarget: FileDropTarget?,
    loadThumbnail: suspend (String, Int) -> ImageBitmap?,
    loadArchiveThumbnail: suspend (String, Int) -> ImageBitmap?,
    buildBreadcrumbs: (String) -> List<VfsBreadcrumb>,
) {
    // ── 从 state / component / actions 派生，消除冗余参数 ──
    val filterQuery = state.filterQuery
    val inlineEditState = state.inlineEditState
    val focusRequester = remember { FocusRequester() }
    val filterFocusRequester = remember { FocusRequester() }
    var showFilterBar by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuOffset by remember { mutableStateOf(IntOffset.Zero) }
    var contextMenuOpenWithApps by remember { mutableStateOf<List<OpenWithApp>>(emptyList()) }
    var paneBounds by remember { mutableStateOf<IntRect?>(null) }
    var tabBarDropZone by remember { mutableStateOf<TabDropZone?>(null) }
    val tabStack by component.tabStack.subscribeAsState()
    val tabBarState = PaneTabBarState(
        activeTabId = state.activeTabId,
        tabs = tabStack.items.map { child ->
            PaneTabItemState(
                id = child.configuration.id,
                title = child.instance.state.value.title,
            )
        },
    )
    fun dispatch(intent: PaneIntent) {
        component.dispatch(intent)
    }
    val paneDropBackground by animateColorAsState(
        targetValue = if (fileDropTarget?.paneId == state.paneId &&
            fileDropTarget.directoryEntryId == null &&
            fileDropTarget.targetDirectoryLocation == state.location
        ) {
            LocalOnyxPalette.current.rowHoverBackground.copy(alpha = 0.28f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 120),
    )

    fun reportPaneDropZone() {
        val tabDropZone = tabBarDropZone ?: return
        onTabDropZoneChange(
            state.paneId,
            tabDropZone.copy(bounds = paneBounds ?: tabDropZone.bounds),
        )
    }

    LaunchedEffect(active) {
        if (active) focusRequester.requestFocus()
    }

    Column(
        modifier = modifier
            .border(
                width = 1.dp,
                color = if (active) LocalOnyxPalette.current.outline else LocalOnyxPalette.current.outlineVariant,
            )
            .background(LocalOnyxPalette.current.surface)
            .onGloballyPositioned { coordinates ->
                paneBounds = coordinates.windowBounds()
                reportPaneDropZone()
            }
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (!active || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (inlineEditState != null) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.Enter -> {
                            dispatch(PaneIntent.ConfirmInlineEdit)
                            true
                        }

                        Key.Escape -> {
                            dispatch(PaneIntent.CancelInlineEdit)
                            true
                        }

                        // 内联编辑期间，其他所有按键交给 BasicTextField 处理
                        else -> false
                    }
                }

                when {
                    event.key == Key.Enter -> {
                        dispatch(PaneIntent.OpenSelectedEntry)
                        true
                    }

                    event.key == Key.F2 -> {
                        dispatch(PaneIntent.BeginRename)
                        true
                    }

                    event.key == Key.N && (event.isCtrlPressed || event.isMetaPressed) -> {
                        if (event.isShiftPressed) {
                            actions.onBeginCreateDirectory()
                        } else {
                            dispatch(PaneIntent.BeginCreateFile)
                        }
                        true
                    }

                    event.key == Key.C && (event.isCtrlPressed || event.isMetaPressed) -> {
                        actions.onCopySelection()
                        true
                    }

                    event.key == Key.X && (event.isCtrlPressed || event.isMetaPressed) -> {
                        actions.onCutSelection()
                        true
                    }

                    event.key == Key.V && (event.isCtrlPressed || event.isMetaPressed) -> {
                        actions.onPaste()
                        true
                    }

                    event.key == Key.DirectionDown -> {
                        dispatch(PaneIntent.MoveSelection(offset = 1, extendSelection = event.isShiftPressed))
                        true
                    }

                    event.key == Key.DirectionUp -> {
                        dispatch(PaneIntent.MoveSelection(offset = -1, extendSelection = event.isShiftPressed))
                        true
                    }

                    event.key == Key.Delete -> {
                        if (state.selectedEntryIds.isNotEmpty()) {
                            actions.onDeleteSelection()
                            true
                        } else {
                            false
                        }
                    }

                    event.key == Key.A && (event.isCtrlPressed || event.isMetaPressed) -> {
                        dispatch(PaneIntent.SelectAll)
                        true
                    }

                    event.key == Key.Escape -> {
                        if (showContextMenu) {
                            showContextMenu = false
                        } else if (showFilterBar) {
                            showFilterBar = false
                            dispatch(PaneIntent.SetFilterQuery(""))
                        } else {
                            dispatch(PaneIntent.ClearSelection)
                        }
                        true
                    }

                    event.key == Key.F && (event.isCtrlPressed || event.isMetaPressed) -> {
                        showFilterBar = true
                        true
                    }

                    event.key == Key.D && (event.isCtrlPressed || event.isMetaPressed) -> {
                        actions.onToggleFavoriteLocation(state.location)
                        true
                    }

                    event.key == Key.F5 -> {
                        dispatch(PaneIntent.Refresh)
                        true
                    }

                    event.key == Key.Backspace -> {
                        dispatch(PaneIntent.GoUp)
                        true
                    }

                    event.key == Key.Comma && (event.isCtrlPressed || event.isMetaPressed) -> {
                        actions.onOpenSettings()
                        true
                    }

                    else -> false
                }
            }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onActivate
            ),
    ) {
        val selectedEntries = (state.entriesState as? PaneEntriesState.Ready)
            ?.entries
            ?.filter { entry -> state.selectedEntryIds.contains(entry.id) }
            .orEmpty()
        val singleSelectedEntry = selectedEntries.singleOrNull()
        val currentLocationFavorite = favoriteLocations.contains(state.location)
        PaneTabBar(
            state = tabBarState,
            active = active,
            onActivate = onActivate,
            onSelectTab = { tabId -> dispatch(PaneIntent.SelectTab(tabId)) },
            onCloseTab = { tabId -> dispatch(PaneIntent.CloseTab(tabId)) },
            onCreateTab = { dispatch(PaneIntent.CreateTab()) },
            onDropTab = { tabId, position -> onDropTab(state.paneId, tabId, position) },
            onDragPositionChange = onTabDragPositionChange,
            onDragEnd = onTabDragEnd,
            onDropZoneChange = { zone ->
                tabBarDropZone = zone
                reportPaneDropZone()
            },
            dropIndicatorIndex = tabDropIndicatorIndex,
        )

        Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))

        // ── Navigation toolbar ─────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(LocalOnyxPalette.current.headerBackground)
                .height(28.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            ToolbarIconButton(
                enabled = state.canGoBack,
                onClick = { onActivate(); dispatch(PaneIntent.GoBack) },
                tooltip = stringResource(Res.string.action_go_back),
            ) {
                Icon(key = AllIconsKeys.Actions.Back, contentDescription = stringResource(Res.string.action_go_back))
            }
            ToolbarIconButton(
                enabled = state.canGoForward,
                onClick = { onActivate(); dispatch(PaneIntent.GoForward) },
                tooltip = stringResource(Res.string.action_go_forward),
            ) {
                Icon(
                    key = AllIconsKeys.Actions.Forward,
                    contentDescription = stringResource(Res.string.action_go_forward),
                )
            }
            ToolbarIconButton(
                enabled = true,
                onClick = { onActivate(); dispatch(PaneIntent.GoUp) },
                tooltip = stringResource(Res.string.action_go_up),
            ) {
                Icon(key = AllIconsKeys.General.ArrowUp, contentDescription = stringResource(Res.string.action_go_up))
            }
            ToolbarIconButton(
                enabled = true,
                onClick = { onActivate(); dispatch(PaneIntent.OpenDirectory(System.getProperty("user.home"))) },
                tooltip = stringResource(Res.string.action_go_home),
            ) {
                Icon(key = AllIconsKeys.Nodes.HomeFolder, contentDescription = stringResource(Res.string.action_go_home))
            }

            ToolbarIconButton(
                enabled = true,
                onClick = { onActivate(); actions.onToggleFavoriteLocation(state.location) },
                tooltip = stringResource(Res.string.action_toggle_favorite),
                selected = currentLocationFavorite,
            ) {
                Text(
                    text = if (currentLocationFavorite) "★" else "☆",
                    fontSize = 11.sp,
                    color = if (currentLocationFavorite) Color(0xFFFFC94D) else LocalOnyxPalette.current.foreground,
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // ── Address bar ─────────────────────────────────────────────
            Box(modifier = Modifier.weight(1f)) {
                HybridAddressBar(
                    location = state.location,
                    onActivate = onActivate,
                    onOpenLocation = { location -> dispatch(PaneIntent.OpenDirectory(location)) },
                    buildBreadcrumbs = buildBreadcrumbs,
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            ToolbarIconButton(
                enabled = true,
                onClick = {
                    onActivate()
                    showFilterBar = !showFilterBar
                    if (!showFilterBar) dispatch(PaneIntent.SetFilterQuery(""))
                },
                tooltip = stringResource(Res.string.action_filter) + " (Ctrl+F)",
                selected = showFilterBar || filterQuery.isNotEmpty(),
            ) {
                Icon(
                    key = AllIconsKeys.Actions.Find,
                    contentDescription = stringResource(Res.string.action_filter),
                )
            }

            if (showFilterBar) {
                LaunchedEffect(showFilterBar) {
                    filterFocusRequester.requestFocus()
                }
                Box(
                    modifier = Modifier.width(190.dp).height(22.dp)
                ) {
                    BasicTextField(
                        value = filterQuery,
                        onValueChange = { dispatch(PaneIntent.SetFilterQuery(it)) },
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(filterFocusRequester)
                            .background(LocalOnyxPalette.current.inputBackground, RoundedCornerShape(4.dp))
                            .border(1.dp, LocalOnyxPalette.current.outlineVariant, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    onActivate()
                                }
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                    showFilterBar = false
                                    dispatch(PaneIntent.SetFilterQuery(""))
                                    focusRequester.requestFocus()
                                    true
                                } else false
                            },
                        textStyle = TextStyle(
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = LocalOnyxPalette.current.foreground,
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(LocalOnyxPalette.current.accent),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (filterQuery.isEmpty()) {
                                    Text(
                                        text = stringResource(Res.string.label_filter_placeholder),
                                        fontSize = 11.sp,
                                        color = LocalOnyxPalette.current.disabledForeground,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            ToolbarIconButton(
                enabled = true,
                onClick = { onActivate(); dispatch(PaneIntent.Refresh) },
                tooltip = stringResource(Res.string.action_refresh_active),
            ) {
                Icon(
                    key = AllIconsKeys.Actions.Refresh,
                    contentDescription = stringResource(Res.string.action_refresh_active),
                )
            }
            ToolbarIconButton(
                enabled = true,
                onClick = { onActivate(); dispatch(PaneIntent.ToggleHiddenItems) },
                tooltip = stringResource(Res.string.action_toggle_hidden_files),
                selected = state.showHiddenItems,
            ) {
                Icon(
                    key = if (state.showHiddenItems) AllIconsKeys.General.Show else AllIconsKeys.Actions.ToggleVisibility,
                    contentDescription = stringResource(Res.string.action_toggle_hidden_files),
                )
            }
        }

        Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))

        state.operationFeedback?.let { feedback ->
            // 3 秒后自动消失
            LaunchedEffect(feedback) {
                kotlinx.coroutines.delay(3000)
                dispatch(PaneIntent.DismissOperationFeedback)
            }
            OperationFeedbackBar(
                feedback = feedback,
                onDismiss = { dispatch(PaneIntent.DismissOperationFeedback) },
            )
            Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
        }

        // ── File list & Inspector ─────────────────────────────────
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onGloballyPositioned { coordinates ->
                        onFileDropZoneChange(
                            FileDropZone(
                                paneId = state.paneId,
                                targetDirectoryLocation = state.location,
                                bounds = coordinates.windowBounds(),
                            )
                        )
                    }
                    .background(paneDropBackground)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {
                            onActivate()
                            showContextMenu = false
                        }
                    ),
            ) {
                PaneEntriesContent(
                    viewMode = state.viewMode,
                    columns = state.detailsColumns,
                    columnWeights = state.detailsColumnWeights,
                    hiddenColumns = state.hiddenColumns,
                    sort = state.detailsSort,
                    selectedEntryIds = state.selectedEntryIds,
                    state = state.entriesState,
                    paneActive = active,
                    contextMenuVisible = showContextMenu,
                    onActivate = onActivate,
                    onOpenEntry = { entry -> dispatch(PaneIntent.OpenEntry(entry)) },
                    onToggleSort = { column -> dispatch(PaneIntent.ToggleSort(column)) },
                    onResizeColumn = { column, nextColumn, deltaWeight ->
                        dispatch(PaneIntent.ResizeDetailsColumn(column, nextColumn, deltaWeight))
                    },
                    onToggleColumnVisibility = { column -> dispatch(PaneIntent.ToggleColumnVisibility(column)) },
                    onSelectEntry = { entryId, additive, range ->
                        dispatch(PaneIntent.SelectEntry(entryId, additive, range))
                    },
                    paneId = state.paneId,
                    fileDropTarget = fileDropTarget,
                    onStartFileDrag = onFileDragStart,
                    onFileDragPositionChange = onFileDragPositionChange,
                    onFileDragEnd = onFileDragEnd,
                    onFileDropZoneChange = onFileDropZoneChange,
                    inlineEditState = inlineEditState,
                    onUpdateInlineEditDraft = { draft -> dispatch(PaneIntent.UpdateInlineEditDraft(draft)) },
                    onConfirmInlineEdit = { dispatch(PaneIntent.ConfirmInlineEdit) },
                    onCancelInlineEdit = { dispatch(PaneIntent.CancelInlineEdit) },
                    onShowContextMenu = { entryId, entrySelected, pointerPosition ->
                        onActivate()
                        contextMenuOffset = pointerPosition
                        if (!entrySelected) dispatch(PaneIntent.SelectEntry(entryId))
                        showContextMenu = true
                    },
                    onDismissContextMenu = { showContextMenu = false },
                    onBeginRename = { dispatch(PaneIntent.BeginRename) },
                    galleryItemSizeDp = state.galleryItemSizeDp,
                    onSelectEntries = { entryIds -> dispatch(PaneIntent.SelectEntries(entryIds)) },
                    inlineExpandedLocations = state.inlineExpandedLocations,
                    inlineExpandedEntries = state.inlineExpandedEntries,
                    onToggleInlineExpand = { location ->
                        onActivate()
                        dispatch(PaneIntent.ToggleInlineExpand(location))
                    },
                    pendingScrollToEntryId = state.pendingScrollToEntryId,
                    onConsumeScroll = { dispatch(PaneIntent.ConsumePendingScroll) },
                    onBlankAreaContextMenu = { pointerPosition ->
                        contextMenuOffset = pointerPosition
                        showContextMenu = true
                    },
                    onRetry = { dispatch(PaneIntent.Refresh) },
                    loadThumbnail = loadThumbnail,
                    loadArchiveThumbnail = loadArchiveThumbnail,
                )

                if (showContextMenu) {
                    val selectedCount = state.selectedEntryIds.size
                    // 异步查询"打开方式"应用列表
                    val singleEntry = singleSelectedEntry
                    LaunchedEffect(showContextMenu, singleEntry?.id) {
                        val queryFn = actions.onQueryOpenWithApps
                        if (singleEntry != null && singleEntry.kind == VFileKind.FILE && queryFn != null) {
                            contextMenuOpenWithApps = withContext(Dispatchers.IO) {
                                queryFn.invoke(singleEntry)
                            }
                        } else {
                            contextMenuOpenWithApps = emptyList()
                        }
                    }
                    PaneContextMenu(
                        anchorOffset = contextMenuOffset,
                        canOperateOnSelection = selectedCount > 0,
                        canOpenSelection = selectedCount == 1,
                        canOpenSelectionInNewTab = singleSelectedEntry?.kind == VFileKind.DIRECTORY,
                        canRenameSelection = selectedCount == 1,
                        canCopyPath = selectedCount > 0,
                        canPaste = canPaste,
                        canExtractSelection = selectedCount > 0 && selectedEntries.any { entry ->
                            entry.kind == VFileKind.FILE && actions.isArchiveFileName(entry.name)
                        },
                        canBatchRename = selectedCount >= 2,
                        onOpenSelection = {
                            dispatch(PaneIntent.OpenSelectedEntry)
                            showContextMenu = false
                        },
                        onOpenSelectionInNewTab = {
                            dispatch(PaneIntent.OpenSelectedInNewTab)
                            showContextMenu = false
                        },
                        onRenameSelection = {
                            dispatch(PaneIntent.BeginRename)
                            showContextMenu = false
                        },
                        onBatchRename = {
                            actions.onBatchRename()
                            showContextMenu = false
                        },
                        onCreateFile = {
                            dispatch(PaneIntent.BeginCreateFile)
                            showContextMenu = false
                        },
                        onCreateDirectory = {
                            actions.onBeginCreateDirectory()
                            showContextMenu = false
                        },
                        onDeleteSelection = {
                            actions.onDeleteSelection()
                            showContextMenu = false
                        },
                        onExtractSelection = {
                            actions.onExtractSelection()
                            showContextMenu = false
                        },
                        onExtractToDirectory = {
                            actions.onExtractToDirectory()
                            showContextMenu = false
                        },
                        onExtractSmart = {
                            actions.onExtractSmart()
                            showContextMenu = false
                        },
                        onCopyPath = {
                            dispatch(PaneIntent.CopySelectedPaths)
                            showContextMenu = false
                        },
                        onCopySelection = {
                            actions.onCopySelection()
                            showContextMenu = false
                        },
                        onCutSelection = {
                            actions.onCutSelection()
                            showContextMenu = false
                        },
                        onPaste = {
                            actions.onPaste()
                            showContextMenu = false
                        },
                        openWithApps = contextMenuOpenWithApps,
                        onOpenWith = { app ->
                            singleSelectedEntry?.let { actions.onOpenWith(it, app) }
                            showContextMenu = false
                        },
                        onOpenWithChooser = {
                            singleSelectedEntry?.let { actions.onOpenWithChooser(it) }
                            showContextMenu = false
                        },
                        onRefresh = {
                            dispatch(PaneIntent.Refresh)
                            showContextMenu = false
                        },
                        onOpenTerminal = {
                            showContextMenu = false
                            actions.onOpenTerminal(state.location)
                        },
                        onClose = { showContextMenu = false },
                    )
                }
            }

            if (state.inspectorState.previewVisible || state.inspectorState.detailsVisible) {
                Divider(Orientation.Vertical, modifier = Modifier.fillMaxHeight().width(1.dp))
                InspectorPanel(
                    entry = singleSelectedEntry,
                    state = state.inspectorState,
                    loadThumbnail = loadThumbnail,
                )
            }
        }
    }
}
