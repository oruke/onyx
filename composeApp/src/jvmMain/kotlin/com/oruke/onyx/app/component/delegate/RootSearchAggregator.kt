package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.SearchPanelState
import com.oruke.onyx.app.component.SearchStatus
import com.oruke.onyx.app.component.toSearchErrorMessage
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.shared.usecase.FileSearchEvent

/**
 * 仅发布执行快照的多根搜索聚合器，由所属组件的主线程顺序调用。
 *
 * @param roots 按稳定优先顺序排列的根。
 * @param onPublish 聚合结果回调。
 */
internal class RootSearchAggregator(roots: List<String>, private val onPublish: (SearchRunSnapshot) -> Unit) {
    /** 按根顺序保存的当前执行快照。 */
    private val states = roots.associateWithTo(linkedMapOf()) { RootSearchState() }

    /**
     * 合并单根事件并发布当前聚合结果，局部失败不会提前终止其他根。
     *
     * @param root 事件所属根。
     * @param event 统一搜索事件。
     */
    fun onEvent(root: String, event: FileSearchEvent) {
        val current = states.getValue(root)
        when (event) {
            is FileSearchEvent.Progress -> current.scanned = event.scannedEntryCount
            is FileSearchEvent.Results -> {
                current.entries = event.entries
                current.scanned = event.scannedEntryCount
                current.limitReached = event.limitReached
            }
            is FileSearchEvent.Completed -> {
                current.scanned = event.scannedEntryCount
                current.limitReached = event.limitReached
                current.completed = true
            }
            is FileSearchEvent.Failed -> {
                current.scanned = event.scannedEntryCount
                current.error = event.failure.toSearchErrorMessage()
                current.completed = true
            }
        }
        publish()
    }

    /**
     * 将扫描流异常记录到所属根，其他根仍可继续运行。
     *
     * @param root 抛出异常的根。
     * @param failure 目录扫描流内异常。
     */
    fun onFailure(root: String, failure: Throwable) {
        states.getValue(root).apply {
            error = failure.toSearchErrorMessage()
            completed = true
        }
        publish()
    }

    /** 聚合根执行快照；局部失败时仍让其他根保持可停止的运行状态。 */
    private fun publish() {
        val error = states.values.firstNotNullOfOrNull { it.error }
        val allDone = states.values.all { it.completed }
        onPublish(SearchRunSnapshot(
            results = mergeSearchResults(states.map { (root, state) -> root to state.entries }),
            scanned = states.values.sumOf { it.scanned },
            limitReached = states.values.any { it.limitReached },
            status = when {
                !allDone -> SearchStatus.RUNNING
                error != null -> SearchStatus.FAILED
                else -> SearchStatus.COMPLETED
            },
            error = error,
        ))
    }
}

/** 单个根的扫描状态，与界面生命周期无关。 */
private data class RootSearchState(
    /** 当前根已匹配条目。 */
    var entries: List<VFile> = emptyList(),
    /** 当前根已扫描数量。 */
    var scanned: Int = 0,
    /** 当前根是否达到结果上限。 */
    var limitReached: Boolean = false,
    /** 当前根是否结束。 */
    var completed: Boolean = false,
    /** 当前根失败详情。 */
    var error: I18nMessage? = null,
)

/** 仅包含运行字段的多根执行快照。 */
internal data class SearchRunSnapshot(
    /** 按根顺序去重后的结果。 */
    val results: List<VFile>,
    /** 累计扫描数量。 */
    val scanned: Int,
    /** 任意根是否达到上限。 */
    val limitReached: Boolean,
    /** 整体运行状态。 */
    val status: SearchStatus,
    /** 首个失败根错误。 */
    val error: I18nMessage?,
)

/**
 * 合并执行字段并按条目标识维持选中项；条目消失时把索引收敛到新结果范围，空列表归零。
 *
 * @param current 最新界面状态。
 * @return 保留窗口交互状态且选中索引有效的新状态。
 */
internal fun SearchRunSnapshot.mergeInto(current: SearchPanelState): SearchPanelState {
    val selectedId = current.results.getOrNull(current.selectedIndex)?.id
    val retainedIndex = results.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }
        ?: current.selectedIndex.coerceIn(0, (results.size - 1).coerceAtLeast(0))
    return current.copy(results = results, selectedIndex = retainedIndex, scannedEntryCount = scanned,
        limitReached = limitReached, status = status, error = error)
}
