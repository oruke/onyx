package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.formatFileSize
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_directory_file_count
import onyx.composeapp.generated.resources.label_filtered_item_count
import onyx.composeapp.generated.resources.label_item_count
import onyx.composeapp.generated.resources.label_mode_details
import onyx.composeapp.generated.resources.label_mode_gallery
import onyx.composeapp.generated.resources.label_selected_size
import onyx.composeapp.generated.resources.label_task_summary
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// ── Status bar ──────────────────────────────────────────────────────────────

@Composable
internal fun StatusBar(
    primaryPane: PaneState,
    secondaryPane: PaneState,
    activePane: PaneId,
    activeTaskCount: Int,
    onSetActiveViewMode: (ViewMode) -> Unit,
) {
    val activeState = if (activePane == PaneId.PRIMARY) primaryPane else secondaryPane
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(LocalOnyxPalette.current.statusBarBackground)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val status = activeState.statusInfo
            Text(
                text = stringResource(
                    Res.string.label_directory_file_count,
                    status.directoryCount,
                    status.fileCount,
                ),
                fontSize = 11.sp,
                color = LocalOnyxPalette.current.mutedForeground,
            )
            Text(
                text = stringResource(Res.string.label_item_count, status.totalItemCount),
                fontSize = 11.sp,
                color = LocalOnyxPalette.current.mutedForeground,
            )
            if (activeState.filterQuery.isNotBlank()) {
                Text(
                    text = stringResource(
                        Res.string.label_filtered_item_count,
                        status.visibleItemCount,
                        status.totalItemCount,
                    ),
                    fontSize = 11.sp,
                    color = LocalOnyxPalette.current.mutedForeground,
                )
            }
            val selectedSizeText = stringResource(
                Res.string.label_selected_size,
                status.selectedCount,
                formatFileSize(status.selectedSizeBytes),
            )
            Text(
                text = selectedSizeText,
                fontSize = 11.sp,
                color = LocalOnyxPalette.current.mutedForeground,
            )
            if (activeTaskCount > 0) {
                Text(
                    text = stringResource(Res.string.label_task_summary, activeTaskCount),
                    fontSize = 11.sp,
                    color = LocalOnyxPalette.current.mutedForeground,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LayoutIconButton(
                selected = activeState.viewMode == ViewMode.DETAILS,
                onClick = { onSetActiveViewMode(ViewMode.DETAILS) },
                tooltip = stringResource(Res.string.label_mode_details),
            ) {
                Icon(
                    key = AllIconsKeys.Actions.ListFiles,
                    contentDescription = stringResource(Res.string.label_mode_details),
                )
            }
            LayoutIconButton(
                selected = activeState.viewMode == ViewMode.GALLERY,
                onClick = { onSetActiveViewMode(ViewMode.GALLERY) },
                tooltip = stringResource(Res.string.label_mode_gallery),
            ) {
                Icon(
                    key = AllIconsKeys.General.Layout,
                    contentDescription = stringResource(Res.string.label_mode_gallery),
                )
            }
        }
    }
}

@Composable
internal fun ZoomSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    val fraction = ((value - 50) / 150f).coerceIn(0f, 1f)
    val sliderWidthDp = 120
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sliderWidthPx = with(density) { sliderWidthDp.dp.toPx() }

    Box(
        modifier = Modifier
            .width(sliderWidthDp.dp)
            .height(16.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val x = change.position.x.coerceIn(0f, sliderWidthPx)
                    val newFraction = x / sliderWidthPx
                    onValueChange((50 + (newFraction * 150).toInt()).coerceIn(50, 200))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val newFraction = (offset.x / sliderWidthPx).coerceIn(0f, 1f)
                    onValueChange((50 + (newFraction * 150).toInt()).coerceIn(50, 200))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(LocalOnyxPalette.current.outlineVariant, RoundedCornerShape(1.dp)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(3.dp)
                .background(LocalOnyxPalette.current.accent, RoundedCornerShape(1.dp)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (fraction * (sliderWidthDp - 8)).dp)
                .size(8.dp)
                .background(LocalOnyxPalette.current.accent, RoundedCornerShape(4.dp)),
        )
    }
}
