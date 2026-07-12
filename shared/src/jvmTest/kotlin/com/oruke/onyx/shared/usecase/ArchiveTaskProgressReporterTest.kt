package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 归档原生回调到任务进度的适配测试。 */
class ArchiveTaskProgressReporterTest {
    /**
     * 绝对解压字节应转换为批次内百分比、滚动速度和 ETA。
     */
    @Test
    fun convertsArchiveBytesToTaskTelemetry() {
        var nowMillis = 0L
        val events = mutableListOf<TaskProgress>()
        val reporter = ArchiveTaskProgressReporter(
            detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, "archive"),
            currentFileName = "archive.zip",
            completedUnits = 0,
            currentUnitWeight = 1,
            totalUnits = 1,
            exposeByteTotals = true,
            clockMillis = { nowMillis },
            onTaskProgress = events::add,
        )

        reporter.onProgress(completedBytes = 0L, totalBytes = 1_000L)
        nowMillis = 250L
        reporter.onProgress(completedBytes = 250L, totalBytes = 1_000L)

        val latest = events.last()
        assertEquals(0.25f, latest.progress)
        assertEquals(250L, latest.processedBytes)
        assertEquals(1_000L, latest.totalBytes)
        assertEquals(1_000L, latest.bytesPerSecond)
        assertEquals(1L, latest.estimatedRemainingSeconds)
        assertTrue(events.size >= 2)
    }
}
