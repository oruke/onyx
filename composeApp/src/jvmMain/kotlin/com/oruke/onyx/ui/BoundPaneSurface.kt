package com.oruke.onyx.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import com.oruke.onyx.app.component.FileTransferOperation
import com.oruke.onyx.app.component.PaneComponent
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.RootState
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.app.filesystem.OpenWithApp
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
    onToggleFavoriteLocation: (String) -> Unit,
) {
    val paneState: PaneState = when (paneId) {
        PaneId.PRIMARY -> state.primaryPane
        PaneId.SECONDARY -> state.secondaryPane
    }
    val paneComponent: PaneComponent = when (paneId) {
        PaneId.PRIMARY -> rootComponent.primaryPane
        PaneId.SECONDARY -> rootComponent.secondaryPane
    }

    PaneSurface(
        state = paneState,
        active = state.activePane == paneId,
        component = paneComponent,
        modifier = modifier,
        onActivate = { rootComponent.activatePane(paneId) },
        canPaste = state.canPaste,
        favoriteLocations = state.settings.favoriteLocations,
        onToggleFavoriteLocation = onToggleFavoriteLocation,
        filterQuery = paneState.filterQuery,
        onFilterQueryChange = paneComponent::setFilterQuery,
        onDeleteSelection = { rootComponent.requestDeleteSelectedInPane(paneId) },
        onExtractSelection = { rootComponent.extractSelectedInPane(paneId) },
        onExtractToDirectory = { rootComponent.extractToDirectoryInPane(paneId) },
        onExtractSmart = { rootComponent.extractSmartInPane(paneId) },
        onBatchRename = { rootComponent.batchRenameInPane(paneId) },
        onCopySelection = { rootComponent.stageCopySelectedInPane(paneId) },
        onCutSelection = { rootComponent.stageCutSelectedInPane(paneId) },
        onPaste = { rootComponent.requestPasteIntoPane(paneId) },
        onOpenSelected = { paneComponent.openSelectedEntry() },
        onOpenSelectedInNewTab = { paneComponent.openSelectedInNewTab() },
        onBeginRename = { paneComponent.beginRename() },
        onBeginCreateFile = { paneComponent.beginCreateFile() },
        onBeginCreateDirectory = { rootComponent.beginCreateDirectoriesInPane(paneId) },
        onCopySelectedPaths = { paneComponent.copySelectedPaths() },
        inlineEditState = paneState.inlineEditState,
        onUpdateInlineEditDraft = paneComponent::updateInlineEditDraft,
        onConfirmInlineEdit = { paneComponent.confirmInlineEdit() },
        onCancelInlineEdit = { paneComponent.cancelInlineEdit() },
        onDismissOperationFeedback = { paneComponent.dismissOperationFeedback() },
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
        openWithApps = emptyList(),
        onOpenWith = { entry, app -> rootComponent.openWithApp(entry, app) },
        onOpenWithChooser = { entry -> rootComponent.openWithChooser(entry) },
        onQueryOpenWithApps = { entry -> rootComponent.listOpenWithApps(entry) },
        onOpenSettings = rootComponent::openSettings,
    )
}
