package com.oruke.onyx.core.model

data class PaneStatusInfo(
    val totalItemCount: Int = 0,
    val visibleItemCount: Int = 0,
    val directoryCount: Int = 0,
    val fileCount: Int = 0,
    val selectedCount: Int = 0,
    val selectedSizeBytes: Long = 0L,
)

data class PaneInlineEditState(
    val mode: PaneInlineEditMode,
    val targetEntryId: String? = null,
    val draftName: String = "",
)

enum class PaneInlineEditMode {
    RENAME,
    CREATE_FILE,
    CREATE_DIRECTORY,
}

data class PaneInspectorState(
    val previewVisible: Boolean = false,
    val detailsVisible: Boolean = false,
)

data class PaneOperationFeedback(
    val kind: PaneOperationFeedbackKind,
    val detail: I18nMessage? = null,
)

enum class PaneOperationFeedbackKind {
    OPEN_FAILED,
    RENAME_FAILED,
    CREATE_FILE_FAILED,
    CREATE_DIRECTORY_FAILED,
    COPY_PATH_FAILED,
    FILE_OPERATION_FAILED,
    WATCH_DEGRADED,
}
