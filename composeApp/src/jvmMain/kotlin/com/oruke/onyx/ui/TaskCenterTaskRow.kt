package com.oruke.onyx.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.resolve
import com.oruke.onyx.ui.theme.taskStatusLabel
import kotlin.math.roundToInt
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_cancel_task
import onyx.composeapp.generated.resources.action_pause_task
import onyx.composeapp.generated.resources.action_remove_task
import onyx.composeapp.generated.resources.action_resume_task
import onyx.composeapp.generated.resources.action_retry_task
import onyx.composeapp.generated.resources.label_task_item_progress
import onyx.composeapp.generated.resources.label_task_remaining_time
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * 使用虚拟化列表展示当前任务详情与结构化错误列表。
 *
 * @param tasks 当前任务快照。
 * @param listState 由窗口宿主持有的滚动状态，隐藏与再次打开时复用。
 * @param expandedErrorTaskIds 当前已展开完整错误列表的任务 ID 集合。
 * @param copiedFeedbackKey 最近一次成功写入剪贴板的反馈键。
 * @param onPauseTask 暂停任务回调。
 * @param onResumeTask 恢复任务回调。
 * @param onRetryTask 重试任务回调。
 * @param onCancelTask 取消任务回调。
 * @param onDismissTask 移除终态任务回调。
 * @param onToggleTaskErrors 展开或收起指定任务错误列表回调。
 * @param onCopyErrorText 复制错误报告或文件名到系统剪贴板回调。
 */
@Composable
internal fun TaskCenterList(
    tasks: List<BackgroundTask>,
    listState: LazyListState,
    expandedErrorTaskIds: Set<String> = emptySet(),
    copiedFeedbackKey: String? = null,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onDismissTask: (String) -> Unit,
    onToggleTaskErrors: (String) -> Unit = {},
    onCopyErrorText: (String, String) -> Unit = { _, _ -> },
) {
    val palette = LocalOnyxPalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.appBackground),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(end = TASK_SCROLLBAR_INSET).testTag("task-list"),
        ) {
            items(
                items = tasks,
                key = { task -> task.id },
            ) { task ->
                TaskCenterTaskRow(
                    task = task,
                    errorsExpanded = task.id in expandedErrorTaskIds,
                    copiedFeedbackKey = copiedFeedbackKey,
                    onPause = { onPauseTask(task.id) },
                    onResume = { onResumeTask(task.id) },
                    onRetry = { onRetryTask(task.id) },
                    onCancel = { onCancelTask(task.id) },
                    onDismiss = { onDismissTask(task.id) },
                    onToggleErrors = { onToggleTaskErrors(task.id) },
                    onCopyErrorText = onCopyErrorText,
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
 * 渲染单个任务的标题、当前文件、进度、速度、ETA、操作按钮与结构化错误区。
 */
@Composable
private fun TaskCenterTaskRow(
    task: BackgroundTask,
    errorsExpanded: Boolean,
    copiedFeedbackKey: String?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onToggleErrors: () -> Unit,
    onCopyErrorText: (String, String) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("task-row-${task.id}")
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
                fontSize = appearance.listFontSize,
                fontWeight = FontWeight.SemiBold,
                color = palette.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = taskStatusLabel(task.status),
                fontSize = appearance.headerFontSize,
                color = taskProgressColor(task.status),
            )
            TaskRowActions(task, onPause, onResume, onRetry, onCancel, onDismiss)
        }

        TaskDetailLines(task)

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
                    fontSize = appearance.headerFontSize,
                    color = palette.mutedForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        TaskErrorSection(
            task = task,
            expanded = errorsExpanded,
            copiedFeedbackKey = copiedFeedbackKey,
            onToggleExpand = onToggleErrors,
            onCopyErrorText = onCopyErrorText,
        )
    }
}

/**
 * 渲染任务生命周期对应的操作按钮。
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
                BackgroundTaskStatus.RUNNING -> key("pause") {
                    TaskActionIcon(
                        onClick = onPause,
                        icon = AllIconsKeys.Actions.Pause,
                        description = stringResource(Res.string.action_pause_task),
                    )
                }
                BackgroundTaskStatus.PAUSED -> key("resume") {
                    TaskActionIcon(
                        onClick = onResume,
                        icon = AllIconsKeys.RunConfigurations.TestState.Run,
                        description = stringResource(Res.string.action_resume_task),
                    )
                }
                else -> Unit
            }
        }
        if (task.status == BackgroundTaskStatus.FAILED) {
            key("retry") {
                TaskActionIcon(
                    onClick = onRetry,
                    icon = AllIconsKeys.Actions.Refresh,
                    description = stringResource(Res.string.action_retry_task),
                )
            }
        }
        val isActive = task.status.isActiveTaskStatus()
        key(if (isActive) "cancel" else "dismiss") {
            TaskActionIcon(
                onClick = if (isActive) onCancel else onDismiss,
                icon = AllIconsKeys.Actions.Close,
                description = stringResource(
                    if (isActive) Res.string.action_cancel_task else Res.string.action_remove_task,
                ),
            )
        }
    }
}

/**
 * 渲染带统一工具提示的任务操作图标。
 */
@Composable
private fun TaskActionIcon(
    onClick: () -> Unit,
    icon: IconKey,
    description: String,
) {
    OnyxTooltip(text = description) {
        IconButton(onClick = onClick, modifier = Modifier.size(LocalOnyxAppearance.current.headerHeight)) {
            Icon(icon, description, Modifier.size(12.dp))
        }
    }
}

/**
 * 渲染任务路径详情与当前活动子文件：始终保留目标路径信息，目录递归传输时额外展示当前子文件。
 */
@Composable
private fun TaskDetailLines(task: BackgroundTask) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    val detailText = task.detail.resolve()
    val activeFile = task.currentFileName?.takeIf { name ->
        task.status.isActiveTaskStatus() && name.isNotBlank()
    }
    val primaryText = detailText.ifBlank { activeFile.orEmpty() }
    if (primaryText.isNotBlank()) {
        Text(
            text = primaryText,
            fontSize = appearance.headerFontSize,
            color = palette.mutedForeground,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
    val secondaryFile = activeFile?.takeIf { file ->
        detailText.isNotBlank() && file != detailText && !detailText.startsWith("$file -> ")
    }
    if (secondaryFile != null) {
        Text(
            text = secondaryFile,
            fontSize = appearance.headerFontSize,
            color = palette.foreground.copy(alpha = 0.82f),
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

/**
 * 构造任务字节、条目、百分比、速度和 ETA 指标。
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

/** 为垂直滚动条预留的内容右侧间距。 */
private val TASK_SCROLLBAR_INSET = 10.dp
