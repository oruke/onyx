package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

/** 后台文件任务的完整运行时快照。 */
data class BackgroundTask(
    /** 任务唯一 ID。 */
    val id: String,
    /** 任务类型。 */
    val kind: BackgroundTaskKind,
    /** 任务标题。 */
    val title: I18nMessage,
    /** 当前状态。 */
    val status: BackgroundTaskStatus,
    /** 当前执行器是否支持暂停与恢复。 */
    val canPause: Boolean = false,
    /** 当前阶段详情。 */
    val detail: I18nMessage,
    /** 0 到 1 的可选进度。 */
    val progress: Float? = null,
    /** 当前文件名。 */
    val currentFileName: String? = null,
    /** 已处理条目数。 */
    val processedCount: Int = 0,
    /** 总条目数。 */
    val totalCount: Int = 0,
    /** 已处理字节数。 */
    val processedBytes: Long = 0L,
    /** 总字节数。 */
    val totalBytes: Long = 0L,
    /** 最近采样窗口内的传输速度，单位为字节每秒。 */
    val bytesPerSecond: Long? = null,
    /** 按当前速度估算的剩余秒数。 */
    val estimatedRemainingSeconds: Long? = null,
    /** 任务开始时间 epoch millis。 */
    val startTimeMillis: Long = 0L,
    /** 任务期间记录的文件错误。 */
    val errors: List<TaskError> = emptyList(),
)

@Serializable
/** 后台任务业务类型。 */
enum class BackgroundTaskKind {
    COPY,
    MOVE,
    DELETE,
    COMPRESS,
    EXTRACT,
    RENAME,
    EXTERNAL_IMPORT,
}

@Serializable
/** 后台任务生命周期状态。 */
enum class BackgroundTaskStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

@Serializable
/** 后台任务中的单文件错误。 */
data class TaskError(
    /** 失败文件名。 */
    val fileName: String,
    /** 可展示错误详情。 */
    val message: String,
)
