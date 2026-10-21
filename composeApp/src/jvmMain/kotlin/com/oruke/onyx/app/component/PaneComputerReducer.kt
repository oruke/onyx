package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.PaneStatusInfo
import java.util.Locale

/**
 * 派生设备概览的显示顺序、过滤与选择；普通文件列表不会参与设备状态校正。
 *
 * @return 与原访问身份和视口绑定的设备概览标签。
 */
internal fun PaneTabState.withDerivedComputerState(): PaneTabState {
    val computer = entriesState as? ComputerEntriesState ?: return this
    val query = filterQuery.trim()
    val sorted =
        computer.devices
            .filter { device ->
                query.isEmpty() ||
                    device.name.contains(query, true) ||
                    device.locations.any { it.contains(query, true) }
            }
            .sortedBy { device ->
                (device.locations.firstOrNull() ?: "~${device.id}").lowercase(Locale.ROOT)
            }
    val remaining = sorted.associateByTo(linkedMapOf()) { it.id }
    val visible =
        if (deferDirectoryOrdering)
            buildList {
                computer.visibleDevices.forEach { old -> remaining.remove(old.id)?.let(::add) }
                addAll(remaining.values)
            }
        else sorted
    val sortedPlaces =
        computer.places.filter {
            query.isEmpty() ||
                it.displayName.orEmpty().contains(query, true) ||
                it.location.contains(query, true)
        }
    // 目录入口通常异步晚于设备到达；手势期间不能向设备上方插入新入口。
    val places =
        if (deferDirectoryOrdering) {
            val byLocation = sortedPlaces.associateBy { it.location }
            computer.visiblePlaces.mapNotNull { byLocation[it.location] }
        } else sortedPlaces
    val ids = visible.map { it.id } + places.map { it.location }
    // 初次恢复历史时清单尚未到达，不能把已有选择和视口提前清空。
    val selection =
        if (computer.loading) selectedEntryIds else selectedEntryIds.intersect(ids.toSet())
    return withTabState { state ->
        state.copy(
            entriesState = computer.copy(visibleDevices = visible, visiblePlaces = places),
            selectedEntryIds = selection,
            selectionAnchorId = selectionAnchorId?.takeIf { computer.loading || it in ids },
            selectionFocusId = selectionFocusId?.takeIf { computer.loading || it in ids },
            browserState =
                state.browserState.copy(
                    directoryOrderDeferred =
                        deferDirectoryOrdering && (visible != sorted || places != sortedPlaces)
                ),
            statusInfo =
                PaneStatusInfo(
                    totalItemCount = computer.devices.size + computer.places.size,
                    visibleItemCount = ids.size,
                    directoryCount = places.size,
                    selectedCount = selection.size,
                ),
        )
    }
}

/** @return 当前设备概览中可按键盘顺序选择的稳定条目标识。 */
internal fun ComputerEntriesState.selectableIds(): List<String> =
    visiblePlaces.map { it.location } + deviceSections.flatMap { group -> group.devices.map { it.id } }

/**
 * 应用设备或目录入口的追加、范围与普通选择，不构造虚假文件对象。
 *
 * @param entryId 已出现在当前可见概览中的目标身份。
 * @param additive 是否保留其他已选条目。
 * @param range 是否按当前显示顺序从锚点扩展。
 * @return 更新选择和交互版本后的标签。
 */
internal fun PaneTabState.selectComputerEntry(
    entryId: String,
    additive: Boolean,
    range: Boolean,
): PaneTabState {
    val ids = (entriesState as? ComputerEntriesState)?.selectableIds().orEmpty()
    val index = ids.indexOf(entryId)
    if (index < 0) return this
    val anchor = ids.indexOf(selectionAnchorId).takeIf { it >= 0 } ?: index
    val next =
        when {
            range ->
                ids.subList(minOf(anchor, index), maxOf(anchor, index) + 1).toSet() +
                    if (additive) selectedEntryIds else emptySet()
            additive ->
                if (entryId in selectedEntryIds) selectedEntryIds - entryId
                else selectedEntryIds + entryId
            else -> setOf(entryId)
        }
    return withTabState {
        it.copy(
            selectedEntryIds = next,
            selectionFocusId = entryId,
            selectionAnchorId = if (range) ids[anchor] else entryId,
            browserState =
                it.browserState.copy(interactionRevision = it.browserState.interactionRevision + 1),
        )
    }
}

/**
 * 按概览真实显示顺序移动焦点，首次向下选择首项，向上选择末项。
 *
 * @param offset 方向键请求的条目偏移。
 * @param extendSelection 是否从锚点扩展选择。
 * @return 更新焦点与选择的标签。
 */
internal fun PaneTabState.moveComputerSelection(
    offset: Int,
    extendSelection: Boolean,
): PaneTabState {
    val ids = (entriesState as? ComputerEntriesState)?.selectableIds().orEmpty()
    val oldIndex = ids.indexOf(selectionFocusId)
    val index =
        if (oldIndex < 0) {
            if (offset < 0) ids.lastIndex else 0
        } else (oldIndex + offset).coerceIn(0, (ids.size - 1).coerceAtLeast(0))
    return ids.getOrNull(index)?.let { selectComputerEntry(it, false, extendSelection) } ?: this
}
