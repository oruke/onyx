package com.oruke.onyx.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.PaneCommand
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.windowBounds
import kotlinx.coroutines.delay
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_filter
import onyx.composeapp.generated.resources.action_go_back
import onyx.composeapp.generated.resources.action_go_forward
import onyx.composeapp.generated.resources.action_go_home
import onyx.composeapp.generated.resources.action_go_up
import onyx.composeapp.generated.resources.action_refresh_active
import onyx.composeapp.generated.resources.action_toggle_favorite
import onyx.composeapp.generated.resources.action_toggle_hidden_files
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** 面板操作反馈自动消失前的展示时长。 */
private const val OPERATION_FEEDBACK_DURATION_MS = 3_000L

/** 可由面板键盘焦点直接执行的统一命令。 */
private val PANE_SHORTCUT_COMMANDS = listOf(
    OnyxCommand.CommandPalette,
    OnyxCommand.QuickOpen,
    OnyxCommand.ShowSearchPanel,
    OnyxCommand.OpenSelection,
    OnyxCommand.RenameSelection,
    OnyxCommand.NewDirectory,
    OnyxCommand.NewFile,
    OnyxCommand.CopySelection,
    OnyxCommand.CutSelection,
    OnyxCommand.Paste,
    OnyxCommand.UndoLastOperation,
    OnyxCommand.RedoLastOperation,
    OnyxCommand.DeleteSelection,
    OnyxCommand.SelectAll,
    OnyxCommand.Filter,
    OnyxCommand.ToggleFavorite,
    OnyxCommand.Refresh,
    OnyxCommand.GoUp,
    OnyxCommand.OpenSettings,
)

/**
 * 渲染单个文件面板，并连接键盘、标签、工具栏、条目视图和检查器。
 *
 * @param model 面板状态与跨组件动作模型。
 * @param dragBindings 标签及文件拖放绑定。
 * @param services 文件预览与路径服务。
 * @param modifier 面板布局修饰符。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PaneSurface(
    model: PaneSurfaceModel,
    dragBindings: PaneDragBindings,
    services: PaneSurfaceServices,
    modifier: Modifier = Modifier,
) {
    val runtime = rememberPaneSurfaceRuntime(model, dragBindings, services)
    PaneSurfaceFocusEffects(runtime)
    val interactionSource = remember { MutableInteractionSource() }
    val palette = LocalOnyxPalette.current
    val paneDropBackground by animateColorAsState(
        targetValue = paneDropBackground(runtime),
        animationSpec = tween(durationMillis = 120),
    )
    Column(
        modifier = modifier
            .border(1.dp, if (model.active) palette.outline else palette.outlineVariant)
            .background(palette.surface)
            .onGloballyPositioned { coordinates ->
                runtime.ui.paneBounds = coordinates.windowBounds()
                runtime.reportTabDropZone()
            }
            .focusRequester(runtime.focusRequester)
            .focusable()
            .onPreviewKeyEvent { event -> handlePaneKeyEvent(event, runtime) }
            .clickable(
                indication = null,
                interactionSource = interactionSource,
                onClick = model.onActivate,
            ),
    ) {
        PaneSurfaceContent(runtime, paneDropBackground)
    }
}

/**
 * 计算面板作为文件放置目标时的背景色。
 *
 * @param runtime 面板运行时控制器。
 * @return 当前放置状态对应的背景色。
 */
@Composable
private fun paneDropBackground(runtime: PaneSurfaceRuntime): Color {
    val target = runtime.dragBindings.fileDropTarget
    val state = runtime.state
    val paneTargeted = target?.paneId == state.paneId &&
        target.directoryEntryId == null &&
        target.targetDirectoryLocation == state.location
    return if (paneTargeted) {
        LocalOnyxPalette.current.rowHoverBackground.copy(alpha = 0.28f)
    } else {
        Color.Transparent
    }
}

/**
 * 在活动面板与内联编辑切换时恢复正确的键盘焦点。
 *
 * @param runtime 面板运行时控制器。
 */
@Composable
private fun PaneSurfaceFocusEffects(runtime: PaneSurfaceRuntime) {
    val inlineEditActive = runtime.state.inlineEditState != null
    val textInputOwnsKeyboard = runtime.ui.addressBarEditing ||
        runtime.ui.filterFocused ||
        runtime.state.commandPaletteVisible
    LaunchedEffect(runtime.model.active) {
        if (runtime.model.active) runtime.focusRequester.requestFocus()
    }
    LaunchedEffect(runtime.model.active, inlineEditActive, textInputOwnsKeyboard) {
        val inlineEditFinished = runtime.ui.inlineEditWasActive && !inlineEditActive
        runtime.ui.inlineEditWasActive = inlineEditActive
        if (runtime.model.active && inlineEditFinished && !textInputOwnsKeyboard) {
            runtime.focusRequester.requestFocus()
        }
    }
}

/**
 * 处理文件面板预览阶段的键盘事件。
 *
 * @param event 键盘事件。
 * @param runtime 面板运行时控制器。
 * @return 事件已消费时返回 `true`。
 */
private fun handlePaneKeyEvent(event: KeyEvent, runtime: PaneSurfaceRuntime): Boolean {
    if (!runtime.model.active || event.type != KeyEventType.KeyDown) return false
    val textInputOwnsKeyboard = runtime.ui.addressBarEditing ||
        runtime.ui.filterFocused ||
        runtime.state.commandPaletteVisible
    return when {
        textInputOwnsKeyboard -> false
        runtime.state.inlineEditState != null -> handleInlineEditKey(event, runtime)
        else -> handleRegularPaneKey(event, runtime)
    }
}

/**
 * 处理非文本编辑状态下的可配置命令和导航按键。
 *
 * @param event 键盘事件。
 * @param runtime 面板运行时控制器。
 * @return 事件已消费时返回 `true`。
 */
private fun handleRegularPaneKey(event: KeyEvent, runtime: PaneSurfaceRuntime): Boolean {
    val shortcutCommand = PANE_SHORTCUT_COMMANDS.firstOrNull { command ->
        event.matchesCommand(command, runtime.model.commandShortcuts)
    }
    if (shortcutCommand != null) return runtime.executeCommand(shortcutCommand)
    return handlePaneNavigationKey(event, runtime)
}

/**
 * 处理内联编辑期间仅由面板拦截的确认与取消按键。
 *
 * @param event 键盘事件。
 * @param runtime 面板运行时控制器。
 * @return 事件已消费时返回 `true`。
 */
private fun handleInlineEditKey(event: KeyEvent, runtime: PaneSurfaceRuntime): Boolean {
    return when (event.key) {
        Key.Enter -> {
            runtime.dispatch(PaneIntent.ConfirmInlineEdit)
            true
        }
        Key.Escape -> {
            runtime.dispatch(PaneIntent.CancelInlineEdit)
            true
        }
        else -> false
    }
}

/**
 * 处理方向键与 Escape 等不属于可配置命令的导航按键。
 *
 * @param event 键盘事件。
 * @param runtime 面板运行时控制器。
 * @return 事件已消费时返回 `true`。
 */
private fun handlePaneNavigationKey(event: KeyEvent, runtime: PaneSurfaceRuntime): Boolean {
    return when (event.key) {
        Key.DirectionDown -> {
            runtime.dispatch(PaneIntent.MoveSelection(1, event.isShiftPressed))
            true
        }
        Key.DirectionUp -> {
            runtime.dispatch(PaneIntent.MoveSelection(-1, event.isShiftPressed))
            true
        }
        Key.Escape -> {
            closePaneOverlay(runtime)
            true
        }
        else -> false
    }
}

/**
 * 按优先级关闭右键菜单、过滤框或当前选择。
 *
 * @param runtime 面板运行时控制器。
 */
private fun closePaneOverlay(runtime: PaneSurfaceRuntime) {
    val filterVisible = runtime.state.filterInputVisible || runtime.state.filterQuery.isNotEmpty()
    when {
        runtime.ui.contextMenuVisible -> runtime.ui.contextMenuVisible = false
        filterVisible -> runtime.closeFilterInput()
        else -> runtime.dispatch(PaneIntent.ClearSelection)
    }
}

/**
 * 按垂直顺序渲染面板各区域。
 *
 * @param runtime 面板运行时控制器。
 * @param paneDropBackground 文件放置目标背景色。
 */
@Composable
private fun ColumnScope.PaneSurfaceContent(runtime: PaneSurfaceRuntime, paneDropBackground: Color) {
    PaneCommandPalette(runtime)
    PaneSurfaceTabBar(runtime)
    Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
    PaneNavigationToolbar(runtime)
    Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
    PaneOperationFeedback(runtime)
    PaneFileArea(runtime, paneDropBackground, Modifier.weight(1f))
}

/**
 * 渲染当前面板命令面板。
 *
 * @param runtime 面板运行时控制器。
 */
@Composable
private fun PaneCommandPalette(runtime: PaneSurfaceRuntime) {
    if (!runtime.state.commandPaletteVisible) return
    val items = OnyxCommandRegistry
        .paneCommandStates(runtime.model.commandShortcuts, runtime::isCommandEnabled)
        .filterNot { commandState -> commandState.spec.command == OnyxCommand.CommandPalette }
        .map { commandState ->
            CommandPaletteItem(
                command = commandState.spec.command,
                label = stringResource(commandState.spec.label),
                shortcut = onyxShortcutHint(commandState.shortcut),
                iconKey = commandState.spec.iconKey,
                enabled = commandState.enabled,
            )
        }
    CommandPalettePopup(
        items = items,
        onExecute = { command ->
            if (runtime.executeCommand(command)) runtime.dispatch(PaneIntent.HideCommandPalette)
        },
        onClose = { runtime.dispatch(PaneIntent.HideCommandPalette) },
    )
}

/**
 * 渲染面板标签栏并注册标签放置区域。
 *
 * @param runtime 面板运行时控制器。
 */
@Composable
private fun PaneSurfaceTabBar(runtime: PaneSurfaceRuntime) {
    val orderedTabs by runtime.model.component.tabStates.collectAsState()
    val state = PaneTabBarState(
        activeTabId = runtime.state.activeTabId,
        tabs = orderedTabs.map { tab -> PaneTabItemState(tab.id, tab.title) },
    )
    PaneTabBar(
        state = state,
        active = runtime.model.active,
        onActivate = runtime.model.onActivate,
        onSelectTab = { tabId -> runtime.dispatch(PaneIntent.SelectTab(tabId)) },
        onCloseTab = { tabId -> runtime.dispatch(PaneIntent.CloseTab(tabId)) },
        onCreateTab = { runtime.dispatch(PaneIntent.CreateTab()) },
        onDropTab = { tabId, position ->
            runtime.dragBindings.onTabDrop(runtime.state.paneId, tabId, position)
        },
        onDragPositionChange = runtime.dragBindings.onTabDragPositionChange,
        onDragEnd = runtime.dragBindings.onTabDragEnd,
        onDropZoneChange = { zone ->
            runtime.ui.tabBarDropZone = zone
            runtime.reportTabDropZone()
        },
        dropIndicatorIndex = runtime.dragBindings.tabDropTarget
            ?.takeIf { target -> target.paneId == runtime.state.paneId }
            ?.index,
    )
}

/**
 * 渲染面板导航工具栏。
 *
 * @param runtime 面板运行时控制器。
 */
@Composable
private fun PaneNavigationToolbar(runtime: PaneSurfaceRuntime) {
    Row(
        modifier = Modifier.fillMaxWidth().background(LocalOnyxPalette.current.headerBackground)
            .height(28.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        PaneHistoryButtons(runtime)
        PaneLocationButtons(runtime)
        Spacer(modifier = Modifier.width(4.dp))
        Box(modifier = Modifier.weight(1f)) {
            HybridAddressBar(
                location = runtime.state.location,
                onActivate = runtime.model.onActivate,
                onOpenLocation = { location -> runtime.dispatch(PaneIntent.OpenDirectory(location)) },
                buildBreadcrumbs = runtime.services.buildBreadcrumbs,
                onEditingChange = { editing -> runtime.ui.addressBarEditing = editing },
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        PaneViewButtons(runtime)
    }
}

/**
 * 渲染后退与前进按钮。
 *
 * @param runtime 面板运行时控制器。
 */
@Composable
private fun PaneHistoryButtons(runtime: PaneSurfaceRuntime) {
    ToolbarIconButton(
        enabled = runtime.state.canGoBack,
        onClick = { runtime.model.onActivate(); runtime.dispatch(PaneIntent.GoBack) },
        tooltip = stringResource(Res.string.action_go_back),
    ) {
        Icon(AllIconsKeys.Actions.Back, stringResource(Res.string.action_go_back))
    }
    ToolbarIconButton(
        enabled = runtime.state.canGoForward,
        onClick = { runtime.model.onActivate(); runtime.dispatch(PaneIntent.GoForward) },
        tooltip = stringResource(Res.string.action_go_forward),
    ) {
        Icon(AllIconsKeys.Actions.Forward, stringResource(Res.string.action_go_forward))
    }
}

/**
 * 渲染上级、主页与收藏按钮。
 *
 * @param runtime 面板运行时控制器。
 */
@Composable
private fun PaneLocationButtons(runtime: PaneSurfaceRuntime) {
    val favorite = runtime.state.location in runtime.model.favoriteLocations
    ToolbarIconButton(
        enabled = true,
        onClick = { runtime.model.onActivate(); runtime.commands.execute(PaneCommand.GO_UP) },
        tooltip = stringResource(Res.string.action_go_up),
    ) {
        Icon(AllIconsKeys.General.ArrowUp, stringResource(Res.string.action_go_up))
    }
    ToolbarIconButton(
        enabled = true,
        onClick = {
            runtime.model.onActivate()
            runtime.dispatch(PaneIntent.OpenDirectory(System.getProperty("user.home")))
        },
        tooltip = stringResource(Res.string.action_go_home),
    ) {
        Icon(AllIconsKeys.Nodes.HomeFolder, stringResource(Res.string.action_go_home))
    }
    ToolbarIconButton(
        enabled = true,
        onClick = { runtime.model.onActivate(); runtime.commands.execute(PaneCommand.TOGGLE_FAVORITE) },
        tooltip = stringResource(Res.string.action_toggle_favorite),
        selected = favorite,
    ) {
        Text(
            text = if (favorite) "★" else "☆",
            fontSize = 11.sp,
            color = if (favorite) LocalOnyxPalette.current.favorite else LocalOnyxPalette.current.foreground,
        )
    }
}

/**
 * 渲染过滤、刷新与隐藏文件按钮。
 *
 * @param runtime 面板运行时控制器。
 */
@Composable
private fun PaneViewButtons(runtime: PaneSurfaceRuntime) {
    val filterVisible = runtime.state.filterInputVisible || runtime.state.filterQuery.isNotEmpty()
    ToolbarIconButton(
        enabled = true,
        onClick = {
            runtime.model.onActivate()
            if (filterVisible) runtime.closeFilterInput() else runtime.openFilterInput()
        },
        tooltip = onyxCommandTooltip(
            stringResource(Res.string.action_filter),
            OnyxCommand.Filter,
            runtime.model.commandShortcuts,
        ),
        selected = filterVisible,
    ) {
        Icon(AllIconsKeys.Actions.Find, stringResource(Res.string.action_filter))
    }
    Spacer(modifier = Modifier.width(4.dp))
    ToolbarIconButton(
        enabled = true,
        onClick = { runtime.model.onActivate(); runtime.commands.execute(PaneCommand.REFRESH) },
        tooltip = stringResource(Res.string.action_refresh_active),
    ) {
        Icon(AllIconsKeys.Actions.Refresh, stringResource(Res.string.action_refresh_active))
    }
    ToolbarIconButton(
        enabled = true,
        onClick = { runtime.model.onActivate(); runtime.dispatch(PaneIntent.ToggleHiddenItems) },
        tooltip = stringResource(Res.string.action_toggle_hidden_files),
        selected = runtime.state.showHiddenItems,
    ) {
        val icon = if (runtime.state.showHiddenItems) {
            AllIconsKeys.General.Show
        } else {
            AllIconsKeys.Actions.ToggleVisibility
        }
        Icon(icon, stringResource(Res.string.action_toggle_hidden_files))
    }
}

/**
 * 渲染短时操作反馈并安排自动关闭。
 *
 * @param runtime 面板运行时控制器。
 */
@Composable
private fun PaneOperationFeedback(runtime: PaneSurfaceRuntime) {
    val feedback = runtime.state.operationFeedback ?: return
    LaunchedEffect(feedback) {
        delay(OPERATION_FEEDBACK_DURATION_MS)
        runtime.dispatch(PaneIntent.DismissOperationFeedback)
    }
    OperationFeedbackBar(
        feedback = feedback,
        onDismiss = { runtime.dispatch(PaneIntent.DismissOperationFeedback) },
    )
    Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
}
