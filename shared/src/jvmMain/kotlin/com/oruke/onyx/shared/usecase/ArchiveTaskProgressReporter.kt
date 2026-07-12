package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.vfs.archive.ArchiveProgressSink

/**
 * 将 7-Zip 绝对字节回调转换为任务中心需要的节流进度、滚动速度和 ETA。
 */
internal class ArchiveTaskProgressReporter(
    /** 当前解压阶段详情。 */
    private val detail: I18nMessage,
    /** 当前压缩文件名。 */
    private val currentFileName: String,
    /** 当前解压批次开始前已经完成的任务单位数。 */
    private val completedUnits: Int,
    /** 当前解压批次在总任务中的单位权重。 */
    private val currentUnitWeight: Int,
    /** 整个任务的总单位数。 */
    private val totalUnits: Int,
    /** 是否将当前批次字节数直接作为整个任务字节数展示。 */
    private val exposeByteTotals: Boolean,
    /** 速度采样使用的单调时间源。 */
    private val clockMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
    /** 节流后任务进度接收器。 */
    private val onTaskProgress: (TaskProgress) -> Unit,
) : ArchiveProgressSink {
    /** 保护回调采样器和时间状态，兼容原生库从不同线程回调。 */
    private val progressLock = Any()

    /** 当前解压批次的滚动速度估算器。 */
    private val rateEstimator = TransferRateEstimator()

    /** 最近一次发出任务快照的时间。 */
    private var lastEmissionMillis = Long.MIN_VALUE

    /** 最近一次收到的完成字节数，用于确保首个回调立即可见。 */
    private var lastCompletedBytes = -1L

    /**
     * 接收 7-Zip 当前批次的绝对字节进度并转换为任务快照。
     *
     * @param completedBytes 当前批次已完成字节数。
     * @param totalBytes 当前批次总字节数。
     */
    override fun onProgress(
        completedBytes: Long,
        totalBytes: Long,
    ) {
        synchronized(progressLock) {
            val normalizedTotal = totalBytes.coerceAtLeast(0L)
            val normalizedCompleted = if (normalizedTotal > 0L) {
                completedBytes.coerceIn(0L, normalizedTotal)
            } else {
                completedBytes.coerceAtLeast(0L)
            }
            val nowMillis = clockMillis()
            val bytesPerSecond = rateEstimator.record(normalizedCompleted, nowMillis)
            val isFirstEmission = lastCompletedBytes < 0L
            val isComplete = normalizedTotal > 0L && normalizedCompleted >= normalizedTotal
            val intervalElapsed = nowMillis - lastEmissionMillis >= PROGRESS_EMISSION_INTERVAL_MILLIS
            lastCompletedBytes = normalizedCompleted
            if (!isFirstEmission && !isComplete && !intervalElapsed) return
            lastEmissionMillis = nowMillis

            val currentFraction = if (normalizedTotal > 0L) {
                normalizedCompleted.toDouble() / normalizedTotal.toDouble()
            } else {
                0.0
            }
            val totalProgress = if (totalUnits > 0) {
                ((completedUnits + currentFraction * currentUnitWeight) / totalUnits)
                    .toFloat()
                    .coerceIn(0f, 1f)
            } else {
                1f
            }
            onTaskProgress(
                TaskProgress(
                    status = BackgroundTaskStatus.RUNNING,
                    detail = detail,
                    progress = totalProgress,
                    currentFileName = currentFileName,
                    processedCount = completedUnits,
                    processedBytes = normalizedCompleted.takeIf { exposeByteTotals },
                    totalBytes = normalizedTotal.takeIf { exposeByteTotals },
                    bytesPerSecond = bytesPerSecond,
                    estimatedRemainingSeconds = estimateArchiveRemainingSeconds(
                        completedBytes = normalizedCompleted,
                        totalBytes = normalizedTotal,
                        bytesPerSecond = bytesPerSecond,
                    ),
                )
            )
        }
    }

    private companion object {
        /** 任务中心更新的最小时间间隔。 */
        const val PROGRESS_EMISSION_INTERVAL_MILLIS = 125L
        /** 将纳秒单调时钟换算为毫秒。 */
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

/**
 * 按当前解压速度估算本批次剩余秒数。
 *
 * @param completedBytes 已完成字节数。
 * @param totalBytes 总字节数。
 * @param bytesPerSecond 当前速度。
 * @return 向上取整的剩余秒数；信息不足时返回 null。
 */
private fun estimateArchiveRemainingSeconds(
    completedBytes: Long,
    totalBytes: Long,
    bytesPerSecond: Long?,
): Long? {
    val speed = bytesPerSecond?.takeIf { value -> value > 0L } ?: return null
    val remainingBytes = (totalBytes - completedBytes).coerceAtLeast(0L)
    if (remainingBytes == 0L) return null
    return (remainingBytes + speed - 1L) / speed
}
