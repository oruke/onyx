package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.OnyxSettings
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    /** 验证最大化仅记录最大化标记而不覆盖浮动尺寸，且最小化瞬态尺寸被忽略。 */
    @Test
    fun withWindowSizePreservesFloatingSizeOnMaximizeAndRejectsMinimizedSize() {
        val initial = OnyxSettings(mainWindowWidth = 1440, mainWindowHeight = 900)
        val maximized = initial.withWindowSize(
            RootIntent.UpdateWindowSize(RootWindowKind.MAIN, width = 2576, height = 1408, maximized = true),
        )
        assertTrue(maximized.mainWindowMaximized)
        assertEquals(1440, maximized.mainWindowWidth)
        assertEquals(900, maximized.mainWindowHeight)

        val ignoredMinimized = maximized.withWindowSize(
            RootIntent.UpdateWindowSize(RootWindowKind.MAIN, width = 160, height = 28, maximized = false),
        )
        assertEquals(maximized, ignoredMinimized)

        val restored = maximized.withWindowSize(
            RootIntent.UpdateWindowSize(RootWindowKind.MAIN, width = 1360, height = 840, maximized = false),
        )
        assertFalse(restored.mainWindowMaximized)
        assertEquals(1360, restored.mainWindowWidth)
        assertEquals(840, restored.mainWindowHeight)
    }

    /** 验证搜索窗口与任务中心窗口尺寸更新及异常最小化尺寸净化。 */
    @Test
    fun searchAndTaskCenterWindowSizesPersistAndSanitize() {
        val updated = OnyxSettings()
            .withWindowSize(RootIntent.UpdateWindowSize(RootWindowKind.SEARCH, 1080, 720, false))
            .withWindowSize(RootIntent.UpdateWindowSize(RootWindowKind.TASK_CENTER, 880, 600, true))
        assertEquals(1080, updated.searchWindowWidth)
        assertEquals(720, updated.searchWindowHeight)
        assertFalse(updated.searchWindowMaximized)
        assertTrue(updated.taskCenterWindowMaximized)
        assertEquals(760, updated.taskCenterWindowWidth)

        val corrupted = OnyxSettings(mainWindowWidth = 160, mainWindowHeight = 28, searchWindowWidth = 100)
        val sanitized = corrupted.sanitizeRootSettings()
        assertEquals(1200, sanitized.mainWindowWidth)
        assertEquals(800, sanitized.mainWindowHeight)
        assertEquals(900, sanitized.searchWindowWidth)
    }
}
