package com.oruke.onyx.ui

import androidx.compose.runtime.Composable
import com.oruke.onyx.app.component.PaneContextMenuCommand
import com.oruke.onyx.app.component.PaneContextMenuIcon
import com.oruke.onyx.app.component.PaneContextMenuNode
import com.oruke.onyx.app.component.PaneContextMenuText
import com.oruke.onyx.app.component.TrashContextMenuCommand
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_archive
import onyx.composeapp.generated.resources.action_batch_rename
import onyx.composeapp.generated.resources.action_close_menu
import onyx.composeapp.generated.resources.action_copy
import onyx.composeapp.generated.resources.action_copy_name
import onyx.composeapp.generated.resources.action_copy_path
import onyx.composeapp.generated.resources.action_create_zip
import onyx.composeapp.generated.resources.action_cut
import onyx.composeapp.generated.resources.action_delete_selected
import onyx.composeapp.generated.resources.action_device_eject
import onyx.composeapp.generated.resources.action_device_mount
import onyx.composeapp.generated.resources.action_device_open_system
import onyx.composeapp.generated.resources.action_device_properties
import onyx.composeapp.generated.resources.action_device_unmount
import onyx.composeapp.generated.resources.action_extract_here
import onyx.composeapp.generated.resources.action_extract_smart
import onyx.composeapp.generated.resources.action_extract_to_directory
import onyx.composeapp.generated.resources.action_new_directory
import onyx.composeapp.generated.resources.action_new_file
import onyx.composeapp.generated.resources.action_open
import onyx.composeapp.generated.resources.action_open_in_new_tab
import onyx.composeapp.generated.resources.action_open_in_new_window
import onyx.composeapp.generated.resources.action_open_terminal
import onyx.composeapp.generated.resources.action_open_with
import onyx.composeapp.generated.resources.action_open_with_other
import onyx.composeapp.generated.resources.action_paste
import onyx.composeapp.generated.resources.action_redo
import onyx.composeapp.generated.resources.action_refresh_active
import onyx.composeapp.generated.resources.action_rename
import onyx.composeapp.generated.resources.action_trash_copy_original_path
import onyx.composeapp.generated.resources.action_trash_delete_permanently
import onyx.composeapp.generated.resources.action_trash_empty
import onyx.composeapp.generated.resources.action_trash_open_original_location
import onyx.composeapp.generated.resources.action_trash_restore
import onyx.composeapp.generated.resources.action_trash_restore_all
import onyx.composeapp.generated.resources.action_undo
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * 将菜单项文案语义解析为可显示文本。
 *
 * @return 当前语言下的菜单文本。
 */
@Composable
internal fun PaneContextMenuNode.Item.contextMenuText(): String {
    return text?.stringResourceValue() ?: displayName.orEmpty()
}

/**
 * 将内置菜单文案语义映射到资源。
 *
 * @return 字符串资源。
 */
@Suppress("CyclomaticComplexMethod") // 穷举菜单文案到资源键的声明式映射。
internal fun PaneContextMenuText.stringResourceKey(): StringResource {
    return when (this) {
        PaneContextMenuText.DEVICE_MOUNT -> Res.string.action_device_mount
        PaneContextMenuText.DEVICE_UNMOUNT -> Res.string.action_device_unmount
        PaneContextMenuText.DEVICE_EJECT -> Res.string.action_device_eject
        PaneContextMenuText.DEVICE_PROPERTIES -> Res.string.action_device_properties
        PaneContextMenuText.DEVICE_OPEN_SYSTEM -> Res.string.action_device_open_system
        PaneContextMenuText.OPEN -> Res.string.action_open
        PaneContextMenuText.OPEN_IN_NEW_TAB -> Res.string.action_open_in_new_tab
        PaneContextMenuText.OPEN_IN_NEW_WINDOW -> Res.string.action_open_in_new_window
        PaneContextMenuText.OPEN_WITH -> Res.string.action_open_with
        PaneContextMenuText.OPEN_WITH_OTHER -> Res.string.action_open_with_other
        PaneContextMenuText.RENAME -> Res.string.action_rename
        PaneContextMenuText.NEW_FILE -> Res.string.action_new_file
        PaneContextMenuText.NEW_DIRECTORY -> Res.string.action_new_directory
        PaneContextMenuText.DELETE_SELECTED -> Res.string.action_delete_selected
        PaneContextMenuText.ARCHIVE -> Res.string.action_archive
        PaneContextMenuText.EXTRACT_HERE -> Res.string.action_extract_here
        PaneContextMenuText.EXTRACT_TO_DIRECTORY -> Res.string.action_extract_to_directory
        PaneContextMenuText.EXTRACT_SMART -> Res.string.action_extract_smart
        PaneContextMenuText.CREATE_ZIP -> Res.string.action_create_zip
        PaneContextMenuText.BATCH_RENAME -> Res.string.action_batch_rename
        PaneContextMenuText.COPY_NAME -> Res.string.action_copy_name
        PaneContextMenuText.COPY_PATH -> Res.string.action_copy_path
        PaneContextMenuText.COPY -> Res.string.action_copy
        PaneContextMenuText.CUT -> Res.string.action_cut
        PaneContextMenuText.PASTE -> Res.string.action_paste
        PaneContextMenuText.UNDO -> Res.string.action_undo
        PaneContextMenuText.REDO -> Res.string.action_redo
        PaneContextMenuText.REFRESH -> Res.string.action_refresh_active
        PaneContextMenuText.OPEN_TERMINAL -> Res.string.action_open_terminal
        PaneContextMenuText.CLOSE_MENU -> Res.string.action_close_menu
        PaneContextMenuText.TRASH_RESTORE -> Res.string.action_trash_restore
        PaneContextMenuText.TRASH_RESTORE_ALL -> Res.string.action_trash_restore_all
        PaneContextMenuText.TRASH_OPEN_ORIGINAL_LOCATION -> Res.string.action_trash_open_original_location
        PaneContextMenuText.TRASH_DELETE_PERMANENTLY -> Res.string.action_trash_delete_permanently
        PaneContextMenuText.TRASH_COPY_ORIGINAL_PATH -> Res.string.action_trash_copy_original_path
        PaneContextMenuText.TRASH_EMPTY -> Res.string.action_trash_empty
    }
}

/**
 * 读取内置菜单文案的当前语言字符串。
 *
 * @return 当前语言字符串。
 */
@Composable
internal fun PaneContextMenuText.stringResourceValue(): String {
    return stringResource(stringResourceKey())
}

/**
 * 将统一菜单图标语义映射到 Jewel 图标。
 *
 * @param iconPath 平台动态图标路径或标识，用于优先渲染系统菜单图标。
 * @return Jewel 图标键。
 */
@Suppress("CyclomaticComplexMethod") // 穷举菜单图标到 Jewel 图标的声明式映射。
internal fun PaneContextMenuIcon.toIconKey(iconPath: String?): IconKey {
    return when (this) {
        PaneContextMenuIcon.OPEN -> AllIconsKeys.Actions.MenuOpen
        PaneContextMenuIcon.OPEN_IN_NEW_TAB -> AllIconsKeys.Actions.OpenNewTab
        PaneContextMenuIcon.EDIT -> AllIconsKeys.Actions.Edit
        PaneContextMenuIcon.FILE -> AllIconsKeys.FileTypes.Any_type
        PaneContextMenuIcon.FOLDER -> AllIconsKeys.Nodes.Folder
        PaneContextMenuIcon.DELETE -> AllIconsKeys.General.Delete
        PaneContextMenuIcon.ARCHIVE -> AllIconsKeys.FileTypes.Archive
        PaneContextMenuIcon.EXTRACT -> AllIconsKeys.Nodes.ExtractedFolder
        PaneContextMenuIcon.COPY -> AllIconsKeys.Actions.Copy
        PaneContextMenuIcon.CUT -> AllIconsKeys.Actions.MenuCut
        PaneContextMenuIcon.PASTE -> AllIconsKeys.Actions.MenuPaste
        PaneContextMenuIcon.UNDO -> AllIconsKeys.Actions.Back
        PaneContextMenuIcon.REDO -> AllIconsKeys.Actions.Forward
        PaneContextMenuIcon.REFRESH -> AllIconsKeys.Actions.Refresh
        PaneContextMenuIcon.TERMINAL -> AllIconsKeys.Debugger.Console
        PaneContextMenuIcon.CLOSE -> AllIconsKeys.Actions.Close
        PaneContextMenuIcon.OPEN_WITH -> AllIconsKeys.General.OpenDisk
        PaneContextMenuIcon.EXECUTE -> {
            if (iconPath.isNullOrBlank()) AllIconsKeys.Actions.Execute else AllIconsKeys.FileTypes.Any_type
        }
    }
}

/**
 * 解析菜单命令对应的 UI 快捷键命令。
 *
 * @return 可展示快捷键的 UI 命令。
 */
internal fun PaneContextMenuCommand.shortcutCommandOrNull(): OnyxCommand? {
    return when (this) {
        is com.oruke.onyx.app.component.ComputerContextMenuCommand -> null
        is TrashContextMenuCommand -> null
        is PaneContextMenuCommand.Pane -> command.toOnyxCommand()
        PaneContextMenuCommand.BatchRename,
        PaneContextMenuCommand.ExtractSelection,
        PaneContextMenuCommand.ExtractToDirectory,
        PaneContextMenuCommand.ExtractSmart,
        PaneContextMenuCommand.CreateZipArchive,
        PaneContextMenuCommand.CopyName,
        PaneContextMenuCommand.CopyPath,
        PaneContextMenuCommand.OpenTerminal,
        is PaneContextMenuCommand.FileContext,
        is PaneContextMenuCommand.UserCommand -> null
    }
}
