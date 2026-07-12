package com.oruke.onyx.vfs.api

/**
 * 跨 Provider 传输阶段，用于向调用方报告目录递归复制中的关键节点。
 */
enum class CrossProviderTransferStage {
    DIRECTORY_CREATED,
    FILE_COPIED,
    ENTRY_SKIPPED,
}

/**
 * 跨 Provider 传输进度事件。
 */
data class CrossProviderTransferProgress(
    /** 当前传输阶段。 */
    val stage: CrossProviderTransferStage,

    /** 源条目位置。 */
    val sourceLocation: String,

    /** 目标条目位置。 */
    val targetLocation: String,

    /** 已成功复制的文件数量。 */
    val copiedFiles: Int,

    /** 已成功创建或复用的目录数量。 */
    val createdDirectories: Int,

    /** 已按冲突策略跳过的条目数量。 */
    val skippedEntries: Int,
)

/**
 * 跨 Provider 传输进度接收器。
 */
fun interface CrossProviderTransferProgressSink {
    /**
     * 接收单次跨 Provider 传输进度事件。
     *
     * @param progress 当前进度事件。
     */
    fun onProgress(progress: CrossProviderTransferProgress)

    /**
     * 进度接收器预置实现。
     */
    companion object {
        /** 默认空实现，供不关注进度的调用方使用。 */
        val NoOp = CrossProviderTransferProgressSink { }
    }
}

/**
 * 跨 Provider 传输中的单项失败。
 */
data class CrossProviderTransferFailure(
    /** 源条目位置。 */
    val sourceLocation: String,

    /** 目标父目录或目标条目位置。 */
    val targetLocation: String,

    /** 导致该条目失败的异常。 */
    val cause: Throwable,
)

/**
 * 跨 Provider 传输汇总报告。
 */
data class CrossProviderTransferReport(
    /** 成功复制的文件数量。 */
    val copiedFiles: Int,

    /** 成功创建或复用的目录数量。 */
    val createdDirectories: Int,

    /** 按冲突策略跳过的条目数量。 */
    val skippedEntries: Int,

    /** 传输过程中聚合到的单项失败。 */
    val failures: List<CrossProviderTransferFailure>,
)

/**
 * 跨 Provider 传输聚合失败，避免大型目录复制因单个子项失败而丢失其他失败信息。
 */
class CrossProviderTransferException(
    /** 传输统计和失败明细。 */
    val report: CrossProviderTransferReport,
) : IllegalStateException(
    "Cross-provider transfer failed for ${report.failures.size} entries"
)
