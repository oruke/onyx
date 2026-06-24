package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.PaneComponent
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.app.component.SearchPanelState
import com.oruke.onyx.app.component.SearchStatus
import com.oruke.onyx.app.component.createTab
import com.oruke.onyx.app.component.openEntry
import com.oruke.onyx.app.component.toSearchErrorMessage
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.shared.usecase.FileCollectionUseCase
import com.oruke.onyx.shared.usecase.FileSearchEvent
import com.oruke.onyx.shared.usecase.FileSearchRequest
import com.oruke.onyx.shared.usecase.FileSearchUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 搜索面板业务委托 — 负责搜索面板的显示/隐藏、查询执行、结果处理。
 *
 * 从 DefaultRootComponent 剥离的纯业务逻辑，自持 SearchPanelState。
 */
internal class RootSearchDelegate(
    private val scope: CoroutineScope,
    private val fileSearchUseCase: FileSearchUseCase,
    private val fileCollectionUseCase: FileCollectionUseCase,
    private val paneState: (PaneId) -> PaneState,
    private val activatePane: (PaneId) -> Unit,
    private val paneComponent: (PaneId) -> PaneComponent,
    private val getActivePane: () -> PaneId,
) {
    private val _searchState = MutableStateFlow(
        SearchPanelState(rootLocation = ""),
    )
    val searchState: StateFlow<SearchPanelState> = _searchState.asStateFlow()

    private var searchJob: Job? = null
    private var searchRunId: String? = null

    fun showSearchPanel() {
        val paneId = getActivePane()
        val location = paneState(paneId).location
        _searchState.value = _searchState.value.copy(
            visible = true,
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

    fun closeSearchPanel() {
        val wasRunning = _searchState.value.status == SearchStatus.RUNNING
        if (wasRunning) {
            searchRunId = null
            searchJob?.cancel()
            searchJob = null
        }
        _searchState.value = _searchState.value.copy(
            visible = false,
            status = if (wasRunning) SearchStatus.CANCELLED else _searchState.value.status,
        )
    }

    fun updateSearchQuery(query: String) {
        if (query != _searchState.value.query && _searchState.value.status == SearchStatus.RUNNING) {
            cancelSearch()
        }
        _searchState.value = _searchState.value.copy(
            query = query,
            status = SearchStatus.IDLE,
            results = emptyList(),
            scannedEntryCount = 0,
            limitReached = false,
            error = null,
        )
    }

    fun executeSearch() {
        val current = _searchState.value
        val query = current.query.trim()
        val paneId = current.paneId
        val rootLocation = current.rootLocation.ifBlank { paneState(paneId).location }
        searchJob?.cancel()
        searchJob = null

        if (query.isBlank()) {
            searchRunId = null
            _searchState.value = current.copy(
                visible = true,
                rootLocation = rootLocation,
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
            rootLocation = rootLocation,
            status = SearchStatus.RUNNING,
            results = emptyList(),
            scannedEntryCount = 0,
            limitReached = false,
            error = null,
        )

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                fileSearchUseCase.search(
                    FileSearchRequest(
                        rootLocation = rootLocation,
                        query = query,
                    )
                ).collect { event ->
                    if (searchRunId != runId) {
                        return@collect
                    }
                    reduceSearchEvent(event)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                if (searchRunId == runId) {
                    _searchState.value = _searchState.value.copy(
                        status = SearchStatus.FAILED,
                        error = failure.toSearchErrorMessage(),
                    )
                }
            } finally {
                if (searchRunId == runId) {
                    searchJob = null
                }
            }
        }
        searchJob = job
        job.start()
    }

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

    fun openSearchResult(entry: VFile) {
        val paneId = _searchState.value.paneId
        activatePane(paneId)
        paneComponent(paneId).openEntry(entry)
    }

    /**
     * 将当前搜索结果保存为虚拟集合并在搜索来源面板打开。
     *
     * @return 无返回值。
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

    private fun reduceSearchEvent(event: FileSearchEvent) {
        when (event) {
            is FileSearchEvent.Progress -> {
                _searchState.value = _searchState.value.copy(
                    scannedEntryCount = event.scannedEntryCount,
                )
            }

            is FileSearchEvent.Results -> {
                _searchState.value = _searchState.value.copy(
                    status = SearchStatus.RUNNING,
                    results = event.entries,
                    scannedEntryCount = event.scannedEntryCount,
                    limitReached = event.limitReached,
                    error = null,
                )
            }

            is FileSearchEvent.Completed -> {
                _searchState.value = _searchState.value.copy(
                    status = SearchStatus.COMPLETED,
                    scannedEntryCount = event.scannedEntryCount,
                    limitReached = event.limitReached,
                    error = null,
                )
            }

            is FileSearchEvent.Failed -> {
                _searchState.value = _searchState.value.copy(
                    status = SearchStatus.FAILED,
                    scannedEntryCount = event.scannedEntryCount,
                    error = event.failure.toSearchErrorMessage(),
                )
            }
        }
    }
}
