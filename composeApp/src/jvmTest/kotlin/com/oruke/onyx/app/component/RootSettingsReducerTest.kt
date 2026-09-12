package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.OnyxSettings
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** 根设置中的搜索历史归并和缺省字段测试。 */
class RootSettingsReducerTest {
    /** 验证记录查询最新在前，并移出重复项。 */
    @Test
    fun recordSearchQueryPrependsAndDeduplicates() {
        val settings = OnyxSettings(searchHistory = listOf("alpha", "beta"))
        val recorded = settings.recordSearchQuery("gamma")
        assertEquals(listOf("gamma", "alpha", "beta"), recorded.searchHistory)

        val reRecorded = recorded.recordSearchQuery("alpha")
        assertEquals(listOf("alpha", "gamma", "beta"), reRecorded.searchHistory)
    }

    /** 验证重复查询再次记录时保持列表不变。 */
    @Test
    fun recordSearchQueryKeepsListWhenRepeated() {
        val settings = OnyxSettings(searchHistory = listOf("alpha"))
        val recorded = settings.recordSearchQuery("alpha")
        assertEquals(settings.searchHistory, recorded.searchHistory)
    }

    /** 验证空白查询不进入历史。 */
    @Test
    fun recordSearchQueryIgnoresBlank() {
        val settings = OnyxSettings(searchHistory = listOf("alpha"))
        val recorded = settings.recordSearchQuery("   ")
        assertEquals(settings.searchHistory, recorded.searchHistory)
    }

    /** 验证历史在净化时被去重并截断到上限。 */
    @Test
    fun sanitizeClampsSearchHistoryToLimit() {
        val manyQueries = (1..30).map { index -> "query-$index" } + listOf("query-1", "query-2")
        val sanitized = OnyxSettings(searchHistory = manyQueries).sanitizeRootSettings()
        assertEquals(20, sanitized.searchHistory.size)
        assertEquals(sanitized.searchHistory, sanitized.searchHistory.distinct())
        assertEquals("query-1", sanitized.searchHistory.first())
    }

    /** 验证旧设置文档缺少搜索字段时使用默认值。 */
    @Test
    fun oldSettingsDocumentDefaultsSearchFields() {
        val settings = Json.decodeFromString<OnyxSettings>("{}")
        assertEquals(emptyList(), settings.searchHistory)
    }
}
