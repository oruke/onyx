package com.oruke.onyx.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.RootIntent
import com.oruke.onyx.app.component.RootState
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.ui.theme.paneState

/**
 * 可在主窗口任意子区域（侧栏、标题栏、状态栏、预览栏、文件面板）触发的窗口级全局命令。
 */
internal val WINDOW_GLOBAL_SHORTCUT_COMMANDS: List<OnyxCommand> = listOf(
    OnyxCommand.CommandPalette,
    OnyxCommand.QuickOpen,
    OnyxCommand.ShowSearchPanel,
    OnyxCommand.FocusAddressBar,
    OnyxCommand.TogglePreviewPane,
    OnyxCommand.OpenSettings,
    OnyxCommand.UndoLastOperation,
    OnyxCommand.RedoLastOperation,
)

/**
 * 追踪当前主窗口内是否存在文本输入框或右键菜单等独占键盘输入的控件。
 *
 * 使用轻量非重组集合存储，避免焦点切换触发整个根树重组。
 */
internal class WindowKeyboardFocusRegistry {
    private val blockingPanes = LinkedHashSet<PaneId>()

    /**
     * 更新指定面板是否正持有文本输入或局部浮层（地址栏、筛选框、内联编辑、右键菜单）。
     *
     * @param paneId 面板标识。
     * @param blocking 为 `true` 时阻止窗口级全局快捷键劫持键盘。
     */
    fun setPaneBlocking(paneId: PaneId, blocking: Boolean) {
        if (blocking) {
            blockingPanes.add(paneId)
        } else {
            blockingPanes.remove(paneId)
        }
    }

    /** 当前是否应阻断窗口级全局快捷键。 */
    fun isBlocking(): Boolean = blockingPanes.isNotEmpty()
}

/**
 * 处理主窗口级全局快捷键，并严格与模态对话框、Quick Open、命令面板及文本输入控件隔离。
 *
 * @param event 键盘预览事件。
 * @param state 当前根状态快照。
 * @param rootComponent 根业务组件。
 * @param focusRegistry 面板文本输入/局部浮层键盘独占登记表。
 * @return 事件已被全局快捷键消费时返回 `true`。
 */
internal fun handleWindowGlobalKeyEvent(
    event: KeyEvent,
    state: RootState,
    rootComponent: RootComponent,
    focusRegistry: WindowKeyboardFocusRegistry,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (state.dialogState != null || state.quickOpenState.visible) return false
    val activePaneState = state.paneState(state.activePane)
    if (activePaneState.commandPaletteVisible || activePaneState.inlineEditState != null) return false
    if (focusRegistry.isBlocking()) return false

    val isAltDFocusAddressBar = event.isAltPressed &&
        !event.isCtrlPressed &&
        !event.isMetaPressed &&
        !event.isShiftPressed &&
        event.key == Key.D
    if (isAltDFocusAddressBar) {
        rootComponent.dispatch(RootIntent.FocusActivePaneAddressBar)
        return true
    }

    val shortcuts = commandShortcutMapFromSettings(state.settings.commandShortcutOverrides)
    val matchedCommand = WINDOW_GLOBAL_SHORTCUT_COMMANDS.firstOrNull { command ->
        event.matchesCommand(command, shortcuts)
    } ?: return false

    return executeWindowGlobalCommand(matchedCommand, state, rootComponent)
}

/**
 * 将匹配到的窗口级全局命令派发到对应的根组件或活动面板组件。
 *
 * @param command 已命中的全局命令。
 * @param state 当前根状态快照。
 * @param rootComponent 根业务组件。
 * @return 命令已执行时返回 `true`。
 */
private fun executeWindowGlobalCommand(
    command: OnyxCommand,
    state: RootState,
    rootComponent: RootComponent,
): Boolean {
    return when (command) {
        OnyxCommand.QuickOpen -> {
            rootComponent.dispatch(RootIntent.ShowQuickOpen)
            true
        }

        OnyxCommand.ShowSearchPanel -> {
            rootComponent.dispatch(RootIntent.ShowSearchPanel)
            true
        }

        OnyxCommand.OpenSettings -> {
            rootComponent.dispatch(RootIntent.OpenSettings)
            true
        }

        OnyxCommand.FocusAddressBar -> {
            rootComponent.dispatch(RootIntent.FocusActivePaneAddressBar)
            true
        }

        OnyxCommand.TogglePreviewPane -> {
            rootComponent.dispatch(RootIntent.TogglePreviewPane)
            true
        }

        OnyxCommand.CommandPalette -> {
            val paneComponent = when (state.activePane) {
                PaneId.PRIMARY -> rootComponent.primaryPane
                PaneId.SECONDARY -> rootComponent.secondaryPane
            }
            paneComponent.dispatch(PaneIntent.ShowCommandPalette)
            true
        }

        OnyxCommand.UndoLastOperation -> {
            if (!state.operationHistoryState.canUndo) return false
            rootComponent.dispatch(RootIntent.UndoLastFileOperation)
            true
        }

        OnyxCommand.RedoLastOperation -> {
            if (!state.operationHistoryState.canRedo) return false
            rootComponent.dispatch(RootIntent.RedoLastFileOperation)
            true
        }

        else -> false
    }
}
