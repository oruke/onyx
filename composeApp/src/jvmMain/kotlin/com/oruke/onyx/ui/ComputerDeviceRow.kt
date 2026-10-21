package com.oruke.onyx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.oruke.onyx.app.component.PaneDeviceIntent
import com.oruke.onyx.core.model.StorageDevice
import com.oruke.onyx.core.model.usedCapacityFraction
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.formatFileSize
import org.jetbrains.jewel.ui.component.Text

/**
 * 对齐名称、可用容量、总容量和占用条；窄面板优先保留名称与限制状态。
 * @param device 当前设备快照。
 * @param model 当前访问和选择。
 * @param actions 组件事件入口。
 * @param busy 当前设备是否正在执行操作。
 * @param columns 与表头共用的列配置。
 * @return 无返回值。
 */
@Composable
internal fun ComputerDeviceRow(
    device: StorageDevice,
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    busy: Boolean,
    columns: ComputerDetailsColumns,
) {
    val appearance = LocalOnyxAppearance.current
    val dimensions = appearance.computer
    val knownCapacity = device.usedCapacityFraction() != null
    ComputerEntrySurface(
        device.id, model, actions,
        { actions.onDeviceIntent(PaneDeviceIntent.Open(device.id)) }, Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = appearance.listRowHeight * 2)
                .padding(appearance.entryHorizontalPadding, appearance.entryContentSpacing),
            horizontalArrangement = Arrangement.spacedBy(dimensions.itemSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(appearance.entryContentSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StorageDeviceIcon(device.kind)
                ComputerDeviceHeading(device, busy, Modifier.weight(1f))
            }
            Text(
                if (knownCapacity) formatFileSize(checkNotNull(device.availableBytes))
                else storageDeviceCapacity(device),
                Modifier.width(dimensions.availableColumnWidth), textAlign = TextAlign.End,
                fontSize = appearance.headerFontSize, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = LocalOnyxPalette.current.mutedForeground,
            )
            if (columns.totalVisible) Text(
                if (knownCapacity) formatFileSize(checkNotNull(device.totalBytes)) else "",
                Modifier.width(dimensions.totalColumnWidth), textAlign = TextAlign.End,
                fontSize = appearance.headerFontSize, color = LocalOnyxPalette.current.mutedForeground,
            )
            if (columns.usageVisible) Column(Modifier.width(dimensions.usageColumnWidth)) {
                ComputerCapacityMeter(device, "computer-capacity-${model.paneId}-${device.id}")
            }
        }
    }
}
