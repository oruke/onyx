package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.resolve
import kotlin.math.roundToInt
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_task_failed_summary
import onyx.composeapp.generated.resources.label_task_summary
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

private const val INDICATOR_BG_ALPHA = 0.14f
private const val MINI_PROGRESS_WIDTH_DP = 44

/**
 * 展示单个或多个进行中后台任务的紧凑胶囊，包含实时进度百分比、传输速率与迷你进度条。
 */
@Composable
internal fun ActiveTasksPill(
    tasks: List<BackgroundTask>,
    activeTasks: List<BackgroundTask>,
    onClick: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    val summary = remember(tasks) { tasks.toTaskCenterSummary() }
    val singleTask = activeTasks.singleOrNull()
    val status = if (summary.pausedCount == summary.activeCount) {
        BackgroundTaskStatus.PAUSED
    } else {
        BackgroundTaskStatus.RUNNING
    }
    val tint = taskProgressColor(status)
    val baseLabel = singleTask?.title?.resolve()
        ?: stringResource(Res.string.label_task_summary, summary.activeCount)
    val progressValue = singleTask?.progress ?: summary.progress
    val speedValue = singleTask?.bytesPerSecond ?: summary.bytesPerSecond
    val label = buildString {
        append(baseLabel)
        if (progressValue != null) {
            append(" · ")
            append((progressValue.coerceIn(0f, 1f) * TASK_PERCENT_MULTIPLIER).roundToInt())
            append('%')
        }
        if (speedValue != null && speedValue > 0L) {
            append(" · ")
            append(formatSpeed(speedValue))
        }
    }
    Row(
        modifier = Modifier
            .testTag("status-bar-task-indicator")
            .height(18.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(tint.copy(alpha = INDICATOR_BG_ALPHA))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            key = singleTask?.let { taskKindIcon(it.kind) } ?: AllIconsKeys.Toolwindows.ToolWindowTodo,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = label,
            fontSize = appearance.headerFontSize,
            color = palette.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 240.dp),
        )
        TaskProgressBar(
            progress = progressValue,
            status = status,
            showIndeterminateOverlay = summary.hasIndeterminateRunning,
            modifier = Modifier.width(MINI_PROGRESS_WIDTH_DP.dp).height(4.dp),
        )
    }
}

/**
 * 展示存在失败任务时的错误提醒胶囊，点击后直接打开任务窗口的失败分类。
 */
@Composable
internal fun FailedTasksPill(
    failedCount: Int,
    onClick: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    Row(
        modifier = Modifier
            .testTag("status-bar-task-indicator")
            .height(18.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(palette.error.copy(alpha = INDICATOR_BG_ALPHA))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            key = AllIconsKeys.General.Error,
            contentDescription = null,
            tint = palette.error,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = stringResource(Res.string.label_task_failed_summary, failedCount),
            fontSize = appearance.headerFontSize,
            color = palette.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 展示最近 5 秒留存期内已完成任务的反馈胶囊。
 */
@Composable
internal fun SucceededTaskPill(
    task: BackgroundTask,
    onClick: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    Row(
        modifier = Modifier
            .testTag("status-bar-task-indicator")
            .height(18.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(palette.success.copy(alpha = INDICATOR_BG_ALPHA))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            key = taskKindIcon(task.kind),
            contentDescription = null,
            tint = palette.success,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = task.detail.resolve(),
            fontSize = appearance.headerFontSize,
            color = palette.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp),
        )
    }
}
