package com.oruke.onyx.core.model

data class BackgroundTask(
    val id: String,
    val title: I18nMessage,
    val status: BackgroundTaskStatus,
    val detail: I18nMessage,
    val progress: Float? = null,
)

enum class BackgroundTaskStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}
