package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import com.oruke.onyx.ui.theme.rememberThumbnail
import com.oruke.onyx.core.model.PaneInspectorState
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.formatFileSize
import com.oruke.onyx.ui.theme.formatModifiedTime
import com.oruke.onyx.ui.theme.isImageFile
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys


// ── Inspector Panel ─────────────────────────────────────────────────────────

@Composable
internal fun InspectorPanel(
    entry: VFile?,
    state: PaneInspectorState,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(260.dp)
            .background(LocalOnyxPalette.current.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (entry == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No item selected",
                    color = LocalOnyxPalette.current.mutedForeground,
                    fontSize = 12.sp
                )
            }
            return
        }

        if (state.previewVisible) {
            val isImage = isImageFile(entry.name)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(LocalOnyxPalette.current.surface, RoundedCornerShape(8.dp))
                    .border(1.dp, LocalOnyxPalette.current.outlineVariant, RoundedCornerShape(8.dp))
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isImage) {
                    val (thumbnail, _) = rememberThumbnail(entry.location, 480)
                    if (thumbnail != null) {
                        Image(
                            bitmap = thumbnail,
                            contentDescription = entry.name,
                            contentScale = ContentScale.Fit,
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Icon(
                        key = if (entry.kind == VFileKind.DIRECTORY) AllIconsKeys.Nodes.Folder else AllIconsKeys.FileTypes.Any_type,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }
        }

        if (state.detailsVisible) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = entry.name,
                    color = LocalOnyxPalette.current.foreground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))

                InspectorDetailRow("Type", if (entry.kind == VFileKind.DIRECTORY) "Directory" else "File")
                if (entry.kind != VFileKind.DIRECTORY) {
                    InspectorDetailRow("Size", formatFileSize(entry.sizeBytes))
                }
                InspectorDetailRow("Modified", formatModifiedTime(entry.modifiedAtEpochMillis))
                InspectorDetailRow("Location", entry.parentLocation ?: "Unknown")
            }
        }
    }
}

@Composable
internal fun InspectorDetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            color = LocalOnyxPalette.current.mutedForeground,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            color = LocalOnyxPalette.current.foreground,
            fontSize = 12.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }
}
