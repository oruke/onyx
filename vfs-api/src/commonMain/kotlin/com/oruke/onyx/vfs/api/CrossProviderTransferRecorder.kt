package com.oruke.onyx.vfs.api

/**
 * 跨 Provider 传输记录器，负责统一统计进度和聚合失败。
 *
 * @param progressSink 进度事件接收器。
 */
internal class CrossProviderTransferRecorder(
    private val progressSink: CrossProviderTransferProgressSink,
) {
    /** 已成功复制的文件数量。 */
    private var copiedFiles = 0

    /** 已成功创建或复用的目录数量。 */
    private var createdDirectories = 0

    /** 已按冲突策略跳过的条目数量。 */
    private var skippedEntries = 0

    /** 已聚合的条目级失败列表。 */
    private val failures = mutableListOf<CrossProviderTransferFailure>()

    /**
     * 记录文件复制成功事件。
     *
     * @param sourceLocation 源文件位置。
     * @param targetLocation 目标文件位置。
     */
    fun recordCopiedFile(
        sourceLocation: String,
        targetLocation: String,
    ) {
        copiedFiles += 1
        emit(
            stage = CrossProviderTransferStage.FILE_COPIED,
            sourceLocation = sourceLocation,
            targetLocation = targetLocation,
        )
    }

    /**
     * 记录目录创建或复用事件。
     *
     * @param sourceLocation 源目录位置。
     * @param targetLocation 目标目录位置。
     */
    fun recordDirectory(
        sourceLocation: String,
        targetLocation: String,
    ) {
        createdDirectories += 1
        emit(
            stage = CrossProviderTransferStage.DIRECTORY_CREATED,
            sourceLocation = sourceLocation,
            targetLocation = targetLocation,
        )
    }

    /**
     * 记录按冲突策略跳过的条目。
     *
     * @param sourceLocation 源条目位置。
     * @param targetLocation 已存在或推导出的目标位置。
     */
    fun recordSkipped(
        sourceLocation: String,
        targetLocation: String,
    ) {
        skippedEntries += 1
        emit(
            stage = CrossProviderTransferStage.ENTRY_SKIPPED,
            sourceLocation = sourceLocation,
            targetLocation = targetLocation,
        )
    }

    /**
     * 记录单项传输失败。
     *
     * @param sourceLocation 源条目位置。
     * @param targetLocation 目标父目录或目标条目位置。
     * @param cause 导致失败的异常。
     */
    fun recordFailure(
        sourceLocation: String,
        targetLocation: String,
        cause: Throwable,
    ) {
        failures += CrossProviderTransferFailure(
            sourceLocation = sourceLocation,
            targetLocation = targetLocation,
            cause = cause,
        )
    }

    /**
     * 如果存在已聚合失败则抛出汇总异常。
     */
    fun throwIfFailed() {
        if (failures.isNotEmpty()) {
            throw CrossProviderTransferException(snapshot())
        }
    }

    /**
     * 生成当前传输报告快照。
     *
     * @return 不可变传输报告。
     */
    private fun snapshot(): CrossProviderTransferReport {
        return CrossProviderTransferReport(
            copiedFiles = copiedFiles,
            createdDirectories = createdDirectories,
            skippedEntries = skippedEntries,
            failures = failures.toList(),
        )
    }

    /**
     * 发出传输进度事件。
     *
     * @param stage 当前传输阶段。
     * @param sourceLocation 源条目位置。
     * @param targetLocation 目标条目位置。
     */
    private fun emit(
        stage: CrossProviderTransferStage,
        sourceLocation: String,
        targetLocation: String,
    ) {
        progressSink.onProgress(
            CrossProviderTransferProgress(
                stage = stage,
                sourceLocation = sourceLocation,
                targetLocation = targetLocation,
                copiedFiles = copiedFiles,
                createdDirectories = createdDirectories,
                skippedEntries = skippedEntries,
            )
        )
    }
}

/**
 * 生成目录 KEEP_BOTH 冲突策略下的下一个可用副本名称。
 *
 * @param existingNames 当前目标目录已有名称集合。
 * @return 不与已有名称冲突的目录名。
 */
internal fun String.nextDirectoryCopyName(existingNames: Set<String>): String {
    var index = 1
    while (true) {
        val candidate = withVfsCopySuffix(index)
        if (candidate !in existingNames) return candidate
        index += 1
    }
}
