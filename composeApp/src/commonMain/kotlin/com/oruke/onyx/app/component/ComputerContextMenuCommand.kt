package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.StorageDeviceAction

/** 设备菜单的明确命令，继续通过已有统一菜单控制器派发。 */
internal data class ComputerContextMenuCommand(
    /** 不包含平台对象的面板设备事件。 */
    val intent: PaneDeviceIntent
) : PaneContextMenuCommand

/**
 * 由组件层组合设备菜单，文件创建、删除和传输命令不会出现在概览菜单中。
 *
 * @param state 当前概览内容。
 * @param entryId 右键目标身份；为空表示概览空白区域。
 * @return 可由现有菜单组件直接渲染的统一模型。
 */
internal fun computerContextMenu(
    state: ComputerEntriesState,
    entryId: String?,
): PaneContextMenuModel {
    val device = state.devices.firstOrNull { it.id == entryId }
    val nodes = buildList {
        if (device != null) {
            val enabled = device.id !in state.busyDeviceIds
            val canOpen =
                device.locations.isNotEmpty() || StorageDeviceAction.MOUNT in device.actions
            add(
                PaneContextMenuNode.Item(
                    "computer-open",
                    PaneContextMenuText.OPEN,
                    icon = PaneContextMenuIcon.OPEN,
                    enabled = enabled && canOpen,
                    command = ComputerContextMenuCommand(PaneDeviceIntent.Open(device.id)),
                )
            )
            add(
                PaneContextMenuNode.Item(
                    "computer-new-tab",
                    PaneContextMenuText.OPEN_IN_NEW_TAB,
                    icon = PaneContextMenuIcon.OPEN_IN_NEW_TAB,
                    enabled = enabled && canOpen,
                    command =
                        ComputerContextMenuCommand(PaneDeviceIntent.Open(device.id, newTab = true)),
                )
            )
            device.actions.forEach { action ->
                add(
                    PaneContextMenuNode.Item(
                        "computer-${action.name}",
                        text =
                            when (action) {
                                StorageDeviceAction.MOUNT -> PaneContextMenuText.DEVICE_MOUNT
                                StorageDeviceAction.UNMOUNT -> PaneContextMenuText.DEVICE_UNMOUNT
                                StorageDeviceAction.EJECT -> PaneContextMenuText.DEVICE_EJECT
                                StorageDeviceAction.PROPERTIES ->
                                    PaneContextMenuText.DEVICE_PROPERTIES
                                StorageDeviceAction.OPEN_SYSTEM ->
                                    PaneContextMenuText.DEVICE_OPEN_SYSTEM
                            },
                        icon = PaneContextMenuIcon.EXECUTE,
                        enabled = enabled,
                        command =
                            ComputerContextMenuCommand(PaneDeviceIntent.Execute(device.id, action)),
                    )
                )
            }
            add(PaneContextMenuNode.Divider)
        } else if (state.places.any { it.location == entryId }) {
            add(
                PaneContextMenuNode.Item(
                    "computer-open-place",
                    PaneContextMenuText.OPEN,
                    icon = PaneContextMenuIcon.OPEN,
                    command = PaneContextMenuCommand.Pane(PaneCommand.OPEN_SELECTION),
                )
            )
            add(
                PaneContextMenuNode.Item(
                    "computer-place-tab",
                    PaneContextMenuText.OPEN_IN_NEW_TAB,
                    icon = PaneContextMenuIcon.OPEN_IN_NEW_TAB,
                    command = PaneContextMenuCommand.Pane(PaneCommand.OPEN_SELECTION_IN_NEW_TAB),
                )
            )
        }
        add(
            PaneContextMenuNode.Item(
                "computer-refresh",
                PaneContextMenuText.REFRESH,
                icon = PaneContextMenuIcon.REFRESH,
                command = PaneContextMenuCommand.Pane(PaneCommand.REFRESH),
            )
        )
    }
    return PaneContextMenuModel(nodes)
}
