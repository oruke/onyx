package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.PaneBrowserState
import com.oruke.onyx.app.component.PaneChromeState
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.PaneTabState
import com.oruke.onyx.app.component.PaneViewportAnchor
import com.oruke.onyx.app.component.PaneVisitSnapshot
import com.oruke.onyx.app.component.navigateBackState
import com.oruke.onyx.app.component.navigateForwardState
import com.oruke.onyx.app.component.navigateToState
import com.oruke.onyx.shared.filesystem.DirectoryContentState
import com.oruke.onyx.app.component.PaneDirectoryReducer
import com.oruke.onyx.core.model.ViewMode

/**
 * 保存访问历史和高频视口数据，组件状态不随每个滚动像素重新派生文件列表。
 * @param cachedContent 获取当前目录的共享快照，缓存缺失时返回 null。
 */
internal class PaneNavigationController(
    private val cachedContent: (String) -> DirectoryContentState?,
) {
    /** 后续新访问使用的递增编号。 */
    private var nextVisitId = 1L
    /** 每个标签当前视图的最近视口，不保存目录内容。 */
    private val viewports = mutableMapOf<String, MutableMap<ViewMode, PaneViewportAnchor>>()
    /** 正在用户滚动的标签，保证每次滚动只递增一次交互版本。 */
    private val scrolling = mutableSetOf<String>()

    /**
     * 接收当前访问的视口报告，返回是否开始了一次用户滚动。
     * @param tab 当前标签。
     * @param event 带访问身份的视口事件。
     * @return 用户刚开始滚动时为 true，过期事件和连续报告为 false。
     */
    fun recordViewport(tab: PaneTabState, event: PaneIntent.ViewportChanged): Boolean {
        if (tab.tabState.browserState.visitId != event.visitId) return false
        viewports.getOrPut(tab.id) { tab.tabState.browserState.viewports.toMutableMap() }[event.mode] = event.anchor
        return if (event.userScrolling) scrolling.add(tab.id) else {
            scrolling.remove(tab.id)
            false
        }
    }

    /**
     * 将高频视口合并进可转移的标签状态。
     * @param tab 当前标签。
     * @return 包含最新视口的标签。
     */
    fun capture(tab: PaneTabState): PaneTabState = tab.withTabState { current ->
        current.copy(browserState = current.browserState.copy(
            viewports = viewports[tab.id]?.toMap() ?: current.browserState.viewports,
        ))
    }

    /**
     * 创建新目录访问，记录离开状态并清空前进历史。
     * @param tab 离开的标签。
     * @param chrome 当前展开状态。
     * @param location 目标目录。
     * @param title 目标标题。
     * @param recordHistory 是否记录当前访问。
     * @return 可以立即呈现缓存的新访问。
     */
    fun open(tab: PaneTabState, chrome: PaneChromeState, location: String, title: String,
             recordHistory: Boolean): PaneTabState {
        val snapshot = snapshot(tab, chrome)
        val recent = (tab.backVisits + tab.forwardVisits).lastOrNull { it.state.location == location }
        val base = tab.navigateToState(location, title, recordHistory).copy(
            backStack = (if (recordHistory) tab.backStack + tab.location else tab.backStack).takeLast(HISTORY_LIMIT),
            backVisits = (if (recordHistory) tab.backVisits + snapshot else tab.backVisits).takeLast(HISTORY_LIMIT),
            forwardVisits = if (recordHistory) emptyList() else tab.forwardVisits,
        ).withTabState { state ->
            state.copy(browserState = PaneBrowserState(visitId = nextVisitId++,
                viewports = recent?.state?.browserState?.viewports.orEmpty()))
        }
        return withCachedContent(base)
    }

    /**
     * 恢复上一访问，路径历史由原有统一转换维持。
     * @param tab 当前标签。
     * @param chrome 当前展开状态。
     * @param title 目标目录标题。
     * @return 恢复后的标签及目标展开快照。
     */
    fun back(tab: PaneTabState, chrome: PaneChromeState, title: String): Pair<PaneTabState, Map<String, Int>> {
        val destination = tab.backVisits.lastOrNull()?.takeIf { it.state.location == tab.backStack.last() }
        val base = tab.navigateBackState(title).copy(backVisits = tab.backVisits.dropLast(1),
            forwardVisits = (tab.forwardVisits + snapshot(tab, chrome)).takeLast(HISTORY_LIMIT))
        return restore(base, destination) to destination?.expansions.orEmpty()
    }

    /**
     * 恢复下一访问，不将相同路径的不同访问合并。
     * @param tab 当前标签。
     * @param chrome 当前展开状态。
     * @param title 目标目录标题。
     * @return 恢复后的标签及目标展开快照。
     */
    fun forward(tab: PaneTabState, chrome: PaneChromeState, title: String): Pair<PaneTabState, Map<String, Int>> {
        val destination = tab.forwardVisits.lastOrNull()?.takeIf { it.state.location == tab.forwardStack.last() }
        val base = tab.navigateForwardState(title).copy(forwardVisits = tab.forwardVisits.dropLast(1),
            backVisits = (tab.backVisits + snapshot(tab, chrome)).takeLast(HISTORY_LIMIT))
        return restore(base, destination) to destination?.expansions.orEmpty()
    }

    /**
     * 保存离开访问的轻量状态，内容不在每条历史中重复存储。
     * @param tab 当前标签。
     * @param chrome 当前展开状态。
     * @return 不含目录列表的访问快照。
     */
    fun snapshot(tab: PaneTabState, chrome: PaneChromeState): PaneVisitSnapshot = PaneVisitSnapshot(
        state = capture(tab).tabState.copy(entriesState = PaneEntriesState.Idle, inlineEditState = null,
            pendingScrollToEntryId = null, refreshing = false),
        expansions = chrome.inlineExpandedEntries.filterKeys { it in chrome.inlineExpandedLocations }
            .mapValues { it.value.depth },
    )

    /**
     * 激活访问时重置视口报告归属，旧视口事件不能污染新访问。
     * @param tab 进入的标签。
     */
    fun activate(tab: PaneTabState) {
        nextVisitId = maxOf(nextVisitId, tab.tabState.browserState.visitId + 1)
        viewports[tab.id] = tab.tabState.browserState.viewports.toMutableMap()
        scrolling.remove(tab.id)
    }

    /**
     * 关闭标签时释放最近视口。
     * @param tabId 已关闭标签。
     */
    fun forget(tabId: String) {
        viewports.remove(tabId)
        scrolling.remove(tabId)
    }

    /**
     * 恢复历史状态，列配置继续使用当前标签配置。
     * @param base 已完成历史栈转换的标签。
     * @param destination 可用的历史快照。
     * @return 保留历史选择和视口的标签。
     */
    private fun restore(base: PaneTabState, destination: PaneVisitSnapshot?): PaneTabState {
        val state = destination?.state ?: base.tabState.copy(browserState = PaneBrowserState(nextVisitId++))
        return withCachedContent(base.copy(tabState = state.copy(canGoBack = base.canGoBack,
            canGoForward = base.canGoForward, detailsColumns = base.detailsColumns,
            detailsColumnWeights = base.detailsColumnWeights, hiddenColumns = base.hiddenColumns,
            galleryItemSizeDp = base.galleryItemSizeDp)))
    }

    /**
     * 用共享快照立即填充访问，没有缓存时保留待加载状态与历史选择。
     * @param tab 待进入标签。
     * @return 含正确首次加载或已就绪状态的标签。
     */
    private fun withCachedContent(tab: PaneTabState): PaneTabState {
        val content = cachedContent(tab.location)
        return if (content?.entries != null) PaneDirectoryReducer.applyContent(tab, content, null)
        else tab.copy(allEntries = emptyList()).withTabState { it.copy(entriesState = PaneEntriesState.Loading) }
    }

    private companion object {
        /** 每个标签最多保留的运行时访问数量。 */
        const val HISTORY_LIMIT = 50
    }
}
