package com.oruke.onyx.shared.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 滚动传输速度估算器测试。 */
class TransferRateEstimatorTest {
    /**
     * 样本时间不足时不应输出抖动速度，达到最短窗口后应返回滚动吞吐。
     */
    @Test
    fun waitsForStableSampleBeforeReportingRate() {
        val estimator = TransferRateEstimator()

        assertNull(estimator.record(processedBytes = 0L, nowMillis = 0L))
        assertNull(estimator.record(processedBytes = 1_000L, nowMillis = 100L))
        assertEquals(10_000L, estimator.record(processedBytes = 2_000L, nowMillis = 200L))
    }

    /**
     * 累计字节回退时应重置采样窗口，避免产生负速度。
     */
    @Test
    fun resetsWhenProcessedBytesMoveBackwards() {
        val estimator = TransferRateEstimator()
        estimator.record(processedBytes = 0L, nowMillis = 0L)
        estimator.record(processedBytes = 2_000L, nowMillis = 250L)

        assertNull(estimator.record(processedBytes = 100L, nowMillis = 300L))
        assertEquals(4_000L, estimator.record(processedBytes = 1_100L, nowMillis = 550L))
    }
}
