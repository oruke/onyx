package com.oruke.onyx.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.formatFileSize
import org.jetbrains.jewel.ui.icons.AllIconsKeys

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

internal fun taskKindIcon(kind: BackgroundTaskKind) = when (kind) {
    BackgroundTaskKind.COPY -> AllIconsKeys.Actions.Copy
    BackgroundTaskKind.MOVE -> AllIconsKeys.Actions.MenuCut
    BackgroundTaskKind.DELETE -> AllIconsKeys.General.Delete
    BackgroundTaskKind.EXTRACT -> AllIconsKeys.Nodes.ExtractedFolder
    BackgroundTaskKind.RENAME -> AllIconsKeys.Actions.Edit
    BackgroundTaskKind.EXTERNAL_IMPORT -> AllIconsKeys.Actions.Download
}

internal fun buildProgressLabel(task: BackgroundTask): String = buildString {
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

internal fun formatSpeed(bytesPerSecond: Double): String {
    return when {
        bytesPerSecond >= BYTES_PER_GIBIBYTE -> "%.1f GB/s".format(bytesPerSecond / BYTES_PER_GIBIBYTE)
        bytesPerSecond >= BYTES_PER_MEBIBYTE -> "%.1f MB/s".format(bytesPerSecond / BYTES_PER_MEBIBYTE)
        bytesPerSecond >= BYTES_PER_KIBIBYTE -> "%.0f KB/s".format(bytesPerSecond / BYTES_PER_KIBIBYTE)
        else -> "%.0f B/s".format(bytesPerSecond)
    }
}

internal fun formatDuration(seconds: Long): String {
    return when {
        seconds < SECONDS_PER_MINUTE -> "${seconds}s"
        seconds < SECONDS_PER_HOUR -> {
            "${seconds / SECONDS_PER_MINUTE}m ${seconds % SECONDS_PER_MINUTE}s"
        }
        else -> {
            "${seconds / SECONDS_PER_HOUR}h " +
                "${(seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE}m"
        }
    }
}

private const val BYTES_PER_KIBIBYTE = 1_024.0
private const val BYTES_PER_MEBIBYTE = 1_048_576.0
private const val BYTES_PER_GIBIBYTE = 1_073_741_824.0
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
