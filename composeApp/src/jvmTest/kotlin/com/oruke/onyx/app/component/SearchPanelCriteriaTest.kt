package com.oruke.onyx.app.component

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 搜索面板可执行条件与历史展示判定测试。 */
class SearchPanelCriteriaTest {
    /** 验证纯结构化筛选也可发起搜索。 */
    @Test
    fun structuredFilterCanStartSearchWithoutKeyword() {
        val state = SearchPanelState(
            filters = SearchFilters(types = setOf(SearchFilterFileType.FILE)),
        )

        assertTrue(state.hasExecutableSearchCriteria())
        assertFalse(state.shouldShowSearchHistory())
    }

    /** 验证仅内容开关或全类型选择不会构成空关键词搜索。 */
    @Test
    fun nonRestrictiveFiltersCannotStartBlankSearch() {
        val contentOnly = SearchPanelState(filters = SearchFilters(searchInContent = true))
        val allTypes = SearchPanelState(
            filters = SearchFilters(
                types = setOf(SearchFilterFileType.FILE, SearchFilterFileType.DIRECTORY),
            ),
        )

        assertFalse(contentOnly.hasExecutableSearchCriteria())
        assertTrue(contentOnly.shouldShowSearchHistory())
        assertFalse(allTypes.hasExecutableSearchCriteria())
    }
}
