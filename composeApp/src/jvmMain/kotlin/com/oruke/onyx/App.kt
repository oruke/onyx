package com.oruke.onyx

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.component.RootIntent
import com.oruke.onyx.app.component.RootWindowKind
import com.oruke.onyx.app.component.RootState
import com.oruke.onyx.app.component.RootLaunchConfiguration
import com.oruke.onyx.app.component.effectiveSettings
import com.oruke.onyx.app.component.findVisibleEntry
import com.oruke.onyx.app.component.visibleEntriesIncludingExpanded
import com.oruke.onyx.app.component.rememberRootApplicationRuntime
import com.oruke.onyx.app.component.rememberRootComponent
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.ui.BatchRenameDialog
import com.oruke.onyx.ui.ConfirmationDialog
import com.oruke.onyx.ui.TrashPermanentDeleteConfirmationDialog
import com.oruke.onyx.ui.EmptyTrashConfirmationDialog
import com.oruke.onyx.ui.ArchivePasswordDialog
import com.oruke.onyx.ui.ConflictResolutionDialog
import com.oruke.onyx.ui.CreateDirectoriesDialog
import com.oruke.onyx.ui.CreateZipArchiveDialog
import com.oruke.onyx.app.platform.ExternalFileDragService
import com.oruke.onyx.ui.FileDragOverlay
import com.oruke.onyx.ui.BoundPaneSurface
import com.oruke.onyx.ui.PaneDragBindings
import com.oruke.onyx.ui.OnyxTooltipOverlay
import com.oruke.onyx.ui.onyxTooltipPointerCleanup
import com.oruke.onyx.ui.PaneSidebar
import com.oruke.onyx.ui.PaneSidebarActions
import com.oruke.onyx.ui.PaneSidebarState
import com.oruke.onyx.ui.PreviewPane
import com.oruke.onyx.ui.ResizablePaneDivider
import com.oruke.onyx.ui.RemoteConnectionsDialog
import com.oruke.onyx.ui.RemoteCredentialsDialog
import com.oruke.onyx.ui.QuickOpenPanel
import com.oruke.onyx.ui.SettingsDialog
import com.oruke.onyx.ui.StatusBar
import com.oruke.onyx.ui.TitleBarContent
import com.oruke.onyx.ui.WindowKeyboardFocusRegistry
import com.oruke.onyx.ui.commandShortcutMapFromSettings
import com.oruke.onyx.ui.handleWindowGlobalKeyEvent
import com.oruke.onyx.ui.theme.FileDragState
import com.oruke.onyx.ui.theme.FileDropTarget
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.FileDropZone
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.LocalOnyxSettings
import com.oruke.onyx.ui.theme.LocalTooltipController
import com.oruke.onyx.ui.theme.OnyxTheme
import com.oruke.onyx.ui.theme.OnyxLocaleEnvironment
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
import com.oruke.onyx.ui.theme.resolveEffectiveDarkTheme
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
    keyboardFocusRegistry: WindowKeyboardFocusRegistry = remember { WindowKeyboardFocusRegistry() },
) {
    // rootComponent 由 main.kt 在 application 级别创建并传入
    val state by rootComponent.state.collectAsState()
    val visualSettings = state.effectiveSettings
    val palette = rememberOnyxPalette(
        themeMode = visualSettings.themeMode,
        themePreset = visualSettings.themePreset,
        themeColors = visualSettings.themeColors,
    )
    val appearance = rememberOnyxAppearance(visualSettings)
    var windowTooltipRequest by remember { mutableStateOf<TooltipRequest?>(null) }
    var titleBarWindowOrigin by remember { mutableStateOf(IntOffset.Zero) }
    val sharedTooltipController = remember {
        TooltipController(
            show = { request -> windowTooltipRequest = request },
            hide = { owner ->
                if (windowTooltipRequest?.owner === owner) {
                    windowTooltipRequest = null
                }
            },
            clear = { windowTooltipRequest = null },
        )
    }
    val onUiScaleChange: (Int) -> Unit = { value ->
        rootComponent.dispatch(
            RootIntent.UpdateSettings(state.settings.copy(uiScale = value)),
        )
    }

    CompositionLocalProvider(
        LocalTooltipController provides sharedTooltipController,
        LocalOnyxPalette provides palette,
        LocalOnyxAppearance provides appearance,
        LocalOnyxSettings provides visualSettings,
    ) {
        TitleBar(
            modifier = Modifier
                .newFullscreenControls()
                .onGloballyPositioned { coordinates ->
                    titleBarWindowOrigin = coordinates.localToWindow(Offset.Zero).toIntOffset()
                }
                .onyxTooltipPointerCleanup(
                    requestProvider = { windowTooltipRequest },
                    windowOriginProvider = { titleBarWindowOrigin },
                    onClear = { windowTooltipRequest = null },
                ),
        ) { _ ->
            TitleBarContent(
                rootComponent = rootComponent,
                layoutMode = state.layoutMode,
                uiScale = visualSettings.uiScale,
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
                    rootComponent.dispatch(RootIntent.ShowSearchPanel)
                },
            )
        }

        // AppContent 在 OnyxTheme 内部渲染，uiScale 通过 LocalDensity 生效
        var workspaceEntered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            workspaceEntered = true
        }
        val entranceAlpha by animateFloatAsState(
            targetValue = if (workspaceEntered) 1f else 0f,
            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            label = "workspaceEntranceAlpha",
        )
        val entranceScale by animateFloatAsState(
            targetValue = if (workspaceEntered) 1f else 0.988f,
            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            label = "workspaceEntranceScale",
        )
        OnyxTheme(
            uiScale = visualSettings.uiScale,
            appearance = appearance,
            themeMode = visualSettings.themeMode,
            themePreset = visualSettings.themePreset,
            themeColors = visualSettings.themeColors,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = entranceAlpha
                        scaleX = entranceScale
                        scaleY = entranceScale
                    },
            ) {
                AppContent(
                    rootComponent = rootComponent,
                    state = state,
                    externalFileDragService = externalFileDragService,
                    externalTooltipRequest = windowTooltipRequest,
                    onClearExternalTooltip = { windowTooltipRequest = null },
                    keyboardFocusRegistry = keyboardFocusRegistry,
                )
            }
        }
    }
}

/**
 * 创建供独立预览或嵌入场景使用的单窗口应用内容。
 *
 * @return 无返回值。
 */
@Composable
internal fun App() {
    val koin = org.koin.compose.getKoin()
    val externalFileDragService = remember { koin.get<ExternalFileDragService>() }
    val applicationRuntime = rememberRootApplicationRuntime()
    val launchConfiguration = remember { RootLaunchConfiguration() }
    val rootComponent = rememberRootComponent(
        applicationRuntime = applicationRuntime,
        externalFileDragService = externalFileDragService,
        launchConfiguration = launchConfiguration,
    )
    DisposableEffect(externalFileDragService) {
        onDispose(externalFileDragService::dispose)
    }
    App(rootComponent, externalFileDragService)
}

/**
 * 使用已装配的根组件与拖放服务渲染单窗口应用内容。
 *
 * @param rootComponent 文件管理器根组件。
 * @param externalFileDragService 与根组件一致的窗口拖放服务。
 * @return 无返回值。
 */
@Composable
internal fun App(
    rootComponent: RootComponent,
    externalFileDragService: ExternalFileDragService,
    dragController: AppDragController? = null,
) {
    val state by rootComponent.state.collectAsState()
    val visualSettings = state.effectiveSettings
    val appearance = rememberOnyxAppearance(visualSettings)
    val isDark = resolveEffectiveDarkTheme(visualSettings.themeMode, isSystemInDarkTheme())

    IntUiTheme(isDark = isDark) {
        OnyxLocaleEnvironment(rootComponent.resourceLocale) {
            CompositionLocalProvider(LocalOnyxSettings provides visualSettings) {
                OnyxTheme(
                    uiScale = visualSettings.uiScale,
                    appearance = appearance,
                    themeMode = visualSettings.themeMode,
                    themePreset = visualSettings.themePreset,
                    themeColors = visualSettings.themeColors,
                ) {
                    AppContent(
                        rootComponent = rootComponent,
                        state = state,
                        externalFileDragService = externalFileDragService,
                        externalDragController = dragController,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppContent(
    rootComponent: RootComponent,
    state: RootState,
    externalFileDragService: ExternalFileDragService,
    externalTooltipRequest: TooltipRequest? = null,
    onClearExternalTooltip: (() -> Unit)? = null,
    externalDragController: AppDragController? = null,
    keyboardFocusRegistry: WindowKeyboardFocusRegistry = remember { WindowKeyboardFocusRegistry() },
) {
    val dragController = externalDragController ?: remember(rootComponent, externalFileDragService) {
        AppDragController(rootComponent, externalFileDragService)
    }
    var localTooltipRequest by remember { mutableStateOf<TooltipRequest?>(null) }
    val useExternalTooltipHost = onClearExternalTooltip != null
    val activeTooltipRequest = if (useExternalTooltipHost) externalTooltipRequest else (localTooltipRequest ?: externalTooltipRequest)
    val latestTooltipRequest = androidx.compose.runtime.rememberUpdatedState(activeTooltipRequest)
    val latestOnClearExternalTooltip = androidx.compose.runtime.rememberUpdatedState(onClearExternalTooltip)
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

    val parentTooltipController = LocalTooltipController.current
    val effectiveTooltipController = if (useExternalTooltipHost) {
        parentTooltipController
    } else {
        remember {
            TooltipController(
                show = { request -> localTooltipRequest = request },
                hide = { owner ->
                    if (localTooltipRequest?.owner === owner) {
                        localTooltipRequest = null
                    }
                },
                clear = { localTooltipRequest = null },
            )
        }
    }

    CompositionLocalProvider(LocalTooltipController provides effectiveTooltipController) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    handleWindowGlobalKeyEvent(
                        event = event,
                        state = rootComponent.state.value,
                        rootComponent = rootComponent,
                        focusRegistry = keyboardFocusRegistry,
                    )
                }
                .onGloballyPositioned { coordinates ->
                    appContentSize = coordinates.size
                    appWindowOrigin = coordinates.localToWindow(Offset.Zero).toIntOffset()
                }
                .onyxTooltipPointerCleanup(
                    requestProvider = { latestTooltipRequest.value },
                    windowOriginProvider = { appWindowOrigin },
                    onClear = {
                        localTooltipRequest = null
                        latestOnClearExternalTooltip.value?.invoke()
                    },
                ),
        ) {
            AppWorkspace(state, rootComponent, dragBindings, toggleFavoriteLocation, keyboardFocusRegistry)

            activeTooltipRequest?.let { request ->
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

/**
 * 显示应用主工作区及其搜索和状态附属区域。
 *
 * @param state 根组件当前状态。
 * @param rootComponent 根组件业务接口。
 * @param dragBindings 面板共享拖放绑定。
 * @param onToggleFavoriteLocation 切换收藏位置的回调。
 * @param keyboardFocusRegistry 面板文本输入/局部浮层键盘独占登记表。
 */
@Composable
private fun AppWorkspace(
    state: RootState,
    rootComponent: RootComponent,
    dragBindings: PaneDragBindings,
    onToggleFavoriteLocation: (String) -> Unit,
    keyboardFocusRegistry: WindowKeyboardFocusRegistry,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().background(LocalOnyxPalette.current.appBackground),
        ) {
            AppMainArea(
                state = state,
                rootComponent = rootComponent,
                dragBindings = dragBindings,
                onToggleFavoriteLocation = onToggleFavoriteLocation,
                keyboardFocusRegistry = keyboardFocusRegistry,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            AppStatusBar(state, rootComponent)
        }

        AppQuickOpenOverlay(state, rootComponent)
    }
}

/**
 * 显示侧栏、文件面板布局与可选预览栏。
 *
 * @param state 根组件当前状态。
 * @param rootComponent 根组件业务接口。
 * @param dragBindings 面板共享拖放绑定。
 * @param onToggleFavoriteLocation 切换收藏位置的回调。
 * @param keyboardFocusRegistry 面板文本输入/局部浮层键盘独占登记表。
 * @param modifier 内容区布局修饰符。
 */
@Composable
private fun AppMainArea(
    state: RootState,
    rootComponent: RootComponent,
    dragBindings: PaneDragBindings,
    onToggleFavoriteLocation: (String) -> Unit,
    keyboardFocusRegistry: WindowKeyboardFocusRegistry,
    modifier: Modifier,
) {
    Row(modifier = modifier) {
        AppSidebar(state, rootComponent, dragBindings, onToggleFavoriteLocation)
        AppPaneLayout(
            state = state,
            rootComponent = rootComponent,
            dragBindings = dragBindings,
            keyboardFocusRegistry = keyboardFocusRegistry,
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
 * @param dragBindings 面板共享拖放绑定。
 * @param onToggleFavoriteLocation 切换收藏位置的回调。
 */
@Composable
private fun AppSidebar(
    state: RootState,
    rootComponent: RootComponent,
    dragBindings: PaneDragBindings,
    onToggleFavoriteLocation: (String) -> Unit,
) {
    val visualSettings = state.effectiveSettings
    if (!visualSettings.sidebarVisible) return
    val dispatch = rootComponent::dispatch
    PaneSidebar(
        state = PaneSidebarState(
            location = state.paneState(state.activePane).location,
            systemQuickAccessLocations = state.systemQuickAccessLocations,
            favoriteLocations = visualSettings.favoriteLocations,
            recentLocations = visualSettings.recentLocations,
            remoteConnections = visualSettings.remoteConnections,
            treeState = state.sidebarTreeState,
            showTree = visualSettings.sidebarTreeVisible,
            collapsedSections = visualSettings.collapsedSidebarSections,
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
            onToggleSection = { dispatch(RootIntent.ToggleSidebarSection(it)) },
            onOpenInNewTab = { dispatch(RootIntent.OpenLocationInNewTab(it)) },
            onCopyPath = { dispatch(RootIntent.CopyLocationPath(it)) },
        ),
        activePane = state.activePane,
        dragBindings = dragBindings,
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
    keyboardFocusRegistry: WindowKeyboardFocusRegistry,
    modifier: Modifier,
) {
    when (state.layoutMode) {
        PaneLayoutMode.SINGLE -> BoundPaneSurface(
            paneId = PaneId.PRIMARY,
            state = state,
            rootComponent = rootComponent,
            modifier = modifier,
            dragBindings = dragBindings,
            onGlobalShortcutBlockChange = { keyboardFocusRegistry.setPaneBlocking(PaneId.PRIMARY, it) },
        )

        PaneLayoutMode.DUAL_VERTICAL -> VerticalPaneLayout(
            state = state,
            rootComponent = rootComponent,
            dragBindings = dragBindings,
            keyboardFocusRegistry = keyboardFocusRegistry,
            modifier = modifier,
        )

        PaneLayoutMode.DUAL_HORIZONTAL -> HorizontalPaneLayout(
            state = state,
            rootComponent = rootComponent,
            dragBindings = dragBindings,
            keyboardFocusRegistry = keyboardFocusRegistry,
            modifier = modifier,
        )
    }
}

/**
 * 显示左右排列的双面板及可拖拽分隔线。
 *
 * @param state 根组件当前状态。
 * @param rootComponent 根组件业务接口。
 * @param dragBindings 面板共享拖放绑定。
 * @param keyboardFocusRegistry 面板文本输入/局部浮层键盘独占登记表。
 * @param modifier 双面板区域修饰符。
 */
@Composable
private fun VerticalPaneLayout(
    state: RootState,
    rootComponent: RootComponent,
    dragBindings: PaneDragBindings,
    keyboardFocusRegistry: WindowKeyboardFocusRegistry,
    modifier: Modifier,
) {
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    Row(modifier = modifier.fillMaxHeight().onSizeChanged { contentSize = it }) {
        BoundPaneSurface(
            paneId = PaneId.PRIMARY,
            state = state,
            rootComponent = rootComponent,
            modifier = Modifier.weight(state.paneSplitFraction),
            dragBindings = dragBindings,
            onGlobalShortcutBlockChange = { keyboardFocusRegistry.setPaneBlocking(PaneId.PRIMARY, it) },
        )
        ResizablePaneDivider(Orientation.Vertical) { delta ->
            val width = contentSize.width.toFloat().coerceAtLeast(1f)
            rootComponent.dispatch(
                RootIntent.SetPaneSplitFraction(rootComponent.state.value.paneSplitFraction + delta / width),
            )
        }
        BoundPaneSurface(
            paneId = PaneId.SECONDARY,
            state = state,
            rootComponent = rootComponent,
            modifier = Modifier.weight(1f - state.paneSplitFraction),
            dragBindings = dragBindings,
            onGlobalShortcutBlockChange = { keyboardFocusRegistry.setPaneBlocking(PaneId.SECONDARY, it) },
        )
    }
}

/**
 * 显示上下排列的双面板及可拖拽分隔线。
 *
 * @param state 根组件当前状态。
 * @param rootComponent 根组件业务接口。
 * @param dragBindings 面板共享拖放绑定。
 * @param keyboardFocusRegistry 面板文本输入/局部浮层键盘独占登记表。
 * @param modifier 双面板区域修饰符。
 */
@Composable
private fun HorizontalPaneLayout(
    state: RootState,
    rootComponent: RootComponent,
    dragBindings: PaneDragBindings,
    keyboardFocusRegistry: WindowKeyboardFocusRegistry,
    modifier: Modifier,
) {
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    Column(modifier = modifier.fillMaxHeight().onSizeChanged { contentSize = it }) {
        BoundPaneSurface(
            paneId = PaneId.PRIMARY,
            state = state,
            rootComponent = rootComponent,
            modifier = Modifier.weight(state.paneSplitFraction),
            dragBindings = dragBindings,
            onGlobalShortcutBlockChange = { keyboardFocusRegistry.setPaneBlocking(PaneId.PRIMARY, it) },
        )
        ResizablePaneDivider(Orientation.Horizontal) { delta ->
            val height = contentSize.height.toFloat().coerceAtLeast(1f)
            rootComponent.dispatch(
                RootIntent.SetPaneSplitFraction(rootComponent.state.value.paneSplitFraction + delta / height),
            )
        }
        BoundPaneSurface(
            paneId = PaneId.SECONDARY,
            state = state,
            rootComponent = rootComponent,
            modifier = Modifier.weight(1f - state.paneSplitFraction),
            dragBindings = dragBindings,
            onGlobalShortcutBlockChange = { keyboardFocusRegistry.setPaneBlocking(PaneId.SECONDARY, it) },
        )
    }
}

/**
 * 在启用时显示活动面板选中项的预览与元数据侧栏，支持左边界拖拽调宽与模式持久化。
 *
 * @param state 根组件当前状态。
 * @param rootComponent 根组件业务接口。
 */
@Composable
private fun AppPreviewPanel(state: RootState, rootComponent: RootComponent) {
    if (!state.showPreviewPane) return
    // 面板宽度与模式属于运行期实时布局状态，直接读取 state.settings 避免非模态设置窗打开时被旧草稿覆盖
    val liveSettings = state.settings
    val density = androidx.compose.ui.platform.LocalDensity.current
    val paneState = state.paneState(state.activePane)
    val focusEntryId = paneState.selectionFocusId
        ?.takeIf { focusId -> focusId in paneState.selectedEntryIds }
        ?: paneState.selectedEntryIds.firstOrNull()
    val selectedEntry = paneState.findVisibleEntry(focusEntryId)
    var liveWidthDp by remember { mutableStateOf(liveSettings.previewPaneWidthDp.toFloat()) }
    LaunchedEffect(liveSettings.previewPaneWidthDp) {
        if (kotlin.math.abs(liveWidthDp - liveSettings.previewPaneWidthDp) >= 1f) {
            liveWidthDp = liveSettings.previewPaneWidthDp.toFloat()
        }
    }
    ResizablePaneDivider(Orientation.Vertical) { deltaPx ->
        val nextWidth = (liveWidthDp - deltaPx / density.density)
            .coerceIn(
                com.oruke.onyx.app.component.MIN_PREVIEW_PANE_WIDTH_DP.toFloat(),
                com.oruke.onyx.app.component.MAX_PREVIEW_PANE_WIDTH_DP.toFloat(),
            )
        liveWidthDp = nextWidth
        val roundedDp = kotlin.math.round(nextWidth).toInt()
        if (roundedDp != liveSettings.previewPaneWidthDp) {
            rootComponent.dispatch(RootIntent.SetPreviewPaneWidth(roundedDp))
        }
    }
    PreviewPane(
        selectedEntry = selectedEntry,
        modifier = Modifier.width(liveWidthDp.dp).fillMaxHeight(),
        activeMode = liveSettings.previewPaneMode,
        onSelectMode = { mode -> rootComponent.dispatch(RootIntent.SetPreviewPaneMode(mode)) },
        onClose = { rootComponent.dispatch(RootIntent.ClosePreviewPane) },
        loadThumbnail = rootComponent::loadThumbnail,
        loadTextPreview = rootComponent::loadTextPreview,
        isImageFileName = rootComponent::isImageFileName,
        isTextPreviewFileName = rootComponent::isTextPreviewFileName,
        isAudioFileName = rootComponent::isAudioFileName,
        isVideoFileName = rootComponent::isVideoFileName,
        peekCachedThumbnail = rootComponent::peekCachedThumbnail,
        loadVersionedThumbnail = rootComponent::loadThumbnail,
        peekCachedArchiveThumbnail = rootComponent::peekCachedArchiveThumbnail,
        loadArchiveThumbnail = rootComponent::loadArchiveThumbnail,
        readImageSize = rootComponent::readImageSize,
        readFileHash = rootComponent::readFileHash,
        readArchiveInfo = rootComponent::readArchiveInfo,
        isArchiveFileName = rootComponent::isArchiveFileName,
        readMediaMetadata = rootComponent::readMediaMetadata,
        loadVideoFrame = rootComponent::loadVideoFrame,
        loadAudioCover = rootComponent::loadAudioCover,
        audioPreviewPlayer = rootComponent.audioPreviewPlayer,
        onOpenImageViewer = { imageEntry ->
            val visibleImages = paneState.visibleEntriesIncludingExpanded()
                .filter { candidate -> candidate.kind == VFileKind.FILE && rootComponent.isImageFileName(candidate.name) }
                .ifEmpty { listOf(imageEntry) }
            rootComponent.dispatch(RootIntent.OpenImageViewer(imageEntry, visibleImages))
        },
        onOpenExternal = { entry -> rootComponent.dispatch(RootIntent.OpenExternalFile(entry)) },
    )
}

/**
 * 在 Quick Open 模式启用时显示浮层搜索。
 *
 * @param state 根组件当前状态。
 * @param rootComponent 根组件业务接口。
 */
@Composable
private fun AppQuickOpenOverlay(state: RootState, rootComponent: RootComponent) {
    if (!state.quickOpenState.visible) return
    val dispatch = rootComponent::dispatch
    QuickOpenPanel(
        state = state.quickOpenState,
        locationLabel = rootComponent::locationLabel,
        onQueryChange = { dispatch(RootIntent.UpdateQuickOpenQuery(it)) },
        onExecuteSearch = { dispatch(RootIntent.ExecuteQuickOpen) },
        onClose = { dispatch(RootIntent.CloseQuickOpen) },
        onOpenResult = { dispatch(RootIntent.OpenQuickOpenResult(it)) },
        onOpenResultInFolder = { dispatch(RootIntent.OpenQuickOpenResultInFolder(it)) },
        onSelectedIndexChange = { dispatch(RootIntent.SetQuickOpenSelectedIndex(it)) },
        recentLocations = state.effectiveSettings.recentLocations,
        onOpenLocation = { location ->
            dispatch(RootIntent.CloseQuickOpen)
            dispatch(RootIntent.OpenLocationInActivePane(location))
        },
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
    if (!state.effectiveSettings.statusBarVisible) return
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
        taskCenter = rootComponent.taskCenter,
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

        is RootDialogState.TrashPermanentDeleteConfirmation -> TrashPermanentDeleteConfirmationDialog(
            state = dialogState,
            onConfirm = { dispatch(RootIntent.ConfirmDialog) },
            onDismiss = { dispatch(RootIntent.DismissDialog) },
        )

        is RootDialogState.EmptyTrashConfirmation -> EmptyTrashConfirmationDialog(
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
            commandShortcuts = commandShortcutMapFromSettings(state.settings.commandShortcutOverrides),
        )

        is RootDialogState.CreateZipArchive -> CreateZipArchiveDialog(
            state = dialogState,
            onDraftChange = { dispatch(RootIntent.UpdateZipArchiveNameDraft(it)) },
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
                dispatch(RootIntent.UpdateWindowSize(RootWindowKind.SETTINGS, width, height))
            },
            onClearThumbnailDiskCache = { dispatch(RootIntent.ClearThumbnailDiskCache) },
        )

        is RootDialogState.RemoteConnections -> RemoteConnectionsDialog(
            state = dialogState,
            connections = state.settings.remoteConnections,
            onDraftChange = { dispatch(RootIntent.UpdateRemoteConnectionDraft(it)) },
            onNew = { dispatch(RootIntent.NewRemoteConnection) },
            onEdit = { dispatch(RootIntent.EditRemoteConnection(it)) },
            onSave = { dispatch(RootIntent.SaveRemoteConnectionDraft) },
            onTest = { dispatch(RootIntent.TestRemoteConnectionDraft) },
            onCancelTest = { dispatch(RootIntent.CancelRemoteConnectionTest) },
            onDelete = { dispatch(RootIntent.DeleteRemoteConnection(it)) },
            onOpen = { dispatch(RootIntent.OpenRemoteConnection(it)) },
            onOpenDraft = { dispatch(RootIntent.OpenRemoteConnectionDraft) },
            onDismiss = { dispatch(RootIntent.DismissDialog) },
        )

        is RootDialogState.BatchRename -> BatchRenameDialog(
            state = dialogState,
            onIntent = dispatch,
            settings = state.effectiveSettings,
            onConfirm = { renames ->
                dispatch(RootIntent.ExecuteBatchRename(dialogState.paneId, renames))
            },
            onDismiss = { dispatch(RootIntent.DismissDialog) },
            initialWidth = state.settings.batchRenameWindowWidth,
            initialHeight = state.settings.batchRenameWindowHeight,
            onWindowSizeChanged = { width, height ->
                dispatch(RootIntent.UpdateWindowSize(RootWindowKind.BATCH_RENAME, width, height))
            },
        )

        is RootDialogState.ArchivePassword -> ArchivePasswordDialog(
            archiveName = dialogState.archiveName,
            error = dialogState.error,
            attempt = dialogState.attempt,
            verifying = dialogState.verifying,
            requestId = dialogState.requestId,
            onConfirm = { dispatch(RootIntent.SubmitArchivePassword(it)) },
            onDismiss = { dispatch(RootIntent.DismissDialog) },
        )

        is RootDialogState.RemoteCredentials -> RemoteCredentialsDialog(
            state = dialogState,
            onDraftChange = { dispatch(RootIntent.UpdateRemoteCredentialsDraft(it)) },
            onConfirm = { dispatch(RootIntent.SubmitRemoteCredentials) },
            onDismiss = { dispatch(RootIntent.DismissDialog) },
            onOpenRemoteConnections = { dispatch(RootIntent.OpenRemoteConnections) },
        )

        null -> Unit
    }
}
