package com.oruke.onyx.core.model

data class BackgroundTask(
    val id: String,
    val kind: BackgroundTaskKind,
    val title: I18nMessage,
    val status: BackgroundTaskStatus,
    val detail: I18nMessage,
    val progress: Float? = null,
    val currentFileName: String? = null,
    val processedCount: Int = 0,
    val totalCount: Int = 0,
    val processedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val startTimeMillis: Long = 0L,
    val errors: List<TaskError> = emptyList(),
)

enum class BackgroundTaskKind {
    COPY,
    MOVE,
    DELETE,
    EXTERNAL_IMPORT,
}

enum class BackgroundTaskStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class TaskError(
    val fileName: String,
    val message: String,
)
