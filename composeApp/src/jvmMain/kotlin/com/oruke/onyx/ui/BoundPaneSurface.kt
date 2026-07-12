package com.oruke.onyx.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.oruke.onyx.app.component.PaneComponent
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.RootIntent
import com.oruke.onyx.app.component.RootState
import com.oruke.onyx.vfs.api.FileContextMenuRequest
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.ui.theme.key

/**
 * PaneSurface 的绑定调用，将 RootComponent + PaneId 的重复参数封装为单次调用。
 *
 * 之前每个布局模式都复制粘贴 40+ 行 PaneSurface 参数，唯一差异仅在 PaneId。
 * 此函数统一绑定所有回调，消除冗余。
 *
 * @param paneId 待渲染的面板标识。
 * @param state 根组件当前状态。
 * @param rootComponent 根组件业务接口。
 * @param modifier 面板布局修饰符。
 * @param dragBindings 标签与文件拖放绑定。
 */
@Composable
internal fun BoundPaneSurface(
    paneId: PaneId,
    state: RootState,
    rootComponent: RootComponent,
    modifier: Modifier = Modifier,
    dragBindings: PaneDragBindings,
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
        model = PaneSurfaceModel(
            state = paneState,
            active = state.activePane == paneId,
            component = paneComponent,
            actions = actions,
            commandShortcuts = commandShortcuts,
            canPaste = state.canPaste,
            favoriteLocations = state.settings.favoriteLocations,
            onActivate = { dispatch(RootIntent.ActivatePane(paneId)) },
        ),
        dragBindings = dragBindings,
        services = PaneSurfaceServices(
            loadThumbnail = rootComponent::loadThumbnail,
            loadArchiveThumbnail = rootComponent::loadArchiveThumbnail,
            readFileHash = rootComponent::readFileHash,
            readArchiveInfo = rootComponent::readArchiveInfo,
            buildBreadcrumbs = rootComponent::buildBreadcrumbs,
        ),
        modifier = modifier,
    )
}
