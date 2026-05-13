package com.oruke.onyx.ui

import com.oruke.onyx.app.component.PaneComponent
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.PaneState

/**
 * 面板命令控制器，集中处理命令启用判断与命令执行分发。
 *
 * @param state 当前面板状态。
 * @param component 面板组件，用于派发领域意图。
 * @param actions 需要 Root 或跨面板能力参与的操作集合。
 * @param canPaste 当前是否允许粘贴。
 * @param onFilterClosed 过滤输入关闭后的 UI 焦点回调。
 */
internal class PaneCommandController(
    private val state: PaneState,
    private val component: PaneComponent,
    private val actions: PaneActions,
    private val canPaste: Boolean,
    private val onFilterClosed: () -> Unit,
) {
    /**
     * 派发面板意图。
     *
     * @param intent 面板意图。
     */
    fun dispatch(intent: PaneIntent) {
        component.dispatch(intent)
    }

    /**
     * 打开过滤输入框。
     */
    fun openFilterInput() {
        dispatch(PaneIntent.ShowFilterInput)
    }

    /**
     * 关闭过滤输入框并清空过滤文本。
     */
    fun closeFilterInput() {
        dispatch(PaneIntent.HideFilterInput(clearQuery = true))
        onFilterClosed()
    }

    /**
     * 判断命令是否可执行。
     *
     * @param command 待检查命令。
     * @return 可执行时返回 true。
     */
    fun isEnabled(command: OnyxCommand): Boolean {
        val selectedCount = state.selectedEntryIds.size
        return when (command) {
            OnyxCommand.OpenSelection,
            OnyxCommand.RenameSelection -> selectedCount == 1

            OnyxCommand.DeleteSelection,
            OnyxCommand.CopySelection,
            OnyxCommand.CutSelection -> selectedCount > 0

            OnyxCommand.Paste -> canPaste
            OnyxCommand.UndoLastOperation -> actions.canUndo
            OnyxCommand.RedoLastOperation -> actions.canRedo
            OnyxCommand.CloseMenu,
            OnyxCommand.CreateDirectories -> false

            else -> true
        }
    }

    /**
     * 执行面板命令。
     *
     * @param command 待执行命令。
     * @return 命令已处理时返回 true。
     */
    fun execute(command: OnyxCommand): Boolean {
        return when (command) {
            OnyxCommand.OpenSelection -> executeIfEnabled(command) {
                dispatch(PaneIntent.OpenSelectedEntry)
            }

            OnyxCommand.RenameSelection -> executeIfEnabled(command) {
                dispatch(PaneIntent.BeginRename)
            }

            OnyxCommand.NewDirectory -> {
                actions.onBeginCreateDirectory()
                true
            }

            OnyxCommand.NewFile -> {
                dispatch(PaneIntent.BeginCreateFile)
                true
            }

            OnyxCommand.CopySelection -> executeIfEnabled(command) {
                actions.onCopySelection()
            }

            OnyxCommand.CutSelection -> executeIfEnabled(command) {
                actions.onCutSelection()
            }

            OnyxCommand.Paste -> executeIfEnabled(command) {
                actions.onPaste()
            }

            OnyxCommand.UndoLastOperation -> executeIfEnabled(command) {
                actions.onUndo()
            }

            OnyxCommand.RedoLastOperation -> executeIfEnabled(command) {
                actions.onRedo()
            }

            OnyxCommand.DeleteSelection -> executeIfEnabled(command) {
                actions.onDeleteSelection()
            }

            OnyxCommand.Filter -> {
                openFilterInput()
                true
            }

            OnyxCommand.Refresh -> {
                dispatch(PaneIntent.Refresh)
                true
            }

            OnyxCommand.SelectAll -> {
                dispatch(PaneIntent.SelectAll)
                true
            }

            OnyxCommand.GoUp -> {
                dispatch(PaneIntent.GoUp)
                true
            }

            OnyxCommand.ToggleFavorite -> {
                actions.onToggleFavoriteLocation(state.location)
                true
            }

            OnyxCommand.OpenSettings -> {
                actions.onOpenSettings()
                true
            }

            OnyxCommand.CommandPalette -> {
                dispatch(PaneIntent.ShowCommandPalette)
                true
            }

            OnyxCommand.CloseMenu,
            OnyxCommand.CreateDirectories -> false
        }
    }

    /**
     * 只有命令启用时才执行动作，避免快捷键和命令面板绕过启用状态。
     *
     * @param command 待执行命令。
     * @param action 具体动作。
     * @return 动作已执行时返回 true。
     */
    private fun executeIfEnabled(
        command: OnyxCommand,
        action: () -> Unit,
    ): Boolean {
        if (!isEnabled(command)) return false
        action()
        return true
    }
}
