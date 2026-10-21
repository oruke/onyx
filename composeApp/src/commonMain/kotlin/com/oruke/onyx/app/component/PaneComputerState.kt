package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.StorageDevice
import com.oruke.onyx.core.model.StorageDeviceAction
import com.oruke.onyx.core.model.SystemQuickAccessLocation

/** 设备概览是独立位置内容，设备不会伪装成可删除的普通文件。 */
internal data class ComputerEntriesState(
    /** 按稳定身份更新的设备列表。 */
    val devices: List<StorageDevice> = emptyList(),
    /** 按当前过滤与交互保护规则派生的可见设备。 */
    val visibleDevices: List<StorageDevice> = emptyList(),
    /** 系统真实目录入口，继续通过统一面板导航打开。 */
    val places: List<SystemQuickAccessLocation> = emptyList(),
    /** 经过当前过滤条件处理的目录入口。 */
    val visiblePlaces: List<SystemQuickAccessLocation> = emptyList(),
    /** 首次尚未得到设备查询结果。 */
    val loading: Boolean = true,
    /** 整体发现失败；已有内容仍然保留。 */
    val failure: I18nMessage? = null,
    /** 目录入口发现失败，与设备发现和操作结果分别保留。 */
    val placesFailure: I18nMessage? = null,
    /** 最近设备操作失败，周期设备更新不能自动清除该错误。 */
    val operationFailure: I18nMessage? = null,
    /** 正在执行设备操作的稳定身份集合。 */
    val busyDeviceIds: Set<String> = emptySet(),
) : PaneEntriesState

/** 设备概览的业务事件，平台对象不得穿过组件入口。 */
internal sealed interface PaneDeviceIntent : PaneIntent {
    /** 打开已挂载设备，或挂载成功后进入实际位置。 */
    data class Open(
        /** 当前概览中的稳定设备身份。 */
        val deviceId: String,
        /** 是否在新的标签页打开设备。 */
        val newTab: Boolean = false,
    ) : PaneDeviceIntent

    /** 执行平台明确提供的单设备操作。 */
    data class Execute(
        /** 目标设备身份。 */
        val deviceId: String,
        /** 用户请求的平台动作。 */
        val action: StorageDeviceAction,
    ) : PaneDeviceIntent
}
