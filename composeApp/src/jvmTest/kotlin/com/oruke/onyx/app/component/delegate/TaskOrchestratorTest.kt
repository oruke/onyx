package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.vfs.api.TaskPersistenceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
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
     * 高频字节进度更新应合并为一次缓存写入。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `task persistence is debounced`() = runTest {
        val repository = RecordingTaskRepository()
        val orchestrator = TaskOrchestrator(scope = this, taskRepository = repository)
        runCurrent()
        orchestrator.appendTask(testTask())
        repeat(PROGRESS_UPDATE_COUNT) { index ->
            orchestrator.updateTask(
                taskId = TEST_TASK_ID,
                status = BackgroundTaskStatus.RUNNING,
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, "progress"),
                progress = index.toFloat() / PROGRESS_UPDATE_COUNT,
                processedBytes = index.toLong(),
            )
        }

        runCurrent()
        assertEquals(0, repository.savedSnapshots.size)
        advanceTimeBy(PERSISTENCE_DEBOUNCE_MILLIS - 1L)
        runCurrent()
        assertEquals(0, repository.savedSnapshots.size)
        advanceTimeBy(1L)
        runCurrent()

        assertEquals(1, repository.savedSnapshots.size)
        assertEquals((PROGRESS_UPDATE_COUNT - 1).toLong(), repository.savedSnapshots.single().single().processedBytes)
    }

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
        const val PROGRESS_UPDATE_COUNT = 20
        const val PERSISTENCE_DEBOUNCE_MILLIS = 500L
        const val TEST_TASK_ID = "task-progress"

        /**
         * 构造持久化防抖测试任务。
         *
         * @return 排队任务快照。
         */
        fun testTask(): BackgroundTask = BackgroundTask(
            id = TEST_TASK_ID,
            kind = BackgroundTaskKind.COPY,
            title = I18nMessage(MessageKey.MSG_STRING_LITERAL, "copy"),
            status = BackgroundTaskStatus.QUEUED,
            detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, "queued"),
        )
    }
}

/** 记录任务保存快照的测试仓储。 */
private class RecordingTaskRepository : TaskPersistenceRepository {
    /** 每次保存收到的任务快照。 */
    val savedSnapshots = mutableListOf<List<BackgroundTask>>()

    /**
     * 测试初始状态没有持久化任务。
     *
     * @return 空任务列表。
     */
    override suspend fun loadTasks(): Result<List<BackgroundTask>> = Result.success(emptyList())

    /**
     * 记录当前任务快照。
     *
     * @param tasks 当前任务列表。
     * @return 成功结果。
     */
    override suspend fun saveTasks(tasks: List<BackgroundTask>): Result<Unit> {
        savedSnapshots += tasks
        return Result.success(Unit)
    }
}
