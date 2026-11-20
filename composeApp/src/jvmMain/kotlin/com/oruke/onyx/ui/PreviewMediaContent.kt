package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import org.jetbrains.jewel.ui.component.Text

/** 翻选 NAS / 磁盘音视频时的防抖延迟（毫秒），避免方向键连按触发大量外部进程。 */
internal const val MEDIA_PROBE_DEBOUNCE_MS = 180L

/** 视频帧与专辑封面抽取的最大像素边长。 */
internal const val MEDIA_PREVIEW_MAX_DIMENSION = 720

/**
 * 高密度线性进度 / 音量滑条：
 * - 通过 [rememberUpdatedState] 始终派发最新回调，防止切文件后触发旧闭包；
 * - 区分拖动中的实时回调 [onValueChange] 与点击/松手后的提交回调 [onValueChangeFinished]，避免拖动音频进度条反复重启进程。
 *
 * @param value 当前归一化值（`0f..1f`）。
 * @param enabled 是否允许交互。
 * @param onValueChange 点击或拖动过程中数值变更回调。
 * @param onValueChangeFinished 点击或拖动结束时的最终提交回调（参数为最终归一化值）。
 * @param modifier 外部布局修饰符。
 */
@Composable
internal fun CompactLinearSlider(
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val palette = LocalOnyxPalette.current
    var trackWidthPx by remember { mutableStateOf(1f) }
    var lastDragRatio by remember { mutableFloatStateOf(value.coerceIn(0f, 1f)) }
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val clamped = value.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .height(16.dp)
            .onSizeChanged { trackWidthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(enabled, trackWidthPx) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val ratio = (offset.x / trackWidthPx).coerceIn(0f, 1f)
                    lastDragRatio = ratio
                    latestOnValueChange(ratio)
                    latestOnValueChangeFinished?.invoke(ratio)
                }
            }
            .pointerInput(enabled, trackWidthPx) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = { latestOnValueChangeFinished?.invoke(lastDragRatio) },
                    onDragCancel = { latestOnValueChangeFinished?.invoke(lastDragRatio) },
                ) { change, _ ->
                    change.consume()
                    val ratio = (change.position.x / trackWidthPx).coerceIn(0f, 1f)
                    lastDragRatio = ratio
                    latestOnValueChange(ratio)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(palette.outlineVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .fillMaxHeight()
                    .background(if (enabled) palette.accent else palette.mutedForeground),
            )
        }
    }
}

/** 预览视图通用的 1 行紧凑底部状态条。 */
@Composable
internal fun PreviewCompactFooter(
    leftText: String,
    rightText: String,
) {
    val palette = LocalOnyxPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.headerBackground.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = leftText,
            color = palette.mutedForeground,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (rightText.isNotBlank()) {
            Text(
                text = rightText,
                color = palette.mutedForeground,
                fontSize = 10.5.sp,
                maxLines = 1,
            )
        }
    }
}
