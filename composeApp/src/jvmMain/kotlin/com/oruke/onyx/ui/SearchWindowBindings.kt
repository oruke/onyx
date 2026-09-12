package com.oruke.onyx.ui

import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.RootIntent

/**
 * 将完整搜索窗口的语义动作统一连接到所属根组件。
 *
 * @return 使用根组件事件入口的窗口回调集合。
 */
internal fun RootComponent.searchWindowActions(): SearchPanelActions = SearchPanelActions(
    onQueryChange = { dispatch(RootIntent.UpdateSearchQuery(it)) },
    onSearch = { dispatch(RootIntent.ExecuteSearch) },
    onCancel = { dispatch(RootIntent.CancelSearch) },
    onClose = { dispatch(RootIntent.CloseSearchPanel) },
    onOpenResult = { dispatch(RootIntent.OpenSearchResult(it)) },
    onOpenResultInFolder = { dispatch(RootIntent.OpenSearchResultInFolder(it)) },
    onUpdateScope = { dispatch(RootIntent.UpdateSearchScope(it)) },
    onUpdateFilters = { dispatch(RootIntent.UpdateSearchFilters(it)) },
    onSelectIndex = { dispatch(RootIntent.SetSearchSelectedIndex(it)) },
    onSetAdvancedFiltersExpanded = { dispatch(RootIntent.SetSearchAdvancedFiltersExpanded(it)) },
    onSetIncludeRemoteLocations = { dispatch(RootIntent.SetSearchIncludeRemoteLocations(it)) },
    onUseCurrentDirectory = { dispatch(RootIntent.UseCurrentDirectoryForSearch) },
    onOpenResultsAsCollection = { dispatch(RootIntent.OpenSearchResultsAsCollection) },
    onClearHistory = { dispatch(RootIntent.ClearSearchHistory) },
)
