package com.oruke.onyx.core.model

data class BackgroundTask(
    val id: String,
    val title: String,
    val status: BackgroundTaskStatus,
    val detail: String,
)

enum class BackgroundTaskStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
}
