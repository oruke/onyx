package com.oruke.onyx.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset
import com.oruke.onyx.app.component.ComputerEntriesState
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.computerContextMenu

/**
 * 记录设备菜单的界面锚点并激活所属面板，业务能力由组件菜单模型判断。
 *
 * @param entryId 右键设备或目录入口身份。
 * @param position 指针在窗口内容中的实际像素位置。
 */
internal fun PaneSurfaceRuntime.showComputerContextMenu(entryId: String, position: IntOffset) {
    model.onActivate()
    dispatch(PaneIntent.SelectEntry(entryId))
    ui.contextMenuOffset = position
    ui.contextMenuEntryIds = setOf(entryId)
    ui.contextMenuSections = emptyList()
    ui.contextMenuQueryToken++
    ui.contextMenuVisible = true
}

/**
 * 复用文件面板现有菜单、定位和滚动容器展示设备操作。
 *
 * @param runtime 当前面板状态与统一事件入口。
 */
@Composable
internal fun BoxScope.PaneComputerContextMenu(runtime: PaneSurfaceRuntime) {
    val computer = runtime.state.entriesState as? ComputerEntriesState ?: return
    PaneContextMenu(
        anchorOffset = runtime.ui.contextMenuOffset,
        model = computerContextMenu(computer, runtime.ui.contextMenuEntryIds.singleOrNull()),
        onCommand = { command ->
            if (runtime.menuCommands.execute(command, emptyList(), runtime.state.location))
                runtime.ui.contextMenuVisible = false
        },
        commandShortcuts = runtime.model.commandShortcuts,
        onClose = { runtime.ui.contextMenuVisible = false },
    )
}
