package com.oruke.onyx.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import com.oruke.onyx.app.component.PaneViewportAnchor
import com.oruke.onyx.app.component.PaneBrowserState
import com.oruke.onyx.core.model.ViewMode
import kotlinx.coroutines.flow.collect

/** Compose 布局报告的物理视口，转换后才进入组件状态。 */
private data class ViewportSample(
    /** 首项稳定身份，布局尚未生成时为空。 */
    val entryId: String?,
    /** 不含新建占位行的首项索引。 */
    val index: Int,
    /** 首行滚动像素偏移。 */
    val offset: Int,
    /** 水平滚动像素偏移。 */
    val horizontalOffset: Int,
    /** 是否正在滚动。 */
    val scrolling: Boolean,
    /** 当前完整可见的条目身份。 */
    val visibleIds: Set<String>,
)

/** 视口适配器的布局暂存；业务状态仍由面板访问快照持有。 */
private class ViewportBinding(
    /** 最近一次已绑定的显示顺序。 */
    var ids: List<String>,
    /** 最近一次有效布局的锚点。 */
    var anchor: PaneViewportAnchor?,
    /** 上一次完成恢复时的逻辑像素比例。 */
    var density: Float,
) {
    /** 程序恢复期间禁止把滚动误报成用户交互。 */
    var restoring = false
    /** 当前锚点已经处理的内容版本。 */
    var contentRevision = -1L

    /**
     * 将尚未消费的重命名身份链应用到当前锚点。
     * @param browser 最新的访问内容版本与身份日志。
     */
    fun applyIdentityChanges(browser: PaneBrowserState) {
        browser.identityChanges.filter { it.revision > contentRevision }.forEach { item ->
            val mapping = item.change.identityChanges
            anchor = anchor?.let { value -> value.copy(entryId = mapping[value.entryId] ?: value.entryId,
                followingIds = value.followingIds.map { mapping[it] ?: it },
                precedingIds = value.precedingIds.map { mapping[it] ?: it }) }
        }
        contentRevision = browser.contentRevision
    }
}

/**
 * 将历史锚点解析到当前显示顺序，删除首项后优先使用存活后继。
 * @param ids 当前显示顺序。
 * @return 限界后的条目索引，空列表返回 0。
 */
private fun PaneViewportAnchor?.resolveIndex(ids: List<String>): Int {
    if (this == null || ids.isEmpty()) return 0
    val candidates = listOfNotNull(entryId) + followingIds + precedingIds
    for (candidate in candidates) {
        val index = ids.indexOf(candidate)
        if (index >= 0) return index
    }
    return index.coerceIn(ids.indices)
}

/**
 * 创建与访问绑定的横向滚动状态，表头与文件行共用该状态。
 * @param model 文件视图模型。
 * @return 按逻辑偏移恢复的水平状态。
 */
@Composable
internal fun rememberPaneHorizontalScroll(model: PaneEntriesViewModel): ScrollState {
    val density = LocalDensity.current.density
    return remember(model.tabId, model.browserState.visitId) {
        ScrollState(((model.browserState.viewports[ViewMode.DETAILS]?.horizontalOffsetDp ?: 0f) * density).toInt())
    }
}

/**
 * 创建详情列表视口，并把布局变化上报为普通锚点。
 * @param model 当前访问模型。
 * @param actions 组件事件入口。
 * @param ids 包含展开子项的显示顺序。
 * @param creating 是否有新建占位行。
 * @param horizontal 表头与内容共用的横向状态。
 * @return 当前访问专属列表状态。
 */
@Composable
internal fun rememberPaneListViewport(
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    ids: List<String>,
    creating: Boolean,
    horizontal: ScrollState,
): LazyListState {
    val density = LocalDensity.current.density
    val anchor = model.browserState.viewports[ViewMode.DETAILS]
    val extra = if (creating) 1 else 0
    val state = remember(model.tabId, model.browserState.visitId) {
        LazyListState(anchor.resolveIndex(ids) + extra, ((anchor?.offsetDp ?: 0f) * density).toInt())
    }
    BindPaneViewport(model, actions, ids, density, sample = {
        val layout = state.layoutInfo
        ViewportSample(layout.visibleItemsInfo.firstOrNull { it.index >= extra }?.key as? String,
            (state.firstVisibleItemIndex - extra).coerceAtLeast(0), state.firstVisibleItemScrollOffset,
            horizontal.value, state.isScrollInProgress || horizontal.isScrollInProgress,
            layout.visibleItemsInfo.filter { it.offset >= layout.viewportStartOffset &&
                it.offset + it.size <= layout.viewportEndOffset }.mapNotNull { it.key as? String }.toSet())
    }, scroll = { index, offset, horizontalOffset ->
        // 交给下一次布局恢复，不能在密度变化尚未应用的组合阶段强制重新测量子组合。
        state.requestScrollToItem(index + extra, offset)
        horizontal.scrollTo(horizontalOffset)
        withFrameNanos { }
    })
    return state
}

/**
 * 创建画廊视口，网格列数变化时仍按条目身份定位。
 * @param model 当前访问模型。
 * @param actions 组件事件入口。
 * @param ids 画廊显示顺序。
 * @param creating 是否有新建占位格。
 * @return 当前访问专属网格状态。
 */
@Composable
internal fun rememberPaneGridViewport(
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    ids: List<String>,
    creating: Boolean,
): LazyGridState {
    val density = LocalDensity.current.density
    val anchor = model.browserState.viewports[ViewMode.GALLERY]
    val extra = if (creating) 1 else 0
    val state = remember(model.tabId, model.browserState.visitId) {
        LazyGridState(anchor.resolveIndex(ids) + extra, ((anchor?.offsetDp ?: 0f) * density).toInt())
    }
    BindPaneViewport(model, actions, ids, density, sample = {
        val layout = state.layoutInfo
        ViewportSample(layout.visibleItemsInfo.firstOrNull { it.index >= extra }?.key as? String,
            (state.firstVisibleItemIndex - extra).coerceAtLeast(0), state.firstVisibleItemScrollOffset, 0,
            state.isScrollInProgress, layout.visibleItemsInfo.filter { it.offset.y >= layout.viewportStartOffset &&
                it.offset.y + it.size.height <= layout.viewportEndOffset }
                .mapNotNull { it.key as? String }.toSet())
    }, scroll = { index, offset, _ ->
        state.requestScrollToItem(index + extra, offset)
        withFrameNanos { }
    })
    return state
}

/**
 * 合并 Compose 布局和组件锚点，内容更新只校正视口，显式定位只在条目不可见时滚动。
 * @param model 当前访问模型。
 * @param actions 组件事件入口。
 * @param ids 当前显示顺序。
 * @param density 当前逻辑到物理像素比例。
 * @param sample 读取当前布局，调用方不能执行 I/O。
 * @param scroll 请求下一次布局恢复指定条目索引及双向偏移，并等待恢复帧。
 */
@Composable
private fun BindPaneViewport(
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    ids: List<String>,
    density: Float,
    sample: () -> ViewportSample,
    scroll: suspend (Int, Int, Int) -> Unit,
) {
    val binding = remember(model.tabId, model.browserState.visitId, model.viewMode) {
        ViewportBinding(ids, model.browserState.viewports[model.viewMode], density).also {
            it.contentRevision = model.browserState.contentRevision
        }
    }
    val currentSample = rememberUpdatedState(sample)
    val currentActions = rememberUpdatedState(actions)
    val currentIds = rememberUpdatedState(ids)
    LaunchedEffect(binding, density) {
        snapshotFlow { currentSample.value() }.collect { value ->
            // 新内容尚未完成锚点校正时，不能用新索引覆盖旧锚点。
            if (binding.restoring || binding.ids !== currentIds.value || binding.density != density) return@collect
            val id = value.entryId?.takeIf { it in binding.ids } ?: return@collect
            val index = value.index.takeIf { binding.ids.getOrNull(it) == id } ?: binding.ids.indexOf(id)
            val anchor = PaneViewportAnchor(id, index, value.offset / density, value.horizontalOffset / density,
                binding.ids.subList(index + 1, minOf(index + 17, binding.ids.size)).toList(),
                binding.ids.subList(maxOf(0, index - 16), index).asReversed().toList())
            binding.anchor = anchor
            currentActions.value.onViewportChanged(anchor, value.scrolling)
        }
    }
    LaunchedEffect(ids, density) {
        binding.applyIdentityChanges(model.browserState)
        val anchor = binding.anchor
        if (binding.ids !== ids || binding.density != density) {
            binding.restoring = true
            try {
                if (anchor != null && ids.isNotEmpty() && !sample().scrolling) {
                    scroll(anchor.resolveIndex(ids), (anchor.offsetDp * density).toInt(),
                        (anchor.horizontalOffsetDp * density).toInt())
                }
            } finally {
                binding.ids = ids
                binding.density = density
                binding.restoring = false
            }
        }
    }
    LaunchedEffect(model.pendingScrollToEntryId) {
        val id = model.pendingScrollToEntryId ?: return@LaunchedEffect
        val index = ids.indexOf(id)
        if (index >= 0 && id !in sample().visibleIds) {
            binding.restoring = true
            try { scroll(index, 0, sample().horizontalOffset) } finally { binding.restoring = false }
        }
        actions.onConsumeScroll()
    }
    DisposableEffect(binding) {
        onDispose { binding.anchor?.let { currentActions.value.onViewportChanged(it, false) } }
    }
}
