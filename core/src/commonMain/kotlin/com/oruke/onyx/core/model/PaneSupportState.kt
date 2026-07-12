package com.oruke.onyx.core.model

/** 面板状态栏所需的条目与选择统计。 */
data class PaneStatusInfo(
    /** 当前目录总条目数。 */
    val totalItemCount: Int = 0,
    /** 过滤后可见条目数。 */
    val visibleItemCount: Int = 0,
    /** 目录数。 */
    val directoryCount: Int = 0,
    /** 文件数。 */
    val fileCount: Int = 0,
    /** 已选择条目数。 */
    val selectedCount: Int = 0,
    /** 已选择文件总字节数。 */
    val selectedSizeBytes: Long = 0L,
)

/** 面板行内重命名或新建输入状态。 */
data class PaneInlineEditState(
    /** 行内编辑模式。 */
    val mode: PaneInlineEditMode,
    /** 重命名目标条目 ID；新建模式为空。 */
    val targetEntryId: String? = null,
    /** 当前名称草稿。 */
    val draftName: String = "",
)

/** 面板支持的行内编辑模式。 */
enum class PaneInlineEditMode {
    RENAME,
    CREATE_FILE,
    CREATE_DIRECTORY,
}

/** 面板预览与详情检查器可见性。 */
data class PaneInspectorState(
    /** 是否展示预览。 */
    val previewVisible: Boolean = false,
    /** 是否展示详情。 */
    val detailsVisible: Boolean = false,
)

/** 面板文件操作反馈。 */
data class PaneOperationFeedback(
    /** 反馈类型。 */
    val kind: PaneOperationFeedbackKind,
    /** 可选国际化详情。 */
    val detail: I18nMessage? = null,
)

/** 面板可展示的文件操作反馈类型。 */
enum class PaneOperationFeedbackKind {
    OPEN_FAILED,
    RENAME_FAILED,
    CREATE_FILE_FAILED,
    CREATE_DIRECTORY_FAILED,
    COPY_PATH_FAILED,
    FILE_OPERATION_FAILED,
    WATCH_DEGRADED,
}
