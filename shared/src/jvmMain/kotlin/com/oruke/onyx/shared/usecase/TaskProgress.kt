package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.DirectoryChange
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.TrashMoveRecord

/**
 * 批量文件传输中单个顶层条目的非取消失败记录。
 *
 * @property entry 失败的顶层源条目。
 * @property targetDirectoryLocation 该条目实际落盘目标父目录。
 * @property cause 真实底层失败原因。
 */
data class TransferEntryFailure(
    val entry: VFile,
    val targetDirectoryLocation: String,
    val cause: Throwable,
)

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
    val directoryChange: DirectoryChange? = null,
    /** 当前步骤为保留相对目录结构而新建的中间目录条目。 */
    val createdDirectories: List<VFile> = emptyList(),
    /** 已确认按冲突策略跳过的条目数量。 */
    val skippedCount: Int = 0,
    /** 当前步骤已确认完成（成功提交或按冲突策略跳过）的顶层条目。 */
    val completedEntry: VFile? = null,
    /** 当前步骤发生的单条目传输失败；记录后任务继续处理后续条目。 */
    val entryFailure: TransferEntryFailure? = null,
)
