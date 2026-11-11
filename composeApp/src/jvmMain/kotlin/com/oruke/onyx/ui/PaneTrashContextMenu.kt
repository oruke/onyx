package com.oruke.onyx.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import com.oruke.onyx.app.component.trashContextMenu
import com.oruke.onyx.core.model.VFile

/**
 * 复用统一右键菜单弹层展示回收站条目操作与空白区域操作。
 *
 * @param runtime 当前面板状态与菜单命令控制器。
 * @param readyEntries 当前回收站已加载的全部条目。
 */
@Composable
internal fun BoxScope.PaneTrashContextMenu(
    runtime: PaneSurfaceRuntime,
    readyEntries: List<VFile>,
) {
    val targetEntries = readyEntries.filter { entry -> entry.id in runtime.ui.contextMenuEntryIds }
    PaneContextMenu(
        anchorOffset = runtime.ui.contextMenuOffset,
        model = trashContextMenu(
            entries = targetEntries,
            totalTrashCount = readyEntries.size,
        ),
        onCommand = { command ->
            if (runtime.menuCommands.execute(command, targetEntries, runtime.state.location)) {
                runtime.ui.contextMenuVisible = false
            }
        },
        commandShortcuts = runtime.model.commandShortcuts,
        onClose = { runtime.ui.contextMenuVisible = false },
    )
}
