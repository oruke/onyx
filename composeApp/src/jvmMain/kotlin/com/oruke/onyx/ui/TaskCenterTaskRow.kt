package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.resolve
import com.oruke.onyx.ui.theme.taskStatusLabel
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_cancel_task
import onyx.composeapp.generated.resources.action_pause_task
import onyx.composeapp.generated.resources.action_remove_task
import onyx.composeapp.generated.resources.action_resume_task
import onyx.composeapp.generated.resources.action_retry_task
import onyx.composeapp.generated.resources.label_task_errors_count
import onyx.composeapp.generated.resources.label_task_item_progress
import onyx.composeapp.generated.resources.label_task_remaining_time
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlin.math.roundToInt

/**
 * 使用虚拟化列表展示当前任务详情。
 *
 * @param tasks 当前任务快照。
 * @param onPauseTask 暂停任务回调。
 * @param onResumeTask 恢复任务回调。
 * @param onRetryTask 重试任务回调。
 * @param onCancelTask 取消任务回调。
 * @param onDismissTask 移除终态任务回调。
 */
@Composable
internal fun TaskCenterList(
    tasks: List<BackgroundTask>,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onDismissTask: (String) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val listState = rememberLazyListState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TASK_LIST_HEIGHT)
            .background(palette.appBackground),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(end = TASK_SCROLLBAR_INSET),
        ) {
            items(
                items = tasks,
                key = { task -> task.id },
            ) { task ->
                TaskCenterTaskRow(
                    task = task,
                    onPause = { onPauseTask(task.id) },
                    onResume = { onResumeTask(task.id) },
                    onRetry = { onRetryTask(task.id) },
                    onCancel = { onCancelTask(task.id) },
                    onDismiss = { onDismissTask(task.id) },
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.outlineVariant),
                )
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

/**
 * 渲染单个任务的标题、当前文件、进度、速度、ETA 和操作按钮。
 *
 * @param task 当前后台任务。
 * @param onPause 暂停回调。
 * @param onResume 恢复回调。
 * @param onRetry 重试回调。
 * @param onCancel 取消回调。
 * @param onDismiss 移除回调。
 */
@Composable
private fun TaskCenterTaskRow(
    task: BackgroundTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.appBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                key = taskKindIcon(task.kind),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = task.title.resolve(),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = taskStatusLabel(task.status),
                fontSize = 10.sp,
                color = taskProgressColor(task.status),
            )
            TaskRowActions(task, onPause, onResume, onRetry, onCancel, onDismiss)
        }

        Text(
            text = task.currentDetailText(),
            fontSize = 10.sp,
            color = palette.mutedForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        TaskProgressBar(
            progress = task.progress,
            status = task.status,
            modifier = Modifier.fillMaxWidth().height(5.dp),
        )

        taskMetrics(task).takeIf(String::isNotEmpty)?.let { metrics ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = metrics,
                    fontSize = 10.sp,
                    color = palette.mutedForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TaskErrorList(task)
    }
}

/**
 * 渲染任务生命周期对应的操作按钮。
 *
 * @param task 当前后台任务。
 * @param onPause 暂停回调。
 * @param onResume 恢复回调。
 * @param onRetry 重试回调。
 * @param onCancel 取消回调。
 * @param onDismiss 移除回调。
 */
@Composable
private fun TaskRowActions(
    task: BackgroundTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (task.canPause) {
            when (task.status) {
                BackgroundTaskStatus.RUNNING -> TaskActionIcon(
                    onClick = onPause,
                    icon = AllIconsKeys.Actions.Pause,
                    description = stringResource(Res.string.action_pause_task),
                )
                BackgroundTaskStatus.PAUSED -> TaskActionIcon(
                    onClick = onResume,
                    icon = AllIconsKeys.RunConfigurations.TestState.Run,
                    description = stringResource(Res.string.action_resume_task),
                )
                else -> Unit
            }
        }
        if (task.status == BackgroundTaskStatus.FAILED) {
            TaskActionIcon(
                onClick = onRetry,
                icon = AllIconsKeys.Actions.Refresh,
                description = stringResource(Res.string.action_retry_task),
            )
        }
        val isActive = task.status.isActiveTaskStatus()
        TaskActionIcon(
            onClick = if (isActive) onCancel else onDismiss,
            icon = AllIconsKeys.Actions.Close,
            description = stringResource(
                if (isActive) Res.string.action_cancel_task else Res.string.action_remove_task
            ),
        )
    }
}

/**
 * 渲染带统一工具提示的任务操作图标。
 *
 * @param onClick 点击回调。
 * @param icon Jewel 图标键。
 * @param description 本地化操作说明。
 */
@Composable
private fun TaskActionIcon(
    onClick: () -> Unit,
    icon: IconKey,
    description: String,
) {
    OnyxTooltip(text = description) {
        IconButton(onClick = onClick, modifier = Modifier.size(18.dp)) {
            Icon(icon, description, Modifier.size(12.dp))
        }
    }
}

/**
 * 返回任务当前最有信息量的详情文本。
 *
 * @return 活动任务优先返回当前文件名，否则返回阶段详情。
 */
@Composable
private fun BackgroundTask.currentDetailText(): String {
    return currentFileName
        ?.takeIf { status.isActiveTaskStatus() }
        ?: detail.resolve()
}

/**
 * 构造任务字节、条目、百分比、速度和 ETA 指标。
 *
 * @param task 当前后台任务。
 * @return 本地化指标文本。
 */
@Composable
private fun taskMetrics(task: BackgroundTask): String {
    val parts = mutableListOf<String>()
    formatByteProgress(task.processedBytes, task.totalBytes)
        .takeIf(String::isNotEmpty)
        ?.let(parts::add)
    if (task.totalCount > 0) {
        parts += stringResource(
            Res.string.label_task_item_progress,
            task.processedCount,
            task.totalCount,
        )
    }
    task.progress?.let { progress -> parts += "${(progress * TASK_PERCENT_MULTIPLIER).roundToInt()}%" }
    task.bytesPerSecond?.let { speed -> parts += formatSpeed(speed) }
    task.estimatedRemainingSeconds?.let { seconds ->
        parts += stringResource(
            Res.string.label_task_remaining_time,
            formatTaskDuration(seconds),
        )
    }
    return parts.joinToString(TASK_METRIC_SEPARATOR)
}

/**
 * 渲染错误摘要和有限数量的文件明细。
 *
 * @param task 当前后台任务。
 */
@Composable
private fun TaskErrorList(task: BackgroundTask) {
    if (task.errors.isEmpty()) return
    val palette = LocalOnyxPalette.current
    Spacer(modifier = Modifier.height(1.dp))
    Text(
        text = stringResource(Res.string.label_task_errors_count, task.errors.size),
        fontSize = 10.sp,
        color = palette.error,
    )
    task.errors.take(MAX_VISIBLE_TASK_ERRORS).forEach { error ->
        Text(
            text = "${error.fileName}: ${error.message}",
            fontSize = 9.sp,
            color = palette.mutedForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 展开任务列表固定高度。 */
private val TASK_LIST_HEIGHT = 230.dp
/** 为垂直滚动条预留的内容右侧间距。 */
private val TASK_SCROLLBAR_INSET = 10.dp
/** 单项任务最多直接展示的错误数。 */
private const val MAX_VISIBLE_TASK_ERRORS = 3
