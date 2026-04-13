package com.oruke.onyx

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.component.PaneComponent
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.rememberRootComponent
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.SortDirection
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_close_menu
import onyx.composeapp.generated.resources.action_copy
import onyx.composeapp.generated.resources.action_cut
import onyx.composeapp.generated.resources.action_go_back
import onyx.composeapp.generated.resources.action_go_forward
import onyx.composeapp.generated.resources.action_go_up
import onyx.composeapp.generated.resources.action_layout_dual_horizontal
import onyx.composeapp.generated.resources.action_layout_dual_vertical
import onyx.composeapp.generated.resources.action_layout_single
import onyx.composeapp.generated.resources.action_paste
import onyx.composeapp.generated.resources.action_refresh_active
import onyx.composeapp.generated.resources.app_name
import onyx.composeapp.generated.resources.label_active_pane
import onyx.composeapp.generated.resources.label_column_modified
import onyx.composeapp.generated.resources.label_column_name
import onyx.composeapp.generated.resources.label_column_size
import onyx.composeapp.generated.resources.label_column_type
import onyx.composeapp.generated.resources.label_context_menu
import onyx.composeapp.generated.resources.label_directory_badge
import onyx.composeapp.generated.resources.label_empty_directory
import onyx.composeapp.generated.resources.label_error_prefix
import onyx.composeapp.generated.resources.label_file_badge
import onyx.composeapp.generated.resources.label_hint_click_folder
import onyx.composeapp.generated.resources.label_items_prefix
import onyx.composeapp.generated.resources.label_loading_entries
import onyx.composeapp.generated.resources.label_location
import onyx.composeapp.generated.resources.label_mode_details
import onyx.composeapp.generated.resources.label_onyx_bootstrap
import onyx.composeapp.generated.resources.label_sort_ascending
import onyx.composeapp.generated.resources.label_sort_descending
import onyx.composeapp.generated.resources.label_task_center
import onyx.composeapp.generated.resources.label_task_status_failed
import onyx.composeapp.generated.resources.label_task_status_queued
import onyx.composeapp.generated.resources.label_task_status_running
import onyx.composeapp.generated.resources.label_task_status_succeeded
import onyx.composeapp.generated.resources.pane_primary
import onyx.composeapp.generated.resources.pane_secondary
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Text
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
@Preview
fun App() {
    val rootComponent = rememberRootComponent()
    App(rootComponent = rootComponent)
}

@Composable
private fun App(
    rootComponent: RootComponent,
) {
    val state by rootComponent.state.collectAsState()
    val palette = rememberOnyxPalette()

    IntUiTheme(
        isDark = isSystemInDarkTheme(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.appBackground)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OnyxToolbar(
                rootComponent = rootComponent,
                layoutMode = state.layoutMode,
                activePane = state.activePane,
                palette = palette,
            )

            if (state.tasks.isNotEmpty()) {
                TaskPanel(
                    tasks = state.tasks,
                    onDismissTask = rootComponent::dismissTask,
                    palette = palette,
                )
            }

            when (state.layoutMode) {
                PaneLayoutMode.SINGLE -> {
                    PaneSurface(
                        title = stringResource(Res.string.pane_primary),
                        state = state.primaryPane,
                        active = state.activePane == PaneId.PRIMARY,
                        component = rootComponent.primaryPane,
                        modifier = Modifier.fillMaxSize(),
                        onActivate = { rootComponent.activatePane(PaneId.PRIMARY) },
                        canPaste = state.canPaste,
                        onDeleteSelection = { rootComponent.requestDeleteSelectedInPane(PaneId.PRIMARY) },
                        onCopySelection = { rootComponent.stageCopySelectedInPane(PaneId.PRIMARY) },
                        onCutSelection = { rootComponent.stageCutSelectedInPane(PaneId.PRIMARY) },
                        onPaste = { rootComponent.requestPasteIntoPane(PaneId.PRIMARY) },
                        palette = palette,
                    )
                }

                PaneLayoutMode.DUAL_VERTICAL -> {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PaneSurface(
                            title = stringResource(Res.string.pane_primary),
                            state = state.primaryPane,
                            active = state.activePane == PaneId.PRIMARY,
                            component = rootComponent.primaryPane,
                            modifier = Modifier.weight(1f),
                            onActivate = { rootComponent.activatePane(PaneId.PRIMARY) },
                            canPaste = state.canPaste,
                            onDeleteSelection = { rootComponent.requestDeleteSelectedInPane(PaneId.PRIMARY) },
                            onCopySelection = { rootComponent.stageCopySelectedInPane(PaneId.PRIMARY) },
                            onCutSelection = { rootComponent.stageCutSelectedInPane(PaneId.PRIMARY) },
                            onPaste = { rootComponent.requestPasteIntoPane(PaneId.PRIMARY) },
                            palette = palette,
                        )
                        PaneSurface(
                            title = stringResource(Res.string.pane_secondary),
                            state = state.secondaryPane,
                            active = state.activePane == PaneId.SECONDARY,
                            component = rootComponent.secondaryPane,
                            modifier = Modifier.weight(1f),
                            onActivate = { rootComponent.activatePane(PaneId.SECONDARY) },
                            canPaste = state.canPaste,
                            onDeleteSelection = { rootComponent.requestDeleteSelectedInPane(PaneId.SECONDARY) },
                            onCopySelection = { rootComponent.stageCopySelectedInPane(PaneId.SECONDARY) },
                            onCutSelection = { rootComponent.stageCutSelectedInPane(PaneId.SECONDARY) },
                            onPaste = { rootComponent.requestPasteIntoPane(PaneId.SECONDARY) },
                            palette = palette,
                        )
                    }
                }

                PaneLayoutMode.DUAL_HORIZONTAL -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PaneSurface(
                            title = stringResource(Res.string.pane_primary),
                            state = state.primaryPane,
                            active = state.activePane == PaneId.PRIMARY,
                            component = rootComponent.primaryPane,
                            modifier = Modifier.weight(1f),
                            onActivate = { rootComponent.activatePane(PaneId.PRIMARY) },
                            canPaste = state.canPaste,
                            onDeleteSelection = { rootComponent.requestDeleteSelectedInPane(PaneId.PRIMARY) },
                            onCopySelection = { rootComponent.stageCopySelectedInPane(PaneId.PRIMARY) },
                            onCutSelection = { rootComponent.stageCutSelectedInPane(PaneId.PRIMARY) },
                            onPaste = { rootComponent.requestPasteIntoPane(PaneId.PRIMARY) },
                            palette = palette,
                        )
                        PaneSurface(
                            title = stringResource(Res.string.pane_secondary),
                            state = state.secondaryPane,
                            active = state.activePane == PaneId.SECONDARY,
                            component = rootComponent.secondaryPane,
                            modifier = Modifier.weight(1f),
                            onActivate = { rootComponent.activatePane(PaneId.SECONDARY) },
                            canPaste = state.canPaste,
                            onDeleteSelection = { rootComponent.requestDeleteSelectedInPane(PaneId.SECONDARY) },
                            onCopySelection = { rootComponent.stageCopySelectedInPane(PaneId.SECONDARY) },
                            onCutSelection = { rootComponent.stageCutSelectedInPane(PaneId.SECONDARY) },
                            onPaste = { rootComponent.requestPasteIntoPane(PaneId.SECONDARY) },
                            palette = palette,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnyxToolbar(
    rootComponent: RootComponent,
    layoutMode: PaneLayoutMode,
    activePane: PaneId,
    palette: OnyxPalette,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, palette.outline, RoundedCornerShape(8.dp))
            .background(palette.surface, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.app_name),
            fontWeight = FontWeight.SemiBold,
        )
        Text(text = stringResource(Res.string.label_onyx_bootstrap))
        Text(
            text = "${stringResource(Res.string.label_active_pane)} ${
                if (activePane == PaneId.PRIMARY) {
                    stringResource(Res.string.pane_primary)
                } else {
                    stringResource(Res.string.pane_secondary)
                }
            }"
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LayoutChip(
                text = stringResource(Res.string.action_layout_single),
                selected = layoutMode == PaneLayoutMode.SINGLE,
                palette = palette,
                onClick = { rootComponent.setLayoutMode(PaneLayoutMode.SINGLE) },
            )
            LayoutChip(
                text = stringResource(Res.string.action_layout_dual_vertical),
                selected = layoutMode == PaneLayoutMode.DUAL_VERTICAL,
                palette = palette,
                onClick = { rootComponent.setLayoutMode(PaneLayoutMode.DUAL_VERTICAL) },
            )
            LayoutChip(
                text = stringResource(Res.string.action_layout_dual_horizontal),
                selected = layoutMode == PaneLayoutMode.DUAL_HORIZONTAL,
                palette = palette,
                onClick = { rootComponent.setLayoutMode(PaneLayoutMode.DUAL_HORIZONTAL) },
            )
            LayoutChip(
                text = stringResource(Res.string.action_refresh_active),
                selected = false,
                palette = palette,
                onClick = rootComponent::refreshActivePane,
            )
        }
    }
}

@Composable
private fun PaneSurface(
    title: String,
    state: PaneState,
    active: Boolean,
    component: PaneComponent,
    modifier: Modifier = Modifier,
    onActivate: () -> Unit,
    canPaste: Boolean,
    onDeleteSelection: () -> Unit,
    onCopySelection: () -> Unit,
    onCutSelection: () -> Unit,
    onPaste: () -> Unit,
    palette: OnyxPalette,
) {
    val focusRequester = remember { FocusRequester() }
    var showContextMenu by remember { mutableStateOf(false) }

    LaunchedEffect(active) {
        if (active) {
            focusRequester.requestFocus()
        }
    }

    Column(
        modifier = modifier
            .border(
                width = if (active) 2.dp else 1.dp,
                color = if (active) palette.accent else palette.outline,
                shape = RoundedCornerShape(8.dp),
            )
            .background(palette.surface, RoundedCornerShape(8.dp))
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (!active || event.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.DirectionDown -> {
                        component.moveSelection(
                            offset = 1,
                            extendSelection = event.isShiftPressed,
                        )
                        true
                    }

                    Key.DirectionUp -> {
                        component.moveSelection(
                            offset = -1,
                            extendSelection = event.isShiftPressed,
                        )
                        true
                    }

                    Key.Enter -> {
                        component.openSelectedEntry()
                        true
                    }

                    Key.Delete -> {
                        if (state.selectedEntryIds.isNotEmpty()) {
                            onDeleteSelection()
                            true
                        } else {
                            false
                        }
                    }

                    Key.A -> {
                        if (event.isCtrlPressed || event.isMetaPressed) {
                            component.selectAll()
                            true
                        } else {
                            false
                        }
                    }

                    Key.Escape -> {
                        component.clearSelection()
                        true
                    }

                    else -> false
                }
            }
            .clickable(onClick = onActivate)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Medium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LayoutChip(
                    text = stringResource(Res.string.action_go_back),
                    selected = false,
                    enabled = state.canGoBack,
                    palette = palette,
                    onClick = {
                        onActivate()
                        component.goBack()
                    },
                )
                LayoutChip(
                    text = stringResource(Res.string.action_go_forward),
                    selected = false,
                    enabled = state.canGoForward,
                    palette = palette,
                    onClick = {
                        onActivate()
                        component.goForward()
                    },
                )
                LayoutChip(
                    text = stringResource(Res.string.action_go_up),
                    selected = false,
                    enabled = true,
                    palette = palette,
                    onClick = {
                        onActivate()
                        component.goUp()
                    },
                )
                Text(text = stringResource(Res.string.label_mode_details))
            }
        }

        HybridAddressBar(
            location = state.location,
            onActivate = onActivate,
            onOpenLocation = component::openDirectory,
        )

        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.outline),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(1.dp, palette.outline, RoundedCornerShape(6.dp))
                .padding(vertical = 4.dp),
        ) {
            PaneEntriesContent(
                columns = state.detailsColumns,
                sort = state.detailsSort,
                selectedEntryIds = state.selectedEntryIds,
                state = state.entriesState,
                onActivate = onActivate,
                onOpenEntry = component::openEntry,
                onToggleSort = component::toggleSort,
                onSelectEntry = component::selectEntry,
                palette = palette,
                onShowContextMenu = { entryId, keepSelection ->
                    onActivate()
                    if (!keepSelection) {
                        component.selectEntry(entryId)
                    }
                    showContextMenu = true
                },
            )

            if (showContextMenu) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable {
                            showContextMenu = false
                        }
                )
            }

            if (showContextMenu) {
                ContextMenuCard(
                    canOperateOnSelection = state.selectedEntryIds.isNotEmpty(),
                    canPaste = canPaste,
                    onCopySelection = {
                        showContextMenu = false
                        onCopySelection()
                    },
                    onCutSelection = {
                        showContextMenu = false
                        onCutSelection()
                    },
                    onPaste = {
                        showContextMenu = false
                        onPaste()
                    },
                    onRefresh = {
                        showContextMenu = false
                        component.refresh()
                    },
                    onClose = { showContextMenu = false },
                    palette = palette,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaneEntriesContent(
    columns: List<DetailsColumn>,
    sort: DetailsSort,
    selectedEntryIds: Set<String>,
    state: PaneEntriesState,
    onActivate: () -> Unit,
    onOpenEntry: (VFile) -> Unit,
    onToggleSort: (DetailsColumn) -> Unit,
    onSelectEntry: (String, Boolean, Boolean) -> Unit,
    palette: OnyxPalette,
    onShowContextMenu: (String, Boolean) -> Unit,
) {
    when (state) {
        PaneEntriesState.Idle,
        PaneEntriesState.Loading,
            -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(text = stringResource(Res.string.label_loading_entries))
            }
        }

        is PaneEntriesState.Failure -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "${stringResource(Res.string.label_error_prefix)} ${state.reason.orEmpty()}".trim(),
                )
            }
        }

        is PaneEntriesState.Ready -> {
            if (state.entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(text = stringResource(Res.string.label_empty_directory))
                }
                return
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DetailsHeader(
                    columns = columns,
                    sort = sort,
                    onToggleSort = onToggleSort,
                    palette = palette,
                )
                Text(
                    text = "${stringResource(Res.string.label_items_prefix)} ${state.entries.size}",
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = palette.mutedForeground,
                )
                Text(
                    text = stringResource(Res.string.label_hint_click_folder),
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = palette.mutedForeground,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(
                        items = state.entries,
                        key = { entry -> entry.id },
                    ) { entry ->
                        EntryRow(
                            columns = columns,
                            entry = entry,
                            selected = selectedEntryIds.contains(entry.id),
                            onActivate = onActivate,
                            onOpenEntry = onOpenEntry,
                            onSelectEntry = onSelectEntry,
                            palette = palette,
                            onShowContextMenu = onShowContextMenu,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun EntryRow(
    columns: List<DetailsColumn>,
    entry: VFile,
    selected: Boolean,
    onActivate: () -> Unit,
    onOpenEntry: (VFile) -> Unit,
    onSelectEntry: (String, Boolean, Boolean) -> Unit,
    palette: OnyxPalette,
    onShowContextMenu: (String, Boolean) -> Unit,
) {
    var additiveSelection by remember { mutableStateOf(false) }
    var rangeSelection by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onPointerEvent(PointerEventType.Press) { event ->
                additiveSelection = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                rangeSelection = event.keyboardModifiers.isShiftPressed
                if (event.buttons.isSecondaryPressed) {
                    onActivate()
                    onShowContextMenu(entry.id, selected)
                }
            }
            .background(
                if (selected) palette.selectionBackground else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .combinedClickable(
                onClick = {
                    onActivate()
                    onSelectEntry(
                        entry.id,
                        additiveSelection,
                        rangeSelection,
                    )
                },
                onDoubleClick = {
                    onActivate()
                    onSelectEntry(
                        entry.id,
                        additiveSelection,
                        rangeSelection,
                    )
                    onOpenEntry(entry)
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { column ->
            when (column) {
                DetailsColumn.NAME -> {
                    Column(
                        modifier = Modifier.weight(0.45f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = entry.name,
                            fontWeight = if (entry.kind == VFileKind.DIRECTORY) FontWeight.Medium else FontWeight.Normal,
                        )
                        Text(
                            text = entry.location,
                            color = palette.mutedForeground,
                        )
                    }
                }

                DetailsColumn.TYPE -> {
                    Text(
                        text = when (entry.kind) {
                            VFileKind.DIRECTORY -> stringResource(Res.string.label_directory_badge)
                            VFileKind.FILE -> stringResource(Res.string.label_file_badge)
                        },
                        modifier = Modifier.weight(0.15f),
                    )
                }

                DetailsColumn.SIZE -> {
                    Text(
                        text = formatFileSize(entry.sizeBytes),
                        modifier = Modifier.weight(0.15f),
                    )
                }

                DetailsColumn.MODIFIED -> {
                    Text(
                        text = formatModifiedTime(entry.modifiedAtEpochMillis),
                        modifier = Modifier.weight(0.25f),
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxScope.ContextMenuCard(
    canOperateOnSelection: Boolean,
    canPaste: Boolean,
    onCopySelection: () -> Unit,
    onCutSelection: () -> Unit,
    onPaste: () -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    palette: OnyxPalette,
) {
    val menuWidth = 220.dp
    Column(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .width(menuWidth)
            .padding(8.dp)
            .border(1.dp, palette.outline, RoundedCornerShape(8.dp))
            .background(palette.floatingSurface, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(Res.string.label_context_menu),
            fontWeight = FontWeight.SemiBold,
        )
        ContextMenuItem(
            text = stringResource(Res.string.action_copy),
            enabled = canOperateOnSelection,
            palette = palette,
            width = menuWidth - 16.dp,
            onClick = onCopySelection,
        )
        ContextMenuItem(
            text = stringResource(Res.string.action_cut),
            enabled = canOperateOnSelection,
            palette = palette,
            width = menuWidth - 16.dp,
            onClick = onCutSelection,
        )
        ContextMenuItem(
            text = stringResource(Res.string.action_paste),
            enabled = canPaste,
            palette = palette,
            width = menuWidth - 16.dp,
            onClick = onPaste,
        )
        ContextMenuItem(
            text = stringResource(Res.string.action_refresh_active),
            enabled = true,
            palette = palette,
            width = menuWidth - 16.dp,
            onClick = onRefresh,
        )
        ContextMenuItem(
            text = stringResource(Res.string.action_close_menu),
            enabled = true,
            palette = palette,
            width = menuWidth - 16.dp,
            onClick = onClose,
        )
    }
}

@Composable
private fun TaskPanel(
    tasks: List<BackgroundTask>,
    onDismissTask: (String) -> Unit,
    palette: OnyxPalette,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, palette.outline, RoundedCornerShape(8.dp))
            .background(palette.surface, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.label_task_center),
            fontWeight = FontWeight.SemiBold,
        )
        tasks.take(5).forEach { task ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(text = task.title)
                    Text(
                        text = "${taskStatusLabel(task.status)} · ${task.detail}",
                        color = palette.mutedForeground,
                    )
                }
                LayoutChip(
                    text = stringResource(Res.string.action_close_menu),
                    selected = false,
                    palette = palette,
                    onClick = { onDismissTask(task.id) },
                )
            }
        }
    }
}

@Composable
private fun LayoutChip(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    palette: OnyxPalette,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .border(
                width = 1.dp,
                color = when {
                    !enabled -> palette.disabledOutline
                    selected -> palette.accent
                    else -> palette.outline
                },
                shape = RoundedCornerShape(999.dp),
            )
            .background(
                when {
                    selected -> palette.selectionBackground
                    else -> Color.Transparent
                },
                RoundedCornerShape(999.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) palette.foreground else palette.disabledForeground,
        )
    }
}

@Composable
private fun ContextMenuItem(
    text: String,
    enabled: Boolean,
    palette: OnyxPalette,
    width: Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(width)
            .background(Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            color = if (enabled) palette.foreground else palette.disabledForeground,
        )
    }
}

@Composable
private fun HybridAddressBar(
    location: String,
    onActivate: () -> Unit,
    onOpenLocation: (String) -> Unit,
) {
    val palette = rememberOnyxPalette()
    var editing by remember { mutableStateOf(false) }
    var draftLocation by remember(location) { mutableStateOf(location) }

    LaunchedEffect(location) {
        if (!editing) {
            draftLocation = location
        }
    }

    if (editing) {
        BasicTextField(
            value = draftLocation,
            onValueChange = { draftLocation = it },
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, palette.outline, RoundedCornerShape(6.dp))
                .background(palette.inputBackground, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        return@onPreviewKeyEvent false
                    }
                    when (event.key) {
                        Key.Enter -> {
                            onActivate()
                            editing = false
                            onOpenLocation(draftLocation)
                            true
                        }

                        Key.Escape -> {
                            editing = false
                            draftLocation = location
                            true
                        }

                        else -> false
                    }
                },
            textStyle = TextStyle(color = palette.foreground),
            singleLine = true,
        )
    } else {
        BreadcrumbAddressBar(
            location = location,
            onActivate = onActivate,
            onEdit = { editing = true },
            onOpenLocation = onOpenLocation,
        )
    }
}

@Composable
private fun BreadcrumbAddressBar(
    location: String,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onOpenLocation: (String) -> Unit,
) {
    val palette = rememberOnyxPalette()
    val scrollState = rememberScrollState()
    val breadcrumbs = remember(location) { buildBreadcrumbs(location) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, palette.outline, RoundedCornerShape(6.dp))
            .background(palette.inputBackground, RoundedCornerShape(6.dp))
            .horizontalScroll(scrollState)
            .clickable(onClick = onEdit)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(Res.string.label_location),
            color = palette.mutedForeground,
        )
        breadcrumbs.forEachIndexed { index, crumb ->
            LayoutChip(
                text = crumb.label,
                selected = index == breadcrumbs.lastIndex,
                palette = palette,
                onClick = {
                    onActivate()
                    onOpenLocation(crumb.location)
                },
            )
        }
    }
}

@Composable
private fun DetailsHeader(
    columns: List<DetailsColumn>,
    sort: DetailsSort,
    onToggleSort: (DetailsColumn) -> Unit,
    palette: OnyxPalette,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { column ->
            when (column) {
                DetailsColumn.NAME -> {
                    SortHeaderCell(
                        text = stringResource(Res.string.label_column_name),
                        sortHint = sortHint(column, sort),
                        modifier = Modifier.weight(0.45f),
                        palette = palette,
                        onClick = { onToggleSort(column) },
                    )
                }

                DetailsColumn.TYPE -> {
                    SortHeaderCell(
                        text = stringResource(Res.string.label_column_type),
                        sortHint = sortHint(column, sort),
                        modifier = Modifier.weight(0.15f),
                        palette = palette,
                        onClick = { onToggleSort(column) },
                    )
                }

                DetailsColumn.SIZE -> {
                    SortHeaderCell(
                        text = stringResource(Res.string.label_column_size),
                        sortHint = sortHint(column, sort),
                        modifier = Modifier.weight(0.15f),
                        palette = palette,
                        onClick = { onToggleSort(column) },
                    )
                }

                DetailsColumn.MODIFIED -> {
                    SortHeaderCell(
                        text = stringResource(Res.string.label_column_modified),
                        sortHint = sortHint(column, sort),
                        modifier = Modifier.weight(0.25f),
                        palette = palette,
                        onClick = { onToggleSort(column) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SortHeaderCell(
    text: String,
    sortHint: String?,
    modifier: Modifier = Modifier,
    palette: OnyxPalette,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.SemiBold,
        )
        if (sortHint != null) {
            Text(
                text = sortHint,
                color = palette.mutedForeground,
            )
        }
    }
}

@Composable
private fun rememberOnyxPalette(): OnyxPalette {
    val dark = isSystemInDarkTheme()
    return remember(dark) {
        if (dark) {
            OnyxPalette(
                appBackground = Color(0xFF1F2329),
                surface = Color(0xFF262B33),
                floatingSurface = Color(0xFF2A3038),
                inputBackground = Color(0xFF20252C),
                outline = Color(0xFF616B75),
                disabledOutline = Color(0xFF4F565F),
                foreground = Color(0xFFE6EDF5),
                mutedForeground = Color(0xFF9AA4AF),
                disabledForeground = Color(0xFF7A828C),
                accent = Color(0xFF4D8DFF),
                selectionBackground = Color(0x334D8DFF),
            )
        } else {
            OnyxPalette(
                appBackground = Color(0xFFF3F5F8),
                surface = Color(0xFFFFFFFF),
                floatingSurface = Color(0xFFF8FAFD),
                inputBackground = Color(0xFFFFFFFF),
                outline = Color(0xFFD0D7E2),
                disabledOutline = Color(0xFFE2E7EF),
                foreground = Color(0xFF1D2733),
                mutedForeground = Color(0xFF5D6B7C),
                disabledForeground = Color(0xFF94A0AE),
                accent = Color(0xFF2F6FEB),
                selectionBackground = Color(0x1A2F6FEB),
            )
        }
    }
}

private data class OnyxPalette(
    val appBackground: Color,
    val surface: Color,
    val floatingSurface: Color,
    val inputBackground: Color,
    val outline: Color,
    val disabledOutline: Color,
    val foreground: Color,
    val mutedForeground: Color,
    val disabledForeground: Color,
    val accent: Color,
    val selectionBackground: Color,
)

private data class Breadcrumb(
    val label: String,
    val location: String,
)

private fun buildBreadcrumbs(location: String): List<Breadcrumb> {
    val path = Path.of(location).normalize().toAbsolutePath()
    val breadcrumbs = mutableListOf<Breadcrumb>()
    var current = path.root ?: path
    breadcrumbs += Breadcrumb(
        label = current.toString().ifBlank { "/" },
        location = current.toString().ifBlank { "/" },
    )

    path.iterator().forEach { segment ->
        current = current.resolve(segment)
        breadcrumbs += Breadcrumb(
            label = segment.toString(),
            location = current.toString(),
        )
    }

    return breadcrumbs.distinctBy { it.location }
}

private fun formatFileSize(sizeBytes: Long?): String {
    if (sizeBytes == null) {
        return "-"
    }
    if (sizeBytes < 1024) {
        return "${sizeBytes} B"
    }

    val units = listOf("KB", "MB", "GB", "TB")
    var value = sizeBytes.toDouble()
    var unitIndex = -1
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex += 1
    }
    return String.format("%.1f %s", value, units[unitIndex])
}

private fun formatModifiedTime(modifiedAtEpochMillis: Long?): String {
    if (modifiedAtEpochMillis == null) {
        return "-"
    }

    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    return formatter.format(
        Instant.ofEpochMilli(modifiedAtEpochMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
    )
}

@Composable
private fun sortHint(
    column: DetailsColumn,
    sort: DetailsSort,
): String? {
    if (sort.column != column) {
        return null
    }
    return when (sort.direction) {
        SortDirection.ASCENDING -> stringResource(Res.string.label_sort_ascending)
        SortDirection.DESCENDING -> stringResource(Res.string.label_sort_descending)
    }
}

@Composable
private fun taskStatusLabel(
    status: BackgroundTaskStatus,
): String {
    return when (status) {
        BackgroundTaskStatus.QUEUED -> stringResource(Res.string.label_task_status_queued)
        BackgroundTaskStatus.RUNNING -> stringResource(Res.string.label_task_status_running)
        BackgroundTaskStatus.SUCCEEDED -> stringResource(Res.string.label_task_status_succeeded)
        BackgroundTaskStatus.FAILED -> stringResource(Res.string.label_task_status_failed)
    }
}
