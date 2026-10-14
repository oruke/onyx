package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.vfs.api.TrashMoveRecord

/** 后台文件任务向 UI 发送的进度快照。 */
data class TaskProgress(
    /** 当前任务状态。 */
    val status: BackgroundTaskStatus,
    /** 当前阶段的国际化详情。 */
    val detail: I18nMessage,
    /** 0 到 1 的可选总体进度。 */
    val progress: Float? = null,
    /** 当前处理文件名。 */
    val currentFileName: String? = null,
    /** 已处理条目数。 */
    val processedCount: Int? = null,
    /** 已处理字节数。 */
    val processedBytes: Long? = null,
    /** 总字节数。 */
    val totalBytes: Long? = null,
    /** 最近采样窗口内的传输速度，单位为字节每秒。 */
    val bytesPerSecond: Long? = null,
    /** 按当前速度估算的剩余秒数。 */
    val estimatedRemainingSeconds: Long? = null,
    /** 当前步骤产生的回收站记录。 */
    val trashRecords: List<TrashMoveRecord> = emptyList(),
    /** 当前步骤已确认的目录变化；无准确结果时显式标记待校验目录。 */
    val directoryChange: com.oruke.onyx.core.model.DirectoryChange? = null,
    /** 已确认按冲突策略跳过的条目数量。 */
    val skippedCount: Int = 0,
)
