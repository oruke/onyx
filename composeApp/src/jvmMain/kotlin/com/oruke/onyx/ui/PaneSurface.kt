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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.app.component.PaneComponent
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.app.component.tabStatesInDisplayOrder
import com.oruke.onyx.app.filesystem.VfsBreadcrumb
import com.oruke.onyx.app.filesystem.ArchiveInfoRequest
import com.oruke.onyx.app.filesystem.ArchiveInfoResult
import com.oruke.onyx.app.filesystem.FileHashRequest
import com.oruke.onyx.app.filesystem.FileHashResult
import com.oruke.onyx.app.filesystem.FileContextMenuRequest
import com.oruke.onyx.app.filesystem.FileContextMenuSection
import com.oruke.onyx.ui.theme.FileDropTarget
import com.oruke.onyx.ui.theme.FileDropZone
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.TabDropZone
import com.oruke.onyx.ui.theme.windowBounds
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    commandShortcuts: OnyxCommandShortcutMap = OnyxCommandShortcutMap.Default,
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
    readFileHash: suspend (FileHashRequest) -> FileHashResult,
    readArchiveInfo: suspend (ArchiveInfoRequest) -> ArchiveInfoResult,
    buildBreadcrumbs: (String) -> List<VfsBreadcrumb>,
) {
    // ── 从 state / component / actions 派生，消除冗余参数 ──
    val filterQuery = state.filterQuery
    val inlineEditState = state.inlineEditState
    val focusRequester = remember { FocusRequester() }
    val filterFocusRequester = remember { FocusRequester() }
    var showFilterBar by remember { mutableStateOf(false) }
    var filterFocusRequestId by remember { mutableStateOf(0) }
    var showCommandPalette by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    var addressBarEditing by remember { mutableStateOf(false) }
    var filterFocused by remember { mutableStateOf(false) }
    var contextMenuOffset by remember { mutableStateOf(IntOffset.Zero) }
    var contextMenuEntryIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var contextMenuQueryToken by remember { mutableStateOf(0) }
    var contextMenuSections by remember { mutableStateOf<List<FileContextMenuSection>>(emptyList()) }
    var paneBounds by remember { mutableStateOf<IntRect?>(null) }
    var tabBarDropZone by remember { mutableStateOf<TabDropZone?>(null) }
    val tabStack by component.tabStack.subscribeAsState()
    val tabOrder by component.tabOrder.collectAsState()
    val orderedTabs = component.tabStatesInDisplayOrder()
    val filterOverlayVisible = showFilterBar || filterQuery.isNotEmpty()
    val textInputOwnsKeyboard = addressBarEditing || filterFocused || showCommandPalette
    val coroutineScope = rememberCoroutineScope()
    val tabBarState = PaneTabBarState(
        activeTabId = state.activeTabId,
        tabs = orderedTabs.map { tab ->
            PaneTabItemState(
                id = tab.id,
                title = tab.title,
            )
        },
    )
    fun dispatch(intent: PaneIntent) {
        component.dispatch(intent)
    }
    fun openFilterInput() {
        showFilterBar = true
        filterFocusRequestId += 1
    }
    fun closeFilterInput() {
        showFilterBar = false
        filterFocused = false
        dispatch(PaneIntent.SetFilterQuery(""))
        focusRequester.requestFocus()
    }
    fun isPaneCommandEnabled(command: OnyxCommand): Boolean {
        val selectedCount = state.selectedEntryIds.size
        return when (command) {
            OnyxCommand.OpenSelection,
            OnyxCommand.RenameSelection -> selectedCount == 1

            OnyxCommand.DeleteSelection,
            OnyxCommand.CopySelection,
            OnyxCommand.CutSelection -> selectedCount > 0

            OnyxCommand.Paste -> canPaste
            OnyxCommand.CloseMenu,
            OnyxCommand.CreateDirectories -> false

            else -> true
        }
    }
    fun executePaneCommand(command: OnyxCommand): Boolean {
        return when (command) {
            OnyxCommand.OpenSelection -> {
                if (!isPaneCommandEnabled(command)) {
                    false
                } else {
                    dispatch(PaneIntent.OpenSelectedEntry)
                    true
                }
            }

            OnyxCommand.RenameSelection -> {
                if (!isPaneCommandEnabled(command)) {
                    false
                } else {
                    dispatch(PaneIntent.BeginRename)
                    true
                }
            }

            OnyxCommand.NewDirectory -> {
                actions.onBeginCreateDirectory()
                true
            }

            OnyxCommand.NewFile -> {
                dispatch(PaneIntent.BeginCreateFile)
                true
            }

            OnyxCommand.CopySelection -> {
                if (!isPaneCommandEnabled(command)) {
                    false
                } else {
                    actions.onCopySelection()
                    true
                }
            }

            OnyxCommand.CutSelection -> {
                if (!isPaneCommandEnabled(command)) {
                    false
                } else {
                    actions.onCutSelection()
                    true
                }
            }

            OnyxCommand.Paste -> {
                if (!isPaneCommandEnabled(command)) {
                    false
                } else {
                    actions.onPaste()
                    true
                }
            }

            OnyxCommand.DeleteSelection -> {
                if (!isPaneCommandEnabled(command)) {
                    false
                } else {
                    actions.onDeleteSelection()
                    true
                }
            }

            OnyxCommand.Filter -> {
                openFilterInput()
                true
            }

            OnyxCommand.Refresh -> {
                dispatch(PaneIntent.Refresh)
                true
            }

            OnyxCommand.SelectAll -> {
                dispatch(PaneIntent.SelectAll)
                true
            }

            OnyxCommand.GoUp -> {
                dispatch(PaneIntent.GoUp)
                true
            }

            OnyxCommand.ToggleFavorite -> {
                actions.onToggleFavoriteLocation(state.location)
                true
            }

            OnyxCommand.OpenSettings -> {
                actions.onOpenSettings()
                true
            }

            OnyxCommand.CommandPalette -> {
                showCommandPalette = true
                true
            }

            OnyxCommand.CloseMenu,
            OnyxCommand.CreateDirectories -> false
        }
    }
    fun loadContextMenuPlatformActions(
        targetEntries: List<VFile>,
        token: Int,
    ) {
        val contextMenuQuery = actions.onQueryContextMenuSections
        if (targetEntries.isEmpty() || contextMenuQuery == null) {
            showContextMenu = true
            return
        }
        val sectionsDeferred: Deferred<List<FileContextMenuSection>> = coroutineScope.async(Dispatchers.IO) {
            runCatching {
                contextMenuQuery.invoke(FileContextMenuRequest(targetEntries))
            }.getOrDefault(emptyList())
        }

        coroutineScope.launch {
            val sections = withTimeoutOrNull(CONTEXT_MENU_PLATFORM_ACTION_TIMEOUT_MS) {
                sectionsDeferred.await()
            }.orEmpty()
            if (contextMenuQueryToken == token) {
                contextMenuSections = sections
                showContextMenu = true
            }
        }
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
                if (textInputOwnsKeyboard) {
                    return@onPreviewKeyEvent false
                }
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
                    event.matchesCommand(OnyxCommand.CommandPalette, commandShortcuts) -> {
                        executePaneCommand(OnyxCommand.CommandPalette)
                    }

                    event.matchesCommand(OnyxCommand.OpenSelection, commandShortcuts) -> {
                        executePaneCommand(OnyxCommand.OpenSelection)
                    }

                    event.matchesCommand(OnyxCommand.RenameSelection, commandShortcuts) -> {
                        executePaneCommand(OnyxCommand.RenameSelection)
                    }

                    event.matchesCommand(OnyxCommand.NewDirectory, commandShortcuts) -> {
                        executePaneCommand(OnyxCommand.NewDirectory)
                    }

                    event.matchesCommand(OnyxCommand.NewFile, commandShortcuts) -> {
                        executePaneCommand(OnyxCommand.NewFile)
                    }

                    event.matchesCommand(OnyxCommand.CopySelection, commandShortcuts) -> {
                        executePaneCommand(OnyxCommand.CopySelection)
                    }

                    event.matchesCommand(OnyxCommand.CutSelection, commandShortcuts) -> {
                        executePaneCommand(OnyxCommand.CutSelection)
                    }

                    event.matchesCommand(OnyxCommand.Paste, commandShortcuts) -> {
                        executePaneCommand(OnyxCommand.Paste)
                    }

                    event.key == Key.DirectionDown -> {
                        dispatch(PaneIntent.MoveSelection(offset = 1, extendSelection = event.isShiftPressed))
                        true
                    }

                    event.key == Key.DirectionUp -> {
                        dispatch(PaneIntent.MoveSelection(offset = -1, extendSelection = event.isShiftPressed))
                        true
                    }

                    event.matchesCommand(OnyxCommand.DeleteSelection, commandShortcuts) -> {
                        executePaneCommand(OnyxCommand.DeleteSelection)
                    }

                    event.matchesCommand(OnyxCommand.SelectAll, commandShortcuts) -> {
                        executePaneCommand(OnyxCommand.SelectAll)
                    }

                    event.key == Key.Escape -> {
                        if (showContextMenu) {
                            showContextMenu = false
                        } else if (filterOverlayVisible) {
                            closeFilterInput()
                        } else {
                            dispatch(PaneIntent.ClearSelection)
                        }
                        true
                    }

                    event.matchesCommand(OnyxCommand.Filter, commandShortcuts) -> {
                        executePaneCommand(OnyxCommand.Filter)
                    }

                    event.matchesCommand(OnyxCommand.ToggleFavorite, commandShortcuts) -> {
                        executePaneCommand(OnyxCommand.ToggleFavorite)
                    }

                    event.matchesCommand(OnyxCommand.Refresh, commandShortcuts) -> {
                        executePaneCommand(OnyxCommand.Refresh)
                    }

                    event.matchesCommand(OnyxCommand.GoUp, commandShortcuts) -> {
                        executePaneCommand(OnyxCommand.GoUp)
                    }

                    event.matchesCommand(OnyxCommand.OpenSettings, commandShortcuts) -> {
                        executePaneCommand(OnyxCommand.OpenSettings)
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
        val readyEntries = (state.entriesState as? PaneEntriesState.Ready)
            ?.entries
            .orEmpty()
        val selectedEntries = readyEntries.filter { entry -> state.selectedEntryIds.contains(entry.id) }
        val contextMenuEntries = readyEntries.filter { entry -> contextMenuEntryIds.contains(entry.id) }
        val singleSelectedEntry = selectedEntries.singleOrNull()
        val currentLocationFavorite = favoriteLocations.contains(state.location)
        if (showCommandPalette) {
            val commandItems = OnyxCommandRegistry
                .paneCommandStates(commandShortcuts, ::isPaneCommandEnabled)
                .filterNot { commandState -> commandState.spec.command == OnyxCommand.CommandPalette }
                .map { commandState ->
                    CommandPaletteItem(
                        command = commandState.spec.command,
                        label = stringResource(commandState.spec.label),
                        shortcut = onyxShortcutHint(commandState.shortcut),
                        iconKey = commandState.spec.iconKey,
                        enabled = commandState.enabled,
                    )
                }
            CommandPalettePopup(
                items = commandItems,
                onExecute = { command ->
                    if (executePaneCommand(command)) {
                        showCommandPalette = false
                    }
                },
                onClose = { showCommandPalette = false },
            )
        }
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
                    onEditingChange = { editing -> addressBarEditing = editing },
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            ToolbarIconButton(
                enabled = true,
                onClick = {
                    onActivate()
                    if (filterOverlayVisible) {
                        closeFilterInput()
                    } else {
                        openFilterInput()
                    }
                },
                tooltip = onyxCommandTooltip(
                    label = stringResource(Res.string.action_filter),
                    command = OnyxCommand.Filter,
                    shortcuts = commandShortcuts,
                ),
                selected = filterOverlayVisible,
            ) {
                Icon(
                    key = AllIconsKeys.Actions.Find,
                    contentDescription = stringResource(Res.string.action_filter),
                )
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
                        showContextMenu = false
                        contextMenuOffset = pointerPosition
                        val targetEntryIds = if (entrySelected && state.selectedEntryIds.isNotEmpty()) {
                            state.selectedEntryIds
                        } else {
                            setOf(entryId)
                        }
                        contextMenuEntryIds = targetEntryIds
                        contextMenuSections = emptyList()
                        val nextToken = contextMenuQueryToken + 1
                        contextMenuQueryToken = nextToken
                        loadContextMenuPlatformActions(
                            targetEntries = readyEntries.filter { entry -> targetEntryIds.contains(entry.id) },
                            token = nextToken,
                        )
                        if (!entrySelected) dispatch(PaneIntent.SelectEntry(entryId))
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
                        showContextMenu = false
                        contextMenuOffset = pointerPosition
                        contextMenuEntryIds = emptySet()
                        contextMenuSections = emptyList()
                        contextMenuQueryToken += 1
                        showContextMenu = true
                    },
                    loadThumbnail = loadThumbnail,
                    loadArchiveThumbnail = loadArchiveThumbnail,
                    isImageFileName = actions.isImageFileName,
                    isArchiveFileName = actions.isArchiveFileName,
                )

                if (filterOverlayVisible) {
                    FloatingFilterInput(
                        query = filterQuery,
                        focusRequester = filterFocusRequester,
                        focusRequestId = filterFocusRequestId,
                        onQueryChange = { dispatch(PaneIntent.SetFilterQuery(it)) },
                        onFocusChanged = { focused ->
                            filterFocused = focused
                            if (focused) {
                                onActivate()
                            }
                        },
                        onClose = { closeFilterInput() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 8.dp),
                    )
                }

                if (showContextMenu) {
                    val selectedCount = contextMenuEntries.size
                    val singleEntry = contextMenuEntries.singleOrNull()
                    PaneContextMenu(
                        anchorOffset = contextMenuOffset,
                        canOperateOnSelection = selectedCount > 0,
                        canOpenSelection = selectedCount == 1,
                        canOpenSelectionInNewTab = singleEntry?.kind == VFileKind.DIRECTORY,
                        canRenameSelection = selectedCount == 1,
                        canCopyPath = selectedCount > 0,
                        canPaste = canPaste,
                        canExtractSelection = selectedCount > 0 && contextMenuEntries.any { entry ->
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
                        contextMenuSections = contextMenuSections,
                        onFileContextMenuCommand = { command ->
                            actions.onFileContextMenuCommand(command, contextMenuEntries)
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
                        commandShortcuts = commandShortcuts,
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
                    readFileHash = readFileHash,
                    readArchiveInfo = readArchiveInfo,
                    isImageFileName = actions.isImageFileName,
                    isArchiveFileName = actions.isArchiveFileName,
                )
            }
        }
    }
}

private const val CONTEXT_MENU_PLATFORM_ACTION_TIMEOUT_MS = 260L

@Composable
private fun FloatingFilterInput(
    query: String,
    focusRequester: FocusRequester,
    focusRequestId: Int,
    onQueryChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(focusRequestId) {
        if (focusRequestId > 0) {
            focusRequester.requestFocus()
        }
    }

    Row(
        modifier = modifier
            .widthIn(min = 190.dp, max = 280.dp)
            .height(28.dp)
            .border(1.dp, LocalOnyxPalette.current.outline, RoundedCornerShape(4.dp))
            .background(LocalOnyxPalette.current.floatingSurface, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            key = AllIconsKeys.Actions.Find,
            contentDescription = null,
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { focusState -> onFocusChanged(focusState.isFocused) }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onClose()
                        true
                    } else {
                        false
                    }
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
                    if (query.isEmpty()) {
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
