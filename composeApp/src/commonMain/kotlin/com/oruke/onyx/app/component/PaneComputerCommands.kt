package com.oruke.onyx.app.component

/**
 * 设备概览的统一命令规则，快捷键、工具栏和菜单共享相同的文件写入限制。
 *
 * @param state 当前面板状态。
 * @param command 待判断命令。
 * @return 概览可以执行时返回 true。
 */
internal fun computerCommandEnabled(state: PaneState, command: PaneCommand): Boolean =
    when (command) {
        PaneCommand.OPEN_SELECTION,
        PaneCommand.OPEN_SELECTION_IN_NEW_TAB -> state.selectedEntryIds.size == 1
        PaneCommand.REFRESH,
        PaneCommand.FILTER,
        PaneCommand.SELECT_ALL,
        PaneCommand.TOGGLE_FAVORITE,
        PaneCommand.OPEN_SETTINGS,
        PaneCommand.COMMAND_PALETTE -> true
        else -> false
    }
