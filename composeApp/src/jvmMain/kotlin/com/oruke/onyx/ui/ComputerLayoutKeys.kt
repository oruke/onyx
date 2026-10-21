package com.oruke.onyx.ui

import com.oruke.onyx.app.component.ComputerEntriesState
import com.oruke.onyx.app.component.deviceSections
import com.oruke.onyx.core.model.StorageDeviceKind

/**
 * 为设备分区建立与清单更新无关的稳定布局键。
 * @param kind 分区设备类型。
 * @return 虚拟列表和网格共用的分区键。
 */
internal fun computerSectionKey(kind: StorageDeviceKind): String = "computer-devices-${kind.name}"

/**
 * 生成与虚拟容器逐项一致的显示键，文件夹快捷区是一个有界布局项目。
 * @param state 当前设备与目录入口。
 * @param details 是否包含紧凑列表的列标题。
 * @return 滚动恢复所用的布局键顺序。
 */
internal fun computerLayoutKeys(state: ComputerEntriesState, details: Boolean): List<String> = buildList {
    if (state.visiblePlaces.isNotEmpty()) {
        add("computer-places")
        add("computer-places-grid")
    }
    if (details) add("computer-columns")
    state.deviceSections.forEach { section ->
        add(computerSectionKey(section.kind))
        addAll(section.devices.map { it.id })
    }
}

/**
 * 将快捷目录的滚动请求映射到其所在布局项目，保留业务选择的真实目录身份。
 * @param state 当前设备与标准目录。
 * @return 只调整布局定位目标的渲染模型。
 */
internal fun PaneEntriesViewModel.forComputerLayout(state: ComputerEntriesState): PaneEntriesViewModel =
    if (state.visiblePlaces.any { it.location == pendingScrollToEntryId }) {
        copy(pendingScrollToEntryId = "computer-places-grid")
    } else this
