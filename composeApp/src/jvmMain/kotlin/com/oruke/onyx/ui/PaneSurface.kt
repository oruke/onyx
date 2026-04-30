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
import com.oruke.onyx.app.component.FileTransferOperation
import com.oruke.onyx.app.component.PaneComponent
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneInlineEditState
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.ui.theme.FileDropTarget
import com.oruke.onyx.ui.theme.FileDropZone
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.TabDropZone
import com.oruke.onyx.ui.theme.windowBounds
import onyx.composeapp.generated.resources.Res
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
    modifier: Modifier = Modifier,
    onActivate: () -> Unit,
    canPaste: Boolean,
    favoriteLocations: List<String>,
    onToggleFavoriteLocation: (String) -> Unit,
    filterQuery: String,
    onFilterQueryChange: (String) -> Unit,
    onDeleteSelection: () -> Unit,
    onCopySelection: () -> Unit,
    onCutSelection: () -> Unit,
    onPaste: () -> Unit,
    onOpenSelected: () -> Unit,
    onOpenSelectedInNewTab: () -> Unit,
    onBeginRename: () -> Unit,
    onBeginCreateFile: () -> Unit,
    onBeginCreateDirectory: () -> Unit,
    onCopySelectedPaths: () -> Unit,
    inlineEditState: PaneInlineEditState?,
    onUpdateInlineEditDraft: (String) -> Unit,
    onConfirmInlineEdit: () -> Unit,
    onCancelInlineEdit: () -> Unit,
    onDismissOperationFeedback: () -> Unit,
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
) {
    val focusRequester = remember { FocusRequester() }
    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuOffset by remember { mutableStateOf(IntOffset.Zero) }
    var paneBounds by remember { mutableStateOf<IntRect?>(null) }
    var tabBarDropZone by remember { mutableStateOf<TabDropZone?>(null) }
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
                    }.let { handled ->
                        if (handled) return@onPreviewKeyEvent true
                    }
                }

                when {
                    event.key == Key.Enter -> {
                        onOpenSelected()
                        true
                    }

                    event.key == Key.F2 -> {
                        onBeginRename()
                        true
                    }

                    event.key == Key.N && (event.isCtrlPressed || event.isMetaPressed) -> {
                        if (event.isShiftPressed) {
                            onBeginCreateDirectory()
                        } else {
                            onBeginCreateFile()
                        }
                        true
                    }

                    event.key == Key.C && (event.isCtrlPressed || event.isMetaPressed) -> {
                        onCopySelection()
                        true
                    }

                    event.key == Key.X && (event.isCtrlPressed || event.isMetaPressed) -> {
                        onCutSelection()
                        true
                    }

                    event.key == Key.V && (event.isCtrlPressed || event.isMetaPressed) -> {
                        onPaste()
                        true
                    }

                    event.key == Key.DirectionDown -> {
                        component.moveSelection(offset = 1, extendSelection = event.isShiftPressed)
                        true
                    }

                    event.key == Key.DirectionUp -> {
                        component.moveSelection(offset = -1, extendSelection = event.isShiftPressed)
                        true
                    }

                    event.key == Key.Delete -> {
                        if (state.selectedEntryIds.isNotEmpty()) {
                            onDeleteSelection()
                            true
                        } else {
                            false
                        }
                    }

                    event.key == Key.A && (event.isCtrlPressed || event.isMetaPressed) -> {
                        component.selectAll()
                        true
                    }

                    event.key == Key.Escape -> {
                        if (showContextMenu) {
                            showContextMenu = false
                        } else {
                            component.clearSelection()
                        }
                        true
                    }

                    event.key == Key.D && (event.isCtrlPressed || event.isMetaPressed) -> {
                        onToggleFavoriteLocation(state.location)
                        true
                    }

                    event.key == Key.F5 -> {
                        component.refresh()
                        true
                    }

                    event.key == Key.Backspace -> {
                        component.goUp()
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
            state = state,
            active = active,
            onActivate = onActivate,
            onSelectTab = component::selectTab,
            onCloseTab = component::closeTab,
            onCreateTab = { component.createTab() },
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
                onClick = { onActivate(); component.goBack() },
                tooltip = stringResource(Res.string.action_go_back),
            ) {
                Icon(key = AllIconsKeys.Actions.Back, contentDescription = stringResource(Res.string.action_go_back))
            }
            ToolbarIconButton(
                enabled = state.canGoForward,
                onClick = { onActivate(); component.goForward() },
                tooltip = stringResource(Res.string.action_go_forward),
            ) {
                Icon(
                    key = AllIconsKeys.Actions.Forward,
                    contentDescription = stringResource(Res.string.action_go_forward),
                )
            }
            ToolbarIconButton(
                enabled = true,
                onClick = { onActivate(); component.goUp() },
                tooltip = stringResource(Res.string.action_go_up),
            ) {
                Icon(key = AllIconsKeys.General.ArrowUp, contentDescription = stringResource(Res.string.action_go_up))
            }
            ToolbarIconButton(
                enabled = true,
                onClick = { onActivate(); component.openDirectory(System.getProperty("user.home")) },
                tooltip = stringResource(Res.string.action_go_home),
            ) {
                Icon(
                    key = AllIconsKeys.Nodes.HomeFolder,
                    contentDescription = stringResource(Res.string.action_go_home),
                )
            }
            ToolbarIconButton(
                enabled = true,
                onClick = { onActivate(); onToggleFavoriteLocation(state.location) },
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
                    onOpenLocation = component::openDirectory,
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Box(
                modifier = Modifier.width(190.dp).height(22.dp)
            ) {
                BasicTextField(
                    value = filterQuery,
                    onValueChange = onFilterQueryChange,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LocalOnyxPalette.current.inputBackground, RoundedCornerShape(4.dp))
                        .border(1.dp, LocalOnyxPalette.current.outlineVariant, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                onActivate()
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

            Spacer(modifier = Modifier.width(4.dp))

            ToolbarIconButton(
                enabled = true,
                onClick = { onActivate(); component.refresh() },
                tooltip = stringResource(Res.string.action_refresh_active),
            ) {
                Icon(
                    key = AllIconsKeys.Actions.Refresh,
                    contentDescription = stringResource(Res.string.action_refresh_active),
                )
            }
            ToolbarIconButton(
                enabled = true,
                onClick = { onActivate(); component.toggleHiddenItems() },
                tooltip = stringResource(Res.string.action_toggle_hidden_files),
                selected = state.showHiddenItems,
            ) {
                Icon(
                    key = if (state.showHiddenItems) AllIconsKeys.Actions.ToggleVisibility else AllIconsKeys.General.Show,
                    contentDescription = stringResource(Res.string.action_toggle_hidden_files),
                )
            }
        }

        Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))

        state.operationFeedback?.let { feedback ->
            // 3 秒后自动消失
            LaunchedEffect(feedback) {
                kotlinx.coroutines.delay(3000)
                onDismissOperationFeedback()
            }
            OperationFeedbackBar(
                feedback = feedback,
                onDismiss = onDismissOperationFeedback,
            )
            Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
        }

        // ── File list & Inspector ──────────────────────────────────────────────────
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
                    onOpenEntry = component::openEntry,
                    onToggleSort = component::toggleSort,
                    onResizeColumn = component::resizeDetailsColumn,
                    onToggleColumnVisibility = component::toggleColumnVisibility,
                    onSelectEntry = component::selectEntry,
                    paneId = state.paneId,
                    fileDropTarget = fileDropTarget,
                    onStartFileDrag = onFileDragStart,
                    onFileDragPositionChange = onFileDragPositionChange,
                    onFileDragEnd = onFileDragEnd,
                    onFileDropZoneChange = onFileDropZoneChange,
                    inlineEditState = inlineEditState,
                    onUpdateInlineEditDraft = onUpdateInlineEditDraft,
                    onConfirmInlineEdit = onConfirmInlineEdit,
                    onCancelInlineEdit = onCancelInlineEdit,
                    onShowContextMenu = { entryId, entrySelected, pointerPosition ->
                        onActivate()
                        contextMenuOffset = pointerPosition
                        if (!entrySelected) component.selectEntry(entryId)
                        showContextMenu = true
                    },
                    onDismissContextMenu = { showContextMenu = false },
                    onBeginRename = onBeginRename,
                    galleryItemSizeDp = state.galleryItemSizeDp,
                )

                if (showContextMenu) {
                    val selectedCount = state.selectedEntryIds.size
                    PaneContextMenu(
                        anchorOffset = contextMenuOffset,
                        canOperateOnSelection = selectedCount > 0,
                        canOpenSelection = selectedCount == 1,
                        canOpenSelectionInNewTab = singleSelectedEntry?.kind == VFileKind.DIRECTORY,
                        canRenameSelection = selectedCount == 1,
                        canCopyPath = selectedCount > 0,
                        canPaste = canPaste,
                        onOpenSelection = {
                            onOpenSelected()
                            showContextMenu = false
                        },
                        onOpenSelectionInNewTab = {
                            onOpenSelectedInNewTab()
                            showContextMenu = false
                        },
                        onRenameSelection = {
                            onBeginRename()
                            showContextMenu = false
                        },
                        onCreateFile = {
                            onBeginCreateFile()
                            showContextMenu = false
                        },
                        onCreateDirectory = {
                            onBeginCreateDirectory()
                            showContextMenu = false
                        },
                        onDeleteSelection = {
                            onDeleteSelection()
                            showContextMenu = false
                        },
                        onCopyPath = {
                            onCopySelectedPaths()
                            showContextMenu = false
                        },
                        onCopySelection = {
                            onCopySelection()
                            showContextMenu = false
                        },
                        onCutSelection = {
                            onCutSelection()
                            showContextMenu = false
                        },
                        onPaste = {
                            onPaste()
                            showContextMenu = false
                        },
                        onRefresh = {
                            component.refresh()
                            showContextMenu = false
                        },
                        onOpenTerminal = {
                            showContextMenu = false
                            val location = state.location
                            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val dir = java.io.File(location)
                                if (!dir.isDirectory) return@launch
                                val terminal = System.getenv("TERMINAL")
                                val candidates = listOfNotNull(
                                    terminal,
                                    "x-terminal-emulator",
                                    "gnome-terminal",
                                    "konsole",
                                    "kitty",
                                    "alacritty",
                                    "xterm",
                                )
                                for (cmd in candidates) {
                                    try {
                                        ProcessBuilder(cmd).directory(dir).start()
                                        return@launch
                                    } catch (_: Exception) {
                                        // 尝试下一个候选
                                    }
                                }
                            }
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
                )
            }
        }
    }
}

