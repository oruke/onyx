package com.oruke.onyx.ui

import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 任务中心总进度聚合测试。 */
class TaskCenterPresentationTest {
    /**
     * 多个传输任务应按字节总量加权，并汇总并发速度与剩余时间。
     */
    @Test
    fun aggregatesActiveTasksByBytes() {
        val summary = listOf(
            task(id = "small", processedBytes = 100L, totalBytes = 100L, bytesPerSecond = 100L),
            task(id = "large", processedBytes = 100L, totalBytes = 900L, bytesPerSecond = 200L),
        ).toTaskCenterSummary()

        assertEquals(2, summary.activeCount)
        assertEquals(200L, summary.processedBytes)
        assertEquals(1_000L, summary.totalBytes)
        assertEquals(0.2f, summary.progress)
        assertEquals(300L, summary.bytesPerSecond)
        assertEquals(3L, summary.estimatedRemainingSeconds)
        assertFalse(summary.hasIndeterminateRunning)
    }

    /**
     * 正在运行但没有百分比的远端任务应触发不确定进度叠加层。
     */
    @Test
    fun marksUnknownRunningTaskAsIndeterminate() {
        val summary = listOf(
            task(
                id = "remote",
                processedBytes = 0L,
                totalBytes = 0L,
                bytesPerSecond = null,
                progress = null,
            )
        ).toTaskCenterSummary()

        assertTrue(summary.hasIndeterminateRunning)
        assertEquals(null, summary.progress)
    }

    /**
     * 只有最近完成任务时总览应显示完成且不再计入活动任务。
     */
    @Test
    fun keepsTerminalTasksOutOfActiveAggregate() {
        val summary = listOf(
            task(
                id = "done",
                processedBytes = 100L,
                totalBytes = 100L,
                bytesPerSecond = null,
                status = BackgroundTaskStatus.SUCCEEDED,
                progress = 1f,
            )
        ).toTaskCenterSummary()

        assertEquals(0, summary.activeCount)
        assertEquals(1, summary.terminalCount)
        assertEquals(1f, summary.progress)
    }

    /** 单个活动任务展开后不应重复展示聚合进度。 */
    @Test
    fun hidesAggregateProgressForSingleActiveTask() {
        val summary = listOf(
            task(id = "single", processedBytes = 50L, totalBytes = 100L, bytesPerSecond = 10L),
        ).toTaskCenterSummary()

        assertFalse(summary.shouldShowAggregateProgress())
    }

    /** 多个活动任务展开后应保留可区分的总进度。 */
    @Test
    fun showsAggregateProgressForMultipleActiveTasks() {
        val summary = listOf(
            task(id = "first", processedBytes = 50L, totalBytes = 100L, bytesPerSecond = 10L),
            task(id = "second", processedBytes = 25L, totalBytes = 100L, bytesPerSecond = 10L),
        ).toTaskCenterSummary()

        assertTrue(summary.shouldShowAggregateProgress())
    }

    /**
     * 构造任务中心聚合测试任务。
     *
     * @param id 任务 ID。
     * @param processedBytes 已完成字节数。
     * @param totalBytes 总字节数。
     * @param bytesPerSecond 当前速度。
     * @param status 任务状态。
     * @param progress 任务进度。
     * @return 后台任务快照。
     */
    private fun task(
        id: String,
        processedBytes: Long,
        totalBytes: Long,
        bytesPerSecond: Long?,
        status: BackgroundTaskStatus = BackgroundTaskStatus.RUNNING,
        progress: Float? = if (totalBytes > 0L) processedBytes.toFloat() / totalBytes else null,
    ): BackgroundTask = BackgroundTask(
        id = id,
        kind = BackgroundTaskKind.COPY,
        title = I18nMessage(MessageKey.MSG_STRING_LITERAL, id),
        status = status,
        detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, id),
        progress = progress,
        processedBytes = processedBytes,
        totalBytes = totalBytes,
        bytesPerSecond = bytesPerSecond,
    )
}
