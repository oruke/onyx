package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.PaneComponent
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.app.component.SearchFilters
import com.oruke.onyx.app.component.SearchPanelMode
import com.oruke.onyx.app.component.SearchPanelState
import com.oruke.onyx.app.component.SearchStatus
import com.oruke.onyx.app.component.createTab
import com.oruke.onyx.app.component.openEntry
import com.oruke.onyx.app.component.openDirectoryAndSelect
import com.oruke.onyx.app.component.toSearchErrorMessage
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.SearchScope
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.shared.usecase.FileCollectionUseCase
import com.oruke.onyx.shared.usecase.FileSearchEvent
import com.oruke.onyx.shared.usecase.FileSearchRequest
import com.oruke.onyx.shared.usecase.FileSearchUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 搜索面板业务委托 — 负责搜索面板的显示/隐藏、查询执行、结果处理。
 *
 * 浮层（Quick Open）与底部抽屉共享同一 [SearchPanelState] 与搜索任务，仅通过
 * [SearchPanelState.mode] 区分显示形态。搜索范围（scope）、结构化过滤器、搜索历史
 * 与多根并行归并均在此层完成。
 */
internal class RootSearchDelegate(
    private val scope: CoroutineScope,
    private val fileSearchUseCase: FileSearchUseCase,
    private val fileCollectionUseCase: FileCollectionUseCase,
    private val paneState: (PaneId) -> PaneState,
    private val activatePane: (PaneId) -> Unit,
    private val paneComponent: (PaneId) -> PaneComponent,
    private val getActivePane: () -> PaneId,
    private val settings: () -> OnyxSettings,
    private val filesystemRoots: () -> List<String>,
    private val onRecordSearchHistory: (String) -> Unit,
) {
    private val _searchState = MutableStateFlow(
        SearchPanelState(rootLocation = ""),
    )
    val searchState: StateFlow<SearchPanelState> = _searchState.asStateFlow()

    private var searchJob: Job? = null
    private var searchRunId: String? = null

    /** 打开完整搜索抽屉。 */
    fun showSearchPanel() {
        show(SearchPanelMode.SEARCH_PANEL)
    }

    /** 打开 Quick Open 浮层。 */
    fun showQuickOpen() {
        show(SearchPanelMode.QUICK_OPEN)
    }

    /**
     * 以指定形态显示搜索界面，并同步搜索来源面板与根位置。
     *
     * @param mode 显示形态。
     */
    private fun show(mode: SearchPanelMode) {
        val paneId = getActivePane()
        val location = paneState(paneId).location
        _searchState.value = _searchState.value.copy(
            visible = true,
            mode = mode,
            paneId = paneId,
            rootLocation = location,
            status = if (_searchState.value.status == SearchStatus.RUNNING) {
                SearchStatus.RUNNING
            } else {
                SearchStatus.IDLE
            },
            error = null,
        )
    }

    /**
     * 关闭搜索界面（浮层与抽屉共用），并使当前搜索运行失效。
     *
     * 即使界面已经显示为失败，其他搜索根也可能仍在运行；必须统一取消并清空运行标识，
     * 否则迟到的流事件会用搜索开始时的可见状态重新打开已关闭的界面。
     */
    fun closeSearchPanel() {
        cancelSearch()
        _searchState.value = _searchState.value.copy(visible = false)
    }

    fun updateSearchQuery(query: String) {
        resetForChange { current ->
            if (query != current.query && current.status == SearchStatus.RUNNING) cancelSearch()
            current.copy(query = query)
        }
    }

    /** @param newScope 新的搜索根范围。 */
    fun updateSearchScope(newScope: SearchScope) {
        resetForChange { current ->
            if (newScope != current.scope && current.status == SearchStatus.RUNNING) cancelSearch()
            current.copy(scope = newScope)
        }
    }

    /** @param filters 新的结构化搜索过滤器。 */
    fun updateSearchFilters(filters: SearchFilters) {
        resetForChange { current ->
            if (filters != current.filters && current.status == SearchStatus.RUNNING) cancelSearch()
            current.copy(filters = filters)
        }
    }

    /**
     * 在保留指定变更的同时把搜索重置到空闲态。
     *
     * @param transform 基于当前状态产出带变更的新状态。
     */
    private fun resetForChange(transform: (SearchPanelState) -> SearchPanelState) {
        val next = transform(_searchState.value)
        _searchState.value = next.copy(
            status = SearchStatus.IDLE,
            results = emptyList(),
            scannedEntryCount = 0,
            limitReached = false,
            error = null,
        )
    }

    /**
     * 按当前查询、范围和过滤器启动一次多根并行搜索。
     *
     * @return 无返回值。
     */
    fun executeSearch() {
        val current = _searchState.value
        val query = current.query.trim()
        val paneId = current.paneId
        val baseRoot = current.rootLocation.ifBlank { paneState(paneId).location }
        searchJob?.cancel()
        searchJob = null

        val builtQuery = current.filters.toCriteriaQuery(query)
        if (builtQuery.isBlank()) {
            searchRunId = null
            _searchState.value = current.copy(
                visible = true,
                rootLocation = baseRoot,
                status = SearchStatus.IDLE,
                results = emptyList(),
                scannedEntryCount = 0,
                limitReached = false,
                error = null,
            )
            return
        }

        val roots = buildSearchRoots(
            scope = current.scope,
            baseRoot = baseRoot,
            favorites = settings().favoriteLocations,
            allRoots = filesystemRoots(),
        )
        if (roots.isEmpty()) {
            searchRunId = null
            _searchState.value = current.copy(
                visible = true,
                rootLocation = baseRoot,
                status = SearchStatus.IDLE,
                results = emptyList(),
                scannedEntryCount = 0,
                limitReached = false,
                error = null,
            )
            return
        }

        val runId = UUID.randomUUID().toString()
        searchRunId = runId
        _searchState.value = current.copy(
            visible = true,
            rootLocation = baseRoot,
            status = SearchStatus.RUNNING,
            results = emptyList(),
            scannedEntryCount = 0,
            limitReached = false,
            error = null,
        )

        val aggregator = RootSearchAggregator(
            roots = roots,
            base = _searchState.value,
            onCompleted = { onRecordSearchHistory(query) },
            onPublish = { merged ->
                if (searchRunId == runId) _searchState.value = merged
            },
        )

        val job = scope.launch {
            try {
                coroutineScope {
                    roots.forEach { root ->
                        launch {
                            collectRoot(runId, root, builtQuery, aggregator)
                        }
                    }
                }
            } finally {
                if (searchRunId == runId) searchJob = null
            }
        }
        searchJob = job
        job.start()
    }

    /**
     * 收集单个搜索根的流事件并交给聚合器归并。
     *
     * @param runId 本次搜索运行标识。
     * @param root 当前根位置。
     * @param builtQuery 转换后的语法查询。
     * @param aggregator 多根结果聚合器。
     */
    private suspend fun collectRoot(
        runId: String,
        root: String,
        builtQuery: String,
        aggregator: RootSearchAggregator,
    ) {
        fileSearchUseCase.search(
            FileSearchRequest(
                rootLocation = root,
                query = builtQuery,
            )
        ).catch { failure ->
            // Flow 的 catch 不处理协程取消，因此取消会继续沿父任务传播。
            if (searchRunId == runId) aggregator.onRootFailure(failure)
        }.collect { event ->
            if (searchRunId == runId) aggregator.onEvent(root, event)
        }
    }

    /**
     * 取消当前搜索运行并阻止其后续事件回写状态。
     *
     * 即使面板状态已被部分失败更新为 [SearchStatus.FAILED]，底层多根协程仍可能存活，
     * 因而不能仅依据展示状态决定是否取消。
     */
    fun cancelSearch() {
        val wasRunning = _searchState.value.status == SearchStatus.RUNNING
        searchRunId = null
        searchJob?.cancel()
        searchJob = null
        if (wasRunning) {
            _searchState.value = _searchState.value.copy(
                status = SearchStatus.CANCELLED,
                error = null,
            )
        }
    }

    /**
     * 在搜索来源面板打开指定条目。
     *
     * @param entry 待打开的搜索结果条目。
     */
    fun openSearchResult(entry: VFile) {
        val currentSearch = _searchState.value
        val paneId = currentSearch.paneId
        activatePane(paneId)
        paneComponent(paneId).openEntry(entry)
        dismissQuickOpenAfterNavigation(currentSearch)
    }

    /**
     * 在搜索来源面板打开条目所在文件夹并选中该条目。
     *
     * @param entry 待定位的搜索结果条目。
     */
    fun openSearchResultInFolder(entry: VFile) {
        val parent = entry.parentLocation ?: return
        val currentSearch = _searchState.value
        val paneId = currentSearch.paneId
        activatePane(paneId)
        paneComponent(paneId).openDirectoryAndSelect(parent, entry.name)
        dismissQuickOpenAfterNavigation(currentSearch)
    }

    /**
     * 在 Quick Open 成功发起导航后关闭浮层。
     *
     * 完整搜索抽屉应保留，以便用户继续查看结果；Quick Open 则是一次性选择器，
     * 若不关闭会持续遮挡已打开的条目或已定位的目录。
     *
     * @param currentSearch 本次导航开始前的搜索状态快照。
     */
    private fun dismissQuickOpenAfterNavigation(currentSearch: SearchPanelState) {
        if (currentSearch.mode == SearchPanelMode.QUICK_OPEN) {
            closeSearchPanel()
        }
    }

    /**
     * 将当前搜索结果保存为虚拟集合并在搜索来源面板打开。
     */
    fun openSearchResultsAsCollection() {
        val currentSearch = _searchState.value
        if (currentSearch.results.isEmpty()) {
            return
        }
        val collection = fileCollectionUseCase.saveSearchResults(
            id = searchRunId ?: UUID.randomUUID().toString(),
            name = currentSearch.query,
            entries = currentSearch.results,
        )
        activatePane(currentSearch.paneId)
        paneComponent(currentSearch.paneId).createTab(collection.location)
    }

    /**
     * 多根搜索聚合器：并行归并各根结果并统一去重。
     *
     * @param roots 按顺序排列的搜索根；保证合并结果按根顺序稳定。
     * @param base 本次搜索开始时的面板状态快照。
     * @param onPublish 归并后发布状态的回调。
     */
    private class RootSearchAggregator(
        roots: List<String>,
        private val base: SearchPanelState,
        private val onCompleted: () -> Unit,
        private val onPublish: (SearchPanelState) -> Unit,
    ) {
        private val states = LinkedHashMap<String, RootSearchState>().apply {
            roots.forEach { root -> put(root, RootSearchState()) }
        }
        private var completedCount = 0
        private var failedCount = 0
        private var firstFailure: I18nMessage? = null
        private var completionRecorded = false

        /** @param event 来自指定搜索根的搜索事件。 */
        fun onEvent(root: String, event: FileSearchEvent) {
            when (event) {
                is FileSearchEvent.Progress -> {
                    states.getValue(root).scanned = event.scannedEntryCount
                }

                is FileSearchEvent.Results -> {
                    val state = states.getValue(root)
                    state.entries = event.entries
                    state.scanned = event.scannedEntryCount
                    state.limitReached = event.limitReached
                }

                is FileSearchEvent.Completed -> {
                    val state = states.getValue(root)
                    state.scanned = event.scannedEntryCount
                    state.limitReached = event.limitReached
                    if (!state.completed) {
                        state.completed = true
                        completedCount++
                    }
                }

                is FileSearchEvent.Failed -> {
                    failedCount++
                    if (firstFailure == null) firstFailure = event.failure.toSearchErrorMessage()
                }
            }
            publish()
        }

        /** @param failure 搜索根在流内异常抛出的失败。 */
        fun onRootFailure(failure: Throwable) {
            failedCount++
            if (firstFailure == null) firstFailure = failure.toSearchErrorMessage()
            publish()
        }

        private fun publish() {
            val merged = mergeSearchResults(states.map { (root, state) -> root to state.entries })
            val scanned = states.values.sumOf { state -> state.scanned }
            val limitReached = states.values.any { state -> state.limitReached }
            val allDone = completedCount + failedCount == states.size
            val status = when {
                firstFailure != null -> SearchStatus.FAILED
                allDone -> SearchStatus.COMPLETED
                else -> SearchStatus.RUNNING
            }
            if (status == SearchStatus.COMPLETED && !completionRecorded) {
                completionRecorded = true
                onCompleted()
            }
            onPublish(
                base.copy(
                    results = merged,
                    scannedEntryCount = scanned,
                    limitReached = limitReached,
                    status = status,
                    error = firstFailure,
                )
            )
        }
    }

    /** 单个搜索根的实时扫描状态。 */
    private data class RootSearchState(
        var entries: List<VFile> = emptyList(),
        var scanned: Int = 0,
        var limitReached: Boolean = false,
        var completed: Boolean = false,
    )
}
