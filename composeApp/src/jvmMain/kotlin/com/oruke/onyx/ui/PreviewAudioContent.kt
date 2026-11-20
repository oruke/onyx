package com.oruke.onyx.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.filesystem.AudioPlaybackError
import com.oruke.onyx.app.filesystem.AudioPreviewPlayer
import com.oruke.onyx.app.filesystem.MediaMetadataResult
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.decodeSfmDisplayText
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.formatFileSize
import kotlinx.coroutines.delay
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_open_externally
import onyx.composeapp.generated.resources.action_preview_mute
import onyx.composeapp.generated.resources.action_preview_pause
import onyx.composeapp.generated.resources.action_preview_play
import onyx.composeapp.generated.resources.action_preview_stop
import onyx.composeapp.generated.resources.label_audio_error_not_found
import onyx.composeapp.generated.resources.label_audio_error_playback_failed
import onyx.composeapp.generated.resources.label_preview_audio_unavailable
import onyx.composeapp.generated.resources.label_preview_media_remote_skipped
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlin.math.roundToInt

/**
 * 音频预览视图：专辑封面 + 标题/艺术家摘要 + 手动点击触发的轻量内置试听器。
 *
 * - 切换选中文件或离开预览视图时自动停止播放并释放音频输出线；
 * - 拖动进度条时仅实时更新本地预览位置，松手或点击时才派发单次 `seekTo`；
 * - 不在组合期同步执行任何外部进程，随播放器异步探测结果刷新可用性。
 *
 * @param entry 当前选中音频条目。
 * @param readMediaMetadata 媒体元数据探测回调。
 * @param loadAudioCover 内嵌专辑封面提取回调。
 * @param audioPlayer 轻量音频试听播放器。
 * @param onOpenExternal 可选外部打开回调。
 */
@Composable
internal fun PreviewAudioContent(
    entry: VFile,
    readMediaMetadata: suspend (VFile) -> MediaMetadataResult,
    loadAudioCover: suspend (VFile, Int) -> ImageBitmap?,
    audioPlayer: AudioPreviewPlayer,
    onOpenExternal: ((VFile) -> Unit)? = null,
) {
    val palette = LocalOnyxPalette.current
    val playback by audioPlayer.state.collectAsState()
    val isCurrentTrack = playback.location == entry.location
    val isPlayingCurrent = isCurrentTrack && playback.isPlaying
    val isLoadingCurrent = isCurrentTrack && playback.isLoading
    val canPlayInline = remember(entry.location, entry.name, playback.ffmpegAvailable) {
        audioPlayer.canPlay(entry)
    }

    var metadataResult by remember(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
        mutableStateOf<MediaMetadataResult?>(null)
    }
    var coverBitmap by remember(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
        mutableStateOf<ImageBitmap?>(null)
    }
    var dragPreviewRatio by remember(entry.location) { mutableStateOf<Float?>(null) }
    var lastNonZeroVolume by remember { mutableFloatStateOf(0.85f) }

    // 切离当前文件或销毁视图时立即停播，防后台幽灵出声
    DisposableEffect(entry.location) {
        onDispose {
            if (audioPlayer.state.value.location == entry.location) {
                audioPlayer.stop()
            }
        }
    }

    LaunchedEffect(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
        delay(MEDIA_PROBE_DEBOUNCE_MS)
        val probed = readMediaMetadata(entry)
        metadataResult = probed
        val info = (probed as? MediaMetadataResult.Ready)?.info
        if (info?.hasEmbeddedCover == true) {
            coverBitmap = loadAudioCover(entry, 480)
        }
    }

    val info = (metadataResult as? MediaMetadataResult.Ready)?.info
    val displayTitle = info?.title ?: entry.name.decodeSfmDisplayText()
    val subtitleParts = buildList {
        info?.artist?.let(::add)
        info?.album?.let(::add)
    }
    val localizedAudioSpec = formatLocalizedAudioSpec(info?.sampleRateHz, info?.channels)
    val techParts = buildList {
        info?.audioCodec?.let(::add)
        info?.bitrateBps?.let { add(formatMediaBitrate(it)) }
        localizedAudioSpec?.let(::add)
    }
    val totalDuration = info?.durationMillis ?: if (isCurrentTrack) playback.durationMillis else 0L
    val playbackPosition = if (isCurrentTrack) playback.positionMillis else 0L
    val displayedRatio = dragPreviewRatio ?: if (totalDuration > 0L) {
        (playbackPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val displayedPosition = if (dragPreviewRatio != null && totalDuration > 0L) {
        (totalDuration * displayedRatio).toLong()
    } else {
        playbackPosition
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(172.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.surfaceVariant.copy(alpha = 0.6f))
                    .border(1.dp, palette.outlineVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = coverBitmap
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = displayTitle,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.High,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    OnyxFileIcon(entry = entry, size = 64.dp)
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = displayTitle,
                    color = palette.foreground,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (subtitleParts.isNotEmpty()) {
                    Text(
                        text = subtitleParts.joinToString(" · "),
                        color = palette.mutedForeground,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
                if (techParts.isNotEmpty()) {
                    Text(
                        text = techParts.joinToString(" · "),
                        color = palette.mutedForeground,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.surfaceVariant.copy(alpha = 0.5f))
                    .border(1.dp, palette.outlineVariant, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompactLinearSlider(
                    value = displayedRatio,
                    enabled = canPlayInline && totalDuration > 0L,
                    onValueChange = { ratio -> dragPreviewRatio = ratio },
                    onValueChangeFinished = { ratio ->
                        dragPreviewRatio = null
                        if (totalDuration > 0L) {
                            audioPlayer.seekTo(entry, (totalDuration * ratio).toLong(), totalDuration)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("preview-audio-seekbar"),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatMediaDuration(displayedPosition),
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = palette.mutedForeground,
                    )
                    Text(
                        text = if (totalDuration > 0L) formatMediaDuration(totalDuration) else "--:--",
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = palette.mutedForeground,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val playTooltip = stringResource(
                        if (isPlayingCurrent) Res.string.action_preview_pause else Res.string.action_preview_play
                    )
                    OnyxTooltip(text = playTooltip) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("preview-audio-play-button")
                                .clip(CircleShape)
                                .background(if (canPlayInline) palette.accent else palette.surfaceVariant)
                                .clickable(enabled = canPlayInline) {
                                    audioPlayer.togglePlayPause(entry, info?.durationMillis)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                key = if (isPlayingCurrent || isLoadingCurrent) {
                                    AllIconsKeys.Actions.Pause
                                } else {
                                    AllIconsKeys.RunConfigurations.TestState.Run
                                },
                                contentDescription = playTooltip,
                                tint = if (canPlayInline) palette.onAccent else palette.mutedForeground,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }

                    if (isCurrentTrack && (isPlayingCurrent || playbackPosition > 0L)) {
                        val stopTooltip = stringResource(Res.string.action_preview_stop)
                        OnyxTooltip(text = stopTooltip) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .testTag("preview-audio-stop-button")
                                    .clip(CircleShape)
                                    .background(palette.surfaceVariant)
                                    .border(1.dp, palette.outlineVariant, CircleShape)
                                    .clickable { audioPlayer.stop() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(RoundedCornerShape(1.5.dp))
                                        .background(palette.foreground.copy(alpha = 0.80f)),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    TitleBarIconButton(
                        onClick = {
                            if (playback.volume > 0.01f) {
                                lastNonZeroVolume = playback.volume
                                audioPlayer.setVolume(0f)
                            } else {
                                audioPlayer.setVolume(lastNonZeroVolume.coerceAtLeast(0.3f))
                            }
                        },
                        tooltip = stringResource(Res.string.action_preview_mute),
                        modifier = Modifier.testTag("preview-audio-mute-button"),
                    ) {
                        Text(
                            text = if (playback.volume <= 0.01f) "0%" else "${(playback.volume * 100).roundToInt()}%",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = palette.mutedForeground,
                        )
                    }

                    CompactLinearSlider(
                        value = playback.volume,
                        enabled = canPlayInline,
                        onValueChange = { vol ->
                            if (vol > 0.01f) lastNonZeroVolume = vol
                            audioPlayer.setVolume(vol)
                        },
                        modifier = Modifier.width(64.dp).testTag("preview-audio-volume-slider"),
                    )

                    if (onOpenExternal != null) {
                        TitleBarIconButton(
                            onClick = { onOpenExternal(entry) },
                            tooltip = stringResource(Res.string.action_open_externally),
                            modifier = Modifier.testTag("preview-open-external-button"),
                        ) {
                            Icon(
                                key = AllIconsKeys.Ide.External_link_arrow,
                                contentDescription = stringResource(Res.string.action_open_externally),
                                tint = palette.foreground.copy(alpha = 0.80f),
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }
                }

                val isPlaybackError = isCurrentTrack && playback.errorKind != null
                val noticeText = when {
                    isPlaybackError -> when (playback.errorKind) {
                        AudioPlaybackError.FILE_NOT_FOUND -> stringResource(Res.string.label_audio_error_not_found)
                        AudioPlaybackError.UNSUPPORTED_FORMAT -> stringResource(Res.string.label_preview_audio_unavailable)
                        AudioPlaybackError.PLAYBACK_FAILED -> stringResource(
                            Res.string.label_audio_error_playback_failed,
                            playback.errorDetail.orEmpty(),
                        )
                        null -> null
                    }

                    metadataResult is MediaMetadataResult.RemoteSkipped ->
                        stringResource(Res.string.label_preview_media_remote_skipped)

                    !canPlayInline -> stringResource(Res.string.label_preview_audio_unavailable)
                    else -> null
                }
                if (noticeText != null) {
                    Text(
                        text = noticeText,
                        color = if (isPlaybackError) palette.errorForeground else palette.mutedForeground,
                        fontSize = 10.5.sp,
                    )
                }
            }
        }

        Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
        PreviewCompactFooter(
            leftText = entry.name.decodeSfmDisplayText(),
            rightText = formatFileSize(entry.sizeBytes),
        )
    }
}
