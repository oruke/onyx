package com.oruke.onyx.ui.theme

import androidx.compose.runtime.Composable
import com.oruke.onyx.core.model.I18nMessage
import onyx.composeapp.generated.resources.msg_system_clipboard_unavailable
import onyx.composeapp.generated.resources.msg_transfer_clipboard_clear_failed
import onyx.composeapp.generated.resources.msg_transfer_skipped_items
import onyx.composeapp.generated.resources.msg_inline_edit_target_unavailable
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.ui.vfsProtocolLabel
import com.oruke.onyx.ui.vfsProviderCapabilityLabel
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsProviderCapability
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.msg_batch_rename_invalid_counter
import onyx.composeapp.generated.resources.msg_batch_rename_invalid_pattern
import onyx.composeapp.generated.resources.msg_rename_preset_name_required
import onyx.composeapp.generated.resources.msg_user_command_invalid
import onyx.composeapp.generated.resources.msg_user_command_local_directory_required
import onyx.composeapp.generated.resources.msg_user_command_launch_failed
import onyx.composeapp.generated.resources.msg_user_command_exit_failed
import onyx.composeapp.generated.resources.msg_vfs_location_unsupported
import onyx.composeapp.generated.resources.msg_search_content_unsupported
import onyx.composeapp.generated.resources.msg_file_name_empty
import onyx.composeapp.generated.resources.msg_file_name_path_separator
import onyx.composeapp.generated.resources.msg_directory_path_relative_required
import onyx.composeapp.generated.resources.msg_directory_path_empty
import onyx.composeapp.generated.resources.msg_directory_path_invalid_segments
import onyx.composeapp.generated.resources.msg_path_outside_current_location
import onyx.composeapp.generated.resources.msg_location_not_directory
import onyx.composeapp.generated.resources.msg_directory_unavailable
import onyx.composeapp.generated.resources.msg_transfer_same_path
import onyx.composeapp.generated.resources.msg_transfer_into_self
import onyx.composeapp.generated.resources.msg_system_open_target_unavailable
import onyx.composeapp.generated.resources.msg_desktop_integration_unavailable
import onyx.composeapp.generated.resources.msg_system_open_unsupported
import onyx.composeapp.generated.resources.msg_terminal_location_not_directory
import onyx.composeapp.generated.resources.msg_terminal_launch_failed
import onyx.composeapp.generated.resources.msg_archive_entry_location_invalid
import onyx.composeapp.generated.resources.msg_archive_entry_path_empty
import onyx.composeapp.generated.resources.msg_archive_entry_not_found
import onyx.composeapp.generated.resources.msg_archive_entry_empty
import onyx.composeapp.generated.resources.action_batch_rename
import onyx.composeapp.generated.resources.action_create_zip
import onyx.composeapp.generated.resources.action_extract_here
import onyx.composeapp.generated.resources.action_extract_to_directory
import onyx.composeapp.generated.resources.action_extract_smart
import onyx.composeapp.generated.resources.msg_batch_rename_files
import onyx.composeapp.generated.resources.msg_batch_rename_starting
import onyx.composeapp.generated.resources.msg_batch_renamed_files
import onyx.composeapp.generated.resources.msg_cancelled
import onyx.composeapp.generated.resources.msg_copied_items
import onyx.composeapp.generated.resources.msg_copy_failed
import onyx.composeapp.generated.resources.msg_copy_items
import onyx.composeapp.generated.resources.msg_cross_provider_transfer_unsupported
import onyx.composeapp.generated.resources.msg_create_folder_failed
import onyx.composeapp.generated.resources.msg_create_folders
import onyx.composeapp.generated.resources.msg_created_folders
import onyx.composeapp.generated.resources.msg_compress_failed
import onyx.composeapp.generated.resources.msg_compress_items
import onyx.composeapp.generated.resources.msg_compressed_items
import onyx.composeapp.generated.resources.msg_delete_failed
import onyx.composeapp.generated.resources.msg_delete_items
import onyx.composeapp.generated.resources.msg_deleted_items
import onyx.composeapp.generated.resources.msg_deleted_items_without_undo
import onyx.composeapp.generated.resources.msg_extract_failed
import onyx.composeapp.generated.resources.msg_extract_items
import onyx.composeapp.generated.resources.msg_extracted_items
import onyx.composeapp.generated.resources.msg_load_settings_failed
import onyx.composeapp.generated.resources.msg_move_failed
import onyx.composeapp.generated.resources.msg_move_items
import onyx.composeapp.generated.resources.msg_moved_items
import onyx.composeapp.generated.resources.msg_restore_session_failed
import onyx.composeapp.generated.resources.msg_vfs_authentication_required
import onyx.composeapp.generated.resources.msg_vfs_authentication_rejected
import onyx.composeapp.generated.resources.msg_vfs_permission_denied
import onyx.composeapp.generated.resources.msg_vfs_not_found
import onyx.composeapp.generated.resources.msg_vfs_already_exists
import onyx.composeapp.generated.resources.msg_vfs_network_failure
import onyx.composeapp.generated.resources.msg_vfs_unsupported_operation
import onyx.composeapp.generated.resources.msg_string_literal
import onyx.composeapp.generated.resources.msg_unknown_error
import onyx.composeapp.generated.resources.msg_search_no_eligible_roots
import onyx.composeapp.generated.resources.msg_archive_password_invalid
import onyx.composeapp.generated.resources.msg_copy_history_changed
import onyx.composeapp.generated.resources.msg_copy_history_unavailable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * 按当前界面语言解析业务消息及协议、能力参数。
 *
 * @return 使用当前 Compose 资源环境格式化的完整消息。
 */
@Composable
@Suppress("SpreadOperator") // Compose 资源 API 只接受 vararg，消息参数在模型层以列表存储。
internal fun I18nMessage.resolve(): String {
    val localizedArgs = args.map { argument ->
        when (argument) {
            is VfsProtocol -> vfsProtocolLabel(argument)
            is VfsProviderCapability -> vfsProviderCapabilityLabel(argument)
            else -> argument
        }
    }
    return stringResource(key.toStringResource(), *localizedArgs.toTypedArray())
}

/**
 * 将业务消息键映射到共享的三语资源。
 *
 * @return 当前消息对应的资源标识。
 */
@Suppress("CyclomaticComplexMethod") // 穷举枚举到资源键的声明式映射，不包含业务分支。
private fun MessageKey.toStringResource(): StringResource {
    return when (this) {
        MessageKey.MSG_COPY_HISTORY_CHANGED -> Res.string.msg_copy_history_changed
        MessageKey.MSG_BATCH_RENAME_INVALID_COUNTER -> Res.string.msg_batch_rename_invalid_counter
        MessageKey.MSG_BATCH_RENAME_INVALID_PATTERN -> Res.string.msg_batch_rename_invalid_pattern
        MessageKey.MSG_RENAME_PRESET_NAME_REQUIRED -> Res.string.msg_rename_preset_name_required
        MessageKey.MSG_USER_COMMAND_INVALID -> Res.string.msg_user_command_invalid
        MessageKey.MSG_USER_COMMAND_LOCAL_DIRECTORY_REQUIRED -> Res.string.msg_user_command_local_directory_required
        MessageKey.MSG_USER_COMMAND_LAUNCH_FAILED -> Res.string.msg_user_command_launch_failed
        MessageKey.MSG_USER_COMMAND_EXIT_FAILED -> Res.string.msg_user_command_exit_failed
        MessageKey.MSG_COPY_HISTORY_UNAVAILABLE -> Res.string.msg_copy_history_unavailable
        MessageKey.ACTION_BATCH_RENAME -> Res.string.action_batch_rename
        MessageKey.ACTION_CREATE_ZIP -> Res.string.action_create_zip
        MessageKey.ACTION_EXTRACT_HERE -> Res.string.action_extract_here
        MessageKey.ACTION_EXTRACT_TO_DIRECTORY -> Res.string.action_extract_to_directory
        MessageKey.ACTION_EXTRACT_SMART -> Res.string.action_extract_smart
        MessageKey.MSG_BATCH_RENAME_FILES -> Res.string.msg_batch_rename_files
        MessageKey.MSG_BATCH_RENAME_STARTING -> Res.string.msg_batch_rename_starting
        MessageKey.MSG_BATCH_RENAMED_FILES -> Res.string.msg_batch_renamed_files
        MessageKey.MSG_CANCELLED -> Res.string.msg_cancelled
        MessageKey.MSG_COPIED_ITEMS -> Res.string.msg_copied_items
        MessageKey.MSG_COPY_FAILED -> Res.string.msg_copy_failed
        MessageKey.MSG_COPY_ITEMS -> Res.string.msg_copy_items
        MessageKey.MSG_CROSS_PROVIDER_TRANSFER_UNSUPPORTED -> Res.string.msg_cross_provider_transfer_unsupported
        MessageKey.MSG_CREATE_FOLDER_FAILED -> Res.string.msg_create_folder_failed
        MessageKey.MSG_CREATE_FOLDERS -> Res.string.msg_create_folders
        MessageKey.MSG_CREATED_FOLDERS -> Res.string.msg_created_folders
        MessageKey.MSG_COMPRESS_FAILED -> Res.string.msg_compress_failed
        MessageKey.MSG_COMPRESS_ITEMS -> Res.string.msg_compress_items
        MessageKey.MSG_COMPRESSED_ITEMS -> Res.string.msg_compressed_items
        MessageKey.MSG_DELETE_FAILED -> Res.string.msg_delete_failed
        MessageKey.MSG_DELETE_ITEMS -> Res.string.msg_delete_items
        MessageKey.MSG_DELETED_ITEMS -> Res.string.msg_deleted_items
        MessageKey.MSG_DELETED_ITEMS_WITHOUT_UNDO -> Res.string.msg_deleted_items_without_undo
        MessageKey.MSG_EXTRACT_FAILED -> Res.string.msg_extract_failed
        MessageKey.MSG_EXTRACT_ITEMS -> Res.string.msg_extract_items
        MessageKey.MSG_EXTRACTED_ITEMS -> Res.string.msg_extracted_items
        MessageKey.MSG_LOAD_SETTINGS_FAILED -> Res.string.msg_load_settings_failed
        MessageKey.MSG_MOVE_FAILED -> Res.string.msg_move_failed
        MessageKey.MSG_MOVE_ITEMS -> Res.string.msg_move_items
        MessageKey.MSG_MOVED_ITEMS -> Res.string.msg_moved_items
        MessageKey.MSG_RESTORE_SESSION_FAILED -> Res.string.msg_restore_session_failed
        MessageKey.MSG_VFS_AUTHENTICATION_REQUIRED -> Res.string.msg_vfs_authentication_required
        MessageKey.MSG_VFS_AUTHENTICATION_REJECTED -> Res.string.msg_vfs_authentication_rejected
        MessageKey.MSG_VFS_PERMISSION_DENIED -> Res.string.msg_vfs_permission_denied
        MessageKey.MSG_VFS_NOT_FOUND -> Res.string.msg_vfs_not_found
        MessageKey.MSG_VFS_ALREADY_EXISTS -> Res.string.msg_vfs_already_exists
        MessageKey.MSG_VFS_NETWORK_FAILURE -> Res.string.msg_vfs_network_failure
        MessageKey.MSG_VFS_UNSUPPORTED_OPERATION -> Res.string.msg_vfs_unsupported_operation
        MessageKey.MSG_VFS_LOCATION_UNSUPPORTED -> Res.string.msg_vfs_location_unsupported
        MessageKey.MSG_STRING_LITERAL -> Res.string.msg_string_literal
        MessageKey.MSG_UNKNOWN_ERROR -> Res.string.msg_unknown_error
        MessageKey.MSG_SYSTEM_CLIPBOARD_UNAVAILABLE -> Res.string.msg_system_clipboard_unavailable
        MessageKey.MSG_TRANSFER_CLIPBOARD_CLEAR_FAILED -> Res.string.msg_transfer_clipboard_clear_failed
        MessageKey.MSG_TRANSFER_SKIPPED_ITEMS -> Res.string.msg_transfer_skipped_items
        MessageKey.MSG_INLINE_EDIT_TARGET_UNAVAILABLE -> Res.string.msg_inline_edit_target_unavailable
        MessageKey.MSG_SEARCH_NO_ELIGIBLE_ROOTS -> Res.string.msg_search_no_eligible_roots
        MessageKey.MSG_ARCHIVE_PASSWORD_INVALID -> Res.string.msg_archive_password_invalid
        MessageKey.MSG_SEARCH_CONTENT_UNSUPPORTED -> Res.string.msg_search_content_unsupported
        MessageKey.MSG_FILE_NAME_EMPTY -> Res.string.msg_file_name_empty
        MessageKey.MSG_FILE_NAME_PATH_SEPARATOR -> Res.string.msg_file_name_path_separator
        MessageKey.MSG_DIRECTORY_PATH_RELATIVE_REQUIRED -> Res.string.msg_directory_path_relative_required
        MessageKey.MSG_DIRECTORY_PATH_EMPTY -> Res.string.msg_directory_path_empty
        MessageKey.MSG_DIRECTORY_PATH_INVALID_SEGMENTS -> Res.string.msg_directory_path_invalid_segments
        MessageKey.MSG_PATH_OUTSIDE_CURRENT_LOCATION -> Res.string.msg_path_outside_current_location
        MessageKey.MSG_LOCATION_NOT_DIRECTORY -> Res.string.msg_location_not_directory
        MessageKey.MSG_DIRECTORY_UNAVAILABLE -> Res.string.msg_directory_unavailable
        MessageKey.MSG_TRANSFER_SAME_PATH -> Res.string.msg_transfer_same_path
        MessageKey.MSG_TRANSFER_INTO_SELF -> Res.string.msg_transfer_into_self
        MessageKey.MSG_SYSTEM_OPEN_TARGET_UNAVAILABLE -> Res.string.msg_system_open_target_unavailable
        MessageKey.MSG_DESKTOP_INTEGRATION_UNAVAILABLE -> Res.string.msg_desktop_integration_unavailable
        MessageKey.MSG_SYSTEM_OPEN_UNSUPPORTED -> Res.string.msg_system_open_unsupported
        MessageKey.MSG_TERMINAL_LOCATION_NOT_DIRECTORY -> Res.string.msg_terminal_location_not_directory
        MessageKey.MSG_TERMINAL_LAUNCH_FAILED -> Res.string.msg_terminal_launch_failed
        MessageKey.MSG_ARCHIVE_ENTRY_LOCATION_INVALID -> Res.string.msg_archive_entry_location_invalid
        MessageKey.MSG_ARCHIVE_ENTRY_PATH_EMPTY -> Res.string.msg_archive_entry_path_empty
        MessageKey.MSG_ARCHIVE_ENTRY_NOT_FOUND -> Res.string.msg_archive_entry_not_found
        MessageKey.MSG_ARCHIVE_ENTRY_EMPTY -> Res.string.msg_archive_entry_empty
    }
}
