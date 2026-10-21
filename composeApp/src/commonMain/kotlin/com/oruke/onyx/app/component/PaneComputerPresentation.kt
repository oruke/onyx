package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.COMPUTER_LOCATION
import com.oruke.onyx.core.model.StorageDevice
import com.oruke.onyx.core.model.StorageDeviceKind
import com.oruke.onyx.core.model.ViewMode

/** 根据当前位置选择展示模式，文件夹和设备概览的偏好互不覆盖。 */
internal val TabState.effectiveViewMode: ViewMode
    get() = if (location == COMPUTER_LOCATION) computerViewMode else viewMode

/** 初始化普通目录的默认视图，和用户在此电脑中切换视图分别表达。 */
internal data class PaneDirectoryDefaultViewIntent(
    /** 持久化设置中的普通目录默认视图。 */
    val mode: ViewMode,
) : PaneIntent

/**
 * 从根组件通过统一事件入口初始化目录默认值，不改动设备概览偏好。
 * @param mode 普通目录默认视图。
 * @return 无返回值。
 */
internal fun PaneComponent.applyDefaultDirectoryViewMode(mode: ViewMode) =
    dispatch(PaneDirectoryDefaultViewIntent(mode))

/** 设备分区的统一显示顺序，键盘范围选择与界面布局必须使用同一顺序。 */
private val computerDeviceKinds = listOf(
    StorageDeviceKind.LOCAL,
    StorageDeviceKind.NETWORK,
    StorageDeviceKind.REMOVABLE,
    StorageDeviceKind.OPTICAL,
    StorageDeviceKind.UNKNOWN,
)

/** 一个非空的设备分区，由组件决定分组，UI 只负责渲染。 */
internal data class ComputerDeviceSection(
    /** 分区的统一设备类型。 */
    val kind: StorageDeviceKind,
    /** 保留组件内部顺序的可见设备。 */
    val devices: List<StorageDevice>,
)

/** 当前可见设备的非空分区，过滤和被动更新仍沿用组件既有规则。 */
internal val ComputerEntriesState.deviceSections: List<ComputerDeviceSection>
    get() = computerDeviceKinds.mapNotNull { kind ->
        visibleDevices.filter { it.kind == kind }.takeIf { it.isNotEmpty() }
            ?.let { ComputerDeviceSection(kind, it) }
    }
