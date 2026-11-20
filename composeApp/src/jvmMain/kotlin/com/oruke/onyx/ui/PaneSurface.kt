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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.PaneCommand
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.isBrowsableDirectory
import com.oruke.onyx.app.component.visibleEntriesIncludingExpanded
import com.oruke.onyx.core.model.ViewMode
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
import com.oruke.onyx.core.model.UserCommandPlacement
import com.oruke.onyx.core.model.isTrashLocation

/** 面板操作反馈自动消失前的展示时长。 */
private const val OPERATION_FEEDBACK_DURATION_MS = 3_000L

/** 可由面板键盘焦点直接执行的统一命令。 */
private val PANE_SHORTCUT_COMMANDS = listOf(
    OnyxCommand.CommandPalette,
    OnyxCommand.QuickOpen,
    OnyxCommand.ShowSearchPanel,
    OnyxCommand.FocusAddressBar,
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
    val currentRuntime by rememberUpdatedState(runtime)
    DisposableEffect(
        runtime.state.paneId,
        runtime.dragBindings.tabDropZones,
        runtime.dragBindings.fileDropZones,
    ) {
        onDispose {
            currentRuntime.clearPaneDropZones()
        }
    }
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
            .onPreviewKeyEvent { event -> handlePaneKeyEvent(event, runtime) }
            .focusable()
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
            !target.isSidebarTarget &&
            target.directoryEntryId == null &&
            target.targetDirectoryLocation == state.location
    return if (paneTargeted) {
        LocalOnyxPalette.current.rowHoverBackground.copy(alpha = 0.28f)
    } else {
        Color.Transparent
    }
}

/**
 * 判断面板悬浮筛选框当前是否同时处于可见且持有键盘焦点的有效状态。
 */
private fun PaneSurfaceRuntime.isFilterEffectiveFocused(): Boolean {
    val filterOverlayVisible = state.filterInputVisible || state.filterQuery.isNotEmpty()
    return filterOverlayVisible && ui.filterFocused
}

/**
 * 判断面板内是否有文本输入控件（地址栏编辑、有效筛选框、命令面板）正在独占键盘输入。
 */
private fun PaneSurfaceRuntime.isTextInputOwningKeyboard(): Boolean {
    return ui.addressBarEditing || isFilterEffectiveFocused() || state.commandPaletteVisible
}

/**
 * 在活动面板、目录跳转、筛选收起、浮层关闭与内联编辑结束时恢复正确的键盘焦点。
 *
 * @param runtime 面板运行时控制器。
 */
@Composable
private fun PaneSurfaceFocusEffects(runtime: PaneSurfaceRuntime) {
    val inlineEditActive = runtime.state.inlineEditState != null
    val filterEffectiveFocused = runtime.isFilterEffectiveFocused()
    val commandPaletteVisible = runtime.state.commandPaletteVisible
    val contextMenuVisible = runtime.ui.contextMenuVisible
    val addressBarEditing = runtime.ui.addressBarEditing
    val modalOverlayVisible = runtime.model.modalOverlayVisible
    val textInputOwnsKeyboard = runtime.isTextInputOwningKeyboard()
    val blocksGlobalShortcuts = textInputOwnsKeyboard || inlineEditActive || contextMenuVisible
    val currentOnGlobalShortcutBlockChange = rememberUpdatedState(runtime.model.onGlobalShortcutBlockChange)
    DisposableEffect(blocksGlobalShortcuts) {
        currentOnGlobalShortcutBlockChange.value(blocksGlobalShortcuts)
        onDispose {
            currentOnGlobalShortcutBlockChange.value(false)
        }
    }
    var lastHandledAddressBarFocusRequestId by remember {
        mutableIntStateOf(runtime.model.addressBarFocusRequestId)
    }
    LaunchedEffect(runtime.model.addressBarFocusRequestId) {
        val requestId = runtime.model.addressBarFocusRequestId
        if (requestId > lastHandledAddressBarFocusRequestId) {
            lastHandledAddressBarFocusRequestId = requestId
            if (runtime.model.active) {
                runtime.focusAddressBar()
            }
        }
    }
    val overlayBlocksPaneFocus = textInputOwnsKeyboard || contextMenuVisible || modalOverlayVisible
    LaunchedEffect(
        runtime.model.active,
        runtime.state.activeTabId,
        runtime.state.location,
        runtime.model.windowActivationRequest,
    ) {
        // 侧栏、工具栏导航、筛选回车进入子目录及子窗口跳转唤醒均会触发此效应；活动面板必须在就绪后恢复文件快捷键焦点。
        if (runtime.model.active && !overlayBlocksPaneFocus) runtime.focusRequester.requestFocus()
    }
    LaunchedEffect(
        runtime.model.active,
        inlineEditActive,
        filterEffectiveFocused,
        commandPaletteVisible,
        contextMenuVisible,
        addressBarEditing,
        modalOverlayVisible,
        textInputOwnsKeyboard,
    ) {
        val inlineEditFinished = runtime.ui.inlineEditWasActive && !inlineEditActive
        val filterFinished = runtime.ui.filterWasFocused && !filterEffectiveFocused
        val commandPaletteFinished = runtime.ui.commandPaletteWasVisible && !commandPaletteVisible
        val contextMenuFinished = runtime.ui.contextMenuWasVisible && !contextMenuVisible
        val addressBarFinished = runtime.ui.addressBarWasEditing && !addressBarEditing
        val modalOverlayFinished = runtime.ui.modalOverlayWasVisible && !modalOverlayVisible
        val anyOverlayFinished = inlineEditFinished ||
            filterFinished ||
            commandPaletteFinished ||
            contextMenuFinished ||
            addressBarFinished ||
            modalOverlayFinished
        runtime.ui.inlineEditWasActive = inlineEditActive
        runtime.ui.filterWasFocused = filterEffectiveFocused
        runtime.ui.commandPaletteWasVisible = commandPaletteVisible
        runtime.ui.contextMenuWasVisible = contextMenuVisible
        runtime.ui.addressBarWasEditing = addressBarEditing
        runtime.ui.modalOverlayWasVisible = modalOverlayVisible
        if (runtime.model.active && anyOverlayFinished && !overlayBlocksPaneFocus) {
            // 等待浮层（如可聚焦 Popup 或 Quick Open 遮罩）完成卸载帧后，再次确认实时状态未被地址栏等输入框接管，再取回主面板键盘焦点。
            withFrameNanos { }
            val stillClearForPaneFocus = runtime.model.active &&
                !runtime.isTextInputOwningKeyboard() &&
                !runtime.ui.contextMenuVisible &&
                !runtime.model.modalOverlayVisible
            if (stillClearForPaneFocus) {
                runtime.focusRequester.requestFocus()
            }
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
    if (runtime.ui.contextMenuVisible) {
        if (event.key == Key.Escape) {
            runtime.ui.contextMenuVisible = false
        }
        // 右键菜单开启期间阻断所有面板快捷键与导航，防止误触 Delete / Enter 等破坏性操作。
        return true
    }
    val textInputOwnsKeyboard = runtime.isTextInputOwningKeyboard()
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
    val isAltDFocusAddressBar = event.isAltPressed &&
        !event.isCtrlPressed &&
        !event.isMetaPressed &&
        !event.isShiftPressed &&
        event.key == Key.D
    if (isAltDFocusAddressBar) {
        return runtime.executeCommand(OnyxCommand.FocusAddressBar)
    }
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
        Key.Enter, Key.NumPadEnter -> {
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
 * 处理方向键、首尾/翻页键、树形展开折叠与 Escape 等不属于可配置命令的导航按键。
 *
 * @param event 键盘事件。
 * @param runtime 面板运行时控制器。
 * @return 事件已消费时返回 `true`。
 */
private fun handlePaneNavigationKey(event: KeyEvent, runtime: PaneSurfaceRuntime): Boolean {
    val isGridMode = runtime.state.viewMode == ViewMode.GALLERY
    val rowStride = if (isGridMode) runtime.ui.gridColumnCount.coerceAtLeast(1) else 1
    val pageStep = runtime.ui.viewportPageSize.coerceAtLeast(1)
    val extendSelection = event.isShiftPressed
    val hasNonShiftModifier = event.isCtrlPressed || event.isMetaPressed || event.isAltPressed
    return when (event.key) {
        Key.DirectionDown -> {
            runtime.dispatch(PaneIntent.MoveSelection(rowStride, extendSelection))
            true
        }

        Key.DirectionUp -> {
            runtime.dispatch(PaneIntent.MoveSelection(-rowStride, extendSelection))
            true
        }

        Key.DirectionRight -> when {
            isGridMode -> {
                runtime.dispatch(PaneIntent.MoveSelection(1, extendSelection))
                true
            }

            !extendSelection && !hasNonShiftModifier -> handleDetailsTreeHorizontalKey(expand = true, runtime = runtime)
            else -> false
        }

        Key.DirectionLeft -> when {
            isGridMode -> {
                runtime.dispatch(PaneIntent.MoveSelection(-1, extendSelection))
                true
            }

            !extendSelection && !hasNonShiftModifier -> handleDetailsTreeHorizontalKey(expand = false, runtime = runtime)
            else -> false
        }

        Key.MoveHome -> {
            runtime.dispatch(PaneIntent.MoveSelectionToBoundary(toEnd = false, extendSelection = extendSelection))
            true
        }

        Key.MoveEnd -> {
            runtime.dispatch(PaneIntent.MoveSelectionToBoundary(toEnd = true, extendSelection = extendSelection))
            true
        }

        Key.PageDown -> {
            runtime.dispatch(PaneIntent.MoveSelection(pageStep, extendSelection))
            true
        }

        Key.PageUp -> {
            runtime.dispatch(PaneIntent.MoveSelection(-pageStep, extendSelection))
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
 * 处理详情树视图下的左/右方向键：右箭头展开折叠目录或进入首个子项，左箭头折叠已展开目录或跳回父目录条目。
 *
 * @param expand 为 `true` 表示按右键展开，为 `false` 表示按左键折叠/回跳父项。
 * @param runtime 面板运行时控制器。
 * @return 已消费按键时返回 `true`。
 */
private fun handleDetailsTreeHorizontalKey(expand: Boolean, runtime: PaneSurfaceRuntime): Boolean {
    val state = runtime.state
    if (state.entriesState !is PaneEntriesState.Ready || isTrashLocation(state.location)) return false
    val visibleEntries = state.visibleEntriesIncludingExpanded()
    if (visibleEntries.isEmpty()) return false
    val focusId = state.selectionFocusId ?: state.selectedEntryIds.singleOrNull() ?: return false
    val currentIndex = visibleEntries.indexOfFirst { it.id == focusId }
    val focusEntry = visibleEntries.getOrNull(currentIndex) ?: return false
    if (expand) {
        if (!focusEntry.isBrowsableDirectory()) return false
        if (focusEntry.location !in state.inlineExpandedLocations) {
            runtime.dispatch(PaneIntent.ToggleInlineExpand(focusEntry.location))
            return true
        }
        val nextEntry = visibleEntries.getOrNull(currentIndex + 1)
        if (nextEntry != null && nextEntry.parentLocation == focusEntry.location) {
            runtime.dispatch(PaneIntent.MoveSelection(1, false))
            return true
        }
        return false
    }
    if (focusEntry.location in state.inlineExpandedLocations) {
        runtime.dispatch(PaneIntent.ToggleInlineExpand(focusEntry.location))
        return true
    }
    val parentLocation = focusEntry.parentLocation ?: return false
    val parentIndex = visibleEntries.indexOfFirst { it.location == parentLocation }
    if (parentIndex >= 0 && parentIndex != currentIndex) {
        runtime.dispatch(PaneIntent.MoveSelection(parentIndex - currentIndex, false))
        return true
    }
    return false
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
    if (isTrashLocation(runtime.state.location)) {
        TrashActionBar(runtime)
        Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
    }
    PaneOperationFeedback(runtime)
    PaneDirectorySyncStatus(runtime)
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
                target = CommandPaletteTarget.BuiltIn(commandState.spec.command),
                label = stringResource(commandState.spec.label),
                shortcut = onyxShortcutHint(commandState.shortcut),
                iconKey = commandState.spec.iconKey,
                enabled = commandState.enabled,
            )
        }
    CommandPalettePopup(
        items = items + runtime.model.userCommands[UserCommandPlacement.COMMAND_PALETTE].orEmpty().map { command ->
            CommandPaletteItem(
                target = CommandPaletteTarget.Custom(command.id),
                label = command.name,
                shortcut = null,
                iconKey = org.jetbrains.jewel.ui.icons.AllIconsKeys.Actions.Execute,
                enabled = command.executable.isNotBlank(),
            )
        },
        onExecute = { target ->
            when (target) {
                is CommandPaletteTarget.BuiltIn -> if (runtime.executeCommand(target.command)) runtime.dispatch(
                    PaneIntent.HideCommandPalette
                )

                is CommandPaletteTarget.Custom -> {
                    runtime.model.actions.onUserCommand(target.id, null)
                    runtime.dispatch(PaneIntent.HideCommandPalette)
                }
            }
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
