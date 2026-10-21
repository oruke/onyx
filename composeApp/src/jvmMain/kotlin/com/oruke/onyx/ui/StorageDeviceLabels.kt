package com.oruke.onyx.ui

import androidx.compose.runtime.Composable
import com.oruke.onyx.core.model.StorageDevice
import com.oruke.onyx.core.model.StorageDeviceKind
import com.oruke.onyx.core.model.StorageDeviceState
import com.oruke.onyx.core.model.StorageVolumeLabel
import com.oruke.onyx.core.model.usedCapacityFraction
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.formatFileSize
import com.oruke.onyx.ui.theme.resolve
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_storage_available
import onyx.composeapp.generated.resources.label_storage_busy
import onyx.composeapp.generated.resources.label_storage_checking
import onyx.composeapp.generated.resources.label_storage_local
import onyx.composeapp.generated.resources.label_storage_network
import onyx.composeapp.generated.resources.label_storage_optical
import onyx.composeapp.generated.resources.label_storage_read_only
import onyx.composeapp.generated.resources.label_storage_removable
import onyx.composeapp.generated.resources.label_storage_summary
import onyx.composeapp.generated.resources.label_storage_unavailable
import onyx.composeapp.generated.resources.label_storage_unknown
import onyx.composeapp.generated.resources.label_storage_unmounted
import onyx.composeapp.generated.resources.label_storage_volume_title
import onyx.composeapp.generated.resources.label_storage_capacity_unknown
import onyx.composeapp.generated.resources.label_storage_low_space
import onyx.composeapp.generated.resources.label_computer_local_volumes
import onyx.composeapp.generated.resources.label_computer_network_locations
import onyx.composeapp.generated.resources.label_computer_removable_devices
import onyx.composeapp.generated.resources.label_computer_optical_drives
import onyx.composeapp.generated.resources.label_computer_other_devices
import org.jetbrains.compose.resources.stringResource

/**
 * 使用统一容量格式展示设备大小，未挂载和正在查询分别显示。
 *
 * @param device 当前设备快照。
 * @return 当前语言的容量文案。
 */
@Composable
internal fun storageDeviceCapacity(device: StorageDevice): String {
    val total = device.totalBytes
    val available = device.availableBytes
    return when {
        device.state == StorageDeviceState.UNAVAILABLE ->
            stringResource(Res.string.label_storage_unavailable)
        device.state == StorageDeviceState.UNMOUNTED ->
            stringResource(Res.string.label_storage_unmounted)
        device.failure != null -> stringResource(Res.string.label_storage_capacity_unknown)
        total != null && total > 0 && available != null && available >= 0 ->
            stringResource(
                Res.string.label_storage_available,
                formatFileSize(available),
                formatFileSize(total),
            )
        total != null && available != null ->
            stringResource(Res.string.label_storage_capacity_unknown)
        else -> stringResource(Res.string.label_storage_checking)
    }
}

/**
 * 本地化设备名称；卷标缺失时使用类型名称，盘符只作为名称后缀。
 * @param label 与真实导航路径分离的卷名信息。
 * @return 此电脑与面包屑共用的显示名称。
 */
@Composable
internal fun storageVolumeTitle(label: StorageVolumeLabel): String {
    val name = label.name ?: storageDeviceKindLabel(label.kind)
    return label.drive?.let { stringResource(Res.string.label_storage_volume_title, name, it) } ?: name
}

/**
 * 独立列出限制状态，避免只读、离线或忙碌被容量文字省略。
 * @param device 当前设备快照。
 * @param busy 是否正在执行设备操作。
 * @return 当前语言的限制与异常提示，没有限制时为空。
 */
@Composable
internal fun storageDeviceRestrictions(device: StorageDevice, busy: Boolean): List<String> {
    val restrictions = mutableListOf<String>()
    if (busy) restrictions += stringResource(Res.string.label_storage_busy)
    if (device.readOnly == true) restrictions += stringResource(Res.string.label_storage_read_only)
    when (device.state) {
        StorageDeviceState.UNAVAILABLE -> restrictions += stringResource(Res.string.label_storage_unavailable)
        StorageDeviceState.UNMOUNTED -> restrictions += stringResource(Res.string.label_storage_unmounted)
        StorageDeviceState.MOUNTED -> Unit
    }
    if (storageDeviceLowSpace(device)) restrictions += stringResource(Res.string.label_storage_low_space)
    return restrictions
}

/**
 * 依照统一外观阈值判断容量提示，不把未知或读取失败当成空间不足。
 * @param device 当前设备快照。
 * @return 已知剩余空间低于阈值时为 true。
 */
@Composable
internal fun storageDeviceLowSpace(device: StorageDevice): Boolean =
    device.usedCapacityFraction()?.let {
        it > 1f - LocalOnyxAppearance.current.computer.lowSpaceFraction
    } == true

/**
 * 展示分区标题，复数分区名称与单个设备的类型名称分别本地化。
 * @param kind 设备分区类型。
 * @return 当前语言的分区名称。
 */
@Composable
internal fun computerDeviceSectionTitle(kind: StorageDeviceKind): String = stringResource(
    when (kind) {
        StorageDeviceKind.LOCAL -> Res.string.label_computer_local_volumes
        StorageDeviceKind.NETWORK -> Res.string.label_computer_network_locations
        StorageDeviceKind.REMOVABLE -> Res.string.label_computer_removable_devices
        StorageDeviceKind.OPTICAL -> Res.string.label_computer_optical_drives
        StorageDeviceKind.UNKNOWN -> Res.string.label_computer_other_devices
    }
)

/**
 * 展示组件提供的设备状态，不在 UI 发起可用性探测。
 *
 * @param device 当前设备快照。
 * @param busy 设备是否正在执行操作。
 * @return 当前语言下的设备状态。
 */
@Composable
internal fun storageDeviceStatus(device: StorageDevice, busy: Boolean): String =
    when {
        busy -> stringResource(Res.string.label_storage_busy)
        device.readOnly == true -> stringResource(Res.string.label_storage_read_only)
        device.state == StorageDeviceState.UNAVAILABLE ->
            stringResource(Res.string.label_storage_unavailable)
        device.state == StorageDeviceState.UNMOUNTED ->
            stringResource(Res.string.label_storage_unmounted)
        else -> storageDeviceKindLabel(device.kind)
    }

/**
 * 紧凑条目优先保留限制状态，再显示容量，避免省略号先隐藏只读或不可用提示。
 *
 * @param device 当前设备快照。
 * @param busy 设备是否仍有在途操作。
 * @return 当前语言的紧凑摘要，失败和忙碌优先于普通容量信息。
 */
@Composable
internal fun storageDeviceCompactSummary(device: StorageDevice, busy: Boolean): String {
    val capacity = storageDeviceCapacity(device)
    val status = storageDeviceStatus(device, busy)
    val failure = device.failure
    return when {
        failure != null -> failure.resolve()
        busy -> status
        device.readOnly == true || device.state == StorageDeviceState.UNAVAILABLE ->
            stringResource(Res.string.label_storage_summary, status, capacity)
        else -> capacity
    }
}

/**
 * 将平台设备分类映射为统一的三语资源。
 *
 * @param kind 当前设备分类。
 * @return 当前语言的设备类型名称。
 */
@Composable
internal fun storageDeviceKindLabel(kind: StorageDeviceKind): String =
    stringResource(
        when (kind) {
            StorageDeviceKind.LOCAL -> Res.string.label_storage_local
            StorageDeviceKind.REMOVABLE -> Res.string.label_storage_removable
            StorageDeviceKind.OPTICAL -> Res.string.label_storage_optical
            StorageDeviceKind.NETWORK -> Res.string.label_storage_network
            StorageDeviceKind.UNKNOWN -> Res.string.label_storage_unknown
        }
    )
