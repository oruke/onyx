package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.oruke.onyx.core.model.StorageDevice
import com.oruke.onyx.core.model.StorageDeviceState
import com.oruke.onyx.core.model.usedCapacityFraction
import com.oruke.onyx.core.model.volumeLabel
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.resolve
import org.jetbrains.jewel.ui.component.Text

/**
 * 概览和列表共用名称、挂载点与限制提示，限制独立换行，不能被容量挤掉。
 * @param device 当前设备快照。
 * @param busy 当前是否有在途设备操作。
 * @param modifier 名称区域的布局约束。
 * @return 无返回值。
 */
@Composable
internal fun ComputerDeviceHeading(device: StorageDevice, busy: Boolean, modifier: Modifier = Modifier) {
    val appearance = LocalOnyxAppearance.current
    val palette = LocalOnyxPalette.current
    val label = device.volumeLabel()
    val restrictions = storageDeviceRestrictions(device, busy)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(appearance.entryContentSpacing / 2)) {
        Text(
            storageVolumeTitle(label), maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium, fontSize = appearance.listFontSize,
            color = appearance.fileNameColor ?: palette.foreground
        )
        if (label.drive == null && device.locations.isNotEmpty()) {
            Text(
                device.locations.joinToString(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = appearance.headerFontSize, color = palette.mutedForeground
            )
        }
        if (restrictions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(appearance.entryContentSpacing)) {
                restrictions.forEach { status ->
                    Text(
                        status, fontSize = appearance.headerFontSize,
                        color = if (device.state == StorageDeviceState.UNAVAILABLE || storageDeviceLowSpace(device))
                            palette.errorForeground else palette.mutedForeground
                    )
                }
            }
        }
        device.failure?.let { failure ->
            Text(failure.resolve(), fontSize = appearance.headerFontSize, color = palette.errorForeground)
        }
    }
}

/**
 * 绘制已知容量的细占用条，未知容量不生成零值进度或虚假的可用空间。
 * @param device 当前设备快照。
 * @param tag 当前面板内的稳定语义标识。
 * @param modifier 容量条的布局约束。
 * @return 无返回值。
 */
@Composable
internal fun ComputerCapacityMeter(device: StorageDevice, tag: String, modifier: Modifier = Modifier) {
    val fraction = device.usedCapacityFraction() ?: return
    val appearance = LocalOnyxAppearance.current
    val palette = LocalOnyxPalette.current
    Box(
        modifier.fillMaxWidth().height(appearance.computer.meterHeight)
        .background(palette.outlineVariant).semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
        }.testTag(tag)
    ) {
        Box(
            Modifier.fillMaxWidth(fraction).fillMaxHeight()
                .background(if (storageDeviceLowSpace(device)) palette.error else palette.accent)
        )
    }
}
