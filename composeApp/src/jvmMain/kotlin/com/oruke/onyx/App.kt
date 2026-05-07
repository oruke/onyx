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
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.ui.BatchRenameDialog
import com.oruke.onyx.ui.ConfirmationDialog
import com.oruke.onyx.ui.ArchivePasswordDialog
import com.oruke.onyx.ui.ConflictResolutionDialog
import com.oruke.onyx.ui.CreateDirectoriesDialog
import com.oruke.onyx.ui.ExternalDragHelper
import com.oruke.onyx.ui.FileDragOverlay
import com.oruke.onyx.app.filesystem.ArchiveService

import com.oruke.onyx.ui.OnyxTooltipOverlay
import com.oruke.onyx.ui.PaneSidebar
import com.oruke.onyx.ui.PaneSurface
import com.oruke.onyx.ui.PreviewPane
import com.oruke.onyx.ui.ResizablePaneDivider
import com.oruke.onyx.ui.SettingsDialog
import com.oruke.onyx.ui.StatusBar
import com.oruke.onyx.ui.JobsBar
import com.oruke.onyx.ui.TitleBarContent
import com.oruke.onyx.ui.theme.FileDragState
import com.oruke.onyx.ui.theme.FileDropTarget
import com.oruke.onyx.ui.theme.FileDropZone
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.LocalTooltipController
import com.oruke.onyx.ui.theme.OnyxTheme
import com.oruke.onyx.ui.theme.TabDropTarget
import com.oruke.onyx.ui.theme.TabDropZone
import com.oruke.onyx.ui.theme.TooltipController
import com.oruke.onyx.ui.theme.TooltipRequest
import com.oruke.onyx.ui.theme.area
import com.oruke.onyx.ui.theme.containsPoint
import com.oruke.onyx.ui.theme.dropIndex
import com.oruke.onyx.ui.theme.key
import com.oruke.onyx.ui.theme.paneState
import com.oruke.onyx.ui.theme.rememberOnyxAppearance
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
fun DecoratedWindowScope.WindowApp(rootComponent: RootComponent) {
    // rootComponent 由 main.kt 在 application 级别创建并传入
    val state by rootComponent.state.collectAsState()
    val palette = rememberOnyxPalette()
    val appearance = rememberOnyxAppearance(
        listRowHeightDp = state.settings.listRowHeightDp,
        listFontSizeSp = state.settings.listFontSizeSp,
        zebraStripeEnabled = state.settings.zebraStripeEnabled,
    )
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
        ),
        LocalOnyxPalette provides palette
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
            )
        }

        // AppContent 在 OnyxTheme 内部渲染，uiScale 通过 LocalDensity 生效
        OnyxTheme(
            uiScale = state.settings.uiScale,
            appearance = appearance,
        ) {
            AppContent(
                rootComponent = rootComponent,
                state = state,
                externalTooltipRequest = titleBarTooltipRequest,
            )
        }
    }
}

@Composable
fun App(rootComponent: RootComponent = rememberRootComponent()) {
    // 独立使用时仍可默认创建 rootComponent
    val state by rootComponent.state.collectAsState()
    val appearance = rememberOnyxAppearance(
        listRowHeightDp = state.settings.listRowHeightDp,
        listFontSizeSp = state.settings.listFontSizeSp,
        zebraStripeEnabled = state.settings.zebraStripeEnabled,
    )

    OnyxTheme(
        uiScale = state.settings.uiScale,
        appearance = appearance,
    ) {
        AppContent(
            rootComponent = rootComponent,
            state = state,
        )
    }
}

@Composable
private fun AppContent(
    rootComponent: RootComponent,
    state: RootState,
    externalTooltipRequest: TooltipRequest? = null,
) {
    val tabDropZones = remember { mutableStateMapOf<PaneId, TabDropZone>() }
    var tabDropTarget by remember { mutableStateOf<TabDropTarget?>(null) }
    val fileDropZones = remember { mutableStateMapOf<String, FileDropZone>() }
    var fileDragState by remember { mutableStateOf<FileDragState?>(null) }
    var fileDropTarget by remember { mutableStateOf<FileDropTarget?>(null) }
    var fileDragPosition by remember { mutableStateOf<IntOffset?>(null) }
    // 用于拖拽时的压缩包临时解压（无状态，可复用）
    val archiveService = remember { ArchiveService() }

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
        // 设置待拖放文件列表 — AWT DragGestureRecognizer 会读取并发起系统级拖放
        val sourcePaneState = rootComponent.state.value.paneState(sourcePaneId)
        val selectedIds = sourcePaneState.selectedEntryIds
        val entries = (sourcePaneState.entriesState as? PaneEntriesState.Ready)?.entries.orEmpty()
        val selectedEntries = entries.filter { it.id in selectedIds }
        // 分离本地文件与压缩包内条目
        val localFiles = mutableListOf<java.io.File>()
        val archiveEntries = mutableListOf<Pair<String, String>>()
        var isArchiveSource = false
        for (entry in selectedEntries) {
            val parsed = ArchiveService.parseArchiveLocation(entry.location)
            if (parsed != null) {
                val (archivePath, innerPath) = parsed
                if (innerPath.isNotBlank()) {
                    archiveEntries.add(archivePath to innerPath)
                    isArchiveSource = true
                }
            } else {
                val file = java.io.File(entry.location)
                if (file.exists()) localFiles.add(file)
            }
        }
        // 确定拖拽操作类型
        val effectiveOperation = when {
            // 压缩包条目始终为解压
            isArchiveSource -> FileTransferOperation.EXTRACT
            // Ctrl 键按下 = 复制（来自 DetailsView）
            operation == FileTransferOperation.COPY -> FileTransferOperation.COPY
            // 默认移动（后续 onFileDragPositionChange 会根据目标卷动态更新）
            else -> FileTransferOperation.MOVE
        }
        val isUserForced = operation == FileTransferOperation.COPY // Ctrl 键强制
        fileDragState = FileDragState(
            sourcePaneId = sourcePaneId,
            operation = effectiveOperation,
            userForced = isUserForced,
        )
        // 本地文件立即可用；压缩包条目延迟到 createTransferable 中解压（不阻塞 EDT）
        ExternalDragHelper.pendingDragFiles = localFiles
        ExternalDragHelper.pendingArchiveEntries = archiveEntries
        ExternalDragHelper.archiveServiceRef = archiveService
    }
    val onFileDragPositionChange: (IntOffset) -> Unit = { windowPosition ->
        fileDragPosition = windowPosition
        fileDropTarget = resolveFileDropTarget(windowPosition)
        // 动态更新操作：同卷移动 / 跨卷复制（Directory Opus 行为）
        // 仅在非用户强制且非压缩包源时才自动检测
        val ds = fileDragState
        if (ds != null && !ds.userForced && ds.operation != FileTransferOperation.EXTRACT) {
            val target = fileDropTarget
            if (target != null) {
                val sourceLoc = rootComponent.state.value.paneState(ds.sourcePaneId).location
                val newOp = resolveVolumeOperation(sourceLoc, target.targetDirectoryLocation)
                if (newOp != ds.operation) {
                    fileDragState = ds.copy(operation = newOp)
                }
            }
        }
    }
    val onFileDragEnd: (IntOffset?) -> Unit = { windowPosition ->
        val dragState = fileDragState
        val target = windowPosition?.let(::resolveFileDropTarget) ?: fileDropTarget
        // 如果 AWT 系统拖放已激活，不执行内部传输逻辑
        if (!ExternalDragHelper.isSystemDragActive && dragState != null && target != null) {
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
        ExternalDragHelper.clearPending()
    }



    when (val dialogState = state.dialogState) {
        is RootDialogState.DeleteSelectionConfirmation -> {
            ConfirmationDialog(
                state = dialogState,
                onConfirm = rootComponent::confirmDialog,
                onDismiss = rootComponent::dismissDialog,
            )
        }

        is RootDialogState.ConflictResolution -> {
            ConflictResolutionDialog(
                state = dialogState,
                onResolve = rootComponent::resolveConflict,
                onDismiss = rootComponent::dismissDialog,
            )
        }

        is RootDialogState.CreateDirectories -> {
            CreateDirectoriesDialog(
                state = dialogState,
                onDraftChange = rootComponent::updateCreateDirectoriesDraft,
                onConfirm = rootComponent::confirmDialog,
                onDismiss = rootComponent::dismissDialog,
            )
        }

        is RootDialogState.Settings -> {
            SettingsDialog(
                state = dialogState,
                onDraftChange = rootComponent::updateSettingsDraft,
                onConfirm = rootComponent::confirmDialog,
                onDismiss = rootComponent::dismissDialog,
                initialWidth = state.settings.settingsWindowWidth,
                initialHeight = state.settings.settingsWindowHeight,
                onWindowSizeChanged = { w, h ->
                    rootComponent.updateSettings(
                        state.settings.copy(settingsWindowWidth = w, settingsWindowHeight = h),
                    )
                },
            )
        }

        is RootDialogState.BatchRename -> {
            BatchRenameDialog(
                state = dialogState,
                onConfirm = { renameMap ->
                    rootComponent.executeBatchRename(dialogState.paneId, renameMap)
                },
                onDismiss = rootComponent::dismissDialog,
                initialWidth = state.settings.batchRenameWindowWidth,
                initialHeight = state.settings.batchRenameWindowHeight,
                onWindowSizeChanged = { w, h ->
                    rootComponent.updateSettings(
                        state.settings.copy(batchRenameWindowWidth = w, batchRenameWindowHeight = h),
                    )
                },
            )
        }

        is RootDialogState.ArchivePassword -> {
            ArchivePasswordDialog(
                archiveName = dialogState.archiveName,
                error = dialogState.error,
                onConfirm = rootComponent::submitArchivePassword,
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
                        .background(LocalOnyxPalette.current.appBackground),
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
                                showTree = state.settings.sidebarTreeVisible,
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
                                    onExtractSelection = { rootComponent.extractSelectedInPane(PaneId.PRIMARY) },
                                    onExtractToDirectory = { rootComponent.extractToDirectoryInPane(PaneId.PRIMARY) },
                                    onExtractSmart = { rootComponent.extractSmartInPane(PaneId.PRIMARY) },
                                    onBatchRename = { rootComponent.batchRenameInPane(PaneId.PRIMARY) },
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
                                    openWithApps = emptyList(),
                                    onOpenWith = { entry, app -> rootComponent.openWithApp(entry, app) },
                                    onOpenWithChooser = { entry -> rootComponent.openWithChooser(entry) },
                                    onQueryOpenWithApps = { entry -> rootComponent.listOpenWithApps(entry) },
                                    onOpenSettings = rootComponent::openSettings,
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
                                        onExtractSelection = { rootComponent.extractSelectedInPane(PaneId.PRIMARY) },
                                        onExtractToDirectory = { rootComponent.extractToDirectoryInPane(PaneId.PRIMARY) },
                                        onExtractSmart = { rootComponent.extractSmartInPane(PaneId.PRIMARY) },
                                        onBatchRename = { rootComponent.batchRenameInPane(PaneId.PRIMARY) },
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
                                        openWithApps = emptyList(),
                                        onOpenWith = { entry, app -> rootComponent.openWithApp(entry, app) },
                                        onOpenWithChooser = { entry -> rootComponent.openWithChooser(entry) },
                                        onQueryOpenWithApps = { entry -> rootComponent.listOpenWithApps(entry) },
                                    onOpenSettings = rootComponent::openSettings,
                                    )
                                    ResizablePaneDivider(
                                        orientation = Orientation.Vertical,
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
                                        onExtractSelection = { rootComponent.extractSelectedInPane(PaneId.SECONDARY) },
                                        onExtractToDirectory = { rootComponent.extractToDirectoryInPane(PaneId.SECONDARY) },
                                        onExtractSmart = { rootComponent.extractSmartInPane(PaneId.SECONDARY) },
                                        onBatchRename = { rootComponent.batchRenameInPane(PaneId.SECONDARY) },
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
                                        openWithApps = emptyList(),
                                        onOpenWith = { entry, app -> rootComponent.openWithApp(entry, app) },
                                        onOpenWithChooser = { entry -> rootComponent.openWithChooser(entry) },
                                        onQueryOpenWithApps = { entry -> rootComponent.listOpenWithApps(entry) },
                                    onOpenSettings = rootComponent::openSettings,
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
                                        onExtractSelection = { rootComponent.extractSelectedInPane(PaneId.PRIMARY) },
                                        onExtractToDirectory = { rootComponent.extractToDirectoryInPane(PaneId.PRIMARY) },
                                        onExtractSmart = { rootComponent.extractSmartInPane(PaneId.PRIMARY) },
                                        onBatchRename = { rootComponent.batchRenameInPane(PaneId.PRIMARY) },
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
                                        openWithApps = emptyList(),
                                        onOpenWith = { entry, app -> rootComponent.openWithApp(entry, app) },
                                        onOpenWithChooser = { entry -> rootComponent.openWithChooser(entry) },
                                        onQueryOpenWithApps = { entry -> rootComponent.listOpenWithApps(entry) },
                                    onOpenSettings = rootComponent::openSettings,
                                    )
                                    ResizablePaneDivider(
                                        orientation = Orientation.Horizontal,
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
                                        onExtractSelection = { rootComponent.extractSelectedInPane(PaneId.SECONDARY) },
                                        onExtractToDirectory = { rootComponent.extractToDirectoryInPane(PaneId.SECONDARY) },
                                        onExtractSmart = { rootComponent.extractSmartInPane(PaneId.SECONDARY) },
                                        onBatchRename = { rootComponent.batchRenameInPane(PaneId.SECONDARY) },
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
                                        openWithApps = emptyList(),
                                        onOpenWith = { entry, app -> rootComponent.openWithApp(entry, app) },
                                        onOpenWithChooser = { entry -> rootComponent.openWithChooser(entry) },
                                        onQueryOpenWithApps = { entry -> rootComponent.listOpenWithApps(entry) },
                                    onOpenSettings = rootComponent::openSettings,
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
                                modifier = Modifier.width(300.dp).fillMaxHeight()
                            )
                        }
                    }

                    // ── Jobs Bar ─────────────────────────────────────────────
                    if (state.tasks.isNotEmpty()) {
                        JobsBar(
                            tasks = state.tasks,
                            onPauseTask = rootComponent::pauseTask,
                            onResumeTask = rootComponent::resumeTask,
                            onCancelTask = rootComponent::cancelTask,
                            onDismissTask = rootComponent::dismissTask,
                            onClearAllTasks = rootComponent::clearAllTasks,
                        )
                    }

                    // ── Status bar ──────────────────────────────────────────────
                    if (state.settings.statusBarVisible) {
                        StatusBar(
                            primaryPane = state.primaryPane,
                            secondaryPane = state.secondaryPane,
                            activePane = state.activePane,
                            onSetActiveViewMode = { mode ->
                                when (state.activePane) {
                                    PaneId.PRIMARY -> rootComponent.primaryPane.setViewMode(mode)
                                    PaneId.SECONDARY -> rootComponent.secondaryPane.setViewMode(mode)
                                }
                            },
                            galleryItemSizeDp = when (state.activePane) {
                                PaneId.PRIMARY -> state.primaryPane.galleryItemSizeDp
                                PaneId.SECONDARY -> state.secondaryPane.galleryItemSizeDp
                            },
                            onGalleryItemSizeChange = { size ->
                                when (state.activePane) {
                                    PaneId.PRIMARY -> rootComponent.primaryPane.setGalleryItemSize(size)
                                    PaneId.SECONDARY -> rootComponent.secondaryPane.setGalleryItemSize(size)
                                }
                            },
                        )
                    }
                }

                (tooltipRequest ?: externalTooltipRequest)?.let { request ->
                    OnyxTooltipOverlay(
                        request = request,
                        appSize = appContentSize,
                        appWindowOrigin = appWindowOrigin,
                    )
                }
                val currentFileDragState = fileDragState
                if (currentFileDragState != null && fileDragPosition != null) {
                    FileDragOverlay(
                        operation = currentFileDragState.operation,
                        pointerWindowPosition = fileDragPosition,
                        targetDirectoryLocation = fileDropTarget?.targetDirectoryLocation,
                        appWindowOrigin = appWindowOrigin,
                    )
                }


            }
        }
    }
}

/**
 * 根据源路径和目标路径判断操作类型（Directory Opus 行为）：
 * - 同一卷（FileStore）→ 移动
 * - 不同卷 → 复制
 */
private fun resolveVolumeOperation(sourceLocation: String, targetLocation: String): FileTransferOperation {
    return try {
        val sourcePath = java.nio.file.Paths.get(sourceLocation)
        val targetPath = java.nio.file.Paths.get(targetLocation)
        val sourceStore = java.nio.file.Files.getFileStore(sourcePath)
        val targetStore = java.nio.file.Files.getFileStore(targetPath)
        if (sourceStore == targetStore) FileTransferOperation.MOVE else FileTransferOperation.COPY
    } catch (_: Exception) {
        // 无法判断（archive:// 或无效路径）回退到移动
        FileTransferOperation.MOVE
    }
}
