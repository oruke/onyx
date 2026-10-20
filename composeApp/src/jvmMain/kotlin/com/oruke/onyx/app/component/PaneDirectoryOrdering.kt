package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.VFile

/** 当前访问是否需要在被动更新时保护条目顺序。 */
internal val PaneTabState.deferDirectoryOrdering: Boolean
    get() = inlineEditState != null || tabState.browserState.pointerInteractionActive ||
        tabState.browserState.scrollInteractionActive

/**
 * 更新真实数据并为后续派生保留旧显示顺序，显式定位仍使用新内容。
 * @param entries 最新的完整目录内容。
 * @param focusEntry 显式导航要求定位的条目，没有定位要求时为空。
 * @return 已更新数据并保留可用旧顺序基线的标签。
 */
internal fun PaneTabState.withDirectoryEntries(entries: List<VFile>, focusEntry: VFile?): PaneTabState {
    val loaded = withLoadedEntries(entries, focusEntry)
    return if (focusEntry == null && entriesState is PaneEntriesState.Ready) {
        loaded.withTabState { it.copy(entriesState = entriesState) }
    } else loaded
}

/**
 * 在元数据、增删及时更新的同时保留已有条目顺序，新条目暂放末尾，交互结束后统一排序。
 * @param entries 按当前配置排序和过滤后的真实条目。
 * @param previous 交互开始前的可见顺序。
 * @return 保持旧顺序的新数据；实际顺序没有变化时直接复用输入列表。
 */
internal fun preserveDirectoryEntryOrder(entries: List<VFile>, previous: List<VFile>): List<VFile> {
    val remaining = entries.associateByTo(linkedMapOf()) { it.id }
    val previousCounts = previous.filter { it.identityKey != null }.groupingBy { it.identityKey }.eachCount()
    val uniqueObjects = entries.filter { it.identityKey != null }.groupBy { it.identityKey }
    val ordered = buildList {
        previous.forEach { old ->
            val identityMatch = old.identityKey?.takeIf { previousCounts[it] == 1 }
                ?.let { uniqueObjects[it]?.singleOrNull() }
            val target = identityMatch ?: remaining[old.id]
            target?.let { remaining.remove(it.id) }?.let(::add)
        }
        addAll(remaining.values)
    }
    return if (ordered == entries) entries else ordered
}

/**
 * 记录当前访问的指针状态，松开时由统一派生流程应用积累的最新排序。
 * @param event 包含标签和访问身份的指针状态。
 */
internal fun DefaultPaneComponent.recordListPointerInteraction(event: PaneIntent.ListPointerInteraction) {
    val current = tab(event.tabId)?.takeIf { it.tabState.browserState.visitId == event.visitId } ?: return
    if (current.tabState.browserState.pointerInteractionActive == event.active) return
    updateTab(current.id) { value -> value.withTabState { state ->
        state.copy(browserState = state.browserState.copy(pointerInteractionActive = event.active))
    } }
}
