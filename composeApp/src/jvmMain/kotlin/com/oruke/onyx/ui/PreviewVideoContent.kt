package com.oruke.onyx.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.filesystem.MediaMetadataResult
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.decodeSfmDisplayText
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.formatFileSize
import kotlinx.coroutines.delay
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_open_externally
import onyx.composeapp.generated.resources.label_preview_media_remote_skipped
import onyx.composeapp.generated.resources.label_preview_media_tool_missing
import onyx.composeapp.generated.resources.label_preview_unavailable
import onyx.composeapp.generated.resources.label_preview_video_frame_loading
import onyx.composeapp.generated.resources.label_preview_video_frame_position
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlin.math.roundToInt

/** 视频预览支持的三个快捷时间点比例（10% / 35% / 65%）。 */
private val VIDEO_FRAME_RATIOS = listOf(0.10f, 0.35f, 0.65f)

/**
 * 视频预览视图：关键帧快照（带 10% / 35% / 65% 快捷切换点）+ 分辨率/时长角标 + 外部播放器打开。
 *
 * 视口底色与占位提示色均收敛到当前 [LocalOnyxPalette] 语义色板，兼容深浅主题。
 *
 * @param entry 当前选中视频条目。
 * @param readMediaMetadata 媒体元数据探测回调。
 * @param loadVideoFrame 指定时长比例的视频抽帧回调。
 * @param onOpenExternal 可选外部播放器打开回调。
 */
@Composable
internal fun PreviewVideoContent(
    entry: VFile,
    readMediaMetadata: suspend (VFile) -> MediaMetadataResult,
    loadVideoFrame: suspend (VFile, Float, Long?, Int) -> ImageBitmap?,
    onOpenExternal: ((VFile) -> Unit)? = null,
) {
    val palette = LocalOnyxPalette.current
    var selectedRatio by remember(entry.location) { mutableFloatStateOf(VIDEO_FRAME_RATIOS.first()) }
    var metadataResult by remember(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
        mutableStateOf<MediaMetadataResult?>(null)
    }
    var frameBitmap by remember(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
        mutableStateOf<ImageBitmap?>(null)
    }
    var isLoadingFrame by remember(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes, selectedRatio) {
        mutableStateOf(true)
    }

    LaunchedEffect(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
        delay(MEDIA_PROBE_DEBOUNCE_MS)
        metadataResult = readMediaMetadata(entry)
    }

    val info = (metadataResult as? MediaMetadataResult.Ready)?.info
    val canExtractFrames = metadataResult !is MediaMetadataResult.RemoteSkipped &&
        metadataResult !is MediaMetadataResult.ToolUnavailable &&
        metadataResult !is MediaMetadataResult.Unavailable

    LaunchedEffect(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes, selectedRatio, metadataResult) {
        val currentMeta = metadataResult ?: return@LaunchedEffect
        if (currentMeta is MediaMetadataResult.RemoteSkipped ||
            currentMeta is MediaMetadataResult.ToolUnavailable ||
            currentMeta is MediaMetadataResult.Unavailable
        ) {
            isLoadingFrame = false
            return@LaunchedEffect
        }
        isLoadingFrame = true
        frameBitmap = loadVideoFrame(entry, selectedRatio, info?.durationMillis, MEDIA_PREVIEW_MAX_DIMENSION)
        isLoadingFrame = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 视频关键帧主体视口（使用调色板语义表面色，避免硬编码孤立色值）
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(palette.appBackground)
                .border(1.dp, palette.outlineVariant, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = frameBitmap
            when {
                bitmap != null -> Image(
                    bitmap = bitmap,
                    contentDescription = entry.name,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize().testTag("preview-video-frame-image"),
                )

                isLoadingFrame -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OnyxFileIcon(entry = entry, size = 48.dp)
                    Text(
                        text = stringResource(Res.string.label_preview_video_frame_loading),
                        color = palette.mutedForeground,
                        fontSize = 11.sp,
                    )
                }

                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    OnyxFileIcon(entry = entry, size = 56.dp)
                    val fallbackMsg = when (metadataResult) {
                        MediaMetadataResult.RemoteSkipped ->
                            stringResource(Res.string.label_preview_media_remote_skipped)
                        MediaMetadataResult.ToolUnavailable ->
                            stringResource(Res.string.label_preview_media_tool_missing)
                        else -> stringResource(Res.string.label_preview_unavailable)
                    }
                    Text(
                        text = fallbackMsg,
                        color = palette.mutedForeground,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // 分辨率 + 时长浮标
            val badgeParts = buildList {
                if (info?.width != null && info.height != null) add("${info.width}×${info.height}")
                info?.durationMillis?.let { add(formatMediaDuration(it)) }
            }
            if (badgeParts.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = badgeParts.joinToString(" · "),
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        // 帧时间点切换条（10% / 35% / 65%）+ 外部播放器按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VIDEO_FRAME_RATIOS.forEach { ratio ->
                    val percent = (ratio * 100).roundToInt()
                    val selected = ratio == selectedRatio
                    val tooltip = stringResource(Res.string.label_preview_video_frame_position, percent)
                    OnyxTooltip(text = tooltip) {
                        Row(
                            modifier = Modifier
                                .testTag("preview-video-frame-$percent")
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (selected) {
                                        palette.accent.copy(alpha = 0.18f)
                                    } else {
                                        palette.surfaceVariant.copy(alpha = 0.6f)
                                    }
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (selected) palette.accent else palette.outlineVariant,
                                    shape = RoundedCornerShape(4.dp),
                                )
                                .clickable(enabled = canExtractFrames) { selectedRatio = ratio }
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(if (selected) palette.accent else palette.mutedForeground),
                            )
                            Text(
                                text = "$percent%",
                                fontSize = 10.5.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) palette.accent else palette.foreground,
                            )
                        }
                    }
                }
            }

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

        Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
        val summaryRight = buildList {
            info?.videoCodec?.let(::add)
            info?.frameRate?.let { add(formatFrameRate(it)) }
            add(formatFileSize(entry.sizeBytes))
        }.joinToString(" · ")
        PreviewCompactFooter(
            leftText = entry.name.decodeSfmDisplayText(),
            rightText = summaryRight,
        )
    }
}
