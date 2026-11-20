package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.filesystem.ArchiveInfoRequest
import com.oruke.onyx.app.filesystem.ArchiveInfoResult
import com.oruke.onyx.app.filesystem.FileHashRequest
import com.oruke.onyx.app.filesystem.FileHashResult
import com.oruke.onyx.app.filesystem.MediaMetadataInfo
import com.oruke.onyx.app.filesystem.MediaMetadataResult
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_metadata_cancel_sha256
import onyx.composeapp.generated.resources.action_metadata_compute_sha256
import onyx.composeapp.generated.resources.label_archive_capability_browse
import onyx.composeapp.generated.resources.label_archive_capability_extract
import onyx.composeapp.generated.resources.label_archive_capability_read_only
import onyx.composeapp.generated.resources.label_archive_encrypted_no
import onyx.composeapp.generated.resources.label_archive_encrypted_yes
import onyx.composeapp.generated.resources.label_inspector_archive_capabilities
import onyx.composeapp.generated.resources.label_inspector_archive_encrypted
import onyx.composeapp.generated.resources.label_inspector_sha256
import onyx.composeapp.generated.resources.label_list_separator
import onyx.composeapp.generated.resources.label_metadata_archive_entries_label
import onyx.composeapp.generated.resources.label_metadata_aspect_ratio
import onyx.composeapp.generated.resources.label_metadata_audio_codec
import onyx.composeapp.generated.resources.label_metadata_audio_format
import onyx.composeapp.generated.resources.label_metadata_bitrate
import onyx.composeapp.generated.resources.label_metadata_dimensions
import onyx.composeapp.generated.resources.label_metadata_duration
import onyx.composeapp.generated.resources.label_metadata_frame_rate
import onyx.composeapp.generated.resources.label_metadata_section_archive
import onyx.composeapp.generated.resources.label_metadata_section_checksum
import onyx.composeapp.generated.resources.label_metadata_section_image
import onyx.composeapp.generated.resources.label_metadata_section_media
import onyx.composeapp.generated.resources.label_metadata_sha256_idle
import onyx.composeapp.generated.resources.label_metadata_subtitles
import onyx.composeapp.generated.resources.label_metadata_subtitles_count
import onyx.composeapp.generated.resources.label_metadata_tag_album
import onyx.composeapp.generated.resources.label_metadata_tag_artist
import onyx.composeapp.generated.resources.label_metadata_tag_title
import onyx.composeapp.generated.resources.label_metadata_tag_track
import onyx.composeapp.generated.resources.label_metadata_video_codec
import onyx.composeapp.generated.resources.label_preview_loading
import onyx.composeapp.generated.resources.label_preview_media_remote_skipped
import onyx.composeapp.generated.resources.label_preview_media_tool_missing
import onyx.composeapp.generated.resources.label_preview_too_large
import onyx.composeapp.generated.resources.label_preview_unavailable
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/** SHA-256 按需计算允许读取的最大字节上限（512 MB，本地与归档均已采用恒定内存流式摘要）。 */
private const val ON_DEMAND_HASH_MAX_BYTES = 512L * 1024L * 1024L

/** 统一风格的紧凑元数据分组容器。 */
@Composable
internal fun MetadataSection(
    title: String,
    content: @Composable () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                color = palette.mutedForeground,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(palette.outlineVariant),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            content()
        }
    }
}

/** 图片分辨率与宽高比分组。 */
@Composable
internal fun MetadataImageSection(
    entry: VFile,
    readImageSize: suspend (VFile) -> IntSize?,
) {
    var dimensions by remember(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
        mutableStateOf<IntSize?>(null)
    }
    var loaded by remember(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
        mutableStateOf(false)
    }
    LaunchedEffect(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
        dimensions = readImageSize(entry)
        loaded = true
    }
    val size = dimensions
    if (!loaded || size != null) {
        MetadataSection(title = stringResource(Res.string.label_metadata_section_image)) {
            if (!loaded) {
                InspectorDetailRow(
                    label = stringResource(Res.string.label_metadata_dimensions),
                    value = stringResource(Res.string.label_preview_loading),
                    compact = true,
                )
            } else if (size != null) {
                InspectorDetailRow(
                    label = stringResource(Res.string.label_metadata_dimensions),
                    value = "${size.width} × ${size.height} px",
                    compact = true,
                )
                InspectorDetailRow(
                    label = stringResource(Res.string.label_metadata_aspect_ratio),
                    value = formatAspectRatio(size.width, size.height),
                    compact = true,
                )
            }
        }
    }
}

/** 音视频流参数与标签元数据分组（带 180ms 防抖保护）。 */
@Composable
internal fun MetadataMediaSection(
    entry: VFile,
    readMediaMetadata: suspend (VFile) -> MediaMetadataResult,
) {
    var result by remember(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
        mutableStateOf<MediaMetadataResult?>(null)
    }
    LaunchedEffect(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
        delay(MEDIA_PROBE_DEBOUNCE_MS)
        result = readMediaMetadata(entry)
    }

    MetadataSection(title = stringResource(Res.string.label_metadata_section_media)) {
        when (val current = result) {
            null -> Text(
                text = stringResource(Res.string.label_preview_loading),
                color = LocalOnyxPalette.current.mutedForeground,
                fontSize = 11.5.sp,
            )

            is MediaMetadataResult.Ready -> RenderMediaMetadataRows(current.info)

            MediaMetadataResult.RemoteSkipped -> Text(
                text = stringResource(Res.string.label_preview_media_remote_skipped),
                color = LocalOnyxPalette.current.mutedForeground,
                fontSize = 11.5.sp,
            )

            MediaMetadataResult.ToolUnavailable -> Text(
                text = stringResource(Res.string.label_preview_media_tool_missing),
                color = LocalOnyxPalette.current.mutedForeground,
                fontSize = 11.5.sp,
            )

            MediaMetadataResult.Unavailable -> Text(
                text = stringResource(Res.string.label_preview_unavailable),
                color = LocalOnyxPalette.current.mutedForeground,
                fontSize = 11.5.sp,
            )

            is MediaMetadataResult.Failed -> Text(
                text = current.reason,
                color = LocalOnyxPalette.current.errorForeground,
                fontSize = 11.5.sp,
            )
        }
    }
}

@Composable
private fun RenderMediaMetadataRows(info: MediaMetadataInfo) {
    info.durationMillis?.let { duration ->
        InspectorDetailRow(
            label = stringResource(Res.string.label_metadata_duration),
            value = formatMediaDuration(duration),
            compact = true,
        )
    }
    if (info.width != null && info.height != null) {
        InspectorDetailRow(
            label = stringResource(Res.string.label_metadata_dimensions),
            value = "${info.width} × ${info.height} (${formatAspectRatio(info.width, info.height)})",
            compact = true,
        )
    }
    info.frameRate?.let { fps ->
        InspectorDetailRow(
            label = stringResource(Res.string.label_metadata_frame_rate),
            value = formatFrameRate(fps),
            compact = true,
        )
    }
    info.videoCodec?.let { codec ->
        InspectorDetailRow(
            label = stringResource(Res.string.label_metadata_video_codec),
            value = codec,
            compact = true,
        )
    }
    info.audioCodec?.let { codec ->
        InspectorDetailRow(
            label = stringResource(Res.string.label_metadata_audio_codec),
            value = codec,
            compact = true,
        )
    }
    formatLocalizedAudioSpec(info.sampleRateHz, info.channels)?.let { spec ->
        InspectorDetailRow(
            label = stringResource(Res.string.label_metadata_audio_format),
            value = spec,
            compact = true,
        )
    }
    info.bitrateBps?.let { bitrate ->
        InspectorDetailRow(
            label = stringResource(Res.string.label_metadata_bitrate),
            value = formatMediaBitrate(bitrate),
            compact = true,
        )
    }
    if (info.subtitleTrackCount > 0) {
        InspectorDetailRow(
            label = stringResource(Res.string.label_metadata_subtitles),
            value = stringResource(Res.string.label_metadata_subtitles_count, info.subtitleTrackCount),
            compact = true,
        )
    }
    info.title?.let { title ->
        InspectorDetailRow(
            label = stringResource(Res.string.label_metadata_tag_title),
            value = title,
            copyValue = title,
        )
    }
    info.artist?.let { artist ->
        InspectorDetailRow(
            label = stringResource(Res.string.label_metadata_tag_artist),
            value = artist,
            copyValue = artist,
        )
    }
    info.album?.let { album ->
        InspectorDetailRow(
            label = stringResource(Res.string.label_metadata_tag_album),
            value = album,
            compact = true,
        )
    }
    info.track?.let { track ->
        InspectorDetailRow(
            label = stringResource(Res.string.label_metadata_tag_track),
            value = track,
            compact = true,
        )
    }
}

/** 压缩包加密状态、支持能力与顶层条目数分组。 */
@Composable
internal fun MetadataArchiveSection(
    entry: VFile,
    readArchiveInfo: suspend (ArchiveInfoRequest) -> ArchiveInfoResult,
) {
    var result by remember(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
        mutableStateOf<ArchiveInfoResult?>(null)
    }
    LaunchedEffect(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
        result = readArchiveInfo(ArchiveInfoRequest(entry))
    }
    MetadataSection(title = stringResource(Res.string.label_metadata_section_archive)) {
        InspectorDetailRow(
            label = stringResource(Res.string.label_inspector_archive_encrypted),
            value = archiveEncryptionValue(result),
            compact = true,
        )
        InspectorDetailRow(
            label = stringResource(Res.string.label_inspector_archive_capabilities),
            value = archiveCapabilityValue(result),
            compact = true,
        )
        val entryCount = (result as? ArchiveInfoResult.Info)?.entryCount
        if (entryCount != null) {
            InspectorDetailRow(
                label = stringResource(Res.string.label_metadata_archive_entries_label),
                value = entryCount.toString(),
                compact = true,
            )
        }
    }
}

/**
 * 按需计算文件 SHA-256 摘要（支持计算中取消与一键复制，且通过 Job 身份校验消除连续取消/重算状态竞态）。
 */
@Composable
internal fun MetadataChecksumSection(
    entry: VFile,
    readFileHash: suspend (FileHashRequest) -> FileHashResult,
) {
    val palette = LocalOnyxPalette.current
    val scope = rememberCoroutineScope()
    var hashResult by remember(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
        mutableStateOf<FileHashResult?>(null)
    }
    var computing by remember(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
        mutableStateOf(false)
    }
    var activeJob by remember(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
        mutableStateOf<Job?>(null)
    }

    DisposableEffect(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
        onDispose {
            activeJob?.cancel()
            activeJob = null
        }
    }

    MetadataSection(title = stringResource(Res.string.label_metadata_section_checksum)) {
        when {
            computing -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(Res.string.label_preview_loading),
                    color = palette.mutedForeground,
                    fontSize = 11.5.sp,
                )
                Box(
                    modifier = Modifier
                        .testTag("metadata-cancel-sha256-button")
                        .clip(RoundedCornerShape(4.dp))
                        .background(palette.surfaceVariant)
                        .border(1.dp, palette.outlineVariant, RoundedCornerShape(4.dp))
                        .clickable {
                            activeJob?.cancel()
                            activeJob = null
                            computing = false
                        }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.action_metadata_cancel_sha256),
                        color = palette.foreground,
                        fontSize = 11.sp,
                    )
                }
            }

            hashResult != null -> {
                val current = hashResult
                val hashValue = (current as? FileHashResult.Hash)?.value
                val displayText = when (current) {
                    is FileHashResult.Hash -> current.value
                    FileHashResult.TooLarge -> stringResource(Res.string.label_preview_too_large)
                    FileHashResult.Unavailable -> stringResource(Res.string.label_preview_unavailable)
                    is FileHashResult.Failed -> current.reason.resolve()
                    null -> ""
                }
                InspectorDetailRow(
                    label = stringResource(Res.string.label_inspector_sha256),
                    value = displayText,
                    copyValue = hashValue,
                    monospace = hashValue != null,
                )
            }

            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(Res.string.label_metadata_sha256_idle),
                    color = palette.mutedForeground,
                    fontSize = 11.sp,
                )
                Box(
                    modifier = Modifier
                        .testTag("metadata-compute-sha256-button")
                        .clip(RoundedCornerShape(4.dp))
                        .background(palette.surfaceVariant)
                        .border(1.dp, palette.outlineVariant, RoundedCornerShape(4.dp))
                        .clickable {
                            activeJob?.cancel()
                            computing = true
                            val launchedJob = scope.launch {
                                val self = coroutineContext[Job]
                                try {
                                    val computed = readFileHash(
                                        FileHashRequest(entry = entry, maxBytes = ON_DEMAND_HASH_MAX_BYTES)
                                    )
                                    if (activeJob === self) {
                                        hashResult = computed
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } finally {
                                    if (activeJob === self) {
                                        computing = false
                                        activeJob = null
                                    }
                                }
                            }
                            activeJob = launchedJob
                        }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.action_metadata_compute_sha256),
                        color = palette.foreground,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun archiveEncryptionValue(result: ArchiveInfoResult?): String {
    return when (result) {
        null -> stringResource(Res.string.label_preview_loading)
        is ArchiveInfoResult.Info -> if (result.encrypted) {
            stringResource(Res.string.label_archive_encrypted_yes)
        } else {
            stringResource(Res.string.label_archive_encrypted_no)
        }

        ArchiveInfoResult.Unavailable -> stringResource(Res.string.label_preview_unavailable)
        is ArchiveInfoResult.Failed -> result.reason.resolve()
    }
}

@Composable
private fun archiveCapabilityValue(result: ArchiveInfoResult?): String {
    return when (result) {
        null -> stringResource(Res.string.label_preview_loading)
        is ArchiveInfoResult.Info -> buildList {
            if (result.canBrowse) {
                add(stringResource(Res.string.label_archive_capability_browse))
            }
            if (result.canExtract) {
                add(stringResource(Res.string.label_archive_capability_extract))
            }
            if (!result.canWrite) {
                add(stringResource(Res.string.label_archive_capability_read_only))
            }
        }.joinToString(separator = stringResource(Res.string.label_list_separator))
        ArchiveInfoResult.Unavailable -> stringResource(Res.string.label_preview_unavailable)
        is ArchiveInfoResult.Failed -> result.reason.resolve()
    }
}
