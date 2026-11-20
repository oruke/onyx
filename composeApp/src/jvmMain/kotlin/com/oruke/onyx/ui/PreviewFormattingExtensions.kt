package com.oruke.onyx.ui

import androidx.compose.runtime.Composable
import com.oruke.onyx.ui.theme.formatFileSize
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_audio_channel_count
import onyx.composeapp.generated.resources.label_audio_channel_mono
import onyx.composeapp.generated.resources.label_audio_channel_stereo
import onyx.composeapp.generated.resources.label_audio_channel_surround
import org.jetbrains.compose.resources.stringResource
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 将毫秒时长格式化为紧凑的 `MM:SS` 或 `H:MM:SS` 文本。
 *
 * @param durationMillis 时长（毫秒）。
 * @return 格式化后的时间字符串。
 */
internal fun formatMediaDuration(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
    } else {
        "%02d:%02d".format(Locale.US, minutes, seconds)
    }
}

/**
 * 将码率（bps）格式化为易读的 `kbps` 或 `Mbps` 文本。
 *
 * @param bitrateBps 码率（比特每秒）。
 * @return 格式化后的码率文本。
 */
internal fun formatMediaBitrate(bitrateBps: Long): String {
    if (bitrateBps <= 0L) return "-"
    val kbps = bitrateBps / 1000.0
    return if (kbps >= 1000.0) {
        "%.2f Mbps".format(Locale.US, kbps / 1000.0)
    } else {
        "${kbps.roundToInt()} kbps"
    }
}

/**
 * 将采样率（Hz）格式化为 kHz 文本（如 `48 kHz` / `44.1 kHz`）。
 *
 * @param sampleRateHz 采样率（Hz）。
 * @return 格式化文本；非正数返回 `null`。
 */
internal fun formatAudioSampleRate(sampleRateHz: Int?): String? {
    if (sampleRateHz == null || sampleRateHz <= 0) return null
    val khz = sampleRateHz / 1000.0
    return if (abs(khz - khz.roundToInt()) < 0.05) {
        "${khz.roundToInt()} kHz"
    } else {
        "%.1f kHz".format(Locale.US, khz)
    }
}

/**
 * 将音频采样率与声道数按当前应用语言格式化为紧凑规格字符串（例如 `48 kHz · 立体声`）。
 *
 * @param sampleRateHz 采样率（Hz）。
 * @param channels 声道数。
 * @return 本地化后的规格文本；两者均为空时返回 `null`。
 */
@Composable
internal fun formatLocalizedAudioSpec(sampleRateHz: Int?, channels: Int?): String? {
    val sampleRateText = formatAudioSampleRate(sampleRateHz)
    val channelText = when {
        channels == null || channels <= 0 -> null
        channels == 1 -> stringResource(Res.string.label_audio_channel_mono)
        channels == 2 -> stringResource(Res.string.label_audio_channel_stereo)
        channels == 6 -> stringResource(Res.string.label_audio_channel_surround, "5.1")
        channels == 8 -> stringResource(Res.string.label_audio_channel_surround, "7.1")
        else -> stringResource(Res.string.label_audio_channel_count, channels)
    }
    return listOfNotNull(sampleRateText, channelText)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")
}

/**
 * 将视频帧率格式化为清晰文本（整数帧率省略小数点，非整数保留两位小数）。
 *
 * @param fps 每秒帧数。
 * @return 格式化后的帧率文本。
 */
internal fun formatFrameRate(fps: Double): String {
    val rounded = fps.roundToInt()
    return if (abs(fps - rounded) < 0.01) {
        "$rounded fps"
    } else {
        "%.2f fps".format(Locale.US, fps)
    }
}

/**
 * 计算宽高像素的最简宽高比（例如 `16:9`、`4:3`、`3:2`）。
 *
 * @param width 宽度像素。
 * @param height 高度像素。
 * @return 约分后的宽高比字符串。
 */
internal fun formatAspectRatio(width: Int, height: Int): String {
    if (width <= 0 || height <= 0) return "-"
    val divisor = greatestCommonDivisor(width, height).coerceAtLeast(1)
    val rw = width / divisor
    val rh = height / divisor
    return if (rw <= 32 && rh <= 32) {
        "$rw:$rh"
    } else {
        "%.2f:1".format(Locale.US, width.toDouble() / height.toDouble())
    }
}

/**
 * 格式化带精确字节数的文件体积（大于等于 1 KB 时附加千分位原始字节数）。
 *
 * @param sizeBytes 文件字节数。
 * @return 适合元数据展示的体积文本。
 */
internal fun formatExactFileSize(sizeBytes: Long?): String {
    if (sizeBytes == null || sizeBytes < 0L) return "-"
    val human = formatFileSize(sizeBytes)
    if (sizeBytes < 1024L) return human
    val exact = NumberFormat.getIntegerInstance(Locale.US).format(sizeBytes)
    return "$human ($exact B)"
}

private fun greatestCommonDivisor(a: Int, b: Int): Int {
    var x = a
    var y = b
    while (y != 0) {
        val temp = x % y
        x = y
        y = temp
    }
    return x
}
