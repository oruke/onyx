package com.oruke.onyx.shared.usecase

import java.util.ArrayDeque

/**
 * 使用最近时间窗口估算文件传输吞吐，避免任务启动扫描时间持续拉低速度。
 */
internal class TransferRateEstimator(
    /** 参与速度计算的滚动窗口时长。 */
    private val windowMillis: Long = DEFAULT_RATE_WINDOW_MILLIS,
    /** 形成有效速度前至少需要的采样时长。 */
    private val minimumElapsedMillis: Long = MINIMUM_RATE_SAMPLE_MILLIS,
) {
    /** 保留窗口内累计字节和时间点的有序样本。 */
    private val samples = ArrayDeque<TransferRateSample>()

    /**
     * 记录累计传输字节并返回当前滚动速度。
     *
     * 时间倒退或累计字节回退时会自动重置窗口，避免产生负速度。
     *
     * @param processedBytes 当前任务累计完成字节数。
     * @param nowMillis 当前单调递增时间，单位毫秒。
     * @return 字节每秒；样本时间不足时返回 null。
     */
    fun record(
        processedBytes: Long,
        nowMillis: Long,
    ): Long? {
        val normalizedBytes = processedBytes.coerceAtLeast(0L)
        val lastSample = samples.peekLast()
        if (lastSample == null || nowMillis < lastSample.timeMillis || normalizedBytes < lastSample.processedBytes) {
            samples.clear()
            samples.addLast(TransferRateSample(normalizedBytes, nowMillis))
            return null
        }

        val currentSample = TransferRateSample(normalizedBytes, nowMillis)
        if (nowMillis - lastSample.timeMillis >= RATE_SAMPLE_INTERVAL_MILLIS) {
            samples.addLast(currentSample)
        } else if (samples.size > 1) {
            samples.removeLast()
            samples.addLast(currentSample)
        }
        trimExpiredSamples(nowMillis)

        val firstSample = samples.first
        val latestSample = samples.last
        val elapsedMillis = latestSample.timeMillis - firstSample.timeMillis
        val transferredBytes = latestSample.processedBytes - firstSample.processedBytes
        return if (elapsedMillis < minimumElapsedMillis || transferredBytes <= 0L) {
            null
        } else {
            (transferredBytes * MILLIS_PER_SECOND / elapsedMillis).coerceAtLeast(1L)
        }
    }

    /**
     * 移除滚动窗口外的样本，同时保留恰好位于窗口边界前的基准样本。
     *
     * @param nowMillis 当前时间，单位毫秒。
     */
    private fun trimExpiredSamples(nowMillis: Long) {
        while (samples.size > 2) {
            val secondSample = samples.elementAt(1)
            if (nowMillis - secondSample.timeMillis <= windowMillis) return
            samples.removeFirst()
        }
    }

    /** 单次速度采样。 */
    private data class TransferRateSample(
        /** 当前累计完成字节数。 */
        val processedBytes: Long,
        /** 采样时间，单位毫秒。 */
        val timeMillis: Long,
    )

    private companion object {
        /** 默认滚动速度窗口。 */
        const val DEFAULT_RATE_WINDOW_MILLIS = 3_000L
        /** 形成稳定速度所需的最短时长。 */
        const val MINIMUM_RATE_SAMPLE_MILLIS = 200L
        /** 内部速度样本的最小时间间隔。 */
        const val RATE_SAMPLE_INTERVAL_MILLIS = 100L
        /** 每秒毫秒数。 */
        const val MILLIS_PER_SECOND = 1_000L
    }
}
