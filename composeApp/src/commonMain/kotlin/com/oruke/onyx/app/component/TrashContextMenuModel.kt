package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.VFile

/**
 * 回收站右键菜单专属动作定义，由根组件统一编排执行。
 */
internal sealed interface TrashContextMenuAction {
    /** 还原指定的回收站条目集合。 */
    data class Restore(val entryIds: Set<String>) : TrashContextMenuAction

    /** 还原回收站中的全部条目。 */
    data object RestoreAll : TrashContextMenuAction

    /** 打开单个回收站条目移入前的原始父目录。 */
    data class OpenOriginalLocation(val entry: VFile) : TrashContextMenuAction

    /** 永久删除指定的回收站条目集合。 */
    data class DeletePermanently(val entryIds: Set<String>) : TrashContextMenuAction

    /** 复制指定回收站条目的原始完整路径。 */
    data class CopyOriginalPath(val entries: List<VFile>) : TrashContextMenuAction

    /** 清空回收站中的所有条目。 */
    data object EmptyTrash : TrashContextMenuAction
}

/**
 * 接入统一面板右键菜单控制器的回收站命令包装。
 *
 * @property action 具体的回收站菜单动作。
 */
internal data class TrashContextMenuCommand(
    val action: TrashContextMenuAction,
) : PaneContextMenuCommand

/**
 * 构建回收站视图（`system://trash`）的右键菜单模型，区分选中条目菜单与空白区域菜单。
 *
 * @param entries 当前右键命中的回收站条目列表；为空表示空白区域右键。
 * @param totalTrashCount 当前回收站已加载的总条目数。
 * @return 可直接交由 `PaneContextMenu` 渲染的统一菜单模型。
 */
internal fun trashContextMenu(
    entries: List<VFile>,
    totalTrashCount: Int,
): PaneContextMenuModel {
    val nodes = buildList {
        if (entries.isNotEmpty()) {
            val targetIds = entries.mapTo(linkedSetOf()) { entry -> entry.id }
            val hasKnownOrigin = entries.any { entry -> entry.parentLocation != null }
            val singleEntry = entries.singleOrNull()
            add(
                PaneContextMenuNode.Item(
                    id = "trash-restore",
                    text = PaneContextMenuText.TRASH_RESTORE,
                    icon = PaneContextMenuIcon.UNDO,
                    enabled = hasKnownOrigin,
                    command = TrashContextMenuCommand(TrashContextMenuAction.Restore(targetIds)),
                ),
            )
            if (singleEntry != null) {
                add(
                    PaneContextMenuNode.Item(
                        id = "trash-open-original-location",
                        text = PaneContextMenuText.TRASH_OPEN_ORIGINAL_LOCATION,
                        icon = PaneContextMenuIcon.FOLDER,
                        enabled = singleEntry.parentLocation != null,
                        command = TrashContextMenuCommand(TrashContextMenuAction.OpenOriginalLocation(singleEntry)),
                    ),
                )
            }
            add(PaneContextMenuNode.Divider)
            add(
                PaneContextMenuNode.Item(
                    id = "trash-delete-permanently",
                    text = PaneContextMenuText.TRASH_DELETE_PERMANENTLY,
                    icon = PaneContextMenuIcon.DELETE,
                    enabled = true,
                    command = TrashContextMenuCommand(TrashContextMenuAction.DeletePermanently(targetIds)),
                ),
            )
            add(PaneContextMenuNode.Divider)
            add(
                PaneContextMenuNode.Item(
                    id = "trash-copy-name",
                    text = PaneContextMenuText.COPY_NAME,
                    icon = PaneContextMenuIcon.COPY,
                    enabled = true,
                    command = PaneContextMenuCommand.CopyName,
                ),
            )
            add(
                PaneContextMenuNode.Item(
                    id = "trash-copy-original-path",
                    text = PaneContextMenuText.TRASH_COPY_ORIGINAL_PATH,
                    icon = PaneContextMenuIcon.COPY,
                    enabled = hasKnownOrigin,
                    command = TrashContextMenuCommand(TrashContextMenuAction.CopyOriginalPath(entries)),
                ),
            )
            add(PaneContextMenuNode.Divider)
            add(
                PaneContextMenuNode.Item(
                    id = "trash-empty",
                    text = PaneContextMenuText.TRASH_EMPTY,
                    icon = PaneContextMenuIcon.DELETE,
                    enabled = totalTrashCount > 0,
                    command = TrashContextMenuCommand(TrashContextMenuAction.EmptyTrash),
                ),
            )
        } else {
            add(
                PaneContextMenuNode.Item(
                    id = "trash-restore-all",
                    text = PaneContextMenuText.TRASH_RESTORE_ALL,
                    icon = PaneContextMenuIcon.UNDO,
                    enabled = totalTrashCount > 0,
                    command = TrashContextMenuCommand(TrashContextMenuAction.RestoreAll),
                ),
            )
            add(
                PaneContextMenuNode.Item(
                    id = "trash-empty",
                    text = PaneContextMenuText.TRASH_EMPTY,
                    icon = PaneContextMenuIcon.DELETE,
                    enabled = totalTrashCount > 0,
                    command = TrashContextMenuCommand(TrashContextMenuAction.EmptyTrash),
                ),
            )
            add(PaneContextMenuNode.Divider)
        }
        add(
            PaneContextMenuNode.Item(
                id = "trash-refresh",
                text = PaneContextMenuText.REFRESH,
                icon = PaneContextMenuIcon.REFRESH,
                command = PaneContextMenuCommand.Pane(PaneCommand.REFRESH),
            ),
        )
        add(
            PaneContextMenuNode.Item(
                id = "trash-close",
                text = PaneContextMenuText.CLOSE_MENU,
                icon = PaneContextMenuIcon.CLOSE,
                command = PaneContextMenuCommand.Pane(PaneCommand.CLOSE_MENU),
            ),
        )
    }
    return PaneContextMenuModel(nodes)
}
