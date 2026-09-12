package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.SearchPanelState
import com.oruke.onyx.app.component.SearchStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.shared.usecase.FileSearchRequest
import com.oruke.onyx.shared.usecase.FileSearchUseCase
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 一个搜索会话独有的运行控制器，负责多根扫描与过期事件隔离。 */
internal class RootSearchRun(
    /** 来源文件窗口的协程作用域。 */
    private val scope: CoroutineScope,
    /** 无会话状态的统一搜索用例。 */
    private val useCase: FileSearchUseCase,
    /** 当前独立搜索会话状态。 */
    private val state: MutableStateFlow<SearchPanelState>,
    /** 全部根成功后记录查询的回调。 */
    private val onCompleted: (String) -> Unit,
) {
    /** 当前会话唯一的活动任务。 */
    private var job: Job? = null
    /** 失效后拒绝接收任何迟到事件的运行标识。 */
    private var runId: String? = null

    /**
     * 取消旧运行后并行扫描已授权根；空查询保持空闲，无授权根则显示明确错误。
     *
     * @param roots 已授权且去重的搜索根。
     * @param query 包含结构化条件的完整表达式。
     * @param historyQuery 成功完成后记录的用户原始查询。
     */
    fun start(roots: List<String>, query: String, historyQuery: String) {
        // 先使旧事件失效，再创建本次查询的独立结果集。
        cancel()
        state.update { current ->
            current.copy(status = SearchStatus.IDLE, results = emptyList(), selectedIndex = 0,
                scannedEntryCount = 0, limitReached = false, error = null)
        }
        if (query.isBlank()) return
        if (roots.isEmpty()) {
            state.update { current -> current.copy(status = SearchStatus.FAILED,
                error = I18nMessage(MessageKey.MSG_SEARCH_NO_ELIGIBLE_ROOTS)) }
            return
        }
        val id = UUID.randomUUID().toString()
        runId = id
        state.update { current -> current.copy(status = SearchStatus.RUNNING) }
        val aggregator = RootSearchAggregator(roots) { snapshot ->
            if (runId == id) {
                // 始终在最新会话上合并，不能让启动快照覆盖隐藏、筛选展开或用户选择。
                state.update { current -> snapshot.mergeInto(current) }
                if (snapshot.status == SearchStatus.COMPLETED) onCompleted(historyQuery)
            }
        }
        // 延迟启动确保登记任务后才可能同步完成，避免留下已经结束的活动任务引用。
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                coroutineScope {
                    roots.forEach { root ->
                        launch {
                            useCase.search(FileSearchRequest(rootLocation = root, query = query))
                                .catch { failure -> if (runId == id) aggregator.onFailure(root, failure) }
                                .collect { event -> if (runId == id) aggregator.onEvent(root, event) }
                        }
                    }
                }
            } finally {
                if (runId == id) job = null
            }
        }
        job?.start()
    }

    /** 使旧运行失效并停止其协程，已发布结果与窗口交互状态保持不变。 */
    fun cancel() {
        val wasRunning = job != null
        runId = null
        job?.cancel()
        job = null
        if (wasRunning) state.update { current -> current.copy(status = SearchStatus.CANCELLED) }
    }
}
