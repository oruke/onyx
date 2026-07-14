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
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.component.RootIntent
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
import com.oruke.onyx.app.platform.ExternalFileDragService
import com.oruke.onyx.ui.FileDragOverlay
import com.oruke.onyx.ui.BoundPaneSurface
import com.oruke.onyx.ui.PaneDragBindings
import com.oruke.onyx.ui.OnyxTooltipOverlay
import com.oruke.onyx.ui.PaneSidebar
import com.oruke.onyx.ui.PaneSidebarActions
import com.oruke.onyx.ui.PaneSidebarState
import com.oruke.onyx.ui.PreviewPane
import com.oruke.onyx.ui.ResizablePaneDivider
import com.oruke.onyx.ui.RemoteConnectionsDialog
import com.oruke.onyx.ui.RemoteCredentialsDialog
import com.oruke.onyx.ui.SearchPanel
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
internal fun DecoratedWindowScope.WindowApp(
    rootComponent: RootComponent,
    externalFileDragService: ExternalFileDragService,
) {
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
        rootComponent.dispatch(
            RootIntent.UpdateSettings(state.settings.copy(uiScale = value)),
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
                onUiScaleChange = onUiScaleChange,
                onToggleSidebar = {
                    rootComponent.dispatch(
                        RootIntent.UpdateSettings(
                            state.settings.copy(sidebarVisible = !state.settings.sidebarVisible)
                        ),
                    )
                },
                showPreviewPane = state.showPreviewPane,
                onTogglePreviewPane = { rootComponent.dispatch(RootIntent.TogglePreviewPane) },
                searchPanelVisible = state.searchState.visible,
                onToggleSearchPanel = {
                    rootComponent.dispatch(
                        if (state.searchState.visible) {
                            RootIntent.CloseSearchPanel
                        } else {
                            RootIntent.ShowSearchPanel
                        },
                    )
                },
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
                externalFileDragService = externalFileDragService,
                externalTooltipRequest = titleBarTooltipRequest,
            )
        }
    }
}

@Composable
internal fun App(rootComponent: RootComponent = rememberRootComponent()) {
    // 独立使用时仍可默认创建 rootComponent
    val koin = org.koin.compose.getKoin()
    val externalFileDragService = remember { koin.get<ExternalFileDragService>() }
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
            externalFileDragService = externalFileDragService,
        )
    }
}

@Composable
private fun AppContent(
    rootComponent: RootComponent,
    state: RootState,
    externalFileDragService: ExternalFileDragService,
    externalTooltipRequest: TooltipRequest? = null,
) {
    val dragController = remember(rootComponent, externalFileDragService) {
        AppDragController(rootComponent, externalFileDragService)
    }
    var tooltipRequest by remember { mutableStateOf<TooltipRequest?>(null) }
    var appContentSize by remember { mutableStateOf(IntSize.Zero) }
    var appWindowOrigin by remember { mutableStateOf(IntOffset.Zero) }
    fun dispatch(intent: RootIntent) {
        rootComponent.dispatch(intent)
    }
    val toggleFavoriteLocation: (String) -> Unit = { location ->
        dispatch(RootIntent.ToggleFavoriteLocation(location))
    }
    val dragBindings = dragController.bindings
    AppDialogHost(state, rootComponent::dispatch)

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
                AppWorkspace(state, rootComponent, dragBindings, toggleFavoriteLocation)

                (tooltipRequest ?: externalTooltipRequest)?.let { request ->
                    OnyxTooltipOverlay(
                        request = request,
                        appSize = appContentSize,
                        appWindowOrigin = appWindowOrigin,
                    )
                }
                val currentFileDragState = dragController.fileDragState
                if (currentFileDragState != null && dragController.fileDragPosition != null) {
                    FileDragOverlay(
                        operation = currentFileDragState.operation,
                        pointerWindowPosition = dragController.fileDragPosition,
                        targetDirectoryLocation = dragController.fileDropTarget?.targetDirectoryLocation,
                        appWindowOrigin = appWindowOrigin,
                    )
                }


            }
        }
    }
}

/**
 * 显示应用主工作区及其搜索、任务和状态附属区域。
 *
 * @param state 根组件当前状态。
 * @param rootComponent 根组件业务接口。
 * @param dragBindings 面板共享拖放绑定。
 * @param onToggleFavoriteLocation 切换收藏位置的回调。
 */
@Composable
private fun AppWorkspace(
    state: RootState,
    rootComponent: RootComponent,
    dragBindings: PaneDragBindings,
    onToggleFavoriteLocation: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(LocalOnyxPalette.current.appBackground),
    ) {
        AppMainArea(
            state = state,
            rootComponent = rootComponent,
            dragBindings = dragBindings,
            onToggleFavoriteLocation = onToggleFavoriteLocation,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        AppSearchPanel(state, rootComponent)
        AppJobsBar(state, rootComponent::dispatch)
        AppStatusBar(state, rootComponent)
    }
}

/**
 * 显示侧栏、文件面板布局与可选预览栏。
 *
 * @param state 根组件当前状态。
 * @param rootComponent 根组件业务接口。
 * @param dragBindings 面板共享拖放绑定。
 * @param onToggleFavoriteLocation 切换收藏位置的回调。
 * @param modifier 内容区布局修饰符。
 */
@Composable
private fun AppMainArea(
    state: RootState,
    rootComponent: RootComponent,
    dragBindings: PaneDragBindings,
    onToggleFavoriteLocation: (String) -> Unit,
    modifier: Modifier,
) {
    Row(modifier = modifier) {
        AppSidebar(state, rootComponent, onToggleFavoriteLocation)
        AppPaneLayout(
            state = state,
            rootComponent = rootComponent,
            dragBindings = dragBindings,
            modifier = Modifier.weight(1f),
        )
        AppPreviewPanel(state, rootComponent)
    }
}

/**
 * 在设置启用时显示位置侧栏及分隔线。
 *
 * @param state 根组件当前状态。
 * @param rootComponent 根组件业务接口。
 * @param onToggleFavoriteLocation 切换收藏位置的回调。
 */
@Composable
private fun AppSidebar(
    state: RootState,
    rootComponent: RootComponent,
    onToggleFavoriteLocation: (String) -> Unit,
) {
    if (!state.settings.sidebarVisible) return
    val dispatch = rootComponent::dispatch
    PaneSidebar(
        state = PaneSidebarState(
            location = state.paneState(state.activePane).location,
            systemQuickAccessLocations = state.systemQuickAccessLocations,
            favoriteLocations = state.settings.favoriteLocations,
            recentLocations = state.settings.recentLocations,
            remoteConnections = state.settings.remoteConnections,
            treeState = state.sidebarTreeState,
            showTree = state.settings.sidebarTreeVisible,
        ),
        actions = PaneSidebarActions(
            locationLabel = rootComponent::locationLabel,
            onActivate = { dispatch(RootIntent.ActivatePane(state.activePane)) },
            onOpenLocation = { dispatch(RootIntent.OpenLocationInActivePane(it)) },
            onToggleFavoriteLocation = onToggleFavoriteLocation,
            onNewRemoteConnection = { dispatch(RootIntent.NewRemoteConnection) },
            onEditRemoteConnection = { dispatch(RootIntent.EditRemoteConnection(it)) },
            onToggleTreeNode = { dispatch(RootIntent.ToggleSidebarTreeNode(it)) },
            onRetryTreeNode = { dispatch(RootIntent.RetrySidebarTreeNode(it)) },
        ),
    )
    Divider(Orientation.Vertical, modifier = Modifier.fillMaxHeight().width(1.dp))
}

/**
 * 根据当前布局模式显示单面板或双面板结构。
 *
 * @param state 根组件当前状态。
 * @param rootComponent 根组件业务接口。
 * @param dragBindings 面板共享拖放绑定。
 * @param modifier 面板布局区域修饰符。
 */
@Composable
private fun AppPaneLayout(
    state: RootState,
    rootComponent: RootComponent,
    dragBindings: PaneDragBindings,
    modifier: Modifier,
) {
    when (state.layoutMode) {
        PaneLayoutMode.SINGLE -> BoundPaneSurface(
            paneId = PaneId.PRIMARY,
            state = state,
            rootComponent = rootComponent,
            modifier = modifier,
            dragBindings = dragBindings,
        )
        PaneLayoutMode.DUAL_VERTICAL -> VerticalPaneLayout(state, rootComponent, dragBindings, modifier)
        PaneLayoutMode.DUAL_HORIZONTAL -> HorizontalPaneLayout(state, rootComponent, dragBindings, modifier)
    }
}

/**
 * 显示左右排列的双面板及可拖拽分隔线。
 *
 * @param state 根组件当前状态。
 * @param rootComponent 根组件业务接口。
 * @param dragBindings 面板共享拖放绑定。
 * @param modifier 双面板区域修饰符。
 */
@Composable
private fun VerticalPaneLayout(
    state: RootState,
    rootComponent: RootComponent,
    dragBindings: PaneDragBindings,
    modifier: Modifier,
) {
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    Row(modifier = modifier.fillMaxHeight().onSizeChanged { contentSize = it }) {
        BoundPaneSurface(
            PaneId.PRIMARY,
            state,
            rootComponent,
            Modifier.weight(state.paneSplitFraction),
            dragBindings,
        )
        ResizablePaneDivider(Orientation.Vertical) { delta ->
            val width = contentSize.width.toFloat().coerceAtLeast(1f)
            rootComponent.dispatch(
                RootIntent.SetPaneSplitFraction(rootComponent.state.value.paneSplitFraction + delta / width),
            )
        }
        BoundPaneSurface(
            PaneId.SECONDARY,
            state,
            rootComponent,
            Modifier.weight(1f - state.paneSplitFraction),
            dragBindings,
        )
    }
}

/**
 * 显示上下排列的双面板及可拖拽分隔线。
 *
 * @param state 根组件当前状态。
 * @param rootComponent 根组件业务接口。
 * @param dragBindings 面板共享拖放绑定。
 * @param modifier 双面板区域修饰符。
 */
@Composable
private fun HorizontalPaneLayout(
    state: RootState,
    rootComponent: RootComponent,
    dragBindings: PaneDragBindings,
    modifier: Modifier,
) {
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    Column(modifier = modifier.fillMaxHeight().onSizeChanged { contentSize = it }) {
        BoundPaneSurface(
            PaneId.PRIMARY,
            state,
            rootComponent,
            Modifier.weight(state.paneSplitFraction),
            dragBindings,
        )
        ResizablePaneDivider(Orientation.Horizontal) { delta ->
            val height = contentSize.height.toFloat().coerceAtLeast(1f)
            rootComponent.dispatch(
                RootIntent.SetPaneSplitFraction(rootComponent.state.value.paneSplitFraction + delta / height),
            )
        }
        BoundPaneSurface(
            PaneId.SECONDARY,
            state,
            rootComponent,
            Modifier.weight(1f - state.paneSplitFraction),
            dragBindings,
        )
    }
}

/**
 * 在启用时显示活动面板选中项的预览。
 *
 * @param state 根组件当前状态。
 * @param rootComponent 根组件业务接口。
 */
@Composable
private fun AppPreviewPanel(state: RootState, rootComponent: RootComponent) {
    if (!state.showPreviewPane) return
    val paneState = state.paneState(state.activePane)
    val selectedId = paneState.selectedEntryIds.firstOrNull()
    val selectedEntry = (paneState.entriesState as? PaneEntriesState.Ready)
        ?.entries
        ?.find { it.id == selectedId }
    Divider(Orientation.Vertical, modifier = Modifier.fillMaxHeight().width(1.dp))
    PreviewPane(
        selectedEntry = selectedEntry,
        modifier = Modifier.width(300.dp).fillMaxHeight(),
        loadThumbnail = rootComponent::loadThumbnail,
        loadTextPreview = rootComponent::loadTextPreview,
        isImageFileName = rootComponent::isImageFileName,
        isTextPreviewFileName = rootComponent::isTextPreviewFileName,
    )
}

/**
 * 在搜索面板可见时显示搜索状态与动作。
 *
 * @param state 根组件当前状态。
 * @param rootComponent 根组件业务接口。
 */
@Composable
private fun AppSearchPanel(state: RootState, rootComponent: RootComponent) {
    if (!state.searchState.visible) return
    val dispatch = rootComponent::dispatch
    SearchPanel(
        state = state.searchState,
        locationLabel = rootComponent::locationLabel,
        onQueryChange = { dispatch(RootIntent.UpdateSearchQuery(it)) },
        onSearch = { dispatch(RootIntent.ExecuteSearch) },
        onCancel = { dispatch(RootIntent.CancelSearch) },
        onClose = { dispatch(RootIntent.CloseSearchPanel) },
        onOpenResult = { dispatch(RootIntent.OpenSearchResult(it)) },
    )
}

/**
 * 在存在后台任务时显示任务栏。
 *
 * @param state 根组件当前状态。
 * @param dispatch 根意图分发器。
 */
@Composable
private fun AppJobsBar(state: RootState, dispatch: (RootIntent) -> Unit) {
    if (state.tasks.isEmpty()) return
    JobsBar(
        tasks = state.tasks,
        onPauseTask = { dispatch(RootIntent.PauseTask(it)) },
        onResumeTask = { dispatch(RootIntent.ResumeTask(it)) },
        onRetryTask = { dispatch(RootIntent.RetryTask(it)) },
        onCancelTask = { dispatch(RootIntent.CancelTask(it)) },
        onDismissTask = { dispatch(RootIntent.DismissTask(it)) },
        onClearAllTasks = { dispatch(RootIntent.ClearAllTasks) },
    )
}

/**
 * 在设置启用时显示活动面板的视图状态栏。
 *
 * @param state 根组件当前状态。
 * @param rootComponent 根组件业务接口。
 */
@Composable
private fun AppStatusBar(state: RootState, rootComponent: RootComponent) {
    if (!state.settings.statusBarVisible) return
    val paneComponent = when (state.activePane) {
        PaneId.PRIMARY -> rootComponent.primaryPane
        PaneId.SECONDARY -> rootComponent.secondaryPane
    }
    val paneState = state.paneState(state.activePane)
    StatusBar(
        primaryPane = state.primaryPane,
        secondaryPane = state.secondaryPane,
        activePane = state.activePane,
        onSetActiveViewMode = { paneComponent.dispatch(PaneIntent.SetViewMode(it)) },
        galleryItemSizeDp = paneState.galleryItemSizeDp,
        onGalleryItemSizeChange = { paneComponent.dispatch(PaneIntent.SetGalleryItemSize(it)) },
    )
}

/**
 * 根据根状态显示当前活动的模态窗口。
 *
 * @param state 根组件当前状态。
 * @param dispatch 根意图分发器。
 */
@Composable
private fun AppDialogHost(state: RootState, dispatch: (RootIntent) -> Unit) {
    when (val dialogState = state.dialogState) {
        is RootDialogState.DeleteSelectionConfirmation -> ConfirmationDialog(
            state = dialogState,
            onConfirm = { dispatch(RootIntent.ConfirmDialog) },
            onDismiss = { dispatch(RootIntent.DismissDialog) },
        )
        is RootDialogState.ConflictResolution -> ConflictResolutionDialog(
            state = dialogState,
            onResolve = { strategy, applyToAll ->
                dispatch(RootIntent.ResolveConflict(strategy, applyToAll))
            },
            onDismiss = { dispatch(RootIntent.DismissDialog) },
        )
        is RootDialogState.CreateDirectories -> CreateDirectoriesDialog(
            state = dialogState,
            onDraftChange = { dispatch(RootIntent.UpdateCreateDirectoriesDraft(it)) },
            onConfirm = { dispatch(RootIntent.ConfirmDialog) },
            onDismiss = { dispatch(RootIntent.DismissDialog) },
        )
        is RootDialogState.Settings -> SettingsDialog(
            state = dialogState,
            onDraftChange = { dispatch(RootIntent.UpdateSettingsDraft(it)) },
            onCleanupInvalidLocations = { dispatch(RootIntent.CleanupInvalidLocations) },
            onConfirm = { dispatch(RootIntent.ConfirmDialog) },
            onDismiss = { dispatch(RootIntent.DismissDialog) },
            initialWidth = state.settings.settingsWindowWidth,
            initialHeight = state.settings.settingsWindowHeight,
            onWindowSizeChanged = { width, height ->
                val settings = state.settings.copy(settingsWindowWidth = width, settingsWindowHeight = height)
                dispatch(RootIntent.UpdateSettings(settings))
            },
        )
        is RootDialogState.RemoteConnections -> RemoteConnectionsDialog(
            state = dialogState,
            connections = state.settings.remoteConnections,
            onDraftChange = { dispatch(RootIntent.UpdateRemoteConnectionDraft(it)) },
            onNew = { dispatch(RootIntent.NewRemoteConnection) },
            onEdit = { dispatch(RootIntent.EditRemoteConnection(it)) },
            onSave = { dispatch(RootIntent.SaveRemoteConnectionDraft) },
            onTest = { dispatch(RootIntent.TestRemoteConnectionDraft) },
            onDelete = { dispatch(RootIntent.DeleteRemoteConnection(it)) },
            onOpen = { dispatch(RootIntent.OpenRemoteConnection(it)) },
            onDismiss = { dispatch(RootIntent.DismissDialog) },
        )
        is RootDialogState.BatchRename -> BatchRenameDialog(
            state = dialogState,
            onConfirm = { renames ->
                dispatch(RootIntent.ExecuteBatchRename(dialogState.paneId, renames))
            },
            onDismiss = { dispatch(RootIntent.DismissDialog) },
            initialWidth = state.settings.batchRenameWindowWidth,
            initialHeight = state.settings.batchRenameWindowHeight,
            onWindowSizeChanged = { width, height ->
                val settings = state.settings.copy(
                    batchRenameWindowWidth = width,
                    batchRenameWindowHeight = height,
                )
                dispatch(RootIntent.UpdateSettings(settings))
            },
        )
        is RootDialogState.ArchivePassword -> ArchivePasswordDialog(
            archiveName = dialogState.archiveName,
            error = dialogState.error,
            onConfirm = { dispatch(RootIntent.SubmitArchivePassword(it)) },
            onDismiss = { dispatch(RootIntent.DismissDialog) },
        )
        is RootDialogState.RemoteCredentials -> RemoteCredentialsDialog(
            state = dialogState,
            onDraftChange = { dispatch(RootIntent.UpdateRemoteCredentialsDraft(it)) },
            onConfirm = { dispatch(RootIntent.SubmitRemoteCredentials) },
            onDismiss = { dispatch(RootIntent.DismissDialog) },
        )
        null -> Unit
    }
}
