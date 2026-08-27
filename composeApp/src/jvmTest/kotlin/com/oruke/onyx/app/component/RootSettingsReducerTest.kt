package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.OnyxSettings
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** 根设置归并逻辑（搜索历史、抽屉高度与旧配置兼容）测试。 */
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

    /** 验证抽屉高度越界时被收敛到合法范围。 */
    @Test
    fun sanitizeClampsSearchDrawerHeight() {
        assertEquals(
            0.8f,
            OnyxSettings(searchDrawerHeight = 1.5f).sanitizeRootSettings().searchDrawerHeight,
        )
        assertEquals(
            0.2f,
            OnyxSettings(searchDrawerHeight = 0.05f).sanitizeRootSettings().searchDrawerHeight,
        )
        assertEquals(
            0.45f,
            OnyxSettings().sanitizeRootSettings().searchDrawerHeight,
        )
    }

    /** 验证任务中心抽屉高度越界时被收敛到合法范围。 */
    @Test
    fun sanitizeClampsJobsDrawerHeight() {
        assertEquals(
            0.6f,
            OnyxSettings(jobsDrawerHeight = 1.5f).sanitizeRootSettings().jobsDrawerHeight,
        )
        assertEquals(
            0.15f,
            OnyxSettings(jobsDrawerHeight = 0.05f).sanitizeRootSettings().jobsDrawerHeight,
        )
        assertEquals(
            0.35f,
            OnyxSettings().sanitizeRootSettings().jobsDrawerHeight,
        )
    }

    /** 验证旧设置文档缺少搜索字段时使用默认值。 */
    @Test
    fun oldSettingsDocumentDefaultsSearchFields() {
        val settings = Json.decodeFromString<OnyxSettings>("{}")
        assertEquals(0.45f, settings.searchDrawerHeight)
        assertEquals(emptyList(), settings.searchHistory)
    }
}
