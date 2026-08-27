package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.SearchScope
import com.oruke.onyx.core.model.VFile

/** 将搜索与快速打开的界面操作映射为根组件意图的扩展函数集合。 */

/** 显示完整搜索抽屉。 */
internal fun RootComponent.showSearchPanel() = dispatch(RootIntent.ShowSearchPanel)

/** 关闭当前搜索界面。 */
internal fun RootComponent.closeSearchPanel() = dispatch(RootIntent.CloseSearchPanel)

/** 显示快速打开浮层。 */
internal fun RootComponent.showQuickOpen() = dispatch(RootIntent.ShowQuickOpen)

/** 关闭快速打开浮层。 */
internal fun RootComponent.closeQuickOpen() = dispatch(RootIntent.CloseQuickOpen)

/**
 * 更新待搜索的关键字。
 *
 * @param query 用户输入的关键字。
 */
internal fun RootComponent.updateSearchQuery(query: String) = dispatch(RootIntent.UpdateSearchQuery(query))

/** 执行当前搜索条件对应的文件搜索。 */
internal fun RootComponent.executeSearch() = dispatch(RootIntent.ExecuteSearch)

/** 取消正在运行的文件搜索。 */
internal fun RootComponent.cancelSearch() = dispatch(RootIntent.CancelSearch)

/**
 * 打开搜索结果中的文件或目录。
 *
 * @param entry 要打开的 VFS 条目。
 */
internal fun RootComponent.openSearchResult(entry: VFile) = dispatch(RootIntent.OpenSearchResult(entry))

/** 将当前所有搜索结果作为集合打开。 */
internal fun RootComponent.openSearchResultsAsCollection() = dispatch(RootIntent.OpenSearchResultsAsCollection)

/**
 * 打开结果所在目录并选中该条目。
 *
 * @param entry 待定位的 VFS 条目。
 */
internal fun RootComponent.openSearchResultInFolder(entry: VFile) =
    dispatch(RootIntent.OpenSearchResultInFolder(entry))

/**
 * 更新搜索范围。
 *
 * @param scope 用户选择的搜索范围。
 */
internal fun RootComponent.updateSearchScope(scope: SearchScope) = dispatch(RootIntent.UpdateSearchScope(scope))

/**
 * 更新结构化搜索筛选条件。
 *
 * @param filters 新的筛选条件快照。
 */
internal fun RootComponent.updateSearchFilters(filters: SearchFilters) =
    dispatch(RootIntent.UpdateSearchFilters(filters))

/**
 * 更新搜索抽屉高度比例。
 *
 * @param height 高度占应用窗口的比例。
 */
internal fun RootComponent.setSearchDrawerHeight(height: Float) = dispatch(RootIntent.SetSearchDrawerHeight(height))

/**
 * 更新任务中心抽屉高度比例。
 *
 * @param height 高度占应用窗口的比例。
 */
internal fun RootComponent.setJobsDrawerHeight(height: Float) = dispatch(RootIntent.SetJobsDrawerHeight(height))

/** 清空已持久化的搜索历史。 */
internal fun RootComponent.clearSearchHistory() = dispatch(RootIntent.ClearSearchHistory)
