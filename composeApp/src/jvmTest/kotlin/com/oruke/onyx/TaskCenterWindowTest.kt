package com.oruke.onyx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.oruke.onyx.app.platform.JvmApplicationLocaleController
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.oruke.onyx.app.component.DefaultTaskCenterComponent
import com.oruke.onyx.app.component.TaskCenterComponent
import com.oruke.onyx.app.component.TaskCenterFilter
import com.oruke.onyx.app.component.TaskCenterIntent
import com.oruke.onyx.app.component.delegate.TaskOrchestrator
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.ui.OnyxTooltipHost
import com.oruke.onyx.ui.TaskCenterButton
import com.oruke.onyx.ui.theme.OnyxTheme
import java.awt.Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_pause_task
import onyx.composeapp.generated.resources.action_resume_task
import org.jetbrains.compose.resources.getString
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.window.styling.TitleBarStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 从生产任务入口与真实独立窗口验证交互、后台任务生命周期和窗口可达性。 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class TaskCenterWindowTest {
    /**
     * 点击入口打开唯一窗口，筛选和清理生效，关闭后任务仍产生进度并能重新查看。
     *
     * @return 无返回值。
     */
    @Test
    fun taskWindowKeepsBackgroundWorkAndSupportsFilteringAndCleanup() = runDesktopComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val orchestrator = TaskOrchestrator(scope)
        val component = DefaultTaskCenterComponent(orchestrator)
        val preferences = MutableStateFlow(OnyxSettings())
        val continueWork = CompletableDeferred<Unit>()
        val finishWork = CompletableDeferred<Unit>()
        val pauseFlag = runOnUiThread { orchestrator.getOrCreatePauseFlag("running") }
        val job = runOnUiThread {
            orchestrator.appendTask(windowTask("running", BackgroundTaskStatus.RUNNING))
            orchestrator.appendTask(windowTask("failed", BackgroundTaskStatus.FAILED))
            orchestrator.appendTask(windowTask("success", BackgroundTaskStatus.SUCCEEDED))
            orchestrator.appendTask(windowTask("cancelled", BackgroundTaskStatus.CANCELLED))
            orchestrator.launchQueuedTask("running") {
                continueWork.await()
                orchestrator.updateTaskFields("running") { task -> task.copy(progress = 0.5f, processedBytes = 50L) }
                finishWork.await()
                orchestrator.updateTaskFields("running") { task ->
                    task.copy(status = BackgroundTaskStatus.SUCCEEDED, progress = 1f, processedBytes = 100L)
                }
                orchestrator.unregisterJob("running")
            }
        }
        try {
            setContent { TaskWindowTestApplication(component, preferences) }
            onNodeWithTag("task-center-open").performClick()
            val firstWindow = awaitTaskWindow()
            awaitTaskWindowTag("task-row-running")
            assertEquals(1, runOnUiThread { visibleTaskWindows().size })
            runOnUiThread { firstWindow.extendedState = Frame.ICONIFIED }
            waitUntil(timeoutMillis = TASK_WINDOW_WAIT_MILLIS) {
                runOnUiThread { firstWindow.extendedState and Frame.ICONIFIED != 0 }
            }
            onNodeWithTag("task-center-open").performClick()
            waitUntil(timeoutMillis = TASK_WINDOW_WAIT_MILLIS) {
                runOnUiThread { firstWindow.extendedState and Frame.ICONIFIED == 0 }
            }
            assertSame(firstWindow, awaitTaskWindow())
            assertEquals(2L, component.state.value.activationRequest)
            clickTaskWindowAction("running", getString(Res.string.action_pause_task))
            assertEquals(BackgroundTaskStatus.PAUSED, component.tasks.value.single { it.id == "running" }.status)
            assertTrue(pauseFlag.value)
            clickTaskWindowAction("running", getString(Res.string.action_resume_task))
            assertEquals(BackgroundTaskStatus.RUNNING, component.tasks.value.single { it.id == "running" }.status)
            assertFalse(pauseFlag.value)
            captureTaskWindow("task-center-all.png")

            clickTaskWindowTag("task-filter-FAILED")
            awaitTaskWindowTag("task-row-failed")
            assertEquals(TaskCenterFilter.FAILED, component.state.value.filter)
            assertFalse(runOnUiThread { firstWindow.hasTaskWindowTag("task-row-running") })
            clickTaskWindowTag("task-filter-FINISHED")
            awaitTaskWindowTag("task-row-success")
            assertFalse(runOnUiThread { firstWindow.hasTaskWindowTag("task-row-failed") })
            clickTaskWindowTag("task-filter-ACTIVE")
            awaitTaskWindowTag("task-row-running")
            clickTaskWindowTag("task-clear-finished")
            waitUntil(timeoutMillis = TASK_WINDOW_WAIT_MILLIS) { component.tasks.value.size == 2 }
            assertEquals(listOf("failed", "running"), component.tasks.value.map { task -> task.id })
            assertTrue(job.isActive)

            clickTaskWindowTag("task-window-close")
            waitUntil(timeoutMillis = TASK_WINDOW_WAIT_MILLIS) { runOnUiThread { visibleTaskWindows().isEmpty() } }
            continueWork.complete(Unit)
            waitUntil(timeoutMillis = TASK_WINDOW_WAIT_MILLIS) {
                component.tasks.value.single { task -> task.id == "running" }.processedBytes == 50L
            }
            assertFalse(component.state.value.visible)
            assertTrue(job.isActive)
            onNodeWithTag("task-center-open").performClick()
            awaitTaskWindowTag("task-row-running")
            assertEquals(TaskCenterFilter.ACTIVE, component.state.value.filter)
            assertEquals(50L, component.tasks.value.single { task -> task.id == "running" }.processedBytes)
            captureTaskWindow("task-center-reopened.png")

            finishWork.complete(Unit)
            waitUntil(timeoutMillis = TASK_WINDOW_WAIT_MILLIS) { job.isCompleted }
            val finishedTask = component.tasks.value.single { task -> task.id == "running" }
            assertEquals(BackgroundTaskStatus.SUCCEEDED, finishedTask.status)
            assertEquals(100L, finishedTask.processedBytes)
            clickTaskWindowTag("task-filter-FINISHED")
            awaitTaskWindowTag("task-row-running")
            clickTaskWindowTag("task-clear-finished")
            waitUntil(timeoutMillis = TASK_WINDOW_WAIT_MILLIS) { component.tasks.value.size == 1 }
            assertEquals("failed", component.tasks.value.single().id)
            clickTaskWindowTag("task-filter-FAILED")
            awaitTaskWindowTag("task-row-failed")
            captureTaskWindow("task-center-failed-preserved.png")
        } finally {
            runOnUiThread { component.dispatch(TaskCenterIntent.Close) }
            waitForIdle()
            scope.cancel()
        }
    }

    /**
     * 运行中调整 UI 缩放后，最小窗口尺寸同步增加且底部关键操作仍位于可见范围。
     *
     * @return 无返回值。
     */
    @Test
    fun scaledMinimumWindowKeepsControlsReachable() = runDesktopComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val orchestrator = TaskOrchestrator(scope)
        val component = DefaultTaskCenterComponent(orchestrator)
        val preferences = MutableStateFlow(OnyxSettings())
        try {
            runOnUiThread {
                orchestrator.appendTask(windowTask("paused", BackgroundTaskStatus.PAUSED))
                orchestrator.appendTask(windowTask("queued", BackgroundTaskStatus.QUEUED))
                orchestrator.appendTask(windowTask("failed", BackgroundTaskStatus.FAILED))
            }
            setContent { TaskWindowTestApplication(component, preferences) }
            onNodeWithTag("task-center-open").performClick()
            val window = awaitTaskWindow()
            runOnUiThread { preferences.value = preferences.value.copy(uiScale = 200) }
            waitUntil(timeoutMillis = TASK_WINDOW_WAIT_MILLIS) { runOnUiThread { window.minimumSize.width == 1120 } }
            runOnUiThread { window.size = window.minimumSize }
            awaitTaskWindowTag("task-window-close")
            captureTaskWindow("task-center-scaled-minimum.png")
            listOf(
                "task-window-close", "task-clear-finished", "task-filter-ALL",
                "task-filter-ACTIVE", "task-filter-FAILED", "task-filter-FINISHED",
            ).forEach { tag -> assertTaskWindowControlIsVisible(tag) }
            clickTaskWindowTag("task-window-close")
            waitUntil(timeoutMillis = TASK_WINDOW_WAIT_MILLIS) { runOnUiThread { visibleTaskWindows().isEmpty() } }
            assertEquals(3, component.tasks.value.size)
        } finally {
            runOnUiThread { component.dispatch(TaskCenterIntent.Close) }
            waitForIdle()
            scope.cancel()
        }
    }
}

/**
 * 使用生产主题、入口组件和独立任务窗口构造等价应用级测试入口。
 *
 * @param component 真实任务中心组件。
 * @param preferences 当前应用外观设置流。
 * @return 无返回值。
 */
@Composable
private fun TaskWindowTestApplication(
    component: TaskCenterComponent,
    preferences: MutableStateFlow<OnyxSettings>,
) {
    val localeController = remember(preferences) { JvmApplicationLocaleController(preferences) }
    val localeScope = rememberCoroutineScope()
    DisposableEffect(localeController, localeScope) {
        val binding = localeController.bind(localeScope)
        onDispose(binding::cancel)
    }
    IntUiTheme(
        theme = JewelTheme.lightThemeDefinition(),
        styling = ComponentStyling.default().decoratedWindow(titleBarStyle = TitleBarStyle.lightWithLightHeader()),
    ) {
        TaskCenterWindow(component, preferences, localeController.appliedLocale)
        OnyxTheme { OnyxTooltipHost { TaskCenterButton(component) } }
    }
}

/**
 * 构造界面验证使用的任务快照，不触碰任何用户文件。
 *
 * @param id 稳定测试任务标识。
 * @param status 初始生命周期状态。
 * @return 具有确定进度和可读标题的文件传输任务。
 */
private fun windowTask(id: String, status: BackgroundTaskStatus): BackgroundTask {
    return BackgroundTask(
        id = id,
        kind = BackgroundTaskKind.COPY,
        title = I18nMessage(MessageKey.MSG_STRING_LITERAL, "Copy sample files"),
        status = status,
        canPause = true,
        detail = I18nMessage(
            MessageKey.MSG_STRING_LITERAL,
            "project-assets-and-reference-materials/sample-document-with-a-long-descriptive-name-final-review.txt",
        ),
        progress = 0.25f,
        totalBytes = 100L,
        processedBytes = 25L,
    )
}
