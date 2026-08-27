package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.SearchFilterFileType
import com.oruke.onyx.app.component.SearchFilters
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** 结构化过滤器到语法查询字符串的转换测试。 */
class SearchFiltersToCriteriaTest {
    /** 验证空过滤器且空查询产生空查询串。 */
    @Test
    fun emptyFiltersAndQueryProduceBlankQuery() {
        assertEquals("", SearchFilters().toCriteriaQuery(""))
    }

    /** 验证名称查询作为普通 token 输出。 */
    @Test
    fun nameQueryEmittedAsPlainToken() {
        assertEquals("report", SearchFilters().toCriteriaQuery("report"))
    }

    /** 验证内容开关开启时名称查询转为内容检索词。 */
    @Test
    fun contentSwitchWrapsQueryAsContentToken() {
        val filters = SearchFilters(searchInContent = true)
        assertEquals("content:budget", filters.toCriteriaQuery("budget"))
    }

    /** 验证单一类型过滤输出 type token。 */
    @Test
    fun singleTypeFilterEmitsTypeToken() {
        val directoryOnly = SearchFilters(types = setOf(SearchFilterFileType.DIRECTORY))
        assertEquals("type:dir", directoryOnly.toCriteriaQuery(""))

        val fileOnly = SearchFilters(types = setOf(SearchFilterFileType.FILE))
        assertEquals("type:file", fileOnly.toCriteriaQuery(""))
    }

    /** 验证同时选择两种类型时不输出类型过滤。 */
    @Test
    fun bothTypesEmitNoTypeToken() {
        val filters = SearchFilters(
            types = setOf(SearchFilterFileType.FILE, SearchFilterFileType.DIRECTORY),
        )
        assertEquals("", filters.toCriteriaQuery(""))
    }

    /** 验证修改时间过滤输出 UTC 日期语法 token。 */
    @Test
    fun modifiedSinceEmitsDateToken() {
        val epoch = Instant.parse("2024-01-15T00:00:00Z").toEpochMilli()
        val filters = SearchFilters(modifiedSinceEpochMillis = epoch)
        assertEquals("modified>2024-01-15", filters.toCriteriaQuery(""))
    }

    /** 验证大小范围过滤输出上下界 token。 */
    @Test
    fun sizeRangeEmitsBoundsTokens() {
        val filters = SearchFilters(minSizeBytes = 1_024, maxSizeBytes = 1_048_576)
        assertEquals("size>=1024 size<=1048576", filters.toCriteriaQuery(""))
    }

    /** 验证组合过滤按稳定顺序拼接 token。 */
    @Test
    fun combinedFiltersProduceStableOrderedTokens() {
        val epoch = Instant.parse("2023-06-01T00:00:00Z").toEpochMilli()
        val filters = SearchFilters(
            types = setOf(SearchFilterFileType.FILE),
            modifiedSinceEpochMillis = epoch,
            minSizeBytes = 512,
            searchInContent = true,
        )
        assertEquals(
            "content:invoice type:file modified>2023-06-01 size>=512",
            filters.toCriteriaQuery("invoice"),
        )
    }
}
