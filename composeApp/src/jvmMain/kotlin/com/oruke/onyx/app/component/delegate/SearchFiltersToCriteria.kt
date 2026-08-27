package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.SearchFilterFileType
import com.oruke.onyx.app.component.SearchFilters
import java.time.Instant
import java.time.ZoneOffset

/**
 * 将结构化 [SearchFilters] 与名称查询转换为 FileSearchUseCase 语法查询字符串。
 *
 * 复用现有语法解析器能力（`content:`、`type:`、`modified>`、`size>=`、`size<=`），
 * 确保与语法解析器兼容，无需修改 shared 层公开契约。
 *
 * @param query 用户输入的名称查询文本；内容开关开启时作为内容检索词。
 * @return 空格分隔的查询 token；无有效条件时为空串。
 */
internal fun SearchFilters.toCriteriaQuery(query: String): String {
    val parts = mutableListOf<String>()
    val trimmed = query.trim()
    if (trimmed.isNotEmpty()) {
        parts += if (searchInContent) "content:$trimmed" else trimmed
    }
    if (types.size == 1) {
        parts += when (types.first()) {
            SearchFilterFileType.DIRECTORY -> "type:dir"
            SearchFilterFileType.FILE -> "type:file"
        }
    }
    modifiedSinceEpochMillis?.let { epochMillis ->
        parts += "modified>${epochMillis.toUtcDateString()}"
    }
    minSizeBytes?.let { bytes -> parts += "size>=$bytes" }
    maxSizeBytes?.let { bytes -> parts += "size<=$bytes" }
    return parts.joinToString(" ")
}

/** 将 epoch millis 换算为 UTC 民用日期字符串（`yyyy-MM-dd`），与语法解析器日期格式一致。 */
private fun Long.toUtcDateString(): String {
    val date = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
    return "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)
}
