package com.oruke.onyx.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_clear_finished_tasks
import onyx.composeapp.generated.resources.label_task_center
import onyx.composeapp.generated.resources.label_task_overall_progress
import onyx.composeapp.generated.resources.label_task_paused_count
import onyx.composeapp.generated.resources.label_task_queued_count
import onyx.composeapp.generated.resources.label_task_recent
import onyx.composeapp.generated.resources.label_task_remaining_time
import onyx.composeapp.generated.resources.label_task_running_count
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlin.math.roundToInt

/**
 * 主窗口底部任务栏与可展开任务中心。
 *
 * @param tasks 当前任务快照。
 * @param onPauseTask 暂停任务回调。
 * @param onResumeTask 恢复任务回调。
 * @param onRetryTask 重试任务回调。
 * @param onCancelTask 取消任务回调。
 * @param onDismissTask 移除终态任务回调。
 * @param onClearAllTasks 清除全部终态任务回调；仅在没有活动任务时暴露入口。
 * @param modifier 外部布局修饰符。
 */
@Composable
internal fun JobsBar(
    tasks: List<BackgroundTask>,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onDismissTask: (String) -> Unit,
    onClearAllTasks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalOnyxPalette.current
    val summary = remember(tasks) { tasks.toTaskCenterSummary() }
    var detailPanelExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        TaskCenterCollapsedBar(
            summary = summary,
            expanded = detailPanelExpanded,
            onToggleExpanded = { detailPanelExpanded = !detailPanelExpanded },
            onClearFinished = onClearAllTasks,
        )

        AnimatedVisibility(
            visible = detailPanelExpanded && tasks.isNotEmpty(),
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface),
            ) {
                TaskCenterOverview(summary)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.outlineVariant),
                )
                TaskCenterList(
                    tasks = tasks,
                    onPauseTask = onPauseTask,
                    onResumeTask = onResumeTask,
                    onRetryTask = onRetryTask,
                    onCancelTask = onCancelTask,
                    onDismissTask = onDismissTask,
                )
            }
        }
    }
}

/**
 * 渲染任务中心折叠总览条。
 *
 * @param summary 任务中心聚合状态。
 * @param expanded 详情区域是否展开。
 * @param onToggleExpanded 切换详情区域回调。
 * @param onClearFinished 清理终态任务回调。
 */
@Composable
private fun TaskCenterCollapsedBar(
    summary: TaskCenterSummary,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onClearFinished: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(COLLAPSED_BAR_HEIGHT)
            .background(palette.statusBarBackground)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OnyxTooltip(text = stringResource(Res.string.label_task_center)) {
            IconButton(onClick = onToggleExpanded, modifier = Modifier.size(20.dp)) {
                Icon(
                    key = if (expanded) AllIconsKeys.General.ArrowDown else AllIconsKeys.General.ArrowRight,
                    contentDescription = stringResource(Res.string.label_task_center),
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Text(
            text = stringResource(Res.string.label_task_center),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.foreground,
        )
        Text(
            text = taskStatusSummary(summary),
            fontSize = 10.sp,
            color = palette.mutedForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TaskProgressBar(
            progress = summary.progress,
            status = summary.overallStatus(),
            showIndeterminateOverlay = summary.hasIndeterminateRunning,
            modifier = Modifier
                .weight(1f)
                .widthIn(min = 72.dp, max = 280.dp)
                .height(5.dp),
        )
        CollapsedTaskMetrics(summary)
        if (summary.activeCount == 0 && summary.terminalCount > 0) {
            OnyxTooltip(text = stringResource(Res.string.action_clear_finished_tasks)) {
                IconButton(onClick = onClearFinished, modifier = Modifier.size(20.dp)) {
                    Icon(
                        key = AllIconsKeys.General.Delete,
                        contentDescription = stringResource(Res.string.action_clear_finished_tasks),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

/**
 * 渲染折叠总览条右侧的百分比、速度和 ETA。
 *
 * @param summary 任务中心聚合状态。
 */
@Composable
private fun CollapsedTaskMetrics(summary: TaskCenterSummary) {
    val palette = LocalOnyxPalette.current
    summary.progress?.let { progress ->
        Text(
            text = "${(progress * TASK_PERCENT_MULTIPLIER).roundToInt()}%",
            fontSize = 10.sp,
            color = palette.foreground,
        )
    }
    summary.bytesPerSecond?.let { speed ->
        Text(text = formatSpeed(speed), fontSize = 10.sp, color = palette.mutedForeground)
    }
    summary.estimatedRemainingSeconds?.let { seconds ->
        Text(
            text = stringResource(
                Res.string.label_task_remaining_time,
                formatTaskDuration(seconds),
            ),
            fontSize = 10.sp,
            color = palette.mutedForeground,
        )
    }
}

/**
 * 渲染展开区域的总体字节、速度、ETA 和总进度。
 *
 * @param summary 任务中心聚合状态。
 */
@Composable
private fun TaskCenterOverview(summary: TaskCenterSummary) {
    val palette = LocalOnyxPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(Res.string.label_task_overall_progress),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.foreground,
            )
            Text(
                text = taskStatusSummary(summary),
                fontSize = 10.sp,
                color = palette.mutedForeground,
            )
            Spacer(modifier = Modifier.weight(1f))
            overviewMetrics(summary).takeIf(String::isNotEmpty)?.let { metrics ->
                Text(
                    text = metrics,
                    fontSize = 10.sp,
                    color = palette.mutedForeground,
                )
            }
        }
        TaskProgressBar(
            progress = summary.progress,
            status = summary.overallStatus(),
            showIndeterminateOverlay = summary.hasIndeterminateRunning,
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )
    }
}

/**
 * 构造运行、排队和暂停数量摘要。
 *
 * @param summary 任务中心聚合状态。
 * @return 本地化状态摘要。
 */
@Composable
private fun taskStatusSummary(summary: TaskCenterSummary): String {
    val parts = mutableListOf<String>()
    if (summary.runningCount > 0) {
        parts += stringResource(Res.string.label_task_running_count, summary.runningCount)
    }
    if (summary.queuedCount > 0) {
        parts += stringResource(Res.string.label_task_queued_count, summary.queuedCount)
    }
    if (summary.pausedCount > 0) {
        parts += stringResource(Res.string.label_task_paused_count, summary.pausedCount)
    }
    if (parts.isEmpty()) {
        parts += stringResource(Res.string.label_task_recent)
    }
    return parts.joinToString(TASK_METRIC_SEPARATOR)
}

/**
 * 构造总览右侧的字节、速度和 ETA 摘要。
 *
 * @param summary 任务中心聚合状态。
 * @return 本地化总览指标。
 */
@Composable
private fun overviewMetrics(summary: TaskCenterSummary): String {
    val parts = mutableListOf<String>()
    formatByteProgress(summary.processedBytes, summary.totalBytes)
        .takeIf(String::isNotEmpty)
        ?.let(parts::add)
    summary.bytesPerSecond?.let { speed -> parts += formatSpeed(speed) }
    summary.estimatedRemainingSeconds?.let { seconds ->
        parts += stringResource(
            Res.string.label_task_remaining_time,
            formatTaskDuration(seconds),
        )
    }
    return parts.joinToString(TASK_METRIC_SEPARATOR)
}

/**
 * 将聚合状态映射为总进度条语义状态。
 *
 * @return 总体任务状态。
 */
private fun TaskCenterSummary.overallStatus(): BackgroundTaskStatus {
    return when {
        activeCount == 0 && terminalCount > 0 -> BackgroundTaskStatus.SUCCEEDED
        activeCount > 0 && pausedCount == activeCount -> BackgroundTaskStatus.PAUSED
        activeCount > 0 -> BackgroundTaskStatus.RUNNING
        else -> BackgroundTaskStatus.QUEUED
    }
}

/** 折叠任务栏高度。 */
private val COLLAPSED_BAR_HEIGHT = 30.dp
/** 任务指标之间的分隔符。 */
internal const val TASK_METRIC_SEPARATOR = "  ·  "
/** 百分比换算系数。 */
internal const val TASK_PERCENT_MULTIPLIER = 100f
