package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.VFileKind

/**
 * 面板级命令 ID，用于让快捷键、工具栏、命令面板和右键菜单共享同一套状态判断。
 */
internal enum class PaneCommand {
    OPEN_SELECTION,
    OPEN_SELECTION_IN_NEW_TAB,
    RENAME_SELECTION,
    NEW_FILE,
    NEW_DIRECTORY,
    DELETE_SELECTION,
    COPY_SELECTION,
    CUT_SELECTION,
    PASTE,
    UNDO_LAST_OPERATION,
    REDO_LAST_OPERATION,
    REFRESH,
    CLOSE_MENU,
    FILTER,
    CREATE_DIRECTORIES,
    SELECT_ALL,
    GO_UP,
    TOGGLE_FAVORITE,
    OPEN_SETTINGS,
    COMMAND_PALETTE,
}

/**
 * 面板命令执行时需要从 Root 或跨面板状态注入的能力。
 *
 * @property onBeginCreateDirectory 进入批量新建目录流程。
 * @property onCopySelection 暂存当前面板选择项为复制源。
 * @property onCutSelection 暂存当前面板选择项为移动源。
 * @property onPaste 将剪贴板或暂存文件操作粘贴到当前面板。
 * @property onUndo 撤销上一条文件操作。
 * @property onRedo 重做上一条文件操作。
 * @property onDeleteSelection 请求删除当前面板选择项。
 * @property onToggleFavoriteLocation 切换指定路径的收藏状态。
 * @property onOpenSettings 打开设置界面。
 */
internal data class PaneCommandExternalActions(
    val onBeginCreateDirectory: () -> Unit,
    val onCopySelection: () -> Unit,
    val onCutSelection: () -> Unit,
    val onPaste: () -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onDeleteSelection: () -> Unit,
    val onToggleFavoriteLocation: (String) -> Unit,
    val onOpenSettings: () -> Unit,
)

/**
 * 面板命令状态判断需要的跨组件上下文。
 *
 * @property canPaste 当前是否允许粘贴。
 * @property canUndo 当前是否允许撤销。
 * @property canRedo 当前是否允许重做。
 */
internal data class PaneCommandExecutionContext(
    val canPaste: Boolean,
    val canUndo: Boolean,
    val canRedo: Boolean,
)

/**
 * 面板命令控制器，集中处理命令启用状态与执行分发。
 *
 * @property state 当前面板状态快照。
 * @property component 面板组件，用于派发面板内 intent。
 * @property executionContext 跨组件命令状态上下文。
 * @property externalActions 需要 Root 或跨面板能力参与的动作集合。
 */
internal class PaneCommandController(
    private val state: PaneState,
    private val component: PaneComponent,
    private val executionContext: PaneCommandExecutionContext,
    private val externalActions: PaneCommandExternalActions,
) {
    /**
     * 派发面板 intent。
     *
     * @param intent 面板内 intent。
     */
    fun dispatch(intent: PaneIntent) {
        component.dispatch(intent)
    }

    /**
     * 打开当前面板的过滤输入框。
     */
    fun openFilterInput() {
        dispatch(PaneIntent.ShowFilterInput)
    }

    /**
     * 关闭当前面板的过滤输入框并清空过滤文本。
     */
    fun closeFilterInput() {
        dispatch(PaneIntent.HideFilterInput(clearQuery = true))
    }

    /**
     * 判断命令在当前面板状态下是否可执行。
     *
     * @param command 待判断的面板命令。
     * @return 可执行时返回 `true`。
     */
    fun isEnabled(command: PaneCommand): Boolean {
        val selectedCount = state.selectedEntryIds.size
        return when (command) {
            PaneCommand.OPEN_SELECTION,
            PaneCommand.RENAME_SELECTION -> selectedCount == 1

            PaneCommand.OPEN_SELECTION_IN_NEW_TAB -> selectedDirectoryCount() == 1

            PaneCommand.DELETE_SELECTION,
            PaneCommand.COPY_SELECTION,
            PaneCommand.CUT_SELECTION -> selectedCount > 0

            PaneCommand.PASTE -> executionContext.canPaste
            PaneCommand.UNDO_LAST_OPERATION -> executionContext.canUndo
            PaneCommand.REDO_LAST_OPERATION -> executionContext.canRedo
            PaneCommand.CLOSE_MENU,
            PaneCommand.CREATE_DIRECTORIES -> false

            PaneCommand.NEW_FILE,
            PaneCommand.NEW_DIRECTORY,
            PaneCommand.REFRESH,
            PaneCommand.FILTER,
            PaneCommand.SELECT_ALL,
            PaneCommand.GO_UP,
            PaneCommand.TOGGLE_FAVORITE,
            PaneCommand.OPEN_SETTINGS,
            PaneCommand.COMMAND_PALETTE -> true
        }
    }

    /**
     * 执行面板命令。
     *
     * @param command 待执行的面板命令。
     * @return 命令已处理时返回 `true`。
     */
    fun execute(command: PaneCommand): Boolean {
        return when (command) {
            PaneCommand.OPEN_SELECTION -> executeIfEnabled(command) {
                dispatch(PaneIntent.OpenSelectedEntry)
            }

            PaneCommand.OPEN_SELECTION_IN_NEW_TAB -> executeIfEnabled(command) {
                dispatch(PaneIntent.OpenSelectedInNewTab)
            }

            PaneCommand.RENAME_SELECTION -> executeIfEnabled(command) {
                dispatch(PaneIntent.BeginRename)
            }

            PaneCommand.NEW_DIRECTORY -> {
                externalActions.onBeginCreateDirectory()
                true
            }

            PaneCommand.NEW_FILE -> {
                dispatch(PaneIntent.BeginCreateFile)
                true
            }

            PaneCommand.COPY_SELECTION -> executeIfEnabled(command) {
                externalActions.onCopySelection()
            }

            PaneCommand.CUT_SELECTION -> executeIfEnabled(command) {
                externalActions.onCutSelection()
            }

            PaneCommand.PASTE -> executeIfEnabled(command) {
                externalActions.onPaste()
            }

            PaneCommand.UNDO_LAST_OPERATION -> executeIfEnabled(command) {
                externalActions.onUndo()
            }

            PaneCommand.REDO_LAST_OPERATION -> executeIfEnabled(command) {
                externalActions.onRedo()
            }

            PaneCommand.DELETE_SELECTION -> executeIfEnabled(command) {
                externalActions.onDeleteSelection()
            }

            PaneCommand.FILTER -> {
                openFilterInput()
                true
            }

            PaneCommand.REFRESH -> {
                dispatch(PaneIntent.Refresh)
                true
            }

            PaneCommand.SELECT_ALL -> {
                dispatch(PaneIntent.SelectAll)
                true
            }

            PaneCommand.GO_UP -> {
                dispatch(PaneIntent.GoUp)
                true
            }

            PaneCommand.TOGGLE_FAVORITE -> {
                externalActions.onToggleFavoriteLocation(state.location)
                true
            }

            PaneCommand.OPEN_SETTINGS -> {
                externalActions.onOpenSettings()
                true
            }

            PaneCommand.COMMAND_PALETTE -> {
                dispatch(PaneIntent.ShowCommandPalette)
                true
            }

            PaneCommand.CLOSE_MENU,
            PaneCommand.CREATE_DIRECTORIES -> false
        }
    }

    /**
     * 统计当前选中目录数量。
     *
     * @return 当前选中项中目录条目的数量。
     */
    private fun selectedDirectoryCount(): Int {
        val readyEntries = state.entriesState as? PaneEntriesState.Ready ?: return 0
        return readyEntries.entries.count { entry ->
            entry.id in state.selectedEntryIds && entry.kind == VFileKind.DIRECTORY
        }
    }

    /**
     * 只在命令启用时执行动作，保证快捷键、工具栏和命令面板不绕过状态来源。
     *
     * @param command 待执行命令。
     * @param action 具体命令动作。
     * @return 动作已执行时返回 `true`。
     */
    private fun executeIfEnabled(
        command: PaneCommand,
        action: () -> Unit,
    ): Boolean {
        if (!isEnabled(command)) return false
        action()
        return true
    }

}
