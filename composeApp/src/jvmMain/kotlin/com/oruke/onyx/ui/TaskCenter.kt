package com.oruke.onyx.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.ui.theme.OnyxPalette
import com.oruke.onyx.ui.theme.taskStatusLabel
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_cancel_task
import onyx.composeapp.generated.resources.action_clear_all_tasks
import onyx.composeapp.generated.resources.action_close_menu
import onyx.composeapp.generated.resources.label_task_center
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// ── Task panel ──────────────────────────────────────────────────────────────

@Composable
internal fun TaskCenterWindow(
    tasks: List<BackgroundTask>,
    palette: OnyxPalette,
    onDismissTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onClearAllTasks: () -> Unit,
    onClose: () -> Unit,
) {
    Window(
        onCloseRequest = onClose,
        title = stringResource(Res.string.label_task_center),
        state = rememberWindowState(width = 460.dp, height = 320.dp),
    ) {
        IntUiTheme(isDark = isSystemInDarkTheme()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.appBackground)
                    .padding(10.dp),
            ) {
                TaskPanel(
                    tasks = tasks,
                    onDismissTask = onDismissTask,
                    onCancelTask = onCancelTask,
                    onClearAllTasks = onClearAllTasks,
                    palette = palette,
                )
            }
        }
    }
}

@Composable
internal fun TaskPanel(
    tasks: List<BackgroundTask>,
    onDismissTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onClearAllTasks: () -> Unit,
    palette: OnyxPalette,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(Res.string.label_task_center),
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = palette.foreground,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ContextMenuItem(
                text = stringResource(Res.string.action_clear_all_tasks),
                enabled = tasks.isNotEmpty(),
                iconKey = AllIconsKeys.Actions.Close,
                palette = palette,
                onClick = onClearAllTasks,
            )
        }
        tasks.forEach { task ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = task.title, fontSize = 12.sp, color = palette.foreground)
                    Text(
                        text = "${taskStatusLabel(task.status)} · ${task.detail}",
                        fontSize = 11.sp,
                        color = palette.mutedForeground,
                    )
                    TaskProgressBar(
                        progress = task.progress,
                        status = task.status,
                        palette = palette,
                    )
                }
                val taskRunning =
                    task.status == BackgroundTaskStatus.QUEUED || task.status == BackgroundTaskStatus.RUNNING
                IconButton(onClick = { if (taskRunning) onCancelTask(task.id) else onDismissTask(task.id) }) {
                    Icon(
                        key = AllIconsKeys.Actions.Close,
                        contentDescription = stringResource(
                            if (taskRunning) Res.string.action_cancel_task else Res.string.action_close_menu
                        )
                    )
                }
            }
        }
    }
}

@Composable
internal fun TaskProgressBar(
    progress: Float?,
    status: BackgroundTaskStatus,
    palette: OnyxPalette,
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
        BackgroundTaskStatus.CANCELLED -> palette.disabledForeground
        BackgroundTaskStatus.SUCCEEDED -> Color(0xFF4DAA57)
        BackgroundTaskStatus.QUEUED, BackgroundTaskStatus.RUNNING -> palette.accent
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(palette.outlineVariant, RoundedCornerShape(2.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedProgress)
                .height(4.dp)
                .background(barColor, RoundedCornerShape(2.dp)),
        )
    }
}

