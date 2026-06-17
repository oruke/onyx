package com.oruke.onyx.vfs.api

/**
 * 跨 provider 传输阶段，用于把目录递归复制中的关键节点统一上报给调用方。
 */
enum class CrossProviderTransferStage {
    DIRECTORY_CREATED,
    FILE_COPIED,
    ENTRY_SKIPPED,
}

/**
 * 跨 provider 传输进度事件。
 *
 * @property stage 当前传输阶段。
 * @property sourceLocation 源条目位置。
 * @property targetLocation 目标条目位置。
 * @property copiedFiles 已成功复制的文件数量。
 * @property createdDirectories 已成功创建或复用的目录数量。
 * @property skippedEntries 已按冲突策略跳过的条目数量。
 */
data class CrossProviderTransferProgress(
    val stage: CrossProviderTransferStage,
    val sourceLocation: String,
    val targetLocation: String,
    val copiedFiles: Int,
    val createdDirectories: Int,
    val skippedEntries: Int,
)

/**
 * 跨 provider 传输进度接收器。
 */
fun interface CrossProviderTransferProgressSink {
    /**
     * 接收单次跨 provider 传输进度事件。
     *
     * @param progress 当前进度事件。
     */
    fun onProgress(progress: CrossProviderTransferProgress)

    companion object {
        /**
         * 默认空实现，保持既有调用方无需关心进度事件。
         */
        val NoOp = CrossProviderTransferProgressSink { }
    }
}

/**
 * 跨 provider 传输中的单项失败。
 *
 * @property sourceLocation 源条目位置。
 * @property targetLocation 目标父目录或目标条目位置。
 * @property cause 导致该条目失败的异常。
 */
data class CrossProviderTransferFailure(
    val sourceLocation: String,
    val targetLocation: String,
    val cause: Throwable,
)

/**
 * 跨 provider 传输汇总报告。
 *
 * @property copiedFiles 成功复制的文件数量。
 * @property createdDirectories 成功创建或复用的目录数量。
 * @property skippedEntries 按冲突策略跳过的条目数量。
 * @property failures 传输过程中聚合到的单项失败。
 */
data class CrossProviderTransferReport(
    val copiedFiles: Int,
    val createdDirectories: Int,
    val skippedEntries: Int,
    val failures: List<CrossProviderTransferFailure>,
)

/**
 * 跨 provider 传输聚合失败，避免大目录复制因为某个子项失败而丢失其它失败信息。
 *
 * @property report 传输统计和失败明细。
 */
class CrossProviderTransferException(
    val report: CrossProviderTransferReport,
) : IllegalStateException(
        "Cross-provider transfer failed for ${report.failures.size} entries"
    )
