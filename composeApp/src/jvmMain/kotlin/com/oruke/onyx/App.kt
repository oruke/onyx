package com.oruke.onyx

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.component.FileTransferOperation
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.component.RootState
import com.oruke.onyx.app.component.rememberRootComponent
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.ui.ConfirmationDialog
import com.oruke.onyx.ui.ConflictResolutionDialog
import com.oruke.onyx.ui.CreateDirectoriesDialog
import com.oruke.onyx.ui.FileDragOverlay
import com.oruke.onyx.ui.OnyxTooltipOverlay
import com.oruke.onyx.ui.PaneSidebar
import com.oruke.onyx.ui.PaneSurface
import com.oruke.onyx.ui.PreviewPane
import com.oruke.onyx.ui.ResizablePaneDivider
import com.oruke.onyx.ui.SettingsDialog
import com.oruke.onyx.ui.StatusBar
import com.oruke.onyx.ui.TaskCenterWindow
import com.oruke.onyx.ui.TitleBarContent
import com.oruke.onyx.ui.theme.FileDragState
import com.oruke.onyx.ui.theme.FileDropTarget
import com.oruke.onyx.ui.theme.FileDropZone
import com.oruke.onyx.ui.theme.LocalTooltipController
import com.oruke.onyx.ui.theme.OnyxPalette
import com.oruke.onyx.ui.theme.TabDropTarget
import com.oruke.onyx.ui.theme.TabDropZone
import com.oruke.onyx.ui.theme.TooltipController
import com.oruke.onyx.ui.theme.TooltipRequest
import com.oruke.onyx.ui.theme.area
import com.oruke.onyx.ui.theme.containsPoint
import com.oruke.onyx.ui.theme.dropIndex
import com.oruke.onyx.ui.theme.key
import com.oruke.onyx.ui.theme.paneState
import com.oruke.onyx.ui.theme.rememberOnyxPalette
import com.oruke.onyx.ui.theme.toIntOffset
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.window.DecoratedWindowScope
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.newFullscreenControls

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
                sidebarVisible = state.settings.sidebarVisible,
                onUiScaleChange = onUiScaleChange,
                onToggleSidebar = {
                    rootComponent.updateSettings(
                        state.settings.copy(sidebarVisible = !state.settings.sidebarVisible),
                    )
                },
                showPreviewPane = state.showPreviewPane,
                onTogglePreviewPane = rootComponent::togglePreviewPane,
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
    val toggleFavoriteLocation: (String) -> Unit = rootComponent::toggleFavoriteLocation
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

    when (val dialogState = state.dialogState) {
        is RootDialogState.DeleteSelectionConfirmation -> {
            ConfirmationDialog(
                state = dialogState,
                palette = palette,
                onConfirm = rootComponent::confirmDialog,
                onDismiss = rootComponent::dismissDialog,
            )
        }

        is RootDialogState.ConflictResolution -> {
            ConflictResolutionDialog(
                state = dialogState,
                palette = palette,
                onResolve = rootComponent::resolveConflict,
                onDismiss = rootComponent::dismissDialog,
            )
        }

        is RootDialogState.CreateDirectories -> {
            CreateDirectoriesDialog(
                state = dialogState,
                palette = palette,
                onDraftChange = rootComponent::updateCreateDirectoriesDraft,
                onConfirm = rootComponent::confirmDialog,
                onDismiss = rootComponent::dismissDialog,
            )
        }

        is RootDialogState.Settings -> {
            SettingsDialog(
                state = dialogState,
                palette = palette,
                onDraftChange = rootComponent::updateSettingsDraft,
                onConfirm = rootComponent::confirmDialog,
                onDismiss = rootComponent::dismissDialog,
            )
        }

        null -> Unit
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
                    val activePaneState = state.paneState(state.activePane)
                    // ── Content area ────────────────────────────────────────────
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        if (state.settings.sidebarVisible) {
                            PaneSidebar(
                                location = activePaneState.location,
                                favoriteLocations = state.settings.favoriteLocations,
                                recentLocations = state.settings.recentLocations,
                                treeState = state.sidebarTreeState,
                                palette = palette,
                                onActivate = {
                                    when (state.activePane) {
                                        PaneId.PRIMARY -> rootComponent.activatePane(PaneId.PRIMARY)
                                        PaneId.SECONDARY -> rootComponent.activatePane(PaneId.SECONDARY)
                                    }
                                },
                                onOpenLocation = rootComponent::openLocationInActivePane,
                                onToggleFavoriteLocation = toggleFavoriteLocation,
                                onToggleTreeNode = rootComponent::toggleSidebarTreeNode,
                                onRetryTreeNode = rootComponent::retrySidebarTreeNode,
                            )
                            Divider(Orientation.Vertical, modifier = Modifier.fillMaxHeight().width(1.dp))
                        }

                        when (state.layoutMode) {
                            PaneLayoutMode.SINGLE -> {
                                PaneSurface(
                                    state = state.primaryPane,
                                    active = state.activePane == PaneId.PRIMARY,
                                    component = rootComponent.primaryPane,
                                    modifier = Modifier.weight(1f),
                                    onActivate = { rootComponent.activatePane(PaneId.PRIMARY) },
                                    canPaste = state.canPaste,
                                    favoriteLocations = state.settings.favoriteLocations,
                                    onToggleFavoriteLocation = toggleFavoriteLocation,
                                    filterQuery = state.primaryPane.filterQuery,
                                    onFilterQueryChange = rootComponent.primaryPane::setFilterQuery,
                                    onDeleteSelection = { rootComponent.requestDeleteSelectedInPane(PaneId.PRIMARY) },
                                    onCopySelection = { rootComponent.stageCopySelectedInPane(PaneId.PRIMARY) },
                                    onCutSelection = { rootComponent.stageCutSelectedInPane(PaneId.PRIMARY) },
                                    onPaste = { rootComponent.requestPasteIntoPane(PaneId.PRIMARY) },
                                    onOpenSelected = { rootComponent.primaryPane.openSelectedEntry() },
                                    onOpenSelectedInNewTab = { rootComponent.primaryPane.openSelectedInNewTab() },
                                    onBeginRename = { rootComponent.primaryPane.beginRename() },
                                    onBeginCreateFile = { rootComponent.primaryPane.beginCreateFile() },
                                    onBeginCreateDirectory = { rootComponent.beginCreateDirectoriesInPane(PaneId.PRIMARY) },
                                    onCopySelectedPaths = { rootComponent.primaryPane.copySelectedPaths() },
                                    inlineEditState = state.primaryPane.inlineEditState,
                                    onUpdateInlineEditDraft = rootComponent.primaryPane::updateInlineEditDraft,
                                    onConfirmInlineEdit = { rootComponent.primaryPane.confirmInlineEdit() },
                                    onCancelInlineEdit = { rootComponent.primaryPane.cancelInlineEdit() },
                                    onDismissOperationFeedback = { rootComponent.primaryPane.dismissOperationFeedback() },
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
                                        .fillMaxHeight()
                                        .onSizeChanged { contentSize = it },
                                ) {
                                    PaneSurface(
                                        state = state.primaryPane,
                                        active = state.activePane == PaneId.PRIMARY,
                                        component = rootComponent.primaryPane,
                                        modifier = Modifier.weight(state.paneSplitFraction),
                                        onActivate = { rootComponent.activatePane(PaneId.PRIMARY) },
                                        canPaste = state.canPaste,
                                        favoriteLocations = state.settings.favoriteLocations,
                                        onToggleFavoriteLocation = toggleFavoriteLocation,
                                        filterQuery = state.primaryPane.filterQuery,
                                        onFilterQueryChange = rootComponent.primaryPane::setFilterQuery,
                                        onDeleteSelection = { rootComponent.requestDeleteSelectedInPane(PaneId.PRIMARY) },
                                        onCopySelection = { rootComponent.stageCopySelectedInPane(PaneId.PRIMARY) },
                                        onCutSelection = { rootComponent.stageCutSelectedInPane(PaneId.PRIMARY) },
                                        onPaste = { rootComponent.requestPasteIntoPane(PaneId.PRIMARY) },
                                        onOpenSelected = { rootComponent.primaryPane.openSelectedEntry() },
                                        onOpenSelectedInNewTab = { rootComponent.primaryPane.openSelectedInNewTab() },
                                        onBeginRename = { rootComponent.primaryPane.beginRename() },
                                        onBeginCreateFile = { rootComponent.primaryPane.beginCreateFile() },
                                        onBeginCreateDirectory = { rootComponent.beginCreateDirectoriesInPane(PaneId.PRIMARY) },
                                        onCopySelectedPaths = { rootComponent.primaryPane.copySelectedPaths() },
                                        inlineEditState = state.primaryPane.inlineEditState,
                                        onUpdateInlineEditDraft = rootComponent.primaryPane::updateInlineEditDraft,
                                        onConfirmInlineEdit = { rootComponent.primaryPane.confirmInlineEdit() },
                                        onCancelInlineEdit = { rootComponent.primaryPane.cancelInlineEdit() },
                                        onDismissOperationFeedback = { rootComponent.primaryPane.dismissOperationFeedback() },
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
                                        favoriteLocations = state.settings.favoriteLocations,
                                        onToggleFavoriteLocation = toggleFavoriteLocation,
                                        filterQuery = state.secondaryPane.filterQuery,
                                        onFilterQueryChange = rootComponent.secondaryPane::setFilterQuery,
                                        onDeleteSelection = { rootComponent.requestDeleteSelectedInPane(PaneId.SECONDARY) },
                                        onCopySelection = { rootComponent.stageCopySelectedInPane(PaneId.SECONDARY) },
                                        onCutSelection = { rootComponent.stageCutSelectedInPane(PaneId.SECONDARY) },
                                        onPaste = { rootComponent.requestPasteIntoPane(PaneId.SECONDARY) },
                                        onOpenSelected = { rootComponent.secondaryPane.openSelectedEntry() },
                                        onOpenSelectedInNewTab = { rootComponent.secondaryPane.openSelectedInNewTab() },
                                        onBeginRename = { rootComponent.secondaryPane.beginRename() },
                                        onBeginCreateFile = { rootComponent.secondaryPane.beginCreateFile() },
                                        onBeginCreateDirectory = { rootComponent.beginCreateDirectoriesInPane(PaneId.SECONDARY) },
                                        onCopySelectedPaths = { rootComponent.secondaryPane.copySelectedPaths() },
                                        inlineEditState = state.secondaryPane.inlineEditState,
                                        onUpdateInlineEditDraft = rootComponent.secondaryPane::updateInlineEditDraft,
                                        onConfirmInlineEdit = { rootComponent.secondaryPane.confirmInlineEdit() },
                                        onCancelInlineEdit = { rootComponent.secondaryPane.cancelInlineEdit() },
                                        onDismissOperationFeedback = { rootComponent.secondaryPane.dismissOperationFeedback() },
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
                                        .fillMaxHeight()
                                        .onSizeChanged { contentSize = it },
                                ) {
                                    PaneSurface(
                                        state = state.primaryPane,
                                        active = state.activePane == PaneId.PRIMARY,
                                        component = rootComponent.primaryPane,
                                        modifier = Modifier.weight(state.paneSplitFraction),
                                        onActivate = { rootComponent.activatePane(PaneId.PRIMARY) },
                                        canPaste = state.canPaste,
                                        favoriteLocations = state.settings.favoriteLocations,
                                        onToggleFavoriteLocation = toggleFavoriteLocation,
                                        filterQuery = state.primaryPane.filterQuery,
                                        onFilterQueryChange = rootComponent.primaryPane::setFilterQuery,
                                        onDeleteSelection = { rootComponent.requestDeleteSelectedInPane(PaneId.PRIMARY) },
                                        onCopySelection = { rootComponent.stageCopySelectedInPane(PaneId.PRIMARY) },
                                        onCutSelection = { rootComponent.stageCutSelectedInPane(PaneId.PRIMARY) },
                                        onPaste = { rootComponent.requestPasteIntoPane(PaneId.PRIMARY) },
                                        onOpenSelected = { rootComponent.primaryPane.openSelectedEntry() },
                                        onOpenSelectedInNewTab = { rootComponent.primaryPane.openSelectedInNewTab() },
                                        onBeginRename = { rootComponent.primaryPane.beginRename() },
                                        onBeginCreateFile = { rootComponent.primaryPane.beginCreateFile() },
                                        onBeginCreateDirectory = { rootComponent.beginCreateDirectoriesInPane(PaneId.PRIMARY) },
                                        onCopySelectedPaths = { rootComponent.primaryPane.copySelectedPaths() },
                                        inlineEditState = state.primaryPane.inlineEditState,
                                        onUpdateInlineEditDraft = rootComponent.primaryPane::updateInlineEditDraft,
                                        onConfirmInlineEdit = { rootComponent.primaryPane.confirmInlineEdit() },
                                        onCancelInlineEdit = { rootComponent.primaryPane.cancelInlineEdit() },
                                        onDismissOperationFeedback = { rootComponent.primaryPane.dismissOperationFeedback() },
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
                                        favoriteLocations = state.settings.favoriteLocations,
                                        onToggleFavoriteLocation = toggleFavoriteLocation,
                                        filterQuery = state.secondaryPane.filterQuery,
                                        onFilterQueryChange = rootComponent.secondaryPane::setFilterQuery,
                                        onDeleteSelection = { rootComponent.requestDeleteSelectedInPane(PaneId.SECONDARY) },
                                        onCopySelection = { rootComponent.stageCopySelectedInPane(PaneId.SECONDARY) },
                                        onCutSelection = { rootComponent.stageCutSelectedInPane(PaneId.SECONDARY) },
                                        onPaste = { rootComponent.requestPasteIntoPane(PaneId.SECONDARY) },
                                        onOpenSelected = { rootComponent.secondaryPane.openSelectedEntry() },
                                        onOpenSelectedInNewTab = { rootComponent.secondaryPane.openSelectedInNewTab() },
                                        onBeginRename = { rootComponent.secondaryPane.beginRename() },
                                        onBeginCreateFile = { rootComponent.secondaryPane.beginCreateFile() },
                                        onBeginCreateDirectory = { rootComponent.beginCreateDirectoriesInPane(PaneId.SECONDARY) },
                                        onCopySelectedPaths = { rootComponent.secondaryPane.copySelectedPaths() },
                                        inlineEditState = state.secondaryPane.inlineEditState,
                                        onUpdateInlineEditDraft = rootComponent.secondaryPane::updateInlineEditDraft,
                                        onConfirmInlineEdit = { rootComponent.secondaryPane.confirmInlineEdit() },
                                        onCancelInlineEdit = { rootComponent.secondaryPane.cancelInlineEdit() },
                                        onDismissOperationFeedback = { rootComponent.secondaryPane.dismissOperationFeedback() },
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

                        if (state.showPreviewPane) {
                            Divider(Orientation.Vertical, modifier = Modifier.fillMaxHeight().width(1.dp))
                            val activePaneState = state.paneState(state.activePane)
                            val selectedEntryId = activePaneState.selectedEntryIds.firstOrNull()
                            val selectedEntry = if (selectedEntryId != null) {
                                (activePaneState.entriesState as? PaneEntriesState.Ready)?.entries?.find { it.id == selectedEntryId }
                            } else null
                            
                            PreviewPane(
                                selectedEntry = selectedEntry,
                                palette = palette,
                                modifier = Modifier.width(300.dp).fillMaxHeight()
                            )
                        }
                    }

                    // ── Status bar ──────────────────────────────────────────────
                    if (state.settings.statusBarVisible) {
                        StatusBar(
                            primaryPane = state.primaryPane,
                            secondaryPane = state.secondaryPane,
                            activePane = state.activePane,
                            activeTaskCount = state.tasks.count { task ->
                                task.status == BackgroundTaskStatus.QUEUED || task.status == BackgroundTaskStatus.RUNNING
                            },
                            onSetActiveViewMode = { mode ->
                                when (state.activePane) {
                                    PaneId.PRIMARY -> rootComponent.primaryPane.setViewMode(mode)
                                    PaneId.SECONDARY -> rootComponent.secondaryPane.setViewMode(mode)
                                }
                            },
                            palette = palette,
                        )
                    }
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

