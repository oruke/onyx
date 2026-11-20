package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.SearchFilterFileType
import com.oruke.onyx.app.component.SearchFilters
import com.oruke.onyx.app.component.SearchModifiedPreset
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 将结构化 [SearchFilters] 与名称查询转换为 FileSearchUseCase 语法查询字符串。
 *
 * 复用现有语法解析器能力（`content:`、`type:`、`modified>`、`modified>=`、`size>=`、`size<=`），
 * 对含空白或引号的内容检索词自动使用双引号包裹，对用户输入中未闭合的双引号安全闭合防止吞掉后续结构化筛选词，
 * 并将修改时间预设转换为带本地时区偏移的零点时间戳（`yyyy-MM-dd'T'00:00:00±HH:MM`）以消除跨时区偏差。
 *
 * @param query 用户输入的名称查询文本；内容开关开启时作为内容检索词。
 * @param now 当前时刻，用于计算相对修改时间预设，测试中可注入固定时刻。
 * @param zoneId 计算修改时间预设所用的时区，默认使用系统本地时区。
 * @return 空格分隔的查询 token；无有效条件时为空串。
 */
internal fun SearchFilters.toCriteriaQuery(
    query: String,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val parts = mutableListOf<String>()
    val trimmed = query.trim()
    val hasStructuredFilters = types.size == 1 ||
        modifiedPreset != null ||
        modifiedSinceEpochMillis != null ||
        minSizeBytes != null ||
        maxSizeBytes != null
    if (trimmed.isNotEmpty()) {
        val queryToken = if (searchInContent) {
            formatContentSearchToken(trimmed)
        } else if (hasStructuredFilters) {
            closeUnbalancedQuotes(trimmed)
        } else {
            trimmed
        }
        parts += queryToken
    }
    if (types.size == 1) {
        parts += when (types.first()) {
            SearchFilterFileType.DIRECTORY -> "type:dir"
            SearchFilterFileType.FILE -> "type:file"
        }
    }
    if (modifiedPreset != null) {
        val localMidnight = modifiedPreset.toLocalDate(now, zoneId).atStartOfDay(zoneId)
        parts += "modified>=${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(localMidnight)}"
    } else if (modifiedSinceEpochMillis != null) {
        parts += "modified>${modifiedSinceEpochMillis.toUtcDateString()}"
    }
    minSizeBytes?.let { bytes -> parts += "size>=$bytes" }
    maxSizeBytes?.let { bytes -> parts += "size<=$bytes" }
    return parts.joinToString(" ")
}

/**
 * 将用户输入格式化为 `content:` 检索 token；含空白或双引号的多词短语使用引号包裹以防拆分。
 * 若用户手动输入了 `content:` / `contains:` 前缀但存在未闭合双引号，自动补全尾引号以免吞掉后续过滤词。
 *
 * @param trimmed 已去首尾空白的内容查询词。
 * @return 可被 [com.oruke.onyx.shared.usecase.FileSearchCriteria] 完整解析的内容 token。
 */
private fun formatContentSearchToken(trimmed: String): String {
    val lowered = trimmed.lowercase()
    if (lowered.startsWith("content:") || lowered.startsWith("contains:")) {
        return closeUnbalancedQuotes(trimmed)
    }
    val needsQuoting = trimmed.any { ch -> ch.isWhitespace() || ch == '"' }
    if (!needsQuoting) return "content:$trimmed"
    val escaped = trimmed.replace("\\", "\\\\").replace("\"", "\\\"")
    return "content:\"$escaped\""
}

/**
 * 若文本包含奇数个未转义双引号，在末尾补全一个闭合双引号，防止后续拼接的 `type:`/`modified`/`size` 被吞入短语。
 *
 * @param text 待检查的查询片段。
 * @return 引号已闭合的安全查询片段。
 */
private fun closeUnbalancedQuotes(text: String): String {
    var inQuotes = false
    var escaping = false
    for (ch in text) {
        when {
            escaping -> escaping = false
            inQuotes && ch == '\\' -> escaping = true
            ch == '"' -> inQuotes = !inQuotes
        }
    }
    if (!inQuotes) return text
    return if (escaping) "$text\\\"" else "$text\""
}

/**
 * 按指定时区将修改时间预设换算为本地日历日期。
 *
 * @param now 当前时刻。
 * @param zoneId 目标本地时区。
 * @return 对应时间窗口的起始日历日期。
 */
private fun SearchModifiedPreset.toLocalDate(now: Instant, zoneId: ZoneId): LocalDate {
    val today = now.atZone(zoneId).toLocalDate()
    return when (this) {
        SearchModifiedPreset.TODAY -> today
        SearchModifiedPreset.LAST_7_DAYS -> today.minusDays(DAYS_PER_WEEK)
        SearchModifiedPreset.LAST_30_DAYS -> today.minusDays(DAYS_PER_MONTH)
    }
}

/** 格式化为与搜索语法解析器一致的 `yyyy-MM-dd` 民用日期。 */
private fun LocalDate.formatIsoDate(): String = "%04d-%02d-%02d".format(year, monthValue, dayOfMonth)

/** 将 epoch millis 换算为 UTC 民用日期字符串（`yyyy-MM-dd`），兼容已有显式时间戳调用方。 */
private fun Long.toUtcDateString(): String =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate().formatIsoDate()

/** 本周筛选回溯的天数。 */
private const val DAYS_PER_WEEK = 7L

/** 本月筛选回溯的天数。 */
private const val DAYS_PER_MONTH = 30L
