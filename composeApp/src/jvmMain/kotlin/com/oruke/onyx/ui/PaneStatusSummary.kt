package com.oruke.onyx.ui

import androidx.compose.runtime.Composable
import com.oruke.onyx.app.component.ComputerEntriesState
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.formatFileSize
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_directory_file_count
import onyx.composeapp.generated.resources.label_directory_refreshing
import onyx.composeapp.generated.resources.label_filtered_item_count
import onyx.composeapp.generated.resources.label_item_count
import onyx.composeapp.generated.resources.label_selected_size
import onyx.composeapp.generated.resources.label_storage_status
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/**
 * 根据位置内容展示文件或设备统计，设备选择不再显示为零字节文件。
 *
 * @param state 当前活动面板状态。
 */
@Composable
internal fun PaneStatusSummary(state: PaneState) {
    val status = state.statusInfo
    val computer = state.entriesState as? ComputerEntriesState
    if (computer != null) {
        PaneStatusText(
            stringResource(
                Res.string.label_storage_status,
                computer.devices.size,
                computer.places.size,
                status.selectedCount,
            )
        )
    } else {
        if (state.activeTabState.refreshing)
            PaneStatusText(stringResource(Res.string.label_directory_refreshing))
        PaneStatusText(
            stringResource(
                Res.string.label_directory_file_count,
                status.directoryCount,
                status.fileCount,
            )
        )
        PaneStatusText(stringResource(Res.string.label_item_count, status.totalItemCount))
        PaneStatusText(
            stringResource(
                Res.string.label_selected_size,
                status.selectedCount,
                formatFileSize(status.selectedSizeBytes),
            )
        )
    }
    if (state.filterQuery.isNotBlank())
        PaneStatusText(
            stringResource(
                Res.string.label_filtered_item_count,
                status.visibleItemCount,
                status.totalItemCount,
            )
        )
}

/**
 * 保持文件与设备统计一致的字号与文字颜色。
 *
 * @param text 当前语言下的统计文本。
 */
@Composable
private fun PaneStatusText(text: String) {
    Text(
        text,
        fontSize = LocalOnyxAppearance.current.feedbackFontSize,
        color = LocalOnyxPalette.current.mutedForeground,
    )
}
