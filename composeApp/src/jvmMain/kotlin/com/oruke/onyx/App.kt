package com.oruke.onyx

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.oruke.onyx.app.component.FileTransferOperation
import com.oruke.onyx.app.component.PaneComponent
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.RootState
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
import onyx.composeapp.generated.resources.action_cancel_task
import onyx.composeapp.generated.resources.action_clear_all_tasks
import onyx.composeapp.generated.resources.action_close_menu
import onyx.composeapp.generated.resources.action_close_tab
import onyx.composeapp.generated.resources.action_copy
import onyx.composeapp.generated.resources.action_cut
import onyx.composeapp.generated.resources.action_go_back
import onyx.composeapp.generated.resources.action_go_forward
import onyx.composeapp.generated.resources.action_go_home
import onyx.composeapp.generated.resources.action_go_up
import onyx.composeapp.generated.resources.action_layout_dual_horizontal
import onyx.composeapp.generated.resources.action_layout_dual_vertical
import onyx.composeapp.generated.resources.action_layout_single
import onyx.composeapp.generated.resources.action_move
import onyx.composeapp.generated.resources.action_new_tab
import onyx.composeapp.generated.resources.action_open_settings
import onyx.composeapp.generated.resources.action_paste
import onyx.composeapp.generated.resources.action_refresh_active
import onyx.composeapp.generated.resources.action_toggle_hidden_files
import onyx.composeapp.generated.resources.app_name
import onyx.composeapp.generated.resources.label_column_modified
import onyx.composeapp.generated.resources.label_column_name
import onyx.composeapp.generated.resources.label_column_size
import onyx.composeapp.generated.resources.label_copy_to_destination
import onyx.composeapp.generated.resources.label_directory_file_count
import onyx.composeapp.generated.resources.label_empty_directory
import onyx.composeapp.generated.resources.label_error_prefix
import onyx.composeapp.generated.resources.label_loading_entries
import onyx.composeapp.generated.resources.label_mode_details
import onyx.composeapp.generated.resources.label_mode_gallery
import onyx.composeapp.generated.resources.label_move_to_destination
import onyx.composeapp.generated.resources.label_task_center
import onyx.composeapp.generated.resources.label_task_status_cancelled
import onyx.composeapp.generated.resources.label_task_status_failed
import onyx.composeapp.generated.resources.label_task_status_queued
import onyx.composeapp.generated.resources.label_task_status_running
import onyx.composeapp.generated.resources.label_task_status_succeeded
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.window.DecoratedWindowScope
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.newFullscreenControls
import java.awt.Cursor
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val DetailsColumnGap = 6.dp
private val PaneDividerHitSlop = 7.dp

private data class TabDropZone(
    val bounds: IntRect,
    val tabIds: List<String>,
    val tabBounds: Map<String, IntRect>,
)

private data class TabDropTarget(
    val paneId: PaneId,
    val index: Int,
)

private data class FileDragState(
    val sourcePaneId: PaneId,
    val operation: FileTransferOperation,
)

private data class FileDropZone(
    val paneId: PaneId,
    val targetDirectoryLocation: String,
    val bounds: IntRect,
    val directoryEntryId: String? = null,
)

private data class FileDropTarget(
    val paneId: PaneId,
    val targetDirectoryLocation: String,
    val directoryEntryId: String?,
)

private data class TooltipRequest(
    val owner: Any,
    val text: String,
    val anchorBounds: IntRect,
    val pointerPosition: IntOffset,
)

private class TooltipController(
    val show: (TooltipRequest) -> Unit,
    val hide: (Any) -> Unit,
)

private val LocalTooltipController = staticCompositionLocalOf {
    TooltipController(
        show = {},
        hide = {},
    )
}

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
                surfaceVariant = Color(0xFF313336),
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
                statusBarBackground = Color(0xFF26282C),
                titleBarBorder = Color(0xFF43454A),
                titleBarActiveBackground = Color(0xFF3A3D41),
                titleBarHoverBackground = Color(0xFF35373A),
                titleBarPressedBackground = Color(0xFF3E4145),
                inactiveSelectionBackground = Color(0xFF43454A),
            )
        } else {
            OnyxPalette(
                appBackground = Color(0xFFF3F5F8),
                titleBarBackground = Color(0xFFF7F8FA),
                surface = Color(0xFFFFFFFF),
                surfaceVariant = Color(0xFFF7F8FA),
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
                headerBackground = Color(0xFFF7F8FA),
                rowHoverBackground = Color(0xFFE8F0FE),
                statusBarBackground = Color(0xFFF7F8FA),
                titleBarBorder = Color(0xFFEBECF0),
                titleBarActiveBackground = Color(0xFFEBECF0),
                titleBarHoverBackground = Color(0xFFF0F2F5),
                titleBarPressedBackground = Color(0xFFE4E6EB),
                inactiveSelectionBackground = Color(0xFFE2E7EF),
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
    val titleBarBorder: Color,
    val titleBarActiveBackground: Color,
    val titleBarHoverBackground: Color,
    val titleBarPressedBackground: Color,
    val inactiveSelectionBackground: Color,
)

// ── Entry point ─────────────────────────────────────────────────────────────

@Composable
fun DecoratedWindowScope.WindowApp() {
    val rootComponent = rememberRootComponent()
    val state by rootComponent.state.collectAsState()
    val palette = rememberOnyxPalette()
    var titleBarTooltipRequest by remember { mutableStateOf<TooltipRequest?>(null) }
    val onUiScaleChange: (Int) -> Unit = { value ->
        rootComponent.updateSettings(
            state.settings.copy(uiScale = value),
        )
    }

    CompositionLocalProvider(
        LocalTooltipController provides TooltipController(
            show = { request -> titleBarTooltipRequest = request },
            hide = { owner ->
                if (titleBarTooltipRequest?.owner === owner) {
                    titleBarTooltipRequest = null
                }
            },
        )
    ) {
        TitleBar(modifier = Modifier.newFullscreenControls()) { _ ->
            TitleBarContent(
                rootComponent = rootComponent,
                layoutMode = state.layoutMode,
                uiScale = state.settings.uiScale,
                onUiScaleChange = onUiScaleChange,
                palette = palette,
            )
        }
    }

    AppContent(
        rootComponent = rootComponent,
        state = state,
        palette = palette,
        externalTooltipRequest = titleBarTooltipRequest,
    )
}

@Composable
fun App() {
    val rootComponent = rememberRootComponent()
    val state by rootComponent.state.collectAsState()
    val palette = rememberOnyxPalette()

    AppContent(
        rootComponent = rootComponent,
        state = state,
        palette = palette,
    )
}

@Composable
private fun AppContent(
    rootComponent: RootComponent,
    state: RootState,
    palette: OnyxPalette,
    externalTooltipRequest: TooltipRequest? = null,
) {
    val tabDropZones = remember { mutableStateMapOf<PaneId, TabDropZone>() }
    var tabDropTarget by remember { mutableStateOf<TabDropTarget?>(null) }
    val fileDropZones = remember { mutableStateMapOf<String, FileDropZone>() }
    var fileDragState by remember { mutableStateOf<FileDragState?>(null) }
    var fileDropTarget by remember { mutableStateOf<FileDropTarget?>(null) }
    var fileDragPosition by remember { mutableStateOf<IntOffset?>(null) }
    var taskCenterVisible by remember { mutableStateOf(false) }
    var tooltipRequest by remember { mutableStateOf<TooltipRequest?>(null) }
    var appContentSize by remember { mutableStateOf(IntSize.Zero) }
    var appWindowOrigin by remember { mutableStateOf(IntOffset.Zero) }
    fun resolveTabDropTarget(windowPosition: IntOffset): TabDropTarget? {
        val target = tabDropZones.entries.firstOrNull { (_, zone) ->
            zone.bounds.containsPoint(windowPosition)
        } ?: return null
        return TabDropTarget(
            paneId = target.key,
            index = target.value.dropIndex(windowPosition),
        )
    }

    val onTabDrop: (PaneId, String, IntOffset) -> Unit = onTabDrop@{ sourcePaneId, tabId, windowPosition ->
        val target = resolveTabDropTarget(windowPosition) ?: return@onTabDrop
        rootComponent.moveTab(
            sourcePaneId = sourcePaneId,
            tabId = tabId,
            targetPaneId = target.paneId,
            targetIndex = target.index,
        )
        tabDropTarget = null
    }
    val onTabDragPositionChange: (IntOffset) -> Unit = { windowPosition ->
        tabDropTarget = resolveTabDropTarget(windowPosition)
    }
    val onTabDragEnd: () -> Unit = {
        tabDropTarget = null
    }

    fun isCurrentFileDropZone(zone: FileDropZone): Boolean {
        val paneState = rootComponent.state.value.paneState(zone.paneId)
        if (zone.directoryEntryId == null) {
            return true
        }
        val entries = (paneState.entriesState as? PaneEntriesState.Ready)?.entries.orEmpty()
        return entries.any { entry ->
            entry.id == zone.directoryEntryId &&
                    entry.location == zone.targetDirectoryLocation &&
                    entry.kind == VFileKind.DIRECTORY
        }
    }

    fun resolveFileDropTarget(windowPosition: IntOffset): FileDropTarget? {
        val dragState = fileDragState ?: return null
        val currentState = rootComponent.state.value
        val zones = fileDropZones.values
            .filter { zone -> isCurrentFileDropZone(zone) }
            .filter { zone -> zone.bounds.containsPoint(windowPosition) }
            .sortedWith(
                compareByDescending<FileDropZone> { it.directoryEntryId != null }
                    .thenBy { it.bounds.area }
            )
        val zone = zones.firstOrNull() ?: return null
        val sourceLocation = currentState.paneState(dragState.sourcePaneId).location
        val targetLocation = if (zone.directoryEntryId == null) {
            currentState.paneState(zone.paneId).location
        } else {
            zone.targetDirectoryLocation
        }
        if (zone.paneId == dragState.sourcePaneId &&
            zone.directoryEntryId == null &&
            targetLocation == sourceLocation
        ) {
            return null
        }
        return FileDropTarget(
            paneId = zone.paneId,
            targetDirectoryLocation = targetLocation,
            directoryEntryId = zone.directoryEntryId,
        )
    }

    val onFileDragStart: (PaneId, FileTransferOperation) -> Unit = { sourcePaneId, operation ->
        fileDragState = FileDragState(
            sourcePaneId = sourcePaneId,
            operation = operation,
        )
    }
    val onFileDragPositionChange: (IntOffset) -> Unit = { windowPosition ->
        fileDragPosition = windowPosition
        fileDropTarget = resolveFileDropTarget(windowPosition)
    }
    val onFileDragEnd: (IntOffset?) -> Unit = { windowPosition ->
        val dragState = fileDragState
        val target = windowPosition?.let(::resolveFileDropTarget) ?: fileDropTarget
        if (dragState != null && target != null) {
            rootComponent.requestTransferSelectedToDirectory(
                sourcePaneId = dragState.sourcePaneId,
                targetDirectoryLocation = target.targetDirectoryLocation,
                operation = dragState.operation,
            )
            rootComponent.activatePane(target.paneId)
        }
        fileDragState = null
        fileDropTarget = null
        fileDragPosition = null
    }

    LaunchedEffect(state.tasks.size) {
        if (state.tasks.isNotEmpty()) {
            taskCenterVisible = true
        }
    }

    if (taskCenterVisible && state.tasks.isNotEmpty()) {
        TaskCenterWindow(
            tasks = state.tasks,
            palette = palette,
            onDismissTask = rootComponent::dismissTask,
            onCancelTask = rootComponent::cancelTask,
            onClearAllTasks = rootComponent::clearAllTasks,
            onClose = { taskCenterVisible = false },
        )
    }

    IntUiTheme(isDark = isSystemInDarkTheme()) {
        CompositionLocalProvider(
            LocalTooltipController provides TooltipController(
                show = { request -> tooltipRequest = request },
                hide = { owner ->
                    if (tooltipRequest?.owner === owner) {
                        tooltipRequest = null
                    }
                },
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coordinates ->
                        appContentSize = coordinates.size
                        appWindowOrigin = coordinates.localToWindow(Offset.Zero).toIntOffset()
                    },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.appBackground),
                ) {
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
                                onDropTab = onTabDrop,
                                onTabDragPositionChange = onTabDragPositionChange,
                                onTabDragEnd = onTabDragEnd,
                                onTabDropZoneChange = { paneId, zone -> tabDropZones[paneId] = zone },
                                tabDropIndicatorIndex = tabDropTarget?.takeIf { it.paneId == PaneId.PRIMARY }?.index,
                                onFileDragStart = onFileDragStart,
                                onFileDragPositionChange = onFileDragPositionChange,
                                onFileDragEnd = onFileDragEnd,
                                onFileDropZoneChange = { zone -> fileDropZones[zone.key] = zone },
                                fileDropTarget = fileDropTarget,
                                palette = palette,
                            )
                        }

                        PaneLayoutMode.DUAL_VERTICAL -> {
                            var contentSize by remember { mutableStateOf(IntSize.Zero) }
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .onSizeChanged { contentSize = it },
                            ) {
                                PaneSurface(
                                    state = state.primaryPane,
                                    active = state.activePane == PaneId.PRIMARY,
                                    component = rootComponent.primaryPane,
                                    modifier = Modifier.weight(state.paneSplitFraction),
                                    onActivate = { rootComponent.activatePane(PaneId.PRIMARY) },
                                    canPaste = state.canPaste,
                                    onDeleteSelection = { rootComponent.requestDeleteSelectedInPane(PaneId.PRIMARY) },
                                    onCopySelection = { rootComponent.stageCopySelectedInPane(PaneId.PRIMARY) },
                                    onCutSelection = { rootComponent.stageCutSelectedInPane(PaneId.PRIMARY) },
                                    onPaste = { rootComponent.requestPasteIntoPane(PaneId.PRIMARY) },
                                    onDropTab = onTabDrop,
                                    onTabDragPositionChange = onTabDragPositionChange,
                                    onTabDragEnd = onTabDragEnd,
                                    onTabDropZoneChange = { paneId, zone -> tabDropZones[paneId] = zone },
                                    tabDropIndicatorIndex = tabDropTarget?.takeIf { it.paneId == PaneId.PRIMARY }?.index,
                                    onFileDragStart = onFileDragStart,
                                    onFileDragPositionChange = onFileDragPositionChange,
                                    onFileDragEnd = onFileDragEnd,
                                    onFileDropZoneChange = { zone -> fileDropZones[zone.key] = zone },
                                    fileDropTarget = fileDropTarget,
                                    palette = palette,
                                )
                                ResizablePaneDivider(
                                    orientation = Orientation.Vertical,
                                    palette = palette,
                                    onDragDelta = { delta ->
                                        val width = contentSize.width.toFloat().coerceAtLeast(1f)
                                        rootComponent.setPaneSplitFraction(rootComponent.state.value.paneSplitFraction + delta / width)
                                    },
                                )
                                PaneSurface(
                                    state = state.secondaryPane,
                                    active = state.activePane == PaneId.SECONDARY,
                                    component = rootComponent.secondaryPane,
                                    modifier = Modifier.weight(1f - state.paneSplitFraction),
                                    onActivate = { rootComponent.activatePane(PaneId.SECONDARY) },
                                    canPaste = state.canPaste,
                                    onDeleteSelection = { rootComponent.requestDeleteSelectedInPane(PaneId.SECONDARY) },
                                    onCopySelection = { rootComponent.stageCopySelectedInPane(PaneId.SECONDARY) },
                                    onCutSelection = { rootComponent.stageCutSelectedInPane(PaneId.SECONDARY) },
                                    onPaste = { rootComponent.requestPasteIntoPane(PaneId.SECONDARY) },
                                    onDropTab = onTabDrop,
                                    onTabDragPositionChange = onTabDragPositionChange,
                                    onTabDragEnd = onTabDragEnd,
                                    onTabDropZoneChange = { paneId, zone -> tabDropZones[paneId] = zone },
                                    tabDropIndicatorIndex = tabDropTarget?.takeIf { it.paneId == PaneId.SECONDARY }?.index,
                                    onFileDragStart = onFileDragStart,
                                    onFileDragPositionChange = onFileDragPositionChange,
                                    onFileDragEnd = onFileDragEnd,
                                    onFileDropZoneChange = { zone -> fileDropZones[zone.key] = zone },
                                    fileDropTarget = fileDropTarget,
                                    palette = palette,
                                )
                            }
                        }

                        PaneLayoutMode.DUAL_HORIZONTAL -> {
                            var contentSize by remember { mutableStateOf(IntSize.Zero) }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .onSizeChanged { contentSize = it },
                            ) {
                                PaneSurface(
                                    state = state.primaryPane,
                                    active = state.activePane == PaneId.PRIMARY,
                                    component = rootComponent.primaryPane,
                                    modifier = Modifier.weight(state.paneSplitFraction),
                                    onActivate = { rootComponent.activatePane(PaneId.PRIMARY) },
                                    canPaste = state.canPaste,
                                    onDeleteSelection = { rootComponent.requestDeleteSelectedInPane(PaneId.PRIMARY) },
                                    onCopySelection = { rootComponent.stageCopySelectedInPane(PaneId.PRIMARY) },
                                    onCutSelection = { rootComponent.stageCutSelectedInPane(PaneId.PRIMARY) },
                                    onPaste = { rootComponent.requestPasteIntoPane(PaneId.PRIMARY) },
                                    onDropTab = onTabDrop,
                                    onTabDragPositionChange = onTabDragPositionChange,
                                    onTabDragEnd = onTabDragEnd,
                                    onTabDropZoneChange = { paneId, zone -> tabDropZones[paneId] = zone },
                                    tabDropIndicatorIndex = tabDropTarget?.takeIf { it.paneId == PaneId.PRIMARY }?.index,
                                    onFileDragStart = onFileDragStart,
                                    onFileDragPositionChange = onFileDragPositionChange,
                                    onFileDragEnd = onFileDragEnd,
                                    onFileDropZoneChange = { zone -> fileDropZones[zone.key] = zone },
                                    fileDropTarget = fileDropTarget,
                                    palette = palette,
                                )
                                ResizablePaneDivider(
                                    orientation = Orientation.Horizontal,
                                    palette = palette,
                                    onDragDelta = { delta ->
                                        val height = contentSize.height.toFloat().coerceAtLeast(1f)
                                        rootComponent.setPaneSplitFraction(rootComponent.state.value.paneSplitFraction + delta / height)
                                    },
                                )
                                PaneSurface(
                                    state = state.secondaryPane,
                                    active = state.activePane == PaneId.SECONDARY,
                                    component = rootComponent.secondaryPane,
                                    modifier = Modifier.weight(1f - state.paneSplitFraction),
                                    onActivate = { rootComponent.activatePane(PaneId.SECONDARY) },
                                    canPaste = state.canPaste,
                                    onDeleteSelection = { rootComponent.requestDeleteSelectedInPane(PaneId.SECONDARY) },
                                    onCopySelection = { rootComponent.stageCopySelectedInPane(PaneId.SECONDARY) },
                                    onCutSelection = { rootComponent.stageCutSelectedInPane(PaneId.SECONDARY) },
                                    onPaste = { rootComponent.requestPasteIntoPane(PaneId.SECONDARY) },
                                    onDropTab = onTabDrop,
                                    onTabDragPositionChange = onTabDragPositionChange,
                                    onTabDragEnd = onTabDragEnd,
                                    onTabDropZoneChange = { paneId, zone -> tabDropZones[paneId] = zone },
                                    tabDropIndicatorIndex = tabDropTarget?.takeIf { it.paneId == PaneId.SECONDARY }?.index,
                                    onFileDragStart = onFileDragStart,
                                    onFileDragPositionChange = onFileDragPositionChange,
                                    onFileDragEnd = onFileDragEnd,
                                    onFileDropZoneChange = { zone -> fileDropZones[zone.key] = zone },
                                    fileDropTarget = fileDropTarget,
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

                (tooltipRequest ?: externalTooltipRequest)?.let { request ->
                    OnyxTooltipOverlay(
                        request = request,
                        appSize = appContentSize,
                        appWindowOrigin = appWindowOrigin,
                        palette = palette,
                    )
                }
                val currentFileDragState = fileDragState
                if (currentFileDragState != null && fileDragPosition != null) {
                    FileDragOverlay(
                        operation = currentFileDragState.operation,
                        pointerWindowPosition = fileDragPosition,
                        targetDirectoryLocation = fileDropTarget?.targetDirectoryLocation,
                        appWindowOrigin = appWindowOrigin,
                        palette = palette,
                    )
                }
            }
        }
    }
}

// ── Title bar ───────────────────────────────────────────────────────────────

@Composable
private fun TitleBarContent(
    rootComponent: RootComponent,
    layoutMode: PaneLayoutMode,
    uiScale: Int,
    onUiScaleChange: (Int) -> Unit,
    palette: OnyxPalette,
) {
    // Jewel DecoratedWindow 的标题栏内容区域
    // 注意：DecoratedWindow 会自动处理窗口拖拽和系统按钮区域
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.app_name),
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = palette.foreground,
        )

        Spacer(modifier = Modifier.width(14.dp))

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "$uiScale%",
                    fontSize = 11.sp,
                    color = palette.mutedForeground,
                )
                ZoomSlider(
                    value = uiScale,
                    onValueChange = onUiScaleChange,
                    palette = palette,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(1.dp),
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
                    contentDescription = stringResource(Res.string.action_layout_single),
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
                    contentDescription = stringResource(Res.string.action_layout_dual_vertical),
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
                    contentDescription = stringResource(Res.string.action_layout_dual_horizontal),
                )
            }

            Spacer(modifier = Modifier.width(3.dp))

            TitleBarIconButton(
                onClick = { },
                palette = palette,
                tooltip = stringResource(Res.string.action_open_settings),
            ) {
                Icon(
                    key = AllIconsKeys.General.GearPlain,
                    contentDescription = stringResource(Res.string.action_open_settings)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun OnyxTooltip(
    text: String,
    palette: OnyxPalette,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val tooltipController = LocalTooltipController.current
    val tooltipOwner = remember { Any() }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var anchorBounds by remember { mutableStateOf<IntRect?>(null) }
    var pointerPosition by remember { mutableStateOf<IntOffset?>(null) }

    fun updatePointerPosition(eventPosition: Offset) {
        pointerPosition = anchorBounds?.let { bounds ->
            IntOffset(
                x = bounds.left + eventPosition.x.roundToInt(),
                y = bounds.top + eventPosition.y.roundToInt(),
            )
        }
    }

    LaunchedEffect(enabled, isHovered, text, anchorBounds, pointerPosition) {
        val bounds = anchorBounds
        val position = pointerPosition
        if (enabled && isHovered && text.isNotBlank() && bounds != null) {
            tooltipController.show(
                TooltipRequest(
                    owner = tooltipOwner,
                    text = text,
                    anchorBounds = bounds,
                    pointerPosition = position ?: bounds.centerBottom(),
                )
            )
        } else {
            tooltipController.hide(tooltipOwner)
        }
    }

    Box(
        modifier = Modifier
            .onGloballyPositioned { coordinates -> anchorBounds = coordinates.windowBounds() }
            .onPointerEvent(PointerEventType.Enter) { event ->
                event.changes.firstOrNull()?.position?.let(::updatePointerPosition)
            }
            .onPointerEvent(PointerEventType.Move) { event ->
                event.changes.firstOrNull()?.position?.let(::updatePointerPosition)
            }
            .hoverable(enabled = enabled, interactionSource = interactionSource),
    ) {
        content()
    }
}

@Composable
private fun OnyxTooltipOverlay(
    request: TooltipRequest,
    appSize: IntSize,
    appWindowOrigin: IntOffset,
    palette: OnyxPalette,
) {
    var tooltipSize by remember(request.text) { mutableStateOf(IntSize.Zero) }
    Text(
        text = request.text,
        modifier = Modifier
            .offset { tooltipOffset(request.pointerPosition, appWindowOrigin, appSize, tooltipSize) }
            .onSizeChanged { tooltipSize = it }
            .widthIn(max = 260.dp)
            .border(1.dp, palette.outlineVariant, RoundedCornerShape(4.dp))
            .background(palette.floatingSurface, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = palette.foreground,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun FileDragOverlay(
    operation: FileTransferOperation,
    pointerWindowPosition: IntOffset?,
    targetDirectoryLocation: String?,
    appWindowOrigin: IntOffset,
    palette: OnyxPalette,
) {
    val alpha by animateFloatAsState(
        targetValue = if (pointerWindowPosition != null) 1f else 0f,
        animationSpec = tween(durationMillis = 100),
    )
    val label = if (targetDirectoryLocation != null) {
        when (operation) {
            FileTransferOperation.COPY -> stringResource(Res.string.label_copy_to_destination, targetDirectoryLocation)
            FileTransferOperation.MOVE -> stringResource(Res.string.label_move_to_destination, targetDirectoryLocation)
        }
    } else {
        when (operation) {
            FileTransferOperation.COPY -> stringResource(Res.string.action_copy)
            FileTransferOperation.MOVE -> stringResource(Res.string.action_move)
        }
    }
    val pointerPosition = pointerWindowPosition ?: return

    Row(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = pointerPosition.x - appWindowOrigin.x + 18,
                    y = pointerPosition.y - appWindowOrigin.y + 18,
                )
            }
            .alpha(alpha)
            .border(1.dp, palette.outline, RoundedCornerShape(4.dp))
            .background(palette.floatingSurface, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            key = if (operation == FileTransferOperation.COPY) AllIconsKeys.Actions.Copy else AllIconsKeys.Actions.MenuCut,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = label,
            modifier = Modifier.widthIn(max = 320.dp),
            color = palette.foreground,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val background = when {
        selected -> palette.titleBarActiveBackground
        isPressed -> palette.titleBarPressedBackground
        isHovered -> palette.titleBarHoverBackground
        else -> Color.Transparent
    }

    OnyxTooltip(text = tooltip, palette = palette) {
        Box(
            modifier = Modifier
                .hoverable(interactionSource)
                .background(background, RoundedCornerShape(4.dp))
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = 5.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun TitleBarIconButton(
    onClick: () -> Unit,
    palette: OnyxPalette,
    tooltip: String,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    OnyxTooltip(text = tooltip, palette = palette) {
        Box(
            modifier = Modifier
                .hoverable(interactionSource)
                .background(
                    when {
                        isPressed -> palette.titleBarPressedBackground
                        isHovered -> palette.titleBarHoverBackground
                        else -> Color.Transparent
                    },
                    RoundedCornerShape(4.dp),
                )
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = 5.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun ResizablePaneDivider(
    orientation: Orientation,
    palette: OnyxPalette,
    onDragDelta: (Float) -> Unit,
) {
    val modifier = when (orientation) {
        Orientation.Vertical -> Modifier
            .fillMaxHeight()
            .width(PaneDividerHitSlop)
            .pointerHoverIcon(horizontalResizePointerIcon())
            .pointerInput(Unit) {
                detectDragGestures { _, dragAmount ->
                    onDragDelta(dragAmount.x)
                }
            }

        Orientation.Horizontal -> Modifier
            .fillMaxWidth()
            .height(PaneDividerHitSlop)
            .pointerHoverIcon(verticalResizePointerIcon())
            .pointerInput(Unit) {
                detectDragGestures { _, dragAmount ->
                    onDragDelta(dragAmount.y)
                }
            }
    }

    Box(
        modifier = modifier.background(palette.appBackground),
        contentAlignment = Alignment.Center,
    ) {
        when (orientation) {
            Orientation.Vertical -> Divider(
                Orientation.Vertical,
                modifier = Modifier.fillMaxHeight().width(1.dp),
            )

            Orientation.Horizontal -> Divider(
                Orientation.Horizontal,
                modifier = Modifier.fillMaxWidth().height(1.dp),
            )
        }
    }
}

@Composable
private fun ToolbarIconButton(
    enabled: Boolean,
    onClick: () -> Unit,
    palette: OnyxPalette,
    tooltip: String,
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val background = when {
        !enabled -> Color.Transparent
        selected -> palette.titleBarActiveBackground
        isPressed -> palette.titleBarPressedBackground
        isHovered -> palette.titleBarHoverBackground
        else -> Color.Transparent
    }

    OnyxTooltip(text = tooltip, palette = palette, enabled = enabled) {
        Box(
            modifier = Modifier
                .hoverable(enabled = enabled, interactionSource = interactionSource)
                .background(background, RoundedCornerShape(4.dp))
                .clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .alpha(if (enabled) 1f else 0.45f)
                .padding(horizontal = 5.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun PaneTabBar(
    state: PaneState,
    active: Boolean,
    palette: OnyxPalette,
    onActivate: () -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCreateTab: () -> Unit,
    onDropTab: (String, IntOffset) -> Unit,
    onDragPositionChange: (IntOffset) -> Unit,
    onDragEnd: () -> Unit,
    onDropZoneChange: (TabDropZone) -> Unit,
    dropIndicatorIndex: Int?,
) {
    val scrollState = rememberScrollState()
    var barBounds by remember { mutableStateOf<IntRect?>(null) }
    val tabBounds = remember { mutableStateMapOf<String, IntRect>() }

    fun reportDropZone() {
        val currentBarBounds = barBounds ?: return
        onDropZoneChange(
            TabDropZone(
                bounds = currentBarBounds,
                tabIds = state.tabs.map { it.id },
                tabBounds = tabBounds.toMap(),
            )
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(palette.headerBackground)
            .onGloballyPositioned { coordinates ->
                barBounds = coordinates.windowBounds()
                reportDropZone()
            }
            .horizontalScroll(scrollState)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        state.tabs.forEachIndexed { index, tab ->
            TabDropIndicator(
                visible = dropIndicatorIndex == index,
                palette = palette,
            )
            PaneTabChip(
                tabId = tab.id,
                title = tab.title,
                selected = tab.id == state.activeTabId,
                closeEnabled = state.tabs.size > 1,
                activePane = active,
                palette = palette,
                onActivate = onActivate,
                onSelect = { onSelectTab(tab.id) },
                onClose = { onCloseTab(tab.id) },
                onDropTab = { position -> onDropTab(tab.id, position) },
                onDragPositionChange = onDragPositionChange,
                onDragEnd = onDragEnd,
                onBoundsChanged = { bounds ->
                    tabBounds[tab.id] = bounds
                    reportDropZone()
                },
            )
        }
        TabDropIndicator(
            visible = dropIndicatorIndex == state.tabs.size,
            palette = palette,
        )

        val newTabTooltip = stringResource(Res.string.action_new_tab)
        OnyxTooltip(text = newTabTooltip, palette = palette) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(24.dp)
                    .background(Color.Transparent, RoundedCornerShape(4.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            onActivate()
                            onCreateTab()
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    key = AllIconsKeys.General.Add,
                    contentDescription = newTabTooltip,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

@Composable
private fun TabDropIndicator(
    visible: Boolean,
    palette: OnyxPalette,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 120)) +
                expandHorizontally(animationSpec = tween(durationMillis = 120)),
        exit = fadeOut(animationSpec = tween(durationMillis = 90)) +
                shrinkHorizontally(animationSpec = tween(durationMillis = 90)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(8.dp)
                .padding(horizontal = 3.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(2.dp)
                    .background(palette.accent, RoundedCornerShape(1.dp)),
            )
        }
    }
}

@Composable
private fun PaneTabChip(
    tabId: String,
    title: String,
    selected: Boolean,
    closeEnabled: Boolean,
    activePane: Boolean,
    palette: OnyxPalette,
    onActivate: () -> Unit,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onDropTab: (IntOffset) -> Unit,
    onDragPositionChange: (IntOffset) -> Unit,
    onDragEnd: () -> Unit,
    onBoundsChanged: (IntRect) -> Unit,
) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var dragPosition by remember { mutableStateOf<IntOffset?>(null) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val background = when {
        selected && activePane -> palette.surface
        selected -> palette.surfaceVariant
        isHovered -> palette.titleBarHoverBackground
        else -> Color.Transparent
    }

    OnyxTooltip(text = title, palette = palette) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 148.dp)
                .onGloballyPositioned { layoutCoordinates ->
                    coordinates = layoutCoordinates
                    onBoundsChanged(layoutCoordinates.windowBounds())
                }
                .background(background, RoundedCornerShape(4.dp))
                .border(
                    width = 1.dp,
                    color = if (selected) palette.outline else Color.Transparent,
                    shape = RoundedCornerShape(4.dp),
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        onActivate()
                        onSelect()
                    },
                )
                .pointerInput(tabId) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragPosition = coordinates?.localToWindow(offset)?.toIntOffset()
                            dragPosition?.let(onDragPositionChange)
                        },
                        onDragCancel = {
                            dragPosition = null
                            onDragEnd()
                        },
                        onDragEnd = {
                            dragPosition?.let(onDropTab)
                            dragPosition = null
                            onDragEnd()
                        },
                        onDrag = { change, _ ->
                            dragPosition = coordinates?.localToWindow(change.position)?.toIntOffset()
                            dragPosition?.let(onDragPositionChange)
                        },
                    )
                }
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.widthIn(max = if (closeEnabled) 112.dp else 132.dp),
                fontSize = 12.sp,
                color = if (selected) palette.foreground else palette.mutedForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (closeEnabled) {
                val closeTabTooltip = stringResource(Res.string.action_close_tab)
                OnyxTooltip(text = closeTabTooltip, palette = palette) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    onActivate()
                                    onClose()
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            key = AllIconsKeys.Actions.Close,
                            contentDescription = closeTabTooltip,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
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
    palette: OnyxPalette,
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
            palette.rowHoverBackground.copy(alpha = 0.28f)
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
                color = if (active) palette.outline else palette.outlineVariant,
            )
            .background(palette.surface)
            .onGloballyPositioned { coordinates ->
                paneBounds = coordinates.windowBounds()
                reportPaneDropZone()
            }
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (!active || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.C -> {
                        if (event.isCtrlPressed || event.isMetaPressed) {
                            onCopySelection()
                            true
                        } else {
                            false
                        }
                    }

                    Key.X -> {
                        if (event.isCtrlPressed || event.isMetaPressed) {
                            onCutSelection()
                            true
                        } else {
                            false
                        }
                    }

                    Key.V -> {
                        if (event.isCtrlPressed || event.isMetaPressed) {
                            onPaste()
                            true
                        } else {
                            false
                        }
                    }
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
                        if (showContextMenu) {
                            showContextMenu = false
                        } else {
                            component.clearSelection()
                        }
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
        PaneTabBar(
            state = state,
            active = active,
            palette = palette,
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
                .background(palette.headerBackground)
                .height(28.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            ToolbarIconButton(
                enabled = state.canGoBack,
                onClick = { onActivate(); component.goBack() },
                palette = palette,
                tooltip = stringResource(Res.string.action_go_back),
            ) {
                Icon(key = AllIconsKeys.Actions.Back, contentDescription = stringResource(Res.string.action_go_back))
            }
            ToolbarIconButton(
                enabled = state.canGoForward,
                onClick = { onActivate(); component.goForward() },
                palette = palette,
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
                palette = palette,
                tooltip = stringResource(Res.string.action_go_up),
            ) {
                Icon(key = AllIconsKeys.General.ArrowUp, contentDescription = stringResource(Res.string.action_go_up))
            }
            ToolbarIconButton(
                enabled = true,
                onClick = { onActivate(); component.openDirectory(System.getProperty("user.home")) },
                palette = palette,
                tooltip = stringResource(Res.string.action_go_home),
            ) {
                Icon(
                    key = AllIconsKeys.Nodes.HomeFolder,
                    contentDescription = stringResource(Res.string.action_go_home),
                )
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

            ToolbarIconButton(
                enabled = true,
                onClick = { onActivate(); component.refresh() },
                palette = palette,
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
                palette = palette,
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

        // ── File list ──────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
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
                columns = state.detailsColumns,
                columnWeights = state.detailsColumnWeights,
                sort = state.detailsSort,
                selectedEntryIds = state.selectedEntryIds,
                state = state.entriesState,
                paneActive = active,
                contextMenuVisible = showContextMenu,
                onActivate = onActivate,
                onOpenEntry = component::openEntry,
                onToggleSort = component::toggleSort,
                onResizeColumn = component::resizeDetailsColumn,
                onSelectEntry = component::selectEntry,
                palette = palette,
                paneId = state.paneId,
                fileDropTarget = fileDropTarget,
                onStartFileDrag = onFileDragStart,
                onFileDragPositionChange = onFileDragPositionChange,
                onFileDragEnd = onFileDragEnd,
                onFileDropZoneChange = onFileDropZoneChange,
                onShowContextMenu = { entryId, entrySelected, pointerPosition ->
                    onActivate()
                    contextMenuOffset = pointerPosition
                    if (!entrySelected) component.selectEntry(entryId)
                    showContextMenu = true
                },
                onDismissContextMenu = { showContextMenu = false },
            )

            if (showContextMenu) {
                PaneContextMenu(
                    anchorOffset = contextMenuOffset,
                    canOperateOnSelection = state.selectedEntryIds.isNotEmpty(),
                    canPaste = canPaste,
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
                .padding(horizontal = 6.dp, vertical = 3.dp)
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
    val maxScroll = scrollState.maxValue

    LaunchedEffect(location, maxScroll) {
        scrollState.scrollTo(maxScroll)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.inputBackground, RoundedCornerShape(4.dp))
            .horizontalScroll(scrollState)
            .clickable(onClick = onEdit)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        breadcrumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Icon(
                    key = AllIconsKeys.General.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = crumb.label,
                color = if (index == breadcrumbs.lastIndex) palette.foreground else palette.mutedForeground,
                fontSize = 12.sp,
                fontWeight = if (index == breadcrumbs.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
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
    columnWeights: Map<DetailsColumn, Float>,
    sort: DetailsSort,
    selectedEntryIds: Set<String>,
    state: PaneEntriesState,
    paneActive: Boolean,
    contextMenuVisible: Boolean,
    onActivate: () -> Unit,
    onOpenEntry: (VFile) -> Unit,
    onToggleSort: (DetailsColumn) -> Unit,
    onResizeColumn: (DetailsColumn, DetailsColumn, Float) -> Unit,
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
                    columnWeights = columnWeights,
                    sort = sort,
                    onToggleSort = onToggleSort,
                    onResizeColumn = onResizeColumn,
                    palette = palette,
                )

                // ── File list ──────────────────────────────────────────
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 4.dp),
                    userScrollEnabled = !contextMenuVisible,
                ) {
                    itemsIndexed(
                        items = state.entries,
                        key = { _, entry -> entry.id },
                    ) { index, entry ->
                        EntryRow(
                            columns = columns,
                            columnWeights = columnWeights,
                            entry = entry,
                            zebra = index % 2 == 1,
                            selected = selectedEntryIds.contains(entry.id),
                            selectedEntryCount = selectedEntryIds.size,
                            paneActive = paneActive,
                            onActivate = onActivate,
                            onOpenEntry = onOpenEntry,
                            onSelectEntry = onSelectEntry,
                            palette = palette,
                            paneId = paneId,
                            fileDropTarget = fileDropTarget,
                            onStartFileDrag = onStartFileDrag,
                            onFileDragPositionChange = onFileDragPositionChange,
                            onFileDragEnd = onFileDragEnd,
                            onFileDropZoneChange = onFileDropZoneChange,
                            onShowContextMenu = onShowContextMenu,
                            onDismissContextMenu = onDismissContextMenu,
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
    columnWeights: Map<DetailsColumn, Float>,
    sort: DetailsSort,
    onToggleSort: (DetailsColumn) -> Unit,
    onResizeColumn: (DetailsColumn, DetailsColumn, Float) -> Unit,
    palette: OnyxPalette,
) {
    var headerWidthPx by remember { mutableStateOf(1) }
    val visibleColumns = remember(columns) { visibleDetailsColumns(columns) }
    val totalWeight = visibleColumns
        .sumOf { column -> detailsColumnWeight(columnWeights, column).toDouble() }
        .toFloat()
        .coerceAtLeast(1f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.headerBackground)
            .height(24.dp)
            .onSizeChanged { headerWidthPx = it.width.coerceAtLeast(1) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visibleColumns.forEachIndexed { index, column ->
            val nextColumn = visibleColumns.getOrNull(index + 1)
            when (column) {
                DetailsColumn.NAME -> SortHeaderCell(
                    text = stringResource(Res.string.label_column_name),
                    sortHint = sortHint(column, sort),
                    modifier = Modifier.weight(detailsColumnWeight(columnWeights, column)),
                    palette = palette,
                    onClick = { onToggleSort(column) },
                )

                DetailsColumn.SIZE -> SortHeaderCell(
                    text = stringResource(Res.string.label_column_size),
                    sortHint = sortHint(column, sort),
                    modifier = Modifier.weight(detailsColumnWeight(columnWeights, column)),
                    palette = palette,
                    onClick = { onToggleSort(column) },
                )

                DetailsColumn.MODIFIED -> SortHeaderCell(
                    text = stringResource(Res.string.label_column_modified),
                    sortHint = sortHint(column, sort),
                    modifier = Modifier.weight(detailsColumnWeight(columnWeights, column)),
                    palette = palette,
                    onClick = { onToggleSort(column) },
                )

                DetailsColumn.TYPE -> Unit
            }
            if (nextColumn != null) {
                DetailsColumnResizeGap(
                    palette = palette,
                    onResize = { deltaPx ->
                        onResizeColumn(column, nextColumn, deltaPx / headerWidthPx.toFloat() * totalWeight)
                    },
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
                fontSize = 11.sp,
                color = palette.mutedForeground,
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
private fun DetailsColumnResizeGap(
    palette: OnyxPalette,
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
                .background(palette.outlineVariant),
        )
    }
}

// ── Entry row (high-density, single-line) ───────────────────────────────────

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    columns: List<DetailsColumn>,
    columnWeights: Map<DetailsColumn, Float>,
    entry: VFile,
    zebra: Boolean,
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
) {
    var additiveSelection by remember { mutableStateOf(false) }
    var rangeSelection by remember { mutableStateOf(false) }
    var dragOperation by remember { mutableStateOf(FileTransferOperation.MOVE) }
    var dragPosition by remember { mutableStateOf<IntOffset?>(null) }
    var rowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val isDirectoryDropTarget = fileDropTarget?.directoryEntryId == entry.id
    val preserveMultiSelectionForDrag = selected && selectedEntryCount > 1
    val rowBackground by animateColorAsState(
        targetValue = when {
            isDirectoryDropTarget -> palette.rowHoverBackground
            selected && paneActive -> palette.selectionBackground
            selected && !paneActive -> palette.inactiveSelectionBackground
            zebra -> palette.surfaceVariant
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 120),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                rowCoordinates = coordinates
            }
            .onPointerEvent(PointerEventType.Press) { event ->
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
            .pointerInput(entry.id, paneId) {
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
            .background(rowBackground)
            .combinedClickable(
                onClick = {
                    onActivate()
                },
                onDoubleClick = {
                    onActivate()
                    onDismissContextMenu()
                    onOpenEntry(entry)
                },
            )
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .height(22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DetailsColumnGap),
    ) {
        visibleDetailsColumns(columns).forEach { column ->
            when (column) {
                DetailsColumn.NAME -> {
                    Row(
                        modifier = Modifier
                            .weight(detailsColumnWeight(columnWeights, column))
                            .onGloballyPositioned { coordinates ->
                                if (entry.kind == VFileKind.DIRECTORY) {
                                    onFileDropZoneChange(
                                        FileDropZone(
                                            paneId = paneId,
                                            targetDirectoryLocation = entry.location,
                                            bounds = coordinates.windowBounds(),
                                            directoryEntryId = entry.id,
                                        )
                                    )
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            key = if (entry.kind == VFileKind.DIRECTORY) AllIconsKeys.Nodes.Folder else AllIconsKeys.FileTypes.Any_type,
                            contentDescription = null,
                        )
                        Text(
                            text = entry.name,
                            modifier = Modifier.weight(1f),
                            fontWeight = if (entry.kind == VFileKind.DIRECTORY) FontWeight.Medium else FontWeight.Normal,
                            fontSize = 12.sp,
                            color = palette.foreground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                DetailsColumn.SIZE -> {
                    Text(
                        text = formatFileSize(entry.sizeBytes),
                        modifier = Modifier.weight(detailsColumnWeight(columnWeights, column)),
                        fontSize = 12.sp,
                        color = palette.mutedForeground,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DetailsColumn.MODIFIED -> {
                    Text(
                        text = formatModifiedTime(entry.modifiedAtEpochMillis),
                        modifier = Modifier.weight(detailsColumnWeight(columnWeights, column)),
                        fontSize = 12.sp,
                        color = palette.mutedForeground,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                DetailsColumn.TYPE -> Unit
            }
        }
    }
}

// ── Context menu ────────────────────────────────────────────────────────────

@Composable
private fun BoxScope.PaneContextMenu(
    anchorOffset: IntOffset,
    canOperateOnSelection: Boolean,
    canPaste: Boolean,
    onCopySelection: () -> Unit,
    onCutSelection: () -> Unit,
    onPaste: () -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    palette: OnyxPalette,
) {
    Popup(
        popupPositionProvider = remember(anchorOffset) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val desiredX = anchorOffset.x
                    val desiredY = anchorOffset.y
                    val x = desiredX.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                    val y = desiredY.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                    return IntOffset(x, y)
                }
            }
        },
        onDismissRequest = onClose,
        properties = PopupProperties(focusable = false),
    ) {
        Column(
            modifier = Modifier
                .width(220.dp)
                .border(1.dp, palette.outlineVariant, RoundedCornerShape(6.dp))
                .background(palette.floatingSurface, RoundedCornerShape(6.dp))
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            ContextMenuItem(
                text = stringResource(Res.string.action_copy),
                enabled = canOperateOnSelection,
                iconKey = AllIconsKeys.Actions.Copy,
                palette = palette,
                onClick = onCopySelection,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_cut),
                enabled = canOperateOnSelection,
                iconKey = AllIconsKeys.Actions.MenuCut,
                palette = palette,
                onClick = onCutSelection,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_paste),
                enabled = canPaste,
                iconKey = AllIconsKeys.Actions.MenuPaste,
                palette = palette,
                onClick = onPaste,
            )
            Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
            ContextMenuItem(
                text = stringResource(Res.string.action_refresh_active),
                enabled = true,
                iconKey = AllIconsKeys.Actions.Refresh,
                palette = palette,
                onClick = onRefresh,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_close_menu),
                enabled = true,
                iconKey = AllIconsKeys.Actions.Close,
                palette = palette,
                onClick = onClose,
            )
        }
    }
}

@Composable
private fun ContextMenuItem(
    text: String,
    enabled: Boolean,
    iconKey: org.jetbrains.jewel.ui.icon.IconKey,
    palette: OnyxPalette,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val background = if (enabled && isHovered) palette.rowHoverBackground else Color.Transparent
    val contentColor = if (enabled) palette.foreground else palette.disabledForeground

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .background(background, RoundedCornerShape(4.dp))
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.55f)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(key = iconKey, contentDescription = null)
        Text(text = text, fontSize = 12.sp, color = contentColor)
    }
}

// ── Task panel ──────────────────────────────────────────────────────────────

@Composable
private fun TaskCenterWindow(
    tasks: List<BackgroundTask>,
    palette: OnyxPalette,
    onDismissTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onClearAllTasks: () -> Unit,
    onClose: () -> Unit,
) {
    Window(
        onCloseRequest = onClose,
        title = stringResource(Res.string.label_task_center),
        state = rememberWindowState(width = 460.dp, height = 320.dp),
    ) {
        IntUiTheme(isDark = isSystemInDarkTheme()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.appBackground)
                    .padding(10.dp),
            ) {
                TaskPanel(
                    tasks = tasks,
                    onDismissTask = onDismissTask,
                    onCancelTask = onCancelTask,
                    onClearAllTasks = onClearAllTasks,
                    palette = palette,
                )
            }
        }
    }
}

@Composable
private fun TaskPanel(
    tasks: List<BackgroundTask>,
    onDismissTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onClearAllTasks: () -> Unit,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ContextMenuItem(
                text = stringResource(Res.string.action_clear_all_tasks),
                enabled = tasks.isNotEmpty(),
                iconKey = AllIconsKeys.Actions.Close,
                palette = palette,
                onClick = onClearAllTasks,
            )
        }
        tasks.forEach { task ->
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
                    TaskProgressBar(
                        progress = task.progress,
                        status = task.status,
                        palette = palette,
                    )
                }
                val taskRunning =
                    task.status == BackgroundTaskStatus.QUEUED || task.status == BackgroundTaskStatus.RUNNING
                IconButton(onClick = { if (taskRunning) onCancelTask(task.id) else onDismissTask(task.id) }) {
                    Icon(
                        key = AllIconsKeys.Actions.Close,
                        contentDescription = stringResource(
                            if (taskRunning) Res.string.action_cancel_task else Res.string.action_close_menu
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskProgressBar(
    progress: Float?,
    status: BackgroundTaskStatus,
    palette: OnyxPalette,
) {
    val targetProgress = when {
        progress != null -> progress.coerceIn(0f, 1f)
        status == BackgroundTaskStatus.SUCCEEDED -> 1f
        else -> 0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 160),
    )
    val barColor = when (status) {
        BackgroundTaskStatus.FAILED -> Color(0xFFD74E4E)
        BackgroundTaskStatus.CANCELLED -> palette.disabledForeground
        BackgroundTaskStatus.SUCCEEDED -> Color(0xFF4DAA57)
        BackgroundTaskStatus.QUEUED, BackgroundTaskStatus.RUNNING -> palette.accent
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(palette.outlineVariant, RoundedCornerShape(2.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedProgress)
                .height(4.dp)
                .background(barColor, RoundedCornerShape(2.dp)),
        )
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val activeState = if (activePane == PaneId.PRIMARY) primaryPane else secondaryPane
            if (activeState.entriesState is PaneEntriesState.Ready) {
                val entries = activeState.entriesState.entries
                val dirs = entries.count { it.kind == VFileKind.DIRECTORY }
                val files = entries.count { it.kind == VFileKind.FILE }
                Text(
                    text = stringResource(Res.string.label_directory_file_count, dirs, files),
                    fontSize = 11.sp,
                    color = palette.mutedForeground,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LayoutIconButton(
                selected = true,
                onClick = { },
                palette = palette,
                tooltip = stringResource(Res.string.label_mode_details),
            ) {
                Icon(
                    key = AllIconsKeys.Actions.ListFiles,
                    contentDescription = stringResource(Res.string.label_mode_details),
                )
            }
            LayoutIconButton(
                selected = false,
                onClick = { },
                palette = palette,
                tooltip = stringResource(Res.string.label_mode_gallery),
            ) {
                Icon(
                    key = AllIconsKeys.General.Layout,
                    contentDescription = stringResource(Res.string.label_mode_gallery),
                )
            }
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

private fun visibleDetailsColumns(columns: List<DetailsColumn>): List<DetailsColumn> {
    return columns.filterNot { it == DetailsColumn.TYPE }
}

private fun detailsColumnWeight(
    weights: Map<DetailsColumn, Float>,
    column: DetailsColumn,
): Float {
    return (weights[column] ?: defaultDetailsColumnWeight(column)).coerceAtLeast(0.01f)
}

private fun defaultDetailsColumnWeight(column: DetailsColumn): Float {
    return when (column) {
        DetailsColumn.NAME -> 0.58f
        DetailsColumn.SIZE -> 0.16f
        DetailsColumn.MODIFIED -> 0.26f
        DetailsColumn.TYPE -> 0.08f
    }
}

private fun horizontalResizePointerIcon(): PointerIcon {
    return PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR))
}

private fun verticalResizePointerIcon(): PointerIcon {
    return PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR))
}

private fun LayoutCoordinates.windowBounds(): IntRect {
    val topLeft = localToWindow(Offset.Zero)
    return IntRect(
        left = topLeft.x.roundToInt(),
        top = topLeft.y.roundToInt(),
        right = topLeft.x.roundToInt() + size.width,
        bottom = topLeft.y.roundToInt() + size.height,
    )
}

private fun Offset.toIntOffset(): IntOffset {
    return IntOffset(x.roundToInt(), y.roundToInt())
}

private fun IntRect.containsPoint(position: IntOffset): Boolean {
    return position.x >= left &&
            position.x <= right &&
            position.y >= top &&
            position.y <= bottom
}

private val IntRect.area: Int
    get() = (width.coerceAtLeast(0)) * (height.coerceAtLeast(0))

private val FileDropZone.key: String
    get() = if (directoryEntryId == null) {
        "${paneId.name}:__pane"
    } else {
        "${paneId.name}:$directoryEntryId:$targetDirectoryLocation"
    }

private fun RootState.paneState(paneId: PaneId): PaneState {
    return when (paneId) {
        PaneId.PRIMARY -> primaryPane
        PaneId.SECONDARY -> secondaryPane
    }
}

private fun tooltipOffset(
    pointerWindowPosition: IntOffset,
    appWindowOrigin: IntOffset,
    appSize: IntSize,
    tooltipSize: IntSize,
): IntOffset {
    val pointerPosition = IntOffset(
        x = pointerWindowPosition.x - appWindowOrigin.x,
        y = pointerWindowPosition.y - appWindowOrigin.y,
    )
    val width = tooltipSize.width.takeIf { it > 0 } ?: 120
    val height = tooltipSize.height.takeIf { it > 0 } ?: 28
    val rightX = pointerPosition.x + 14
    val leftX = pointerPosition.x - width - 14
    val maxX = (appSize.width - width - 4).coerceAtLeast(4)
    val x = if (rightX <= maxX) rightX else leftX.coerceAtLeast(4)
    val belowY = pointerPosition.y + 18
    val aboveY = pointerPosition.y - height - 12
    val y = if (belowY + height <= appSize.height) belowY else aboveY.coerceAtLeast(4)
    return IntOffset(x, y)
}

private fun IntRect.centerBottom(): IntOffset {
    return IntOffset(
        x = left + width / 2,
        y = bottom,
    )
}

private fun TabDropZone.dropIndex(position: IntOffset): Int {
    tabIds.forEachIndexed { index, tabId ->
        val bounds = tabBounds[tabId] ?: return@forEachIndexed
        val centerX = bounds.left + bounds.width / 2
        if (position.x < centerX) {
            return index
        }
    }
    return tabIds.size
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
        BackgroundTaskStatus.CANCELLED -> stringResource(Res.string.label_task_status_cancelled)
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
            .height(16.dp)
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
