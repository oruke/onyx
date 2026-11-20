package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.SearchFilterFileType
import com.oruke.onyx.app.component.SearchFilters
import com.oruke.onyx.app.component.SearchModifiedPreset
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.shared.usecase.FileSearchEvent
import com.oruke.onyx.shared.usecase.FileSearchRequest
import com.oruke.onyx.shared.usecase.FileSearchUseCase
import com.oruke.onyx.vfs.api.FileRepository
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

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

    /** 验证多词内容搜索自动使用双引号包裹，避免空白将其拆分为名称查询词。 */
    @Test
    fun multiWordContentQueryIsQuotedAndPreservesExistingPrefix() {
        val filters = SearchFilters(searchInContent = true, types = setOf(SearchFilterFileType.FILE))
        assertEquals(
            """content:"hello world" type:file""",
            filters.toCriteriaQuery("hello world"),
        )
        assertEquals(
            """content:"say \"hi\"" type:file""",
            filters.toCriteriaQuery("""say "hi""""),
        )
        assertEquals(
            """content:"already quoted" type:file""",
            filters.toCriteriaQuery("""content:"already quoted""""),
        )
        // 用户输入未闭合引号叠加高级筛选时，必须安全闭合引号以免吞掉后续 type:/size 过滤条件。
        assertEquals(
            """content:"unclosed phrase" type:file""",
            filters.toCriteriaQuery("""content:"unclosed phrase"""),
        )
        val plainWithType = SearchFilters(types = setOf(SearchFilterFileType.FILE), minSizeBytes = 100)
        assertEquals(
            """"unclosed name" type:file size>=100""",
            plainWithType.toCriteriaQuery(""""unclosed name"""),
        )
    }

    /** 验证修改时间预设输出带本地时区偏移的零点时间戳，且端到端 FileSearchUseCase 精准命中东八区凌晨文件。 */
    @Test
    fun modifiedPresetUsesLocalTimezoneCalendarDate(): Unit = runBlocking {
        // UTC 2026-11-18T16:30:00Z 对应北京时间（Asia/Shanghai, UTC+8）2026-11-19 00:30。
        val midnightUtc = Instant.parse("2026-11-18T16:30:00Z")
        val shanghai = ZoneId.of("Asia/Shanghai")

        val todayQuery = SearchFilters(modifiedPreset = SearchModifiedPreset.TODAY)
            .toCriteriaQuery("", midnightUtc, shanghai)
        assertEquals("modified>=2026-11-19T00:00:00+08:00", todayQuery)
        assertEquals(
            "modified>=2026-11-12T00:00:00+08:00",
            SearchFilters(modifiedPreset = SearchModifiedPreset.LAST_7_DAYS).toCriteriaQuery("", midnightUtc, shanghai),
        )
        assertEquals(
            "modified>=2026-10-20T00:00:00+08:00",
            SearchFilters(modifiedPreset = SearchModifiedPreset.LAST_30_DAYS).toCriteriaQuery("", midnightUtc, shanghai),
        )

        // 端到端通过 FileSearchUseCase 验证：00:25+08:00 命中，前一日 23:59+08:00 不命中。
        val earlyToday = VFile(
            id = "early",
            name = "early.txt",
            location = "/tz/early.txt",
            parentLocation = "/tz",
            kind = VFileKind.FILE,
            sizeBytes = 10L,
            modifiedAtEpochMillis = Instant.parse("2026-11-18T16:25:00Z").toEpochMilli(),
            hidden = false,
            capabilities = emptySet(),
        )
        val lateYesterday = VFile(
            id = "late",
            name = "late.txt",
            location = "/tz/late.txt",
            parentLocation = "/tz",
            kind = VFileKind.FILE,
            sizeBytes = 10L,
            modifiedAtEpochMillis = Instant.parse("2026-11-18T15:59:00Z").toEpochMilli(),
            hidden = false,
            capabilities = emptySet(),
        )
        val repo = object : FileRepository {
            override suspend fun list(location: String): Result<List<VFile>> =
                Result.success(if (location == "/tz") listOf(earlyToday, lateYesterday) else emptyList())
            override fun defaultLocation(): String = "/tz"
        }
        val events = FileSearchUseCase(repo).search(FileSearchRequest("/tz", todayQuery)).toList()
        val matched = events.filterIsInstance<FileSearchEvent.Results>().last().entries.map { it.name }
        assertEquals(listOf("early.txt"), matched)
    }
}
