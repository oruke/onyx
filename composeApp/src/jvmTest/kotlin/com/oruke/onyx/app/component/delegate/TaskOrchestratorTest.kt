package com.oruke.onyx.app.component.delegate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 后台任务调度器测试。
 *
 * 验证任务状态管理之外的运行时约束，避免 QUEUED 状态退化成纯展示字段。
 */
class TaskOrchestratorTest {
    /**
     * 验证队列任务会遵守调度器的并发上限。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `launch queued task respects concurrency limit`() = runTest {
        val orchestrator = TaskOrchestrator(scope = this)
        val releaseSignals = List(TASK_COUNT) { CompletableDeferred<Unit>() }
        var runningCount = 0
        var maxRunningCount = 0

        repeat(TASK_COUNT) { index ->
            orchestrator.launchQueuedTask("task-$index") {
                runningCount += 1
                maxRunningCount = maxOf(maxRunningCount, runningCount)
                try {
                    releaseSignals[index].await()
                } finally {
                    runningCount -= 1
                }
            }
        }

        runCurrent()

        assertEquals(EXPECTED_MAX_RUNNING, maxRunningCount)
        assertEquals(EXPECTED_MAX_RUNNING, runningCount)

        releaseSignals.first().complete(Unit)
        runCurrent()

        assertEquals(EXPECTED_MAX_RUNNING, maxRunningCount)
        assertEquals(EXPECTED_MAX_RUNNING, runningCount)

        releaseSignals.drop(1).forEach { signal -> signal.complete(Unit) }
        runCurrent()

        assertEquals(0, runningCount)
    }

    private companion object {
        const val TASK_COUNT = 3
        const val EXPECTED_MAX_RUNNING = 2
    }
}
