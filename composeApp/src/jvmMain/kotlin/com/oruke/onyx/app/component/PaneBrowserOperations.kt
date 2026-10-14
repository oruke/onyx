package com.oruke.onyx.app.component

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * 保存当前访问的最新视口和展开信息，供导航、切换及转移使用。
 * @return 已捕获的活动标签。
 */
internal fun DefaultPaneComponent.captureActiveVisit(): PaneTabState? {
    val active = activeTab() ?: return null
    val captured = navigationController.capture(active)
    tabExpansions[active.id] = navigationController.snapshot(active, mutableState.value.chromeState).expansions
    updateTab(active.id) { captured }
    return captured
}

/**
 * 恢复一个访问的展开元数据，再通过共享目录订阅填充实际内容。
 * @param expansions 展开位置与深度。
 */
internal fun DefaultPaneComponent.restoreExpansions(expansions: Map<String, Int>) {
    inlineDirectoryJobs.values.forEach { it.cancel() }
    inlineDirectoryJobs.clear()
    updatePaneChrome { chrome ->
        chrome.copy(inlineExpandedLocations = expansions.keys,
            inlineExpandedEntries = expansions.mapValues { (location, depth) ->
                InlineExpandedEntry(location, depth)
            })
    }
}

/** 按当前展开集合创建和释放子目录订阅，取消后迟到结果不能修改新访问。 */
internal fun DefaultPaneComponent.synchronizeInlineDirectories() {
    val pane = mutableState.value
    val wanted = pane.inlineExpandedLocations
    inlineDirectoryJobs.keys.toList().filterNot { it in wanted }.forEach { location ->
        inlineDirectoryJobs.remove(location)?.cancel()
    }
    wanted.filterNot { it in inlineDirectoryJobs }.forEach { location ->
        val depth = pane.inlineExpandedEntries[location]?.depth ?: 1
        val tabId = pane.activeTabId
        val visitId = pane.activeTabState.browserState.visitId
        inlineDirectoryJobs[location] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val subscription = directories.subscribe(location)
            try {
                subscription.state.collect { content ->
                    val current = mutableState.value
                    if (current.activeTabId != tabId || current.activeTabState.browserState.visitId != visitId ||
                        location !in current.inlineExpandedLocations) return@collect
                    val entries = content.entries
                    if (entries != null) {
                        mutableState.value = current.withInlineExpandChildren(location, depth, entries)
                        updateTab(tabId) { it }
                    } else if (content.failure != null) {
                        mutableState.value = current.withInlineExpandFailure(location, depth)
                    }
                }
            } finally {
                subscription.close()
            }
        }
    }
}

/**
 * 记录视口，高频事件只写控制器，用户开始滚动才改变可见交互版本。
 * @param event 渲染访问上报的视口。
 */
internal fun DefaultPaneComponent.recordViewport(event: PaneIntent.ViewportChanged) {
    val current = tab(event.tabId) ?: return
    if (navigationController.recordViewport(current, event)) {
        updateTab(current.id) { value ->
            value.withTabState { state -> state.copy(pendingScrollToEntryId = null,
                browserState = state.browserState.copy(
                    interactionRevision = state.browserState.interactionRevision + 1)) }
        }
    }
}
