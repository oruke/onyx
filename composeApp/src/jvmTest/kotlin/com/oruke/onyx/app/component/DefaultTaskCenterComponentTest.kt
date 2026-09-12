package com.oruke.onyx.app.component

import com.oruke.onyx.app.component.delegate.TaskOrchestrator
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 通过真实任务编排与协程验证任务窗口状态独立于后台任务执行。 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTaskCenterComponentTest {
    /** 关闭期间任务继续产生进度，重新打开保留筛选与任务身份且每次请求都会激活窗口。 */
    @Test
    fun closingWindowPreservesProgressAndReopeningRetainsState() = runTest {
        val orchestrator = TaskOrchestrator(this)
        val component = DefaultTaskCenterComponent(orchestrator)
        val continueWork = CompletableDeferred<Unit>()
        val finishWork = CompletableDeferred<Unit>()
        orchestrator.appendTask(testTask("transfer", BackgroundTaskStatus.QUEUED))
        val job = orchestrator.launchQueuedTask("transfer") {
            orchestrator.updateTaskFields("transfer") { task ->
                task.copy(status = BackgroundTaskStatus.RUNNING, progress = 0f)
            }
            continueWork.await()
            orchestrator.updateTaskFields("transfer") { task -> task.copy(progress = 0.5f, processedBytes = 50L) }
            finishWork.await()
            orchestrator.updateTaskFields("transfer") { task ->
                task.copy(status = BackgroundTaskStatus.SUCCEEDED, progress = 1f, processedBytes = 100L)
            }
            orchestrator.unregisterJob("transfer")
        }
        runCurrent()

        component.dispatch(TaskCenterIntent.Open)
        component.dispatch(TaskCenterIntent.SetFilter(TaskCenterFilter.ACTIVE))
        component.dispatch(TaskCenterIntent.Close)
        continueWork.complete(Unit)
        runCurrent()

        assertFalse(component.state.value.visible)
        assertTrue(job.isActive)
        assertSame(orchestrator.tasks, component.tasks)
        assertEquals(50L, component.tasks.value.single().processedBytes)
        component.dispatch(TaskCenterIntent.Open)
        assertEquals(
            TaskCenterState(visible = true, activationRequest = 2L, filter = TaskCenterFilter.ACTIVE),
            component.state.value,
        )
        assertEquals("transfer", component.tasks.value.single().id)
        component.dispatch(TaskCenterIntent.Open)
        assertEquals(3L, component.state.value.activationRequest)

        component.dispatch(TaskCenterIntent.Close)
        finishWork.complete(Unit)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(BackgroundTaskStatus.SUCCEEDED, component.tasks.value.single().status)
        assertEquals(100L, component.tasks.value.single().processedBytes)
        assertFalse(component.state.value.visible)
    }

    /** 清理成功与取消任务时保留失败记录和所有活动协程，恢复后仍能完成原任务。 */
    @Test
    fun clearingFinishedTasksKeepsEveryActiveLifecycleRunning() = runTest {
        val orchestrator = TaskOrchestrator(this)
        val component = DefaultTaskCenterComponent(orchestrator)
        val finishRunning = CompletableDeferred<Unit>()
        val pauseFlag = orchestrator.getOrCreatePauseFlag("paused")
        pauseFlag.value = true
        orchestrator.appendTask(testTask("running", BackgroundTaskStatus.RUNNING))
        orchestrator.appendTask(testTask("paused", BackgroundTaskStatus.PAUSED, canPause = true))
        orchestrator.appendTask(testTask("queued", BackgroundTaskStatus.QUEUED))
        val runningJob = orchestrator.launchQueuedTask("running") {
            finishRunning.await()
            orchestrator.updateTaskFields("running") { task -> task.copy(status = BackgroundTaskStatus.SUCCEEDED) }
            orchestrator.unregisterJob("running")
        }
        val pausedJob = orchestrator.launchQueuedTask("paused") {
            pauseFlag.first { paused -> !paused }
            orchestrator.updateTaskFields("paused") { task -> task.copy(status = BackgroundTaskStatus.SUCCEEDED) }
            orchestrator.unregisterJob("paused")
        }
        val queuedJob = orchestrator.launchQueuedTask("queued") {
            orchestrator.updateTaskFields("queued") { task -> task.copy(status = BackgroundTaskStatus.SUCCEEDED) }
            orchestrator.unregisterJob("queued")
        }
        runCurrent()
        orchestrator.appendTask(testTask("success", BackgroundTaskStatus.SUCCEEDED))
        orchestrator.appendTask(testTask("failure", BackgroundTaskStatus.FAILED))
        orchestrator.appendTask(testTask("cancelled", BackgroundTaskStatus.CANCELLED))

        component.dispatch(TaskCenterIntent.Dismiss("running"))
        component.dispatch(TaskCenterIntent.ClearFinished)
        runCurrent()
        assertEquals(listOf("failure", "queued", "paused", "running"), component.tasks.value.map { task -> task.id })
        assertTrue(runningJob.isActive)
        assertTrue(pausedJob.isActive)
        assertTrue(queuedJob.isActive)
        assertTrue(pauseFlag.value)

        component.dispatch(TaskCenterIntent.Resume("paused"))
        finishRunning.complete(Unit)
        runCurrent()
        assertTrue(listOf(runningJob, pausedJob, queuedJob).all { job -> job.isCompleted && !job.isCancelled })
        assertTrue(component.tasks.value.filterNot { task -> task.id == "failure" }
            .all { task -> task.status == BackgroundTaskStatus.SUCCEEDED })
        component.dispatch(TaskCenterIntent.ClearFinished)
        assertEquals("failure", component.tasks.value.single().id)
        component.dispatch(TaskCenterIntent.Dismiss("failure"))
        assertTrue(component.tasks.value.isEmpty())
    }

    /** 按生命周期筛选并保持组内原始顺序，失败任务不混入已结束筛选。 */
    @Test
    fun filtersPrioritizeActiveAndFailedTasksWithoutReorderingGroups() = runTest {
        val orchestrator = TaskOrchestrator(this)
        val component = DefaultTaskCenterComponent(orchestrator)
        val originalTasks = listOf(
            testTask("success", BackgroundTaskStatus.SUCCEEDED),
            testTask("failed-first", BackgroundTaskStatus.FAILED),
            testTask("paused", BackgroundTaskStatus.PAUSED),
            testTask("cancelled", BackgroundTaskStatus.CANCELLED),
            testTask("running", BackgroundTaskStatus.RUNNING),
            testTask("failed-last", BackgroundTaskStatus.FAILED),
            testTask("queued", BackgroundTaskStatus.QUEUED),
        )
        originalTasks.reversed().forEach(orchestrator::appendTask)
        val expectations = mapOf(
            TaskCenterFilter.ALL to listOf(
                "paused", "running", "queued", "failed-first", "failed-last", "success", "cancelled",
            ),
            TaskCenterFilter.ACTIVE to listOf("paused", "running", "queued"),
            TaskCenterFilter.FAILED to listOf("failed-first", "failed-last"),
            TaskCenterFilter.FINISHED to listOf("success", "cancelled"),
        )
        expectations.forEach { (filter, expectedIds) ->
            component.dispatch(TaskCenterIntent.SetFilter(filter))
            assertEquals(
                expectedIds,
                component.tasks.value.forTaskCenterFilter(component.state.value.filter).map { task -> task.id },
            )
        }
        assertEquals(originalTasks, component.tasks.value)
    }

    /** 不支持暂停的任务不发生伪暂停，终态不被恢复，失败重试执行真实登记动作。 */
    @Test
    fun taskActionsValidateLifecycleBeforeReachingTheOrchestrator() = runTest {
        val orchestrator = TaskOrchestrator(this)
        val component = DefaultTaskCenterComponent(orchestrator)
        val releaseTask = CompletableDeferred<Unit>()
        val pauseFlag = orchestrator.getOrCreatePauseFlag("transfer")
        orchestrator.appendTask(testTask("transfer", BackgroundTaskStatus.RUNNING))
        val job = orchestrator.launchQueuedTask("transfer") { releaseTask.await() }
        runCurrent()
        component.dispatch(TaskCenterIntent.Pause("transfer"))
        assertFalse(pauseFlag.value)
        assertEquals(BackgroundTaskStatus.RUNNING, component.tasks.value.single().status)

        orchestrator.updateTaskFields("transfer") { task -> task.copy(canPause = true) }
        component.dispatch(TaskCenterIntent.Pause("transfer"))
        assertTrue(pauseFlag.value)
        assertEquals(BackgroundTaskStatus.PAUSED, component.tasks.value.single().status)
        component.dispatch(TaskCenterIntent.Resume("transfer"))
        assertFalse(pauseFlag.value)
        assertEquals(BackgroundTaskStatus.RUNNING, component.tasks.value.single().status)
        component.dispatch(TaskCenterIntent.Cancel("transfer"))
        runCurrent()
        assertTrue(job.isCancelled)
        orchestrator.unregisterJob("transfer")

        orchestrator.updateTaskFields("transfer") { task -> task.copy(status = BackgroundTaskStatus.FAILED) }
        orchestrator.getOrCreatePauseFlag("transfer").value = true
        component.dispatch(TaskCenterIntent.Resume("transfer"))
        assertEquals(BackgroundTaskStatus.FAILED, component.tasks.value.single().status)
        orchestrator.registerRetryHandler("transfer") {
            orchestrator.appendTask(testTask("retried", BackgroundTaskStatus.QUEUED))
            orchestrator.launchQueuedTask("retried") {
                orchestrator.updateTaskFields("retried") { task ->
                    task.copy(status = BackgroundTaskStatus.SUCCEEDED, processedBytes = 100L)
                }
                orchestrator.unregisterJob("retried")
            }
        }
        component.dispatch(TaskCenterIntent.Retry("transfer"))
        runCurrent()
        assertEquals("retried", component.tasks.value.single().id)
        assertEquals(BackgroundTaskStatus.SUCCEEDED, component.tasks.value.single().status)
        assertEquals(100L, component.tasks.value.single().processedBytes)
    }

    private companion object {
        /**
         * 构造不依赖真实文件路径的后台文件传输快照。
         *
         * @param id 测试任务标识。
         * @param status 初始生命周期状态。
         * @param canPause 是否允许通过组件暂停任务。
         * @return 可交给真实编排器执行的测试任务。
         */
        fun testTask(id: String, status: BackgroundTaskStatus, canPause: Boolean = false): BackgroundTask {
            return BackgroundTask(
                id = id,
                kind = BackgroundTaskKind.COPY,
                title = I18nMessage(MessageKey.MSG_STRING_LITERAL, "Transfer"),
                status = status,
                canPause = canPause,
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, "Test transfer"),
                totalBytes = 100L,
            )
        }
    }
}
