package com.oruke.onyx.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.formatFileSize
import com.oruke.onyx.ui.theme.resolve
import com.oruke.onyx.ui.theme.taskStatusLabel
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_cancel_task
import onyx.composeapp.generated.resources.action_clear_all_tasks
import onyx.composeapp.generated.resources.action_pause_task
import onyx.composeapp.generated.resources.action_resume_task
import onyx.composeapp.generated.resources.label_task_center
import onyx.composeapp.generated.resources.label_task_errors_count
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// ── Jobs Bar ────────────────────────────────────────────────────────────────
// 嵌入主窗口底部，每个任务一个紧凑按钮+微型进度条。
// 仅当有活跃（QUEUED/RUNNING/PAUSED）或最近完成的任务时显示。

@Composable
internal fun JobsBar(
    tasks: List<BackgroundTask>,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
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
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val isActive = task.status == BackgroundTaskStatus.QUEUED
            || task.status == BackgroundTaskStatus.RUNNING
            || task.status == BackgroundTaskStatus.PAUSED

    val chipBackground = when (task.status) {
        BackgroundTaskStatus.FAILED -> Color(0x30D74E4E)
        BackgroundTaskStatus.SUCCEEDED -> Color(0x304DAA57)
        BackgroundTaskStatus.PAUSED -> Color(0x30E8A317)
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

// ── 微型进度条 ──────────────────────────────────────────────────────────

@Composable
internal fun MiniProgressBar(
    progress: Float?,
    status: BackgroundTaskStatus,
    modifier: Modifier = Modifier,
) {
    val palette = LocalOnyxPalette.current
    val targetProgress = (progress ?: 0f).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 160),
    )

    val barColor = when (status) {
        BackgroundTaskStatus.FAILED -> Color(0xFFD74E4E)
        BackgroundTaskStatus.CANCELLED -> palette.disabledForeground
        BackgroundTaskStatus.SUCCEEDED -> Color(0xFF4DAA57)
        BackgroundTaskStatus.PAUSED -> Color(0xFFE8A317)
        BackgroundTaskStatus.QUEUED, BackgroundTaskStatus.RUNNING -> palette.accent
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(palette.outlineVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animatedProgress)
                .background(barColor, RoundedCornerShape(2.dp)),
        )
    }
}

// ── 任务详情面板（展开区域） ────────────────────────────────────────────

@Composable
private fun TaskDetailPanel(
    tasks: List<BackgroundTask>,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
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
        // 第一行：标题 + 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (task.status == BackgroundTaskStatus.RUNNING) {
                    IconButton(onClick = onPause, modifier = Modifier.size(18.dp)) {
                        Icon(
                            key = AllIconsKeys.Actions.Pause,
                            contentDescription = stringResource(Res.string.action_pause_task),
                            modifier = Modifier.size(12.dp),
                        )
                    }
                } else if (task.status == BackgroundTaskStatus.PAUSED) {
                    IconButton(onClick = onResume, modifier = Modifier.size(18.dp)) {
                        Icon(
                            key = AllIconsKeys.RunConfigurations.TestState.Run,
                            contentDescription = stringResource(Res.string.action_resume_task),
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
                IconButton(
                    onClick = { if (isActive) onCancel() else onDismiss() },
                    modifier = Modifier.size(18.dp),
                ) {
                    Icon(
                        key = AllIconsKeys.Actions.Close,
                        contentDescription = stringResource(Res.string.action_cancel_task),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 第二行：进度详情
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 当前文件名或详情
            val currentFileName = task.currentFileName
            val detailText = when {
                currentFileName != null && isActive -> currentFileName
                else -> task.detail.resolve()
            }
            Text(
                text = detailText,
                fontSize = 11.sp,
                color = palette.mutedForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            // 字节进度 + 文件计数
            val progressLabel = buildProgressLabel(task)
            if (progressLabel.isNotEmpty()) {
                Text(
                    text = progressLabel,
                    fontSize = 10.sp,
                    color = palette.mutedForeground,
                )
            }
        }

        // 第二·五行：速度 + 剩余时间
        if (isActive && task.processedBytes > 0 && task.startTimeMillis > 0) {
            val elapsedMs = System.currentTimeMillis() - task.startTimeMillis
            val speedBps = if (elapsedMs > 0) task.processedBytes * 1000.0 / elapsedMs else 0.0
            val speedLabel = formatSpeed(speedBps)
            val remainingLabel = if (speedBps > 0 && task.totalBytes > task.processedBytes) {
                val remainingBytes = task.totalBytes - task.processedBytes
                val remainingSec = (remainingBytes / speedBps).toLong()
                formatDuration(remainingSec)
            } else null

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildString {
                        append(speedLabel)
                        if (remainingLabel != null) {
                            append("  ·  ")
                            append(remainingLabel)
                        }
                    },
                    fontSize = 10.sp,
                    color = palette.mutedForeground,
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 第三行：进度条
        TaskProgressBar(
            progress = task.progress,
            status = task.status,
        )

        // 错误列表
        if (task.errors.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.label_task_errors_count, task.errors.size),
                fontSize = 10.sp,
                color = Color(0xFFD74E4E),
            )
            task.errors.take(5).forEach { error ->
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
}

// ── 完整进度条（详情面板用） ────────────────────────────────────────────

@Composable
internal fun TaskProgressBar(
    progress: Float?,
    status: BackgroundTaskStatus,
) {
    val targetProgress = when {
        progress != null -> progress.coerceIn(0f, 1f)
        status == BackgroundTaskStatus.SUCCEEDED -> 1f
        else -> 0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 160),
    )
    val barColor = when (status) {
        BackgroundTaskStatus.FAILED -> Color(0xFFD74E4E)
        BackgroundTaskStatus.CANCELLED -> LocalOnyxPalette.current.disabledForeground
        BackgroundTaskStatus.SUCCEEDED -> Color(0xFF4DAA57)
        BackgroundTaskStatus.PAUSED -> Color(0xFFE8A317)
        BackgroundTaskStatus.QUEUED, BackgroundTaskStatus.RUNNING -> LocalOnyxPalette.current.accent
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(LocalOnyxPalette.current.outlineVariant, RoundedCornerShape(2.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedProgress)
                .height(4.dp)
                .background(barColor, RoundedCornerShape(2.dp)),
        )
    }
}

// ── 工具函数 ────────────────────────────────────────────────────────────

private fun taskKindIcon(kind: BackgroundTaskKind) = when (kind) {
    BackgroundTaskKind.COPY -> AllIconsKeys.Actions.Copy
    BackgroundTaskKind.MOVE -> AllIconsKeys.Actions.MenuCut
    BackgroundTaskKind.DELETE -> AllIconsKeys.General.Delete
    BackgroundTaskKind.EXTRACT -> AllIconsKeys.Nodes.ExtractedFolder
    BackgroundTaskKind.RENAME -> AllIconsKeys.Actions.Edit
    BackgroundTaskKind.EXTERNAL_IMPORT -> AllIconsKeys.Actions.Download
}

private fun buildProgressLabel(task: BackgroundTask): String = buildString {
    if (task.totalBytes > 0) {
        append(formatFileSize(task.processedBytes))
        append(" / ")
        append(formatFileSize(task.totalBytes))
        if (task.totalCount > 1) {
            append("  (${task.processedCount}/${task.totalCount})")
        }
    } else if (task.totalCount > 0) {
        append("${task.processedCount} / ${task.totalCount}")
    }
}

private fun formatSpeed(bytesPerSecond: Double): String {
    return when {
        bytesPerSecond >= 1_073_741_824 -> "%.1f GB/s".format(bytesPerSecond / 1_073_741_824)
        bytesPerSecond >= 1_048_576 -> "%.1f MB/s".format(bytesPerSecond / 1_048_576)
        bytesPerSecond >= 1_024 -> "%.0f KB/s".format(bytesPerSecond / 1_024)
        else -> "%.0f B/s".format(bytesPerSecond)
    }
}

private fun formatDuration(seconds: Long): String {
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}
