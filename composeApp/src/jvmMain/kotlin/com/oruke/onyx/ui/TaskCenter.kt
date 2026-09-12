package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.component.TaskCenterFilter
import com.oruke.onyx.app.component.TaskCenterIntent
import com.oruke.onyx.app.component.TaskCenterState
import com.oruke.onyx.app.component.forTaskCenterFilter
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.rememberOnyxButtonStyle
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_close_window
import onyx.composeapp.generated.resources.action_task_clear_finished
import onyx.composeapp.generated.resources.label_task_background_hint
import onyx.composeapp.generated.resources.label_task_empty
import onyx.composeapp.generated.resources.label_task_filter_active
import onyx.composeapp.generated.resources.label_task_filter_all
import onyx.composeapp.generated.resources.label_task_filter_failed
import onyx.composeapp.generated.resources.label_task_filter_finished
import onyx.composeapp.generated.resources.label_task_filter_empty
import onyx.composeapp.generated.resources.label_task_overall_progress
import onyx.composeapp.generated.resources.label_task_remaining_time
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalTextStyle
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.defaultButtonStyle
import org.jetbrains.jewel.ui.theme.outlinedButtonStyle

/**
 * 独立任务窗口的内容：顶部分类、可伸缩的任务列表与底部操作栏。
 * 沿用 Jewel 的紧凑工具窗口风格；这里只渲染快照并派发事件，关闭窗口不会取消任务。
 *
 * @param state 全应用共享的任务窗口状态。
 * @param tasks 全部后台任务快照。
 * @param listState 窗口隐藏期间仍保留的列表滚动位置。
 * @param dispatch 统一任务中心事件入口。
 * @return 无返回值。
 */
@Composable
internal fun TaskCenterContent(
    state: TaskCenterState,
    tasks: List<BackgroundTask>,
    listState: LazyListState,
    dispatch: (TaskCenterIntent) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    val summary = remember(tasks) { tasks.toTaskCenterSummary() }
    val visibleTasks = remember(tasks, state.filter) { tasks.forTaskCenterFilter(state.filter) }
    val outlinedButtonStyle = rememberOnyxButtonStyle(JewelTheme.outlinedButtonStyle)
    Column(Modifier.fillMaxSize().background(palette.appBackground).testTag("task-center")) {
        TaskCenterFilters(state.filter, tasks, dispatch)
        Divider(Orientation.Horizontal)
        if (summary.shouldShowAggregateProgress()) {
            TaskCenterOverview(summary)
            Divider(Orientation.Horizontal)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (visibleTasks.isEmpty()) {
                Text(
                    text = stringResource(
                        if (tasks.isEmpty()) Res.string.label_task_empty else Res.string.label_task_filter_empty,
                    ),
                    color = palette.mutedForeground,
                    fontSize = appearance.listFontSize,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            } else {
                TaskCenterList(
                    tasks = visibleTasks,
                    listState = listState,
                    onPauseTask = { dispatch(TaskCenterIntent.Pause(it)) },
                    onResumeTask = { dispatch(TaskCenterIntent.Resume(it)) },
                    onRetryTask = { dispatch(TaskCenterIntent.Retry(it)) },
                    onCancelTask = { dispatch(TaskCenterIntent.Cancel(it)) },
                    onDismissTask = { dispatch(TaskCenterIntent.Dismiss(it)) },
                )
            }
        }
        Divider(Orientation.Horizontal)
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Res.string.label_task_background_hint),
                color = palette.mutedForeground,
                fontSize = appearance.headerFontSize,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(
                    onClick = { dispatch(TaskCenterIntent.ClearFinished) },
                    enabled = tasks.forTaskCenterFilter(TaskCenterFilter.FINISHED).isNotEmpty(),
                    modifier = Modifier.testTag("task-clear-finished"),
                    style = outlinedButtonStyle,
                ) { Text(stringResource(Res.string.action_task_clear_finished), style = LocalTextStyle.current) }
                OutlinedButton(
                    onClick = { dispatch(TaskCenterIntent.Close) },
                    modifier = Modifier.testTag("task-window-close"),
                    style = outlinedButtonStyle,
                ) { Text(stringResource(Res.string.action_close_window), style = LocalTextStyle.current) }
            }
        }
    }
}

/**
 * 渲染带数量的任务分类，窄窗口自动换行以保证全部入口可达。
 *
 * @param filter 当前选中的分类。
 * @param tasks 全部后台任务快照。
 * @param dispatch 统一任务中心事件入口。
 * @return 无返回值。
 */
@Composable
private fun TaskCenterFilters(
    filter: TaskCenterFilter,
    tasks: List<BackgroundTask>,
    dispatch: (TaskCenterIntent) -> Unit,
) {
    val defaultButtonStyle = rememberOnyxButtonStyle(JewelTheme.defaultButtonStyle)
    val outlinedButtonStyle = rememberOnyxButtonStyle(JewelTheme.outlinedButtonStyle)
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TaskCenterFilter.entries.forEach { candidate ->
            val label = stringResource(
                when (candidate) {
                    TaskCenterFilter.ALL -> Res.string.label_task_filter_all
                    TaskCenterFilter.ACTIVE -> Res.string.label_task_filter_active
                    TaskCenterFilter.FAILED -> Res.string.label_task_filter_failed
                    TaskCenterFilter.FINISHED -> Res.string.label_task_filter_finished
                },
                tasks.forTaskCenterFilter(candidate).size,
            )
            val modifier = Modifier.testTag("task-filter-${candidate.name}").semantics {
                selected = candidate == filter
            }
            if (candidate == filter) {
                DefaultButton(
                    onClick = { dispatch(TaskCenterIntent.SetFilter(candidate)) },
                    modifier = modifier,
                    style = defaultButtonStyle,
                ) { Text(label, style = LocalTextStyle.current) }
            } else {
                OutlinedButton(
                    onClick = { dispatch(TaskCenterIntent.SetFilter(candidate)) },
                    modifier = modifier,
                    style = outlinedButtonStyle,
                ) { Text(label, style = LocalTextStyle.current) }
            }
        }
    }
}

/**
 * 多任务时展示按字节加权的总进度、合计速度和预计剩余时间。
 *
 * @param summary 任务中心聚合状态；调用方仅在多个活动任务时渲染。
 * @return 无返回值。
 */
@Composable
private fun TaskCenterOverview(summary: TaskCenterSummary) {
    val appearance = LocalOnyxAppearance.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(Res.string.label_task_overall_progress),
            fontSize = appearance.headerFontSize,
            fontWeight = FontWeight.SemiBold,
        )
        val metrics = buildList {
            formatByteProgress(summary.processedBytes, summary.totalBytes).takeIf(String::isNotEmpty)?.let(::add)
            summary.bytesPerSecond?.let { add(formatSpeed(it)) }
            summary.estimatedRemainingSeconds?.let {
                add(stringResource(Res.string.label_task_remaining_time, formatTaskDuration(it)))
            }
        }
        if (metrics.isNotEmpty()) {
            Text(
                text = metrics.joinToString(TASK_METRIC_SEPARATOR),
                fontSize = appearance.headerFontSize,
                color = LocalOnyxPalette.current.mutedForeground,
            )
        }
        TaskProgressBar(
            progress = summary.progress,
            status = if (summary.pausedCount == summary.activeCount) {
                BackgroundTaskStatus.PAUSED
            } else {
                BackgroundTaskStatus.RUNNING
            },
            showIndeterminateOverlay = summary.hasIndeterminateRunning,
            modifier = Modifier.fillMaxWidth().height(5.dp),
        )
    }
}

/** 任务指标之间的分隔符。 */
internal const val TASK_METRIC_SEPARATOR = "  ·  "
/** 百分比换算系数。 */
internal const val TASK_PERCENT_MULTIPLIER = 100f
