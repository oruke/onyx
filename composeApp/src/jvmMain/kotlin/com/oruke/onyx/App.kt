package com.oruke.onyx

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import onyx.composeapp.generated.resources.label_column_modified
import onyx.composeapp.generated.resources.label_column_name
import onyx.composeapp.generated.resources.label_column_size
import onyx.composeapp.generated.resources.label_column_type
import onyx.composeapp.generated.resources.label_directory_badge
import onyx.composeapp.generated.resources.label_empty_directory
import onyx.composeapp.generated.resources.label_error_prefix
import onyx.composeapp.generated.resources.label_file_badge
import onyx.composeapp.generated.resources.label_items_prefix
import onyx.composeapp.generated.resources.label_loading_entries
import onyx.composeapp.generated.resources.label_task_center
import onyx.composeapp.generated.resources.label_task_status_failed
import onyx.composeapp.generated.resources.label_task_status_queued
import onyx.composeapp.generated.resources.label_task_status_running
import onyx.composeapp.generated.resources.label_task_status_succeeded
import onyx.composeapp.generated.resources.pane_primary
import onyx.composeapp.generated.resources.pane_secondary
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Palette ────────────────────────────────────────────────────────────────

@Composable
private fun rememberOnyxPalette(): OnyxPalette {
    val dark = isSystemInDarkTheme()
    return remember(dark) {
        if (dark) {
            OnyxPalette(
                appBackground = Color(0xFF1E1F22),
                titleBarBackground = Color(0xFF2B2D30),
                surface = Color(0xFF2B2D30),
                surfaceVariant = Color(0xFF3C3F41),
                floatingSurface = Color(0xFF3C3F41),
                inputBackground = Color(0xFF1E1F22),
                outline = Color(0xFF4E5157),
                outlineVariant = Color(0xFF3C3F41),
                foreground = Color(0xFFD4D4D8),
                mutedForeground = Color(0xFF8C8C94),
                disabledForeground = Color(0xFF6E6E6E),
                accent = Color(0xFF4D8DFF),
                accentVariant = Color(0xFF2A5FAD),
                selectionBackground = Color(0xFF2D4F80),
                selectionForeground = Color(0xFFD4D4D8),
                headerBackground = Color(0xFF353739),
                rowHoverBackground = Color(0xFF2D4F80),
                statusBarBackground = Color(0xFF3C3F41),
            )
        } else {
            OnyxPalette(
                appBackground = Color(0xFFF3F5F8),
                titleBarBackground = Color(0xFFFFFFFF),
                surface = Color(0xFFFFFFFF),
                surfaceVariant = Color(0xFFF5F6F8),
                floatingSurface = Color(0xFFF8FAFD),
                inputBackground = Color(0xFFFFFFFF),
                outline = Color(0xFFD0D7E2),
                outlineVariant = Color(0xFFE2E7EF),
                foreground = Color(0xFF1D2733),
                mutedForeground = Color(0xFF5D6B7C),
                disabledForeground = Color(0xFF94A0AE),
                accent = Color(0xFF2F6FEB),
                accentVariant = Color(0xFF5B9BF0),
                selectionBackground = Color(0xFFD0E0FF),
                selectionForeground = Color(0xFF1D2733),
                headerBackground = Color(0xFFF0F2F5),
                rowHoverBackground = Color(0xFFE8F0FE),
                statusBarBackground = Color(0xFFE8EBF0),
            )
        }
    }
}

private data class OnyxPalette(
    val appBackground: Color,
    val titleBarBackground: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val floatingSurface: Color,
    val inputBackground: Color,
    val outline: Color,
    val outlineVariant: Color,
    val foreground: Color,
    val mutedForeground: Color,
    val disabledForeground: Color,
    val accent: Color,
    val accentVariant: Color,
    val selectionBackground: Color,
    val selectionForeground: Color,
    val headerBackground: Color,
    val rowHoverBackground: Color,
    val statusBarBackground: Color,
)

// ── Entry point ─────────────────────────────────────────────────────────────

@Composable
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
    var uiScale by remember { mutableStateOf(100) }

    IntUiTheme(isDark = isSystemInDarkTheme()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.appBackground),
        ) {
            // ── Immersive title bar ──────────────────────────────────────
            TitleBar(
                rootComponent = rootComponent,
                layoutMode = state.layoutMode,
                uiScale = uiScale,
                onUiScaleChange = { uiScale = it },
                palette = palette,
            )

            // ── Task panel (if any) ─────────────────────────────────────
            if (state.tasks.isNotEmpty()) {
                TaskPanel(
                    tasks = state.tasks,
                    onDismissTask = rootComponent::dismissTask,
                    palette = palette,
                )
            }

            // ── Content area ────────────────────────────────────────────
            when (state.layoutMode) {
                PaneLayoutMode.SINGLE -> {
                    PaneSurface(
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
                }

                PaneLayoutMode.DUAL_VERTICAL -> {
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        PaneSurface(
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
                        Divider(Orientation.Vertical, modifier = Modifier.fillMaxHeight().width(1.dp))
                        PaneSurface(
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
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        PaneSurface(
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
                        Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
                        PaneSurface(
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

            // ── Status bar ──────────────────────────────────────────────
            StatusBar(
                primaryPane = state.primaryPane,
                secondaryPane = state.secondaryPane,
                activePane = state.activePane,
                layoutMode = state.layoutMode,
                palette = palette,
            )
        }
    }
}

// ── Title bar ───────────────────────────────────────────────────────────────

@Composable
private fun TitleBar(
    rootComponent: RootComponent,
    layoutMode: PaneLayoutMode,
    uiScale: Int,
    onUiScaleChange: (Int) -> Unit,
    palette: OnyxPalette,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(palette.titleBarBackground)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: App name
        Text(
            text = stringResource(Res.string.app_name),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = palette.foreground,
        )

        Spacer(modifier = Modifier.width(16.dp))

        // Center: Zoom percentage
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = "$uiScale%",
                fontSize = 12.sp,
                color = palette.mutedForeground,
            )
            ZoomSlider(
                value = uiScale,
                onValueChange = onUiScaleChange,
                palette = palette,
            )
        }

        // Right: Layout toggles + Settings
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LayoutIconButton(
                selected = layoutMode == PaneLayoutMode.SINGLE,
                onClick = { rootComponent.setLayoutMode(PaneLayoutMode.SINGLE) },
                palette = palette,
                tooltip = stringResource(Res.string.action_layout_single),
            ) {
                Icon(
                    key = AllIconsKeys.General.Layout,
                    contentDescription = stringResource(Res.string.action_layout_single)
                )
            }
            LayoutIconButton(
                selected = layoutMode == PaneLayoutMode.DUAL_VERTICAL,
                onClick = { rootComponent.setLayoutMode(PaneLayoutMode.DUAL_VERTICAL) },
                palette = palette,
                tooltip = stringResource(Res.string.action_layout_dual_vertical),
            ) {
                Icon(
                    key = AllIconsKeys.Actions.SplitVertically,
                    contentDescription = stringResource(Res.string.action_layout_dual_vertical)
                )
            }
            LayoutIconButton(
                selected = layoutMode == PaneLayoutMode.DUAL_HORIZONTAL,
                onClick = { rootComponent.setLayoutMode(PaneLayoutMode.DUAL_HORIZONTAL) },
                palette = palette,
                tooltip = stringResource(Res.string.action_layout_dual_horizontal),
            ) {
                Icon(
                    key = AllIconsKeys.Actions.SplitHorizontally,
                    contentDescription = stringResource(Res.string.action_layout_dual_horizontal)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            IconButton(onClick = { /* TODO: Settings */ }) {
                Icon(key = AllIconsKeys.General.GearPlain, contentDescription = "Settings")
            }
        }
    }
}

@Composable
private fun LayoutIconButton(
    selected: Boolean,
    onClick: () -> Unit,
    palette: OnyxPalette,
    tooltip: String,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(
                if (selected) palette.accentVariant else Color.Transparent,
                RoundedCornerShape(4.dp),
            )
            .clickable(onClick = onClick)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// ── Pane surface ───────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun PaneSurface(
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
        if (active) focusRequester.requestFocus()
    }

    Column(
        modifier = modifier
            .border(
                width = if (active) 2.dp else 1.dp,
                color = if (active) palette.accent else palette.outlineVariant,
            )
            .background(palette.surface)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (!active || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        component.moveSelection(offset = 1, extendSelection = event.isShiftPressed)
                        true
                    }
                    Key.DirectionUp -> {
                        component.moveSelection(offset = -1, extendSelection = event.isShiftPressed)
                        true
                    }
                    Key.Enter -> {
                        component.openSelectedEntry()
                        true
                    }
                    Key.Delete -> {
                        if (state.selectedEntryIds.isNotEmpty()) {
                            onDeleteSelection(); true
                        } else false
                    }
                    Key.A -> {
                        if (event.isCtrlPressed || event.isMetaPressed) {
                            component.selectAll(); true
                        } else false
                    }
                    Key.Escape -> {
                        component.clearSelection()
                        true
                    }
                    else -> false
                }
            }
            .clickable(onClick = onActivate),
    ) {
        // ── Navigation toolbar ─────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.headerBackground)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            IconButton(
                onClick = { onActivate(); component.goBack() },
                enabled = state.canGoBack,
            ) {
                Icon(key = AllIconsKeys.Actions.Back, contentDescription = stringResource(Res.string.action_go_back))
            }
            IconButton(
                onClick = { onActivate(); component.goForward() },
                enabled = state.canGoForward,
            ) {
                Icon(
                    key = AllIconsKeys.Actions.Forward,
                    contentDescription = stringResource(Res.string.action_go_forward)
                )
            }
            IconButton(
                onClick = { onActivate(); component.goUp() },
            ) {
                Icon(key = AllIconsKeys.General.ArrowUp, contentDescription = stringResource(Res.string.action_go_up))
            }
            IconButton(
                onClick = { onActivate(); component.openDirectory(System.getProperty("user.home")) },
            ) {
                Icon(key = AllIconsKeys.Nodes.HomeFolder, contentDescription = "Home")
            }

            Spacer(modifier = Modifier.width(4.dp))

            // ── Address bar ─────────────────────────────────────────────
            Box(modifier = Modifier.weight(1f)) {
                HybridAddressBar(
                    location = state.location,
                    onActivate = onActivate,
                    onOpenLocation = component::openDirectory,
                    palette = palette,
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            IconButton(
                onClick = { onActivate(); component.refresh() },
            ) {
                Icon(
                    key = AllIconsKeys.Actions.Refresh,
                    contentDescription = stringResource(Res.string.action_refresh_active)
                )
            }
            IconButton(
                onClick = { /* TODO: Toggle visibility */ },
            ) {
                Icon(key = AllIconsKeys.Actions.ToggleVisibility, contentDescription = "Visibility")
            }
        }

        Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))

        // ── File list ──────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable(onClick = onActivate),
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
                    if (!keepSelection) component.selectEntry(entryId)
                    showContextMenu = true
                },
            )

            if (showContextMenu) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { showContextMenu = false }
                )
            }

            if (showContextMenu) {
                ContextMenuCard(
                    canOperateOnSelection = state.selectedEntryIds.isNotEmpty(),
                    canPaste = canPaste,
                    onCopySelection = { showContextMenu = false; onCopySelection() },
                    onCutSelection = { showContextMenu = false; onCutSelection() },
                    onPaste = { showContextMenu = false; onPaste() },
                    onRefresh = { showContextMenu = false; component.refresh() },
                    onClose = { showContextMenu = false },
                    palette = palette,
                )
            }
        }
    }
}

// ── Address bar ────────────────────────────────────────────────────────────

@Composable
private fun HybridAddressBar(
    location: String,
    onActivate: () -> Unit,
    onOpenLocation: (String) -> Unit,
    palette: OnyxPalette,
) {
    var editing by remember { mutableStateOf(false) }
    var draftLocation by remember(location) { mutableStateOf(location) }

    LaunchedEffect(location) {
        if (!editing) draftLocation = location
    }

    if (editing) {
        BasicTextField(
            value = draftLocation,
            onValueChange = { draftLocation = it },
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.inputBackground, RoundedCornerShape(4.dp))
                .border(1.dp, palette.accent, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter -> {
                            onActivate(); editing = false; onOpenLocation(draftLocation); true
                        }

                        Key.Escape -> {
                            editing = false; draftLocation = location; true
                        }

                        else -> false
                    }
                },
            textStyle = TextStyle(color = palette.foreground, fontSize = 12.sp),
            singleLine = true,
        )
    } else {
        BreadcrumbAddressBar(
            location = location,
            onActivate = onActivate,
            onEdit = { editing = true },
            onOpenLocation = onOpenLocation,
            palette = palette,
        )
    }
}

@Composable
private fun BreadcrumbAddressBar(
    location: String,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onOpenLocation: (String) -> Unit,
    palette: OnyxPalette,
) {
    val scrollState = rememberScrollState()
    val breadcrumbs = remember(location) { buildBreadcrumbs(location) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.inputBackground, RoundedCornerShape(4.dp))
            .border(1.dp, palette.outlineVariant, RoundedCornerShape(4.dp))
            .horizontalScroll(scrollState)
            .clickable(onClick = onEdit)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        breadcrumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Text(
                    text = "›",
                    color = palette.mutedForeground,
                    fontSize = 11.sp,
                )
            }
            Text(
                text = crumb.label,
                color = if (index == breadcrumbs.lastIndex) palette.accent else palette.mutedForeground,
                fontSize = 12.sp,
                modifier = Modifier.clickable {
                    onActivate()
                    onOpenLocation(crumb.location)
                },
            )
        }
    }
}

// ── File entries ────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
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
        PaneEntriesState.Idle, PaneEntriesState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = stringResource(Res.string.label_loading_entries),
                    color = palette.mutedForeground,
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
                    color = palette.foreground,
                    fontSize = 12.sp,
                )
            }
        }

        is PaneEntriesState.Ready -> {
            if (state.entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = stringResource(Res.string.label_empty_directory),
                        color = palette.mutedForeground,
                        fontSize = 12.sp
                    )
                }
                return
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // ── Column headers ─────────────────────────────────────
                DetailsHeader(
                    columns = columns,
                    sort = sort,
                    onToggleSort = onToggleSort,
                    palette = palette,
                )

                // ── File list ──────────────────────────────────────────
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 4.dp),
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

// ── Details header ──────────────────────────────────────────────────────────

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
            .background(palette.headerBackground)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { column ->
            when (column) {
                DetailsColumn.NAME -> SortHeaderCell(
                    text = stringResource(Res.string.label_column_name),
                    sortHint = sortHint(column, sort),
                    modifier = Modifier.weight(0.50f),
                    palette = palette,
                    onClick = { onToggleSort(column) },
                )

                DetailsColumn.TYPE -> SortHeaderCell(
                    text = stringResource(Res.string.label_column_type),
                    sortHint = sortHint(column, sort),
                    modifier = Modifier.weight(0.15f),
                    palette = palette,
                    onClick = { onToggleSort(column) },
                )

                DetailsColumn.SIZE -> SortHeaderCell(
                    text = stringResource(Res.string.label_column_size),
                    sortHint = sortHint(column, sort),
                    modifier = Modifier.weight(0.15f),
                    palette = palette,
                    onClick = { onToggleSort(column) },
                    textAlign = TextAlign.End,
                )

                DetailsColumn.MODIFIED -> SortHeaderCell(
                    text = stringResource(Res.string.label_column_modified),
                    sortHint = sortHint(column, sort),
                    modifier = Modifier.weight(0.20f),
                    palette = palette,
                    onClick = { onToggleSort(column) },
                    textAlign = TextAlign.End,
                )
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
    textAlign: TextAlign = TextAlign.Start,
) {
    Row(
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (textAlign == TextAlign.End) Arrangement.End else Arrangement.Start,
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            color = palette.mutedForeground,
            textAlign = textAlign,
            modifier = Modifier.weight(1f),
        )
        if (sortHint != null) {
            Text(
                text = sortHint,
                fontSize = 10.sp,
                color = palette.accent,
            )
        }
    }
}

// ── Entry row (high-density, single-line) ───────────────────────────────────

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
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
            )
            .combinedClickable(
                onClick = {
                    onActivate()
                    onSelectEntry(entry.id, additiveSelection, rangeSelection)
                },
                onDoubleClick = {
                    onActivate()
                    onSelectEntry(entry.id, additiveSelection, rangeSelection)
                    onOpenEntry(entry)
                },
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { column ->
            when (column) {
                DetailsColumn.NAME -> {
                    Row(
                        modifier = Modifier.weight(0.50f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            key = if (entry.kind == VFileKind.DIRECTORY) AllIconsKeys.Nodes.Folder else AllIconsKeys.FileTypes.Any_type,
                            contentDescription = null,
                        )
                        Text(
                            text = entry.name,
                            fontWeight = if (entry.kind == VFileKind.DIRECTORY) FontWeight.Medium else FontWeight.Normal,
                            fontSize = 12.sp,
                            color = palette.foreground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                        fontSize = 12.sp,
                        color = palette.mutedForeground,
                    )
                }
                DetailsColumn.SIZE -> {
                    Text(
                        text = formatFileSize(entry.sizeBytes),
                        modifier = Modifier.weight(0.15f),
                        fontSize = 12.sp,
                        color = palette.mutedForeground,
                        textAlign = TextAlign.End,
                    )
                }
                DetailsColumn.MODIFIED -> {
                    Text(
                        text = formatModifiedTime(entry.modifiedAtEpochMillis),
                        modifier = Modifier.weight(0.20f),
                        fontSize = 12.sp,
                        color = palette.mutedForeground,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

// ── Context menu ────────────────────────────────────────────────────────────

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
    val menuWidth = 200.dp
    Column(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .width(menuWidth)
            .padding(4.dp)
            .border(1.dp, palette.outline, RoundedCornerShape(4.dp))
            .background(palette.floatingSurface, RoundedCornerShape(4.dp))
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        ContextMenuItem(stringResource(Res.string.action_copy), canOperateOnSelection, palette, onCopySelection)
        ContextMenuItem(stringResource(Res.string.action_cut), canOperateOnSelection, palette, onCutSelection)
        ContextMenuItem(stringResource(Res.string.action_paste), canPaste, palette, onPaste)
        Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
        ContextMenuItem(stringResource(Res.string.action_refresh_active), true, palette, onRefresh)
        ContextMenuItem(stringResource(Res.string.action_close_menu), true, palette, onClose)
    }
}

@Composable
private fun ContextMenuItem(
    text: String,
    enabled: Boolean,
    palette: OnyxPalette,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = if (enabled) palette.foreground else palette.disabledForeground,
            fontSize = 12.sp,
        )
    }
}

// ── Task panel ──────────────────────────────────────────────────────────────

@Composable
private fun TaskPanel(
    tasks: List<BackgroundTask>,
    onDismissTask: (String) -> Unit,
    palette: OnyxPalette,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(Res.string.label_task_center),
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = palette.foreground,
        )
        tasks.take(5).forEach { task ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = task.title, fontSize = 12.sp, color = palette.foreground)
                    Text(
                        text = "${taskStatusLabel(task.status)} · ${task.detail}",
                        fontSize = 11.sp,
                        color = palette.mutedForeground,
                    )
                }
                IconButton(onClick = { onDismissTask(task.id) }) {
                    Icon(
                        key = AllIconsKeys.Actions.Close,
                        contentDescription = stringResource(Res.string.action_close_menu)
                    )
                }
            }
        }
    }
}

// ── Status bar ──────────────────────────────────────────────────────────────

@Composable
private fun StatusBar(
    primaryPane: PaneState,
    secondaryPane: PaneState,
    activePane: PaneId,
    layoutMode: PaneLayoutMode,
    palette: OnyxPalette,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(palette.statusBarBackground)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Left side: active pane stats
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val activeState = if (activePane == PaneId.PRIMARY) primaryPane else secondaryPane
            val paneLabel =
                if (activePane == PaneId.PRIMARY) stringResource(Res.string.pane_primary) else stringResource(Res.string.pane_secondary)
            Text(
                text = paneLabel,
                fontSize = 11.sp,
                color = palette.accent,
                fontWeight = FontWeight.Medium,
            )
            if (activeState.entriesState is PaneEntriesState.Ready) {
                val entries = activeState.entriesState.entries
                val dirs = entries.count { it.kind == VFileKind.DIRECTORY }
                val files = entries.count { it.kind == VFileKind.FILE }
                Text(
                    text = "${stringResource(Res.string.label_items_prefix)} $dirs dirs, $files files",
                    fontSize = 11.sp,
                    color = palette.mutedForeground,
                )
            }
        }

        // Right side: layout mode indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                key = when (layoutMode) {
                    PaneLayoutMode.SINGLE -> AllIconsKeys.General.Layout
                    PaneLayoutMode.DUAL_VERTICAL -> AllIconsKeys.Actions.SplitVertically
                    PaneLayoutMode.DUAL_HORIZONTAL -> AllIconsKeys.Actions.SplitHorizontally
                },
                contentDescription = null,
            )
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

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
    if (sizeBytes == null) return "-"
    if (sizeBytes < 1024) return "${sizeBytes} B"
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
    if (modifiedAtEpochMillis == null) return "-"
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    return formatter.format(
        Instant.ofEpochMilli(modifiedAtEpochMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime(),
    )
}

@Composable
private fun sortHint(
    column: DetailsColumn,
    sort: DetailsSort,
): String? {
    if (sort.column != column) return null
    return when (sort.direction) {
        SortDirection.ASCENDING -> "▲"
        SortDirection.DESCENDING -> "▼"
    }
}

@Composable
private fun taskStatusLabel(status: BackgroundTaskStatus): String {
    return when (status) {
        BackgroundTaskStatus.QUEUED -> stringResource(Res.string.label_task_status_queued)
        BackgroundTaskStatus.RUNNING -> stringResource(Res.string.label_task_status_running)
        BackgroundTaskStatus.SUCCEEDED -> stringResource(Res.string.label_task_status_succeeded)
        BackgroundTaskStatus.FAILED -> stringResource(Res.string.label_task_status_failed)
    }
}

private fun String?.orEmpty(): String = this ?: ""

@Composable
private fun ZoomSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    palette: OnyxPalette,
) {
    val fraction = ((value - 50) / 150f).coerceIn(0f, 1f)
    val sliderWidthDp = 120
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sliderWidthPx = with(density) { sliderWidthDp.dp.toPx() }

    Box(
        modifier = Modifier
            .width(sliderWidthDp.dp)
            .height(20.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val x = change.position.x.coerceIn(0f, sliderWidthPx)
                    val newFraction = x / sliderWidthPx
                    onValueChange((50 + (newFraction * 150).toInt()).coerceIn(50, 200))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val newFraction = (offset.x / sliderWidthPx).coerceIn(0f, 1f)
                    onValueChange((50 + (newFraction * 150).toInt()).coerceIn(50, 200))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(palette.outlineVariant, RoundedCornerShape(1.dp)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(3.dp)
                .background(palette.accent, RoundedCornerShape(1.dp)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (fraction * (sliderWidthDp - 8)).dp)
                .size(8.dp)
                .background(palette.accent, RoundedCornerShape(4.dp)),
        )
    }
}