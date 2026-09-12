package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.PaneComponent
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.app.component.SearchFilters
import com.oruke.onyx.app.component.SearchPanelMode
import com.oruke.onyx.app.component.SearchPanelState
import com.oruke.onyx.app.component.SearchStatus
import com.oruke.onyx.app.component.createTab
import com.oruke.onyx.app.component.openDirectoryAndSelect
import com.oruke.onyx.app.component.openEntry
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.SearchScope
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.shared.usecase.FileCollectionUseCase
import com.oruke.onyx.shared.usecase.FileSearchUseCase
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 一个独立搜索会话的交互控制器；完整搜索和快速打开各自创建实例。 */
internal class RootSearchDelegate(
    /** 当前文件管理器窗口的协程作用域。 */
    scope: CoroutineScope,
    /** 统一文件搜索用例。 */
    fileSearchUseCase: FileSearchUseCase,
    /** 搜索结果固定快照集合用例。 */
    private val fileCollectionUseCase: FileCollectionUseCase,
    /** 查询来源面板当前状态。 */
    private val paneState: (PaneId) -> PaneState,
    /** 激活搜索来源面板。 */
    private val activatePane: (PaneId) -> Unit,
    /** 获取统一面板导航入口。 */
    private val paneComponent: (PaneId) -> PaneComponent,
    /** 查询当前活动面板角色。 */
    private val getActivePane: () -> PaneId,
    /** 当前共享用户设置。 */
    private val settings: () -> OnyxSettings,
    /** 宿主文件系统根位置。 */
    private val filesystemRoots: () -> List<String>,
    /** 成功完成搜索后记录查询历史。 */
    onRecordSearchHistory: (String) -> Unit,
    /** 当前会话固定的展示形态。 */
    private val mode: SearchPanelMode = SearchPanelMode.SEARCH_PANEL,
) {
    /** 当前会话的可变状态，后台结果只合并执行字段。 */
    private val mutableSearchState = MutableStateFlow(SearchPanelState(mode = mode))
    /** 对外只读的独立搜索状态。 */
    val searchState: StateFlow<SearchPanelState> = mutableSearchState.asStateFlow()
    /** 当前会话独有的多根执行器与取消标识。 */
    private val searchRun = RootSearchRun(scope, fileSearchUseCase, mutableSearchState, onRecordSearchHistory)

    /** 显示完整搜索；已有会话保持原始来源目录与查询。 */
    fun showSearchPanel() {
        if (mutableSearchState.value.rootLocation.isBlank()) useCurrentDirectory()
        mutableSearchState.update { current ->
            current.copy(visible = true, activationRequest = current.activationRequest + 1L)
        }
    }

    /** 显示一次性快速打开，以当前活动目录为来源，不影响其他搜索实例。 */
    fun showQuickOpen() {
        useCurrentDirectory()
        // 快速打开只检索用户已打开的当前目录；进入该目录已明确选择远程来源，不要求不存在的范围开关。
        mutableSearchState.update { current -> current.copy(includeRemoteLocations = true) }
        showSearchPanel()
    }

    /** 隐藏当前搜索；完整窗口继续后台工作，快速打开取消自身的搜索任务。 */
    fun closeSearchPanel() {
        if (mode == SearchPanelMode.QUICK_OPEN) cancelSearch()
        mutableSearchState.update { current -> current.copy(visible = false) }
    }

    /** 显式采用当前面板目录，保留查询并清理旧运行结果。 */
    fun useCurrentDirectory() {
        val paneId = getActivePane()
        val root = paneState(paneId).location
        resetForChange { current ->
            current.copy(paneId = paneId, rootLocation = root, scope = SearchScope.CURRENT_DIRECTORY)
        }
    }

    /**
     * 更新查询；实际变化时取消当前会话旧运行并清理结果。
     *
     * @param query 新查询文本。
     */
    fun updateSearchQuery(query: String) {
        if (query != mutableSearchState.value.query) resetForChange { current -> current.copy(query = query) }
    }

    /**
     * 更新搜索范围，实际变化时使旧运行结果失效。
     *
     * @param newScope 用户明确选择的搜索范围。
     */
    fun updateSearchScope(newScope: SearchScope) {
        if (newScope != mutableSearchState.value.scope) resetForChange { current -> current.copy(scope = newScope) }
    }

    /**
     * 更新结构化条件并清理旧查询结果。
     *
     * @param filters 新结构化筛选条件。
     */
    fun updateSearchFilters(filters: SearchFilters) {
        if (filters != mutableSearchState.value.filters) resetForChange { current -> current.copy(filters = filters) }
    }

    /**
     * 更新当前会话的网络扫描授权，授权变化后重新执行才会读取新范围。
     *
     * @param include 用户是否明确允许当前会话读取网络位置。
     */
    fun setIncludeRemoteLocations(include: Boolean) {
        if (include != mutableSearchState.value.includeRemoteLocations) {
            resetForChange { current -> current.copy(includeRemoteLocations = include) }
        }
    }

    /**
     * 更新高级筛选区域可见性，不改变已运行的查询。
     *
     * @param expanded 高级筛选区域是否展开。
     */
    fun setAdvancedFiltersExpanded(expanded: Boolean) {
        mutableSearchState.update { current -> current.copy(advancedFiltersExpanded = expanded) }
    }

    /**
     * 更新选中条目，越界索引收敛到当前结果范围。
     *
     * @param index 要选中的结果索引。
     */
    fun setSelectedIndex(index: Int) {
        mutableSearchState.update { current ->
            current.copy(selectedIndex = index.coerceIn(0, (current.results.size - 1).coerceAtLeast(0)))
        }
    }

    /** 按当前查询、显式范围与网络位置选项启动本会话的独立搜索。 */
    fun executeSearch() {
        val current = mutableSearchState.value
        val query = current.filters.toCriteriaQuery(current.query.trim())
        val roots = buildSearchRoots(
            scope = current.scope, baseRoot = current.rootLocation,
            favorites = settings().favoriteLocations, allRoots = filesystemRoots(),
            includeRemoteLocations = current.includeRemoteLocations,
        )
        searchRun.start(roots, query, current.query.trim())
    }

    /** 停止本会话的搜索并保留已收到的部分结果。 */
    fun cancelSearch() = searchRun.cancel()

    /**
     * 在会话绑定的来源面板打开条目，快速打开同时结束自身会话。
     *
     * @param entry 要打开的搜索结果。
     */
    fun openSearchResult(entry: VFile) {
        val paneId = mutableSearchState.value.paneId
        activatePane(paneId)
        paneComponent(paneId).openEntry(entry)
        if (mode == SearchPanelMode.QUICK_OPEN) closeSearchPanel()
    }

    /**
     * 在会话绑定的来源面板定位条目；没有父目录的条目不执行导航。
     *
     * @param entry 要定位并选中的结果。
     */
    fun openSearchResultInFolder(entry: VFile) {
        val parent = entry.parentLocation ?: return
        val paneId = mutableSearchState.value.paneId
        activatePane(paneId)
        paneComponent(paneId).openDirectoryAndSelect(parent, entry.name)
        if (mode == SearchPanelMode.QUICK_OPEN) closeSearchPanel()
    }

    /** 把当前结果复制为新集合快照，后续搜索发布或再次固定不会修改已打开集合。 */
    fun openSearchResultsAsCollection() {
        val current = mutableSearchState.value
        if (current.results.isEmpty()) return
        val collection = fileCollectionUseCase.saveSearchResults(
            id = UUID.randomUUID().toString(), name = current.query, entries = current.results.toList(),
        )
        activatePane(current.paneId)
        paneComponent(current.paneId).createTab(collection.location)
    }

    /**
     * 取消旧运行后应用条件变更，清理执行字段并保留窗口交互状态。
     *
     * @param transform 当前条件的最小变更。
     */
    private fun resetForChange(transform: (SearchPanelState) -> SearchPanelState) {
        searchRun.cancel()
        mutableSearchState.update { current ->
            transform(current).copy(status = SearchStatus.IDLE, results = emptyList(), selectedIndex = 0,
                scannedEntryCount = 0, limitReached = false, error = null)
        }
    }
}
