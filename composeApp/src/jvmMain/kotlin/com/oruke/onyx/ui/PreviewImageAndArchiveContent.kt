package com.oruke.onyx.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.filesystem.ArchiveInfoRequest
import com.oruke.onyx.app.filesystem.ArchiveInfoResult
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.decodeSfmDisplayText
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.formatFileSize
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_open_externally
import onyx.composeapp.generated.resources.action_preview_open_image_viewer
import onyx.composeapp.generated.resources.label_archive_encrypted_yes
import onyx.composeapp.generated.resources.label_preview_archive_entries
import onyx.composeapp.generated.resources.label_preview_loading
import onyx.composeapp.generated.resources.label_preview_unavailable
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** 预览面板图片与压缩包封面请求的最大边长。 */
internal const val PREVIEW_THUMBNAIL_DIMENSION = 960

/**
 * 图片内容优先预览：占满面板主体区域，双击或点工具图标直接唤起内置大图查看器。
 */
@Composable
internal fun PreviewImageContent(
    entry: VFile,
    loadThumbnail: suspend (String, Int) -> ImageBitmap?,
    loadVersionedThumbnail: (suspend (String, Int, Long?, Long?) -> ImageBitmap?)?,
    peekCachedThumbnail: (String, Int, Long?, Long?) -> ImageBitmap?,
    readImageSize: (suspend (VFile) -> IntSize?)?,
    onOpenImageViewer: ((VFile) -> Unit)?,
    onOpenExternal: ((VFile) -> Unit)?,
) {
    val palette = LocalOnyxPalette.current
    val versionKey = entry.modifiedAtEpochMillis to entry.sizeBytes
    val loader = remember(entry.modifiedAtEpochMillis, entry.sizeBytes, loadThumbnail, loadVersionedThumbnail) {
        if (loadVersionedThumbnail != null) {
            suspend { loc: String, dim: Int ->
                loadVersionedThumbnail(loc, dim, entry.modifiedAtEpochMillis, entry.sizeBytes)
            }
        } else {
            loadThumbnail
        }
    }
    val (thumbnail, isLoading) = rememberAsyncBitmap(
        location = entry.location,
        maxDimension = PREVIEW_THUMBNAIL_DIMENSION,
        loader = loader,
        versionKey = versionKey,
        peekCached = { loc, dim ->
            peekCachedThumbnail(loc, dim, entry.modifiedAtEpochMillis, entry.sizeBytes)
        },
    )
    var imageSize by remember(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
        mutableStateOf<IntSize?>(null)
    }
    LaunchedEffect(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes, readImageSize) {
        imageSize = readImageSize?.invoke(entry)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(palette.surfaceVariant.copy(alpha = 0.45f))
                .border(1.dp, palette.outlineVariant, RoundedCornerShape(6.dp))
                .pointerInput(entry.location, onOpenImageViewer) {
                    if (onOpenImageViewer != null) {
                        detectTapGestures(onDoubleTap = { onOpenImageViewer(entry) })
                    }
                }
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                thumbnail != null -> Image(
                    bitmap = thumbnail,
                    contentDescription = entry.name,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize().testTag("preview-image-bitmap"),
                )

                isLoading -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OnyxFileIcon(entry = entry, size = 48.dp)
                    Text(
                        text = stringResource(Res.string.label_preview_loading),
                        color = palette.mutedForeground,
                        fontSize = 11.sp,
                    )
                }

                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OnyxFileIcon(entry = entry, size = 56.dp)
                    Text(
                        text = stringResource(Res.string.label_preview_unavailable),
                        color = palette.mutedForeground,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.headerBackground.copy(alpha = 0.5f))
                .padding(start = 10.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val summaryText = buildList {
                imageSize?.let { add("${it.width}×${it.height}") }
                add(formatFileSize(entry.sizeBytes))
            }.joinToString(" · ")
            Text(
                text = entry.name.decodeSfmDisplayText(),
                color = palette.foreground,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = summaryText,
                color = palette.mutedForeground,
                fontSize = 10.5.sp,
                maxLines = 1,
            )
            if (onOpenImageViewer != null) {
                TitleBarIconButton(
                    onClick = { onOpenImageViewer(entry) },
                    tooltip = stringResource(Res.string.action_preview_open_image_viewer),
                    modifier = Modifier.testTag("preview-open-image-viewer-button"),
                ) {
                    Icon(
                        key = AllIconsKeys.General.FitContent,
                        contentDescription = stringResource(Res.string.action_preview_open_image_viewer),
                        tint = palette.foreground.copy(alpha = 0.80f),
                        modifier = Modifier.size(13.dp),
                    )
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
    }
}

/** 压缩包预览：展示内嵌首图封面（漫画/电子书等）与顶层条目/加密概况。 */
@Composable
internal fun PreviewArchiveContent(
    entry: VFile,
    loadArchiveThumbnail: (suspend (String, Int, Long?, Long?) -> ImageBitmap?)?,
    peekCachedArchiveThumbnail: (String, Int, Long?, Long?) -> ImageBitmap?,
    readArchiveInfo: (suspend (ArchiveInfoRequest) -> ArchiveInfoResult)?,
    onOpenExternal: ((VFile) -> Unit)?,
) {
    val palette = LocalOnyxPalette.current
    val (cover, isLoading) = if (loadArchiveThumbnail != null) {
        val loader = remember(entry.modifiedAtEpochMillis, entry.sizeBytes, loadArchiveThumbnail) {
            suspend { loc: String, dim: Int ->
                loadArchiveThumbnail(loc, dim, entry.modifiedAtEpochMillis, entry.sizeBytes)
            }
        }
        rememberAsyncBitmap(
            location = entry.location,
            maxDimension = PREVIEW_THUMBNAIL_DIMENSION,
            loader = loader,
            versionKey = entry.modifiedAtEpochMillis to entry.sizeBytes,
            peekCached = { loc, dim ->
                peekCachedArchiveThumbnail(loc, dim, entry.modifiedAtEpochMillis, entry.sizeBytes)
            },
        )
    } else {
        null to false
    }

    var archiveInfo by remember(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes) {
        mutableStateOf<ArchiveInfoResult?>(null)
    }
    LaunchedEffect(entry.location, entry.modifiedAtEpochMillis, entry.sizeBytes, readArchiveInfo) {
        archiveInfo = readArchiveInfo?.invoke(ArchiveInfoRequest(entry))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(palette.surfaceVariant.copy(alpha = 0.45f))
                .border(1.dp, palette.outlineVariant, RoundedCornerShape(6.dp))
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                cover != null -> Image(
                    bitmap = cover,
                    contentDescription = entry.name,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize().testTag("preview-archive-cover"),
                )

                isLoading -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OnyxFileIcon(entry = entry, size = 56.dp)
                    Text(
                        text = stringResource(Res.string.label_preview_loading),
                        color = palette.mutedForeground,
                        fontSize = 11.sp,
                    )
                }

                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OnyxFileIcon(entry = entry, size = 64.dp)
                    Text(
                        text = entry.name.decodeSfmDisplayText(),
                        color = palette.foreground,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
        val info = archiveInfo as? ArchiveInfoResult.Info
        val rightSummary = buildList {
            info?.entryCount?.let { add(stringResource(Res.string.label_preview_archive_entries, it)) }
            if (info?.encrypted == true) {
                add(stringResource(Res.string.label_archive_encrypted_yes))
            }
            add(formatFileSize(entry.sizeBytes))
        }.joinToString(" · ")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.headerBackground.copy(alpha = 0.5f))
                .padding(start = 10.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = entry.name.decodeSfmDisplayText(),
                color = palette.foreground,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = rightSummary,
                color = palette.mutedForeground,
                fontSize = 10.5.sp,
                maxLines = 1,
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
    }
}

/** 文件夹、回收站条目或其他暂不支持内置预览的二进制文件兜底卡片。 */
@Composable
internal fun PreviewFallbackCard(
    entry: VFile,
    hint: String,
    onOpenExternal: ((VFile) -> Unit)?,
) {
    val palette = LocalOnyxPalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OnyxFileIcon(entry = entry, size = 64.dp)
        Text(
            text = entry.name.decodeSfmDisplayText(),
            color = palette.foreground,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = hint,
            color = palette.mutedForeground,
            fontSize = 11.5.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (onOpenExternal != null) {
            Row(
                modifier = Modifier
                    .padding(top = 14.dp)
                    .testTag("preview-open-external-button")
                    .clip(RoundedCornerShape(4.dp))
                    .background(palette.surfaceVariant)
                    .border(1.dp, palette.outlineVariant, RoundedCornerShape(4.dp))
                    .clickable { onOpenExternal(entry) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    key = AllIconsKeys.Ide.External_link_arrow,
                    contentDescription = null,
                    tint = palette.foreground,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = stringResource(Res.string.action_open_externally),
                    color = palette.foreground,
                    fontSize = 11.5.sp,
                )
            }
        }
    }
}
