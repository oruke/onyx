package com.oruke.onyx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.oruke.onyx.app.component.PaneDeviceIntent
import com.oruke.onyx.core.model.StorageDevice
import com.oruke.onyx.core.model.StorageDeviceState
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import org.jetbrains.jewel.ui.component.Text

/**
 * 紧凑磁盘块将图标、卷名、状态和容量集中在一起，复用统一选择、菜单和双击行为。
 * @param device 当前设备快照。
 * @param model 当前面板与访问模型。
 * @param actions 组件事件入口。
 * @param busy 当前设备是否正在执行操作。
 * @return 无返回值。
 */
@Composable
internal fun ComputerDeviceTile(
    device: StorageDevice,
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    busy: Boolean,
) {
    val appearance = LocalOnyxAppearance.current
    ComputerEntrySurface(
        device.id, model, actions,
        { actions.onDeviceIntent(PaneDeviceIntent.Open(device.id)) }, Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = appearance.listRowHeight * 3)
                .padding(appearance.entryHorizontalPadding, appearance.entryContentSpacing),
            horizontalArrangement = Arrangement.spacedBy(appearance.computer.itemSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StorageDeviceIcon(device.kind, Modifier.size(appearance.computer.deviceIconSize))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(appearance.entryContentSpacing)) {
                ComputerDeviceHeading(device, busy)
                ComputerCapacityMeter(device, "computer-capacity-${model.paneId}-${device.id}")
                if (device.state == StorageDeviceState.MOUNTED) {
                    Text(storageDeviceCapacity(device), fontSize = appearance.headerFontSize,
                        color = LocalOnyxPalette.current.mutedForeground)
                }
            }
        }
    }
}
