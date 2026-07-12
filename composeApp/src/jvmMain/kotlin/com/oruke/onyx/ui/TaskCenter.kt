package com.oruke.onyx.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import onyx.composeapp.generated.resources.action_clear_all_tasks
import onyx.composeapp.generated.resources.action_pause_task
import onyx.composeapp.generated.resources.action_resume_task
import onyx.composeapp.generated.resources.action_retry_task
import onyx.composeapp.generated.resources.label_task_center
import onyx.composeapp.generated.resources.label_task_errors_count
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** 任务状态底色相对于语义色的透明度。 */
private const val TASK_STATUS_BACKGROUND_ALPHA = 0.19f

/** 任务详情中最多直接展示的错误条数。 */
private const val MAX_VISIBLE_TASK_ERRORS = 5

// ── Jobs Bar ────────────────────────────────────────────────────────────────
// 嵌入主窗口底部，每个任务一个紧凑按钮+微型进度条。
// 仅当有活跃（QUEUED/RUNNING/PAUSED）或最近完成的任务时显示。

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
    var detailPanelExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        // ── 紧凑按钮行 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(palette.statusBarBackground)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 展开/折叠按钮
            IconButton(
                onClick = { detailPanelExpanded = !detailPanelExpanded },
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    key = if (detailPanelExpanded) AllIconsKeys.General.ArrowDown
                    else AllIconsKeys.General.ArrowRight,
                    contentDescription = stringResource(Res.string.label_task_center),
                    modifier = Modifier.size(12.dp),
                )
            }

            Text(
                text = stringResource(Res.string.label_task_center),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.mutedForeground,
            )

            Spacer(modifier = Modifier.width(4.dp))

            // 每个活跃任务一个微型按钮
            tasks.forEach { task ->
                JobChip(
                    task = task,
                    onPause = { onPauseTask(task.id) },
                    onResume = { onResumeTask(task.id) },
                    onRetry = { onRetryTask(task.id) },
                    onCancel = { onCancelTask(task.id) },
                    onDismiss = { onDismissTask(task.id) },
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // 全部清除
            if (tasks.isNotEmpty()) {
                IconButton(
                    onClick = onClearAllTasks,
                    modifier = Modifier.size(20.dp),
                ) {
                    Icon(
                        key = AllIconsKeys.Actions.Close,
                        contentDescription = stringResource(Res.string.action_clear_all_tasks),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }

        // ── 展开的详情面板 ──
        AnimatedVisibility(
            visible = detailPanelExpanded && tasks.isNotEmpty(),
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            TaskDetailPanel(
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

// ── Job Chip（单个任务按钮） ────────────────────────────────────────────

@Composable
private fun JobChip(
    task: BackgroundTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val isActive = task.status == BackgroundTaskStatus.QUEUED
            || task.status == BackgroundTaskStatus.RUNNING
            || task.status == BackgroundTaskStatus.PAUSED

    val chipBackground = when (task.status) {
        BackgroundTaskStatus.FAILED -> palette.error.copy(alpha = TASK_STATUS_BACKGROUND_ALPHA)
        BackgroundTaskStatus.SUCCEEDED -> palette.success.copy(alpha = TASK_STATUS_BACKGROUND_ALPHA)
        BackgroundTaskStatus.PAUSED -> palette.favorite.copy(alpha = TASK_STATUS_BACKGROUND_ALPHA)
        else -> palette.surfaceVariant
    }

    Row(
        modifier = Modifier
            .height(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(chipBackground)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 任务类型图标
        Icon(
            key = taskKindIcon(task.kind),
            contentDescription = null,
            modifier = Modifier.size(12.dp),
        )

        // 标题（截断显示）
        Text(
            text = task.title.resolve(),
            fontSize = 10.sp,
            color = palette.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(100.dp),
        )

        // 微型进度条
        if (isActive) {
            MiniProgressBar(
                progress = task.progress,
                status = task.status,
                modifier = Modifier.width(60.dp).height(4.dp),
            )

            // 百分比
            val percentText = task.progress?.let { "${(it * 100).toInt()}%" } ?: ""
            if (percentText.isNotEmpty()) {
                Text(
                    text = percentText,
                    fontSize = 9.sp,
                    color = palette.mutedForeground,
                )
            }
        } else {
            Text(
                text = taskStatusLabel(task.status),
                fontSize = 9.sp,
                color = palette.mutedForeground,
            )
        }

        // 暂停/恢复
        if (task.status == BackgroundTaskStatus.RUNNING) {
            IconButton(onClick = onPause, modifier = Modifier.size(14.dp)) {
                Icon(
                    key = AllIconsKeys.Actions.Pause,
                    contentDescription = stringResource(Res.string.action_pause_task),
                    modifier = Modifier.size(10.dp),
                )
            }
        } else if (task.status == BackgroundTaskStatus.PAUSED) {
            IconButton(onClick = onResume, modifier = Modifier.size(14.dp)) {
                Icon(
                    key = AllIconsKeys.RunConfigurations.TestState.Run,
                    contentDescription = stringResource(Res.string.action_resume_task),
                    modifier = Modifier.size(10.dp),
                )
            }
        }

        if (task.status == BackgroundTaskStatus.FAILED) {
            IconButton(onClick = onRetry, modifier = Modifier.size(14.dp)) {
                Icon(
                    key = AllIconsKeys.Actions.Refresh,
                    contentDescription = stringResource(Res.string.action_retry_task),
                    modifier = Modifier.size(10.dp),
                )
            }
        }

        // 取消/关闭
        IconButton(
            onClick = { if (isActive) onCancel() else onDismiss() },
            modifier = Modifier.size(14.dp),
        ) {
            Icon(
                key = AllIconsKeys.Actions.Close,
                contentDescription = stringResource(Res.string.action_cancel_task),
                modifier = Modifier.size(10.dp),
            )
        }
    }
}

// ── 任务详情面板（展开区域） ────────────────────────────────────────────

@Composable
private fun TaskDetailPanel(
    tasks: List<BackgroundTask>,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onDismissTask: (String) -> Unit,
) {
    val palette = LocalOnyxPalette.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(palette.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tasks.forEach { task ->
            TaskDetailRow(
                task = task,
                onPause = { onPauseTask(task.id) },
                onResume = { onResumeTask(task.id) },
                onRetry = { onRetryTask(task.id) },
                onCancel = { onCancelTask(task.id) },
                onDismiss = { onDismissTask(task.id) },
            )
        }
    }
}

// ── 单个任务详情行 ──────────────────────────────────────────────────────

@Composable
private fun TaskDetailRow(
    task: BackgroundTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val isActive = task.status == BackgroundTaskStatus.QUEUED
            || task.status == BackgroundTaskStatus.RUNNING
            || task.status == BackgroundTaskStatus.PAUSED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(palette.appBackground)
            .padding(8.dp),
    ) {
        TaskDetailHeader(task, onPause, onResume, onRetry) {
            if (isActive) onCancel() else onDismiss()
        }
        Spacer(modifier = Modifier.height(4.dp))
        TaskDetailProgress(task, isActive)
        Spacer(modifier = Modifier.height(4.dp))
        TaskProgressBar(progress = task.progress, status = task.status)
        TaskErrorList(task)
    }
}

/**
 * 渲染任务标题和状态相关操作按钮。
 *
 * @param task 当前后台任务。
 * @param onPause 暂停回调。
 * @param onResume 恢复回调。
 * @param onRetry 重试回调。
 * @param onClose 取消或移除回调。
 */
@Composable
private fun TaskDetailHeader(
    task: BackgroundTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(taskKindIcon(task.kind), null, Modifier.size(14.dp))
            Text(
                text = task.title.resolve(),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.foreground,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            when (task.status) {
                BackgroundTaskStatus.RUNNING -> TaskActionIcon(
                    onPause,
                    AllIconsKeys.Actions.Pause,
                    stringResource(Res.string.action_pause_task),
                )
                BackgroundTaskStatus.PAUSED -> TaskActionIcon(
                    onResume,
                    AllIconsKeys.RunConfigurations.TestState.Run,
                    stringResource(Res.string.action_resume_task),
                )
                else -> Unit
            }
            if (task.status == BackgroundTaskStatus.FAILED) {
                TaskActionIcon(onRetry, AllIconsKeys.Actions.Refresh, stringResource(Res.string.action_retry_task))
            }
            TaskActionIcon(onClose, AllIconsKeys.Actions.Close, stringResource(Res.string.action_cancel_task))
        }
    }
}

/**
 * 渲染任务操作图标。
 *
 * @param onClick 点击回调。
 * @param icon Jewel 图标键。
 * @param description 无障碍说明。
 */
@Composable
private fun TaskActionIcon(
    onClick: () -> Unit,
    icon: org.jetbrains.jewel.ui.icon.IconKey,
    description: String,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(18.dp)) {
        Icon(icon, description, Modifier.size(12.dp))
    }
}

/**
 * 渲染任务当前文件、计数、速度和剩余时间。
 *
 * @param task 当前后台任务。
 * @param isActive 任务是否仍处于活动状态。
 */
@Composable
private fun TaskDetailProgress(task: BackgroundTask, isActive: Boolean) {
    val palette = LocalOnyxPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val detailText = task.currentFileName?.takeIf { isActive } ?: task.detail.resolve()
        Text(
            text = detailText,
            fontSize = 11.sp,
            color = palette.mutedForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        buildProgressLabel(task).takeIf(String::isNotEmpty)?.let { label ->
            Text(label, fontSize = 10.sp, color = palette.mutedForeground)
        }
    }
    val timingLabel = task.timingLabel(isActive)
    if (timingLabel != null) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(timingLabel, fontSize = 10.sp, color = palette.mutedForeground)
        }
    }
}

/**
 * 计算任务速度和剩余时间标签。
 *
 * @param isActive 任务是否仍处于活动状态。
 * @return 有足够进度信息时返回标签，否则返回 `null`。
 */
private fun BackgroundTask.timingLabel(isActive: Boolean): String? {
    val elapsedMs = System.currentTimeMillis() - startTimeMillis
    val hasTiming = isActive && processedBytes > 0 && startTimeMillis > 0 && elapsedMs > 0
    return if (hasTiming) {
        val speedBps = processedBytes * 1000.0 / elapsedMs
        val remaining = if (speedBps > 0 && totalBytes > processedBytes) {
            formatDuration(((totalBytes - processedBytes) / speedBps).toLong())
        } else {
            null
        }
        listOfNotNull(formatSpeed(speedBps), remaining).joinToString("  ·  ")
    } else {
        null
    }
}

/**
 * 渲染任务错误摘要和有限数量的明细。
 *
 * @param task 当前后台任务。
 */
@Composable
private fun TaskErrorList(task: BackgroundTask) {
    if (task.errors.isNotEmpty()) {
        val palette = LocalOnyxPalette.current
        Spacer(modifier = Modifier.height(4.dp))
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
}
