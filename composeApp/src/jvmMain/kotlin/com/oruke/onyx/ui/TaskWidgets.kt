package com.oruke.onyx.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.formatFileSize
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.format_duration_hours_minutes
import onyx.composeapp.generated.resources.format_duration_minutes_seconds
import onyx.composeapp.generated.resources.format_duration_seconds
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * 渲染确定进度，并可在其上叠加无法量化阶段的移动指示段。
 *
 * @param progress 0 到 1 的确定进度，未知时为 null。
 * @param status 当前任务状态。
 * @param showIndeterminateOverlay 是否展示不确定进度动画。
 * @param modifier 外部布局修饰符。
 */
@Composable
internal fun TaskProgressBar(
    progress: Float?,
    status: BackgroundTaskStatus,
    showIndeterminateOverlay: Boolean = progress == null && status == BackgroundTaskStatus.RUNNING,
    modifier: Modifier = Modifier,
) {
    val palette = LocalOnyxPalette.current
    val targetProgress = when {
        progress != null -> progress.coerceIn(0f, 1f)
        status == BackgroundTaskStatus.SUCCEEDED -> 1f
        else -> 0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = DETERMINATE_ANIMATION_MILLIS),
    )
    val barColor = taskProgressColor(status)
    BoxWithConstraints(
        modifier = modifier
            .height(DEFAULT_PROGRESS_HEIGHT)
            .clip(RoundedCornerShape(PROGRESS_RADIUS))
            .background(palette.outlineVariant),
    ) {
        if (animatedProgress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress)
                    .background(barColor),
            )
        }
        if (showIndeterminateOverlay && status == BackgroundTaskStatus.RUNNING) {
            IndeterminateProgressSegment(maxWidth = maxWidth, color = barColor)
        }
    }
}

/**
 * 仅在任务进度无法量化时启动移动指示段动画。
 *
 * @param maxWidth 进度轨道宽度。
 * @param color 当前任务语义色。
 */
@Composable
private fun IndeterminateProgressSegment(
    maxWidth: Dp,
    color: Color,
) {
    val transition = rememberInfiniteTransition()
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = INDETERMINATE_ANIMATION_MILLIS,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
    )
    val travelWidth = maxWidth * (1f + INDETERMINATE_SEGMENT_FRACTION)
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(INDETERMINATE_SEGMENT_FRACTION)
            .offset(x = travelWidth * phase - maxWidth * INDETERMINATE_SEGMENT_FRACTION)
            .background(color.copy(alpha = INDETERMINATE_ALPHA)),
    )
}

/**
 * 返回任务状态对应的语义进度色。
 *
 * @param status 当前任务状态。
 * @return 状态语义色。
 */
@Composable
internal fun taskProgressColor(status: BackgroundTaskStatus): Color {
    val palette = LocalOnyxPalette.current
    return when (status) {
        BackgroundTaskStatus.FAILED -> palette.error
        BackgroundTaskStatus.CANCELLED -> palette.disabledForeground
        BackgroundTaskStatus.SUCCEEDED -> palette.success
        BackgroundTaskStatus.PAUSED -> palette.favorite
        BackgroundTaskStatus.QUEUED,
        BackgroundTaskStatus.RUNNING -> palette.accent
    }
}

/**
 * 返回任务类型对应的 Jewel 图标。
 *
 * @param kind 任务业务类型。
 * @return Jewel 图标键。
 */
internal fun taskKindIcon(kind: BackgroundTaskKind) = when (kind) {
    BackgroundTaskKind.COPY -> AllIconsKeys.Actions.Copy
    BackgroundTaskKind.MOVE -> AllIconsKeys.Actions.MenuCut
    BackgroundTaskKind.DELETE -> AllIconsKeys.General.Delete
    BackgroundTaskKind.EXTRACT -> AllIconsKeys.Nodes.ExtractedFolder
    BackgroundTaskKind.RENAME -> AllIconsKeys.Actions.Edit
    BackgroundTaskKind.EXTERNAL_IMPORT -> AllIconsKeys.Actions.Download
}

/**
 * 格式化字节完成量。
 *
 * @param processedBytes 已完成字节数。
 * @param totalBytes 总字节数。
 * @return `已完成 / 总量` 文本；总量未知时返回空字符串。
 */
internal fun formatByteProgress(
    processedBytes: Long,
    totalBytes: Long,
): String {
    if (totalBytes <= 0L) return ""
    return "${formatFileSize(processedBytes.coerceAtLeast(0L))} / ${formatFileSize(totalBytes)}"
}

/**
 * 将字节每秒格式化为紧凑传输速度。
 *
 * @param bytesPerSecond 字节每秒。
 * @return 使用 IEC 换算基数的速度文本。
 */
internal fun formatSpeed(bytesPerSecond: Long): String {
    val speed = bytesPerSecond.coerceAtLeast(0L).toDouble()
    return when {
        speed >= BYTES_PER_GIBIBYTE -> "%.1f GB/s".format(speed / BYTES_PER_GIBIBYTE)
        speed >= BYTES_PER_MEBIBYTE -> "%.1f MB/s".format(speed / BYTES_PER_MEBIBYTE)
        speed >= BYTES_PER_KIBIBYTE -> "%.0f KB/s".format(speed / BYTES_PER_KIBIBYTE)
        else -> "%.0f B/s".format(speed)
    }
}

/**
 * 按当前语言格式化紧凑剩余时长。
 *
 * @param seconds 总秒数。
 * @return 本地化时长文本。
 */
@Composable
internal fun formatTaskDuration(seconds: Long): String {
    val normalizedSeconds = seconds.coerceAtLeast(0L)
    return when {
        normalizedSeconds < SECONDS_PER_MINUTE -> stringResource(
            Res.string.format_duration_seconds,
            normalizedSeconds,
        )
        normalizedSeconds < SECONDS_PER_HOUR -> stringResource(
            Res.string.format_duration_minutes_seconds,
            normalizedSeconds / SECONDS_PER_MINUTE,
            normalizedSeconds % SECONDS_PER_MINUTE,
        )
        else -> stringResource(
            Res.string.format_duration_hours_minutes,
            normalizedSeconds / SECONDS_PER_HOUR,
            (normalizedSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE,
        )
    }
}

/** 确定进度补间时长。 */
private const val DETERMINATE_ANIMATION_MILLIS = 160
/** 不确定进度完成一次移动的时长。 */
private const val INDETERMINATE_ANIMATION_MILLIS = 1_050
/** 不确定进度移动段宽度。 */
private const val INDETERMINATE_SEGMENT_FRACTION = 0.28f
/** 不确定进度移动段透明度。 */
private const val INDETERMINATE_ALPHA = 0.72f
/** 默认进度条高度。 */
private val DEFAULT_PROGRESS_HEIGHT = 5.dp
/** 进度条圆角。 */
private val PROGRESS_RADIUS = 2.dp
/** IEC KiB 字节数。 */
private const val BYTES_PER_KIBIBYTE = 1_024.0
/** IEC MiB 字节数。 */
private const val BYTES_PER_MEBIBYTE = 1_048_576.0
/** IEC GiB 字节数。 */
private const val BYTES_PER_GIBIBYTE = 1_073_741_824.0
/** 每分钟秒数。 */
private const val SECONDS_PER_MINUTE = 60L
/** 每小时秒数。 */
private const val SECONDS_PER_HOUR = 3_600L
