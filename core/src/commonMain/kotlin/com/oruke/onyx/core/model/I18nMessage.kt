package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

@Serializable
/** 业务层可引用的稳定国际化消息键。 */
enum class MessageKey {
    ACTION_BATCH_RENAME,
    ACTION_CREATE_ZIP,
    ACTION_EXTRACT_HERE,
    ACTION_EXTRACT_TO_DIRECTORY,
    ACTION_EXTRACT_SMART,
    MSG_BATCH_RENAME_FILES,
    MSG_BATCH_RENAME_STARTING,
    MSG_BATCH_RENAMED_FILES,
    MSG_CANCELLED,
    MSG_COPIED_ITEMS,
    MSG_COPY_FAILED,
    MSG_COPY_ITEMS,
    MSG_CREATE_FOLDER_FAILED,
    MSG_CREATE_FOLDERS,
    MSG_CREATED_FOLDERS,
    MSG_COMPRESS_FAILED,
    MSG_COMPRESS_ITEMS,
    MSG_COMPRESSED_ITEMS,
    MSG_DELETE_FAILED,
    MSG_DELETE_ITEMS,
    MSG_DELETED_ITEMS,
    MSG_DELETED_ITEMS_WITHOUT_UNDO,
    MSG_EXTRACT_FAILED,
    MSG_EXTRACT_ITEMS,
    MSG_EXTRACTED_ITEMS,
    MSG_LOAD_SETTINGS_FAILED,
    MSG_MOVE_FAILED,
    MSG_MOVE_ITEMS,
    MSG_MOVED_ITEMS,
    MSG_RESTORE_SESSION_FAILED,
    MSG_CROSS_PROVIDER_TRANSFER_UNSUPPORTED,
    MSG_VFS_AUTHENTICATION_REQUIRED,
    MSG_VFS_AUTHENTICATION_REJECTED,
    MSG_VFS_PERMISSION_DENIED,
    MSG_VFS_NOT_FOUND,
    MSG_VFS_ALREADY_EXISTS,
    MSG_VFS_NETWORK_FAILURE,
    MSG_VFS_UNSUPPORTED_OPERATION,
    /** 没有注册可处理指定位置的文件协议。 */
    MSG_VFS_LOCATION_UNSUPPORTED,
    MSG_STRING_LITERAL,
    MSG_UNKNOWN_ERROR,
    /** 当前范围没有用户授权扫描的位置。 */
    MSG_SEARCH_NO_ELIGIBLE_ROOTS,
    /** 指定位置不支持文件内容搜索。 */
    MSG_SEARCH_CONTENT_UNSUPPORTED,
    /** 文件或目录名称为空。 */
    MSG_FILE_NAME_EMPTY,
    /** 单个文件或目录名称包含路径分隔符。 */
    MSG_FILE_NAME_PATH_SEPARATOR,
    /** 批量目录创建只接受相对路径。 */
    MSG_DIRECTORY_PATH_RELATIVE_REQUIRED,
    /** 待创建的目录路径为空。 */
    MSG_DIRECTORY_PATH_EMPTY,
    /** 待创建的目录路径包含无效片段。 */
    MSG_DIRECTORY_PATH_INVALID_SEGMENTS,
    /** 目标路径必须保留在当前目录内。 */
    MSG_PATH_OUTSIDE_CURRENT_LOCATION,
    /** 指定位置不是目录。 */
    MSG_LOCATION_NOT_DIRECTORY,
    /** 目录入口存在，但其目标离线或当前无法访问。 */
    MSG_DIRECTORY_UNAVAILABLE,
    /** 文件传输的源路径与目标路径相同。 */
    MSG_TRANSFER_SAME_PATH,
    /** 文件传输试图将目录放入自身。 */
    MSG_TRANSFER_INTO_SELF,
    /** 系统打开操作的目标已不可用。 */
    MSG_SYSTEM_OPEN_TARGET_UNAVAILABLE,
    /** 当前平台没有可用的桌面集成能力。 */
    MSG_DESKTOP_INTEGRATION_UNAVAILABLE,
    /** 当前平台不支持通过系统打开文件。 */
    MSG_SYSTEM_OPEN_UNSUPPORTED,
    /** 打开终端的位置不是目录。 */
    MSG_TERMINAL_LOCATION_NOT_DIRECTORY,
    /** 所有可用终端命令均启动失败。 */
    MSG_TERMINAL_LAUNCH_FAILED,
    /** 待预览位置不是有效的压缩包条目。 */
    MSG_ARCHIVE_ENTRY_LOCATION_INVALID,
    /** 待预览的压缩包条目路径为空。 */
    MSG_ARCHIVE_ENTRY_PATH_EMPTY,
    /** 待预览的压缩包条目不存在。 */
    MSG_ARCHIVE_ENTRY_NOT_FOUND,
    /** 待预览的压缩包条目内容为空。 */
    MSG_ARCHIVE_ENTRY_EMPTY,
    MSG_ARCHIVE_PASSWORD_INVALID,
}

/**
 * 业务层消息只携带稳定 key 和参数，不直接依赖 Compose Resources。
 * 具体资源映射统一在 UI 层完成。
 */
data class I18nMessage(
    /** 稳定消息键。 */
    val key: MessageKey,
    /** 资源格式化参数。 */
    val args: List<Any> = emptyList()
) {
    /**
     * 使用可变参数构造国际化消息。
     *
     * @param key 稳定消息键。
     * @param args 资源格式化参数。
     */
    constructor(key: MessageKey, vararg args: Any) : this(key, args.toList())
}
