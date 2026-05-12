package com.oruke.onyx.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.app.component.PaneComponent
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.RootIntent
import com.oruke.onyx.app.component.RootState
import com.oruke.onyx.app.filesystem.FileContextMenuRequest
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.ui.theme.FileDropTarget
import com.oruke.onyx.ui.theme.FileDropZone
import com.oruke.onyx.ui.theme.TabDropTarget
import com.oruke.onyx.ui.theme.TabDropZone
import com.oruke.onyx.ui.theme.key

/**
 * PaneSurface 的绑定调用，将 RootComponent + PaneId 的重复参数封装为单次调用。
 *
 * 之前每个布局模式都复制粘贴 40+ 行 PaneSurface 参数，唯一差异仅在 PaneId。
 * 此函数统一绑定所有回调，消除冗余。
 */
@Composable
internal fun BoundPaneSurface(
    paneId: PaneId,
    state: RootState,
    rootComponent: RootComponent,
    modifier: Modifier = Modifier,
    // 拖放相关
    tabDropZones: SnapshotStateMap<PaneId, TabDropZone>,
    tabDropTarget: TabDropTarget?,
    fileDropTarget: FileDropTarget?,
    fileDropZones: SnapshotStateMap<String, FileDropZone>,
    onTabDrop: (PaneId, String, IntOffset) -> Unit,
    onTabDragPositionChange: (IntOffset) -> Unit,
    onTabDragEnd: () -> Unit,
    onFileDragStart: (PaneId, FileTransferOperation) -> Unit,
    onFileDragPositionChange: (IntOffset) -> Unit,
    onFileDragEnd: (IntOffset?) -> Unit,
) {
    val paneState: PaneState = when (paneId) {
        PaneId.PRIMARY -> state.primaryPane
        PaneId.SECONDARY -> state.secondaryPane
    }
    val paneComponent: PaneComponent = when (paneId) {
        PaneId.PRIMARY -> rootComponent.primaryPane
        PaneId.SECONDARY -> rootComponent.secondaryPane
    }
    fun dispatch(intent: RootIntent) {
        rootComponent.dispatch(intent)
    }

    val actions = PaneActions(
        onDeleteSelection = { dispatch(RootIntent.RequestDeleteSelectedInPane(paneId)) },
        onExtractSelection = { dispatch(RootIntent.ExtractSelectedInPane(paneId)) },
        onExtractToDirectory = { dispatch(RootIntent.ExtractToDirectoryInPane(paneId)) },
        onExtractSmart = { dispatch(RootIntent.ExtractSmartInPane(paneId)) },
        onBatchRename = { dispatch(RootIntent.BatchRenameInPane(paneId)) },
        onCopySelection = { dispatch(RootIntent.StageCopySelectedInPane(paneId)) },
        onCutSelection = { dispatch(RootIntent.StageCutSelectedInPane(paneId)) },
        onPaste = { dispatch(RootIntent.RequestPasteIntoPane(paneId)) },
        canUndo = state.operationHistoryState.canUndo,
        canRedo = state.operationHistoryState.canRedo,
        onUndo = { dispatch(RootIntent.UndoLastFileOperation) },
        onRedo = { dispatch(RootIntent.RedoLastFileOperation) },
        onBeginCreateDirectory = { dispatch(RootIntent.BeginCreateDirectoriesInPane(paneId)) },
        onToggleFavoriteLocation = { location -> dispatch(RootIntent.ToggleFavoriteLocation(location)) },
        onOpenSettings = { dispatch(RootIntent.OpenSettings) },
        supportsContextMenuOpenWith = rootComponent::supportsContextMenuOpenWith,
        onFileContextMenuCommand = { command, entries ->
            dispatch(RootIntent.ExecuteFileContextMenuCommand(command, entries))
        },
        onOpenTerminal = { location -> dispatch(RootIntent.OpenTerminalAt(location)) },
        isArchiveFileName = rootComponent::isArchiveFileName,
        isImageFileName = rootComponent::isImageFileName,
        onQueryContextMenuSections = { request: FileContextMenuRequest ->
            rootComponent.listContextMenuSections(request)
        },
    )
    val commandShortcuts = remember(state.settings.commandShortcutOverrides) {
        commandShortcutMapFromSettings(state.settings.commandShortcutOverrides)
    }

    PaneSurface(
        state = paneState,
        active = state.activePane == paneId,
        component = paneComponent,
        actions = actions,
        commandShortcuts = commandShortcuts,
        modifier = modifier,
        onActivate = { dispatch(RootIntent.ActivatePane(paneId)) },
        canPaste = state.canPaste,
        favoriteLocations = state.settings.favoriteLocations,
        onDropTab = onTabDrop,
        onTabDragPositionChange = onTabDragPositionChange,
        onTabDragEnd = onTabDragEnd,
        onTabDropZoneChange = { id, zone -> tabDropZones[id] = zone },
        tabDropIndicatorIndex = tabDropTarget?.takeIf { it.paneId == paneId }?.index,
        onFileDragStart = onFileDragStart,
        onFileDragPositionChange = onFileDragPositionChange,
        onFileDragEnd = onFileDragEnd,
        onFileDropZoneChange = { zone -> fileDropZones[zone.key] = zone },
        fileDropTarget = fileDropTarget,
        loadThumbnail = rootComponent::loadThumbnail,
        loadArchiveThumbnail = rootComponent::loadArchiveThumbnail,
        readFileHash = rootComponent::readFileHash,
        readArchiveInfo = rootComponent::readArchiveInfo,
        buildBreadcrumbs = rootComponent::buildBreadcrumbs,
    )
}
