package com.oruke.onyx.app.component

/**
 * 回收站虚拟位置的统一命令启用规则：禁止新建、重命名、复制、剪切和粘贴，保留选择、过滤、永久删除与撤销重做。
 *
 * @param state 当前面板状态。
 * @param executionContext 跨组件撤销与重做上下文。
 * @param command 待判断命令。
 * @return 在回收站中允许执行时返回 `true`。
 */
internal fun trashCommandEnabled(
    state: PaneState,
    executionContext: PaneCommandExecutionContext,
    command: PaneCommand,
): Boolean =
    when (command) {
        PaneCommand.OPEN_SELECTION -> state.selectedEntryIds.size == 1
        PaneCommand.DELETE_SELECTION -> state.selectedEntryIds.isNotEmpty()
        PaneCommand.UNDO_LAST_OPERATION -> executionContext.canUndo
        PaneCommand.REDO_LAST_OPERATION -> executionContext.canRedo
        PaneCommand.REFRESH,
        PaneCommand.FILTER,
        PaneCommand.SELECT_ALL,
        PaneCommand.GO_UP,
        PaneCommand.TOGGLE_FAVORITE,
        PaneCommand.OPEN_SETTINGS,
        PaneCommand.COMMAND_PALETTE -> true
        else -> false
    }
