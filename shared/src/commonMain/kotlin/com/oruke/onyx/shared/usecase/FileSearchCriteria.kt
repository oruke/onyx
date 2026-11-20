package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.core.model.containsSfmMappedChars
import com.oruke.onyx.core.model.decodeSfmDisplayText

/** 解析完成且可重复用于条目匹配的搜索条件。 */
internal data class FileSearchCriteria(
    /** 小写名称查询。 */
    val nameQuery: String,
    /** 带点号的小写扩展名过滤。 */
    val extensionQuery: String?,
    /** 文件或目录类型过滤。 */
    val kind: VFileKind?,
    /** 最小文件大小。 */
    val minSizeBytes: Long?,
    /** 最大文件大小。 */
    val maxSizeBytes: Long?,
    /** 最早修改时间。 */
    val modifiedAfterEpochMillis: Long?,
    /** 最晚修改时间。 */
    val modifiedBeforeEpochMillis: Long?,
    /** 小写文件内容查询。 */
    val contentQuery: String?,
) {
    /** 查询是否需要读取文件内容。 */
    val requiresContent: Boolean
        get() = contentQuery != null

    /** 查询是否至少包含一个有效条件。 */
    val isValid: Boolean
        get() = nameQuery.isNotBlank() ||
            extensionQuery != null ||
            kind != null ||
            minSizeBytes != null ||
            maxSizeBytes != null ||
            modifiedAfterEpochMillis != null ||
            modifiedBeforeEpochMillis != null ||
            contentQuery != null

    /**
     * 检查条目的类型、名称、大小和修改时间。
     *
     * @param entry 待匹配条目。
     * @return 所有元数据条件均满足时返回 true。
     */
    fun matchesMetadata(entry: VFile): Boolean {
        return matchesKind(entry) &&
            matchesEntryName(entry.name) &&
            matchesSize(entry.sizeBytes) &&
            matchesModifiedTime(entry.modifiedAtEpochMillis)
    }

    /**
     * 同时兼容原始文件名与 SFM 私用区还原后的可读名称进行大小写无关匹配。
     *
     * @param rawName 条目底层原始名称。
     * @return 原始名或还原名满足扩展名与名称条件时返回 true。
     */
    private fun matchesEntryName(rawName: String): Boolean {
        val rawMatched = matchesName(rawName.lowercase())
        return rawMatched ||
            (rawName.containsSfmMappedChars() && matchesName(rawName.decodeSfmDisplayText().lowercase()))
    }

    /**
     * 检查可选文件内容条件。
     *
     * @param entry 待匹配条目。
     * @param contentSearchService 内容检索服务。
     * @param maxContentBytes 最大读取字节数。
     * @return 成功时携带是否匹配，读取失败时携带异常。
     */
    suspend fun matchesContent(
        entry: VFile,
        contentSearchService: FileContentSearchService,
        maxContentBytes: Long,
    ): Result<Boolean> {
        val query = contentQuery
        return when {
            query == null -> Result.success(true)
            entry.kind != VFileKind.FILE -> Result.success(false)
            !contentSearchService.supports(entry) -> Result.failure(
                LocalizedException(
                    I18nMessage(MessageKey.MSG_SEARCH_CONTENT_UNSUPPORTED, entry.location),
                )
            )

            else -> contentSearchService.contains(entry, query, maxContentBytes)
        }
    }

    /**
     * 检查类型条件。
     *
     * @param entry 待匹配条目。
     * @return 类型匹配或未指定类型时返回 true。
     */
    private fun matchesKind(entry: VFile): Boolean = kind == null || entry.kind == kind

    /**
     * 检查名称与扩展名条件。
     *
     * @param normalizedName 小写文件名。
     * @return 名称条件全部满足时返回 true。
     */
    private fun matchesName(normalizedName: String): Boolean =
        (extensionQuery == null || normalizedName.endsWith(extensionQuery)) &&
            (nameQuery.isBlank() || normalizedName.contains(nameQuery))

    /**
     * 检查文件大小范围。
     *
     * @param size 文件大小；目录或未知大小为空。
     * @return 大小条件全部满足时返回 true。
     */
    private fun matchesSize(size: Long?): Boolean =
        (minSizeBytes == null || size != null && size >= minSizeBytes) &&
            (maxSizeBytes == null || size != null && size <= maxSizeBytes)

    /**
     * 检查修改时间范围。
     *
     * @param modified 修改时间 epoch millis；未知时为空。
     * @return 时间条件全部满足时返回 true。
     */
    private fun matchesModifiedTime(modified: Long?): Boolean =
        (modifiedAfterEpochMillis == null || modified != null && modified >= modifiedAfterEpochMillis) &&
            (modifiedBeforeEpochMillis == null || modified != null && modified <= modifiedBeforeEpochMillis)

    internal companion object {
        /**
         * 解析用户搜索表达式。
         *
         * @param rawQuery 原始搜索文本。
         * @return 可复用的结构化搜索条件。
         */
        fun parse(rawQuery: String): FileSearchCriteria = SearchCriteriaParser().parse(rawQuery)
    }
}

/** 将搜索 token 汇总为 [FileSearchCriteria] 的有状态解析器。 */
private class SearchCriteriaParser {
    /** 扩展名过滤。 */
    private var extensionQuery: String? = null

    /** 类型过滤。 */
    private var kind: VFileKind? = null

    /** 最小文件大小。 */
    private var minSizeBytes: Long? = null

    /** 最大文件大小。 */
    private var maxSizeBytes: Long? = null

    /** 最早修改时间。 */
    private var modifiedAfterEpochMillis: Long? = null

    /** 最晚修改时间。 */
    private var modifiedBeforeEpochMillis: Long? = null

    /** 内容查询。 */
    private var contentQuery: String? = null

    /** 普通名称 token。 */
    private val nameTokens = mutableListOf<String>()

    /**
     * 解析完整查询文本。
     *
     * @param rawQuery 原始搜索文本。
     * @return 结构化搜索条件。
     */
    fun parse(rawQuery: String): FileSearchCriteria {
        tokenizeSearchQuery(rawQuery.trim())
            .filter(String::isNotBlank)
            .forEach(::consume)
        return build()
    }

    /**
     * 消费单个查询 token，并更新对应条件。
     *
     * @param token 保留原始大小写与引号信息的查询 token。
     */
    private fun consume(token: String) {
        val normalized = token.lowercase()
        when {
            normalized.startsWith(".") && normalized.length > 1 -> extensionQuery = normalized
            normalized.startsWith("type:") || normalized.startsWith("kind:") -> {
                kind = parseKind(unquoteSearchValue(normalized.substringAfter(':')))
            }

            normalized.startsWith("size") -> applySizeFilter(parseSizeFilter(normalized))
            normalized.startsWith("modified") || normalized.startsWith("mtime") -> {
                applyDateFilter(parseDateFilter(normalized))
            }

            normalized.startsWith("content:") || normalized.startsWith("contains:") -> {
                contentQuery = unquoteSearchValue(token.substringAfter(':'))
                    .trim()
                    .takeIf(String::isNotBlank)
                    ?.lowercase()
            }

            else -> {
                val plainName = unquoteSearchValue(normalized).trim()
                if (plainName.isNotEmpty()) nameTokens += plainName
            }
        }
    }

    /**
     * 将大小过滤映射到最小值或最大值。
     *
     * @param filter 已解析大小过滤；无效表达式为空。
     */
    private fun applySizeFilter(filter: NumericFilter?) {
        when (filter?.operator) {
            FilterOperator.GREATER_THAN,
            FilterOperator.GREATER_THAN_OR_EQUALS -> minSizeBytes = filter.value

            FilterOperator.LESS_THAN,
            FilterOperator.LESS_THAN_OR_EQUALS -> maxSizeBytes = filter.value

            null -> Unit
        }
    }

    /**
     * 将日期过滤映射到最早时间或最晚时间。
     *
     * @param filter 已解析日期过滤；无效表达式为空。
     */
    private fun applyDateFilter(filter: NumericFilter?) {
        when (filter?.operator) {
            FilterOperator.GREATER_THAN,
            FilterOperator.GREATER_THAN_OR_EQUALS -> modifiedAfterEpochMillis = filter.value

            FilterOperator.LESS_THAN,
            FilterOperator.LESS_THAN_OR_EQUALS -> modifiedBeforeEpochMillis = filter.value

            null -> Unit
        }
    }

    /**
     * 构造不可变条件快照。
     *
     * @return 当前解析状态对应的搜索条件。
     */
    private fun build(): FileSearchCriteria = FileSearchCriteria(
        nameQuery = nameTokens.joinToString(" "),
        extensionQuery = extensionQuery,
        kind = kind,
        minSizeBytes = minSizeBytes,
        maxSizeBytes = maxSizeBytes,
        modifiedAfterEpochMillis = modifiedAfterEpochMillis,
        modifiedBeforeEpochMillis = modifiedBeforeEpochMillis,
        contentQuery = contentQuery,
    )

    private companion object {
        /**
         * 将查询表达式按空白切分为 token，同时完整保留双引号包裹的多词短语（例如 `content:"hello world"`）。
         *
         * @param raw 已去除首尾空白的原始查询串。
         * @return 拆分后的查询 token 列表。
         */
        fun tokenizeSearchQuery(raw: String): List<String> {
            if (raw.isEmpty()) return emptyList()
            val tokens = mutableListOf<String>()
            val current = StringBuilder()
            var inQuotes = false
            var escaping = false
            for (ch in raw) {
                when {
                    escaping -> {
                        current.append(ch)
                        escaping = false
                    }

                    inQuotes && ch == '\\' -> {
                        current.append(ch)
                        escaping = true
                    }

                    ch == '"' -> {
                        inQuotes = !inQuotes
                        current.append(ch)
                    }

                    !inQuotes && ch.isWhitespace() -> {
                        if (current.isNotEmpty()) {
                            tokens += current.toString()
                            current.clear()
                        }
                    }

                    else -> current.append(ch)
                }
            }
            if (current.isNotEmpty()) tokens += current.toString()
            return tokens
        }

        /**
         * 剥离成对双引号并反转义其中的 `\"` 与 `\\`。
         *
         * @param raw 可能带有外层双引号的片段值。
         * @return 还原后的字面量文本。
         */
        fun unquoteSearchValue(raw: String): String {
            val trimmed = raw.trim()
            val hasUnescapedClosingQuote = trimmed.length >= 2 &&
                trimmed.startsWith('"') &&
                trimmed.endsWith('"') &&
                countPrecedingBackslashes(trimmed, trimmed.lastIndex) % 2 == 0
            val inner = when {
                hasUnescapedClosingQuote -> trimmed.substring(1, trimmed.length - 1)
                trimmed.startsWith('"') -> trimmed.substring(1)
                else -> return trimmed
            }
            val result = StringBuilder(inner.length)
            var index = 0
            while (index < inner.length) {
                val ch = inner[index]
                if (ch == '\\' && index + 1 < inner.length && (inner[index + 1] == '"' || inner[index + 1] == '\\')) {
                    result.append(inner[index + 1])
                    index += 2
                } else {
                    result.append(ch)
                    index += 1
                }
            }
            return result.toString()
        }

        /**
         * 统计字符串指定下标之前连续出现的反斜杠数量，用于区分闭合引号与转义引号（如 `\"`）。
         *
         * @param text 待检查字符串。
         * @param quoteIndex 目标双引号下标。
         * @return 紧邻该引号之前的连续反斜杠个数。
         */
        private fun countPrecedingBackslashes(text: String, quoteIndex: Int): Int {
            var count = 0
            var cursor = quoteIndex - 1
            while (cursor >= 0 && text[cursor] == '\\') {
                count += 1
                cursor -= 1
            }
            return count
        }
    }
}

/**
 * 解析类型过滤值。
 *
 * @param value `type:` 或 `kind:` 后的值。
 * @return 对应文件类型；无法识别时返回空。
 */
private fun parseKind(value: String): VFileKind? = when (value) {
    "file", "files" -> VFileKind.FILE
    "dir", "dirs", "directory", "directories", "folder", "folders" -> VFileKind.DIRECTORY
    else -> null
}

/**
 * 解析大小过滤表达式。
 *
 * @param token 以 `size` 开头的查询 token。
 * @return 标准字节过滤；无效表达式返回空。
 */
private fun parseSizeFilter(token: String): NumericFilter? =
    parseFilterExpression(token.removePrefix("size"))?.let { expression ->
        parseSizeBytes(expression.value)?.let { bytes ->
            NumericFilter(expression.operator, bytes)
        }
    }

/**
 * 解析修改日期过滤表达式。
 *
 * @param token 以 `modified` 或 `mtime` 开头的查询 token。
 * @return 调整到日期边界的毫秒过滤；无效表达式返回空。
 */
private fun parseDateFilter(token: String): NumericFilter? {
    val prefix = if (token.startsWith("modified")) "modified" else "mtime"
    return parseFilterExpression(token.removePrefix(prefix))?.let { expression ->
        val exactTimestamp = parseIsoOffsetDateTimeEpochMillis(expression.value)
        if (exactTimestamp != null) {
            NumericFilter(expression.operator, exactTimestamp)
        } else {
            parseDateEpochMillis(expression.value)?.let { epochMillis ->
                val adjusted = when (expression.operator) {
                    FilterOperator.LESS_THAN,
                    FilterOperator.LESS_THAN_OR_EQUALS -> epochMillis + MILLIS_PER_DAY - 1

                    FilterOperator.GREATER_THAN,
                    FilterOperator.GREATER_THAN_OR_EQUALS -> epochMillis
                }
                NumericFilter(expression.operator, adjusted)
            }
        }
    }
}

/**
 * 解析带时区偏移的 ISO-8601 时间戳（如 `2026-11-19t00:00:00+08:00` 或 `2026-11-19t00:00:00z`）为绝对 UTC epoch millis。
 *
 * @param value 已转小写的日期时间操作数。
 * @return 对应绝对 epoch millis；不符合格式时返回空。
 */
private fun parseIsoOffsetDateTimeEpochMillis(value: String): Long? {
    val tIndex = value.indexOf('t')
    if (tIndex != ISO_DATE_LENGTH || value.length < ISO_MIN_DATETIME_LENGTH) return null
    val civilDate = parseCivilDate(value.substring(0, ISO_DATE_LENGTH)) ?: return null
    val timeAndZone = value.substring(ISO_DATE_LENGTH + 1)
    if (timeAndZone.length < ISO_TIME_LENGTH + 1) return null
    val timeParts = timeAndZone.substring(0, ISO_TIME_LENGTH).split(':')
    if (timeParts.size != TIME_PART_COUNT) return null
    val hour = timeParts[0].toIntOrNull()?.takeIf { it in 0..MAX_HOUR } ?: return null
    val minute = timeParts[1].toIntOrNull()?.takeIf { it in 0..MAX_MINUTE_OR_SECOND } ?: return null
    val second = timeParts[2].toIntOrNull()?.takeIf { it in 0..MAX_MINUTE_OR_SECOND } ?: return null
    val zonePart = timeAndZone.substring(ISO_TIME_LENGTH)
    val offsetMillis = parseZoneOffsetMillis(zonePart) ?: return null
    val timeOfDayMillis = (hour * SECONDS_PER_HOUR + minute * SECONDS_PER_MINUTE + second) * MILLIS_PER_SECOND
    return civilDate.toEpochMillis() + timeOfDayMillis - offsetMillis
}

/**
 * 解析 ISO-8601 时区偏移片段（`z`、`+hh:mm`、`-hh:mm`）为毫秒偏移量。
 *
 * @param zonePart 已转小写的时区后缀。
 * @return 相对 UTC 的毫秒偏移；格式无效时返回空。
 */
private fun parseZoneOffsetMillis(zonePart: String): Long? {
    if (zonePart == "z") return 0L
    if (zonePart.length != ISO_OFFSET_LENGTH) return null
    val sign = when (zonePart[0]) {
        '+' -> 1L
        '-' -> -1L
        else -> return null
    }
    if (zonePart[3] != ':') return null
    val offsetHour = zonePart.substring(1, 3).toIntOrNull()?.takeIf { it in 0..MAX_OFFSET_HOUR } ?: return null
    val offsetMinute = zonePart.substring(4, 6).toIntOrNull()?.takeIf { it in 0..MAX_MINUTE_OR_SECOND } ?: return null
    return sign * (offsetHour * SECONDS_PER_HOUR + offsetMinute * SECONDS_PER_MINUTE) * MILLIS_PER_SECOND
}

/**
 * 解析比较运算符及其操作数。
 *
 * @param value 去掉字段名前缀后的表达式。
 * @return 结构化比较表达式；无运算符或操作数时返回空。
 */
private fun parseFilterExpression(value: String): ParsedFilterExpression? {
    val operator = when {
        value.startsWith(">=") -> FilterOperator.GREATER_THAN_OR_EQUALS
        value.startsWith("<=") -> FilterOperator.LESS_THAN_OR_EQUALS
        value.startsWith(">") -> FilterOperator.GREATER_THAN
        value.startsWith("<") -> FilterOperator.LESS_THAN
        else -> null
    }
    return operator?.let { resolvedOperator ->
        value.removePrefix(resolvedOperator.symbol)
            .trim()
            .takeIf(String::isNotBlank)
            ?.let { operand -> ParsedFilterExpression(resolvedOperator, operand) }
    }
}

/**
 * 将带二进制单位的大小文本转换为字节数。
 *
 * @param value 数值与可选 B/KB/MB/GB/TB 后缀。
 * @return 字节数；格式或单位无效时返回空。
 */
private fun parseSizeBytes(value: String): Long? {
    val number = value.dropLastWhile(Char::isLetter)
    val suffix = value.removePrefix(number).lowercase()
    val amount = number.toDoubleOrNull()
    val multiplier = SIZE_MULTIPLIERS[suffix]
    return if (amount == null || multiplier == null) null else (amount * multiplier).toLong()
}

/**
 * 将 `yyyy-MM-dd` 日期换算为 UTC epoch millis。
 *
 * @param value 日期文本。
 * @return 当日零点 epoch millis；格式或范围无效时返回空。
 */
private fun parseDateEpochMillis(value: String): Long? = parseCivilDate(value)?.toEpochMillis()

/**
 * 校验并解析民用日期。
 *
 * @param value `yyyy-MM-dd` 日期文本。
 * @return 有效年月日；格式或范围无效时返回空。
 */
private fun parseCivilDate(value: String): CivilDate? {
    val parts = value.split('-')
    if (parts.size != DATE_PART_COUNT) return null
    val year = parts[0].toIntOrNull()
    val month = parts[1].toIntOrNull()
    val day = parts[2].toIntOrNull()
    val valid = year != null && month != null && day != null &&
        month in MIN_MONTH..MAX_MONTH && day in MIN_DAY..MAX_DAY
    return if (valid) CivilDate(requireNotNull(year), requireNotNull(month), requireNotNull(day)) else null
}

/** 适用于当前搜索语法的民用日期。 */
private data class CivilDate(
    /** 公历年份。 */
    val year: Int,
    /** 公历月份。 */
    val month: Int,
    /** 公历日期。 */
    val day: Int,
) {
    /**
     * 使用无时区公历算法换算 epoch millis。
     *
     * @return 当日零点 epoch millis。
     */
    fun toEpochMillis(): Long {
        val adjustedYear = if (month <= FEBRUARY) year - 1 else year
        val era = adjustedYear.floorDivide(YEARS_PER_ERA)
        val yearOfEra = adjustedYear - era * YEARS_PER_ERA
        val adjustedMonth = month + if (month > FEBRUARY) MONTH_SHIFT_AFTER_FEBRUARY else MONTH_SHIFT_BEFORE_MARCH
        val dayOfYear = (DAYS_PER_FIVE_MONTHS * adjustedMonth + DAY_ALGORITHM_OFFSET) /
            MONTH_ALGORITHM_DIVISOR + day - 1
        val dayOfEra = yearOfEra * DAYS_PER_COMMON_YEAR +
            yearOfEra / LEAP_YEAR_INTERVAL -
            yearOfEra / NON_LEAP_CENTURY_INTERVAL +
            dayOfYear
        val epochDay = era * DAYS_PER_ERA + dayOfEra - DAYS_FROM_CIVIL_TO_EPOCH
        return epochDay * MILLIS_PER_DAY
    }
}

/**
 * 对负年份也执行数学意义的向下整除。
 *
 * @param divisor 正除数。
 * @return 向负无穷取整的商。
 */
private fun Int.floorDivide(divisor: Int): Int {
    var result = this / divisor
    if ((this xor divisor) < 0 && result * divisor != this) result--
    return result
}

/** 比较运算符及查询文本表示。 */
private enum class FilterOperator(
    /** 查询中的运算符文本。 */
    val symbol: String,
) {
    GREATER_THAN(">"),
    GREATER_THAN_OR_EQUALS(">="),
    LESS_THAN("<"),
    LESS_THAN_OR_EQUALS("<="),
}

/** 已拆分运算符和操作数的过滤表达式。 */
private data class ParsedFilterExpression(
    /** 比较运算符。 */
    val operator: FilterOperator,
    /** 尚未按业务类型转换的操作数。 */
    val value: String,
)

/** 数值化后的过滤条件。 */
private data class NumericFilter(
    /** 比较运算符。 */
    val operator: FilterOperator,
    /** 字节数或 epoch millis。 */
    val value: Long,
)

private val SIZE_MULTIPLIERS = mapOf(
    "" to 1L,
    "b" to 1L,
    "k" to 1_024L,
    "kb" to 1_024L,
    "m" to 1_048_576L,
    "mb" to 1_048_576L,
    "g" to 1_073_741_824L,
    "gb" to 1_073_741_824L,
    "t" to 1_099_511_627_776L,
    "tb" to 1_099_511_627_776L,
)
private const val DATE_PART_COUNT = 3
private const val TIME_PART_COUNT = 3
private const val ISO_DATE_LENGTH = 10
private const val ISO_TIME_LENGTH = 8
private const val ISO_MIN_DATETIME_LENGTH = 20
private const val ISO_OFFSET_LENGTH = 6
private const val MAX_HOUR = 23
private const val MAX_MINUTE_OR_SECOND = 59
private const val MAX_OFFSET_HOUR = 18
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val MILLIS_PER_SECOND = 1_000L
private const val MIN_MONTH = 1
private const val MAX_MONTH = 12
private const val MIN_DAY = 1
private const val MAX_DAY = 31
private const val FEBRUARY = 2
private const val YEARS_PER_ERA = 400
private const val MONTH_SHIFT_AFTER_FEBRUARY = -3
private const val MONTH_SHIFT_BEFORE_MARCH = 9
private const val DAYS_PER_FIVE_MONTHS = 153
private const val DAY_ALGORITHM_OFFSET = 2
private const val MONTH_ALGORITHM_DIVISOR = 5
private const val DAYS_PER_COMMON_YEAR = 365
private const val LEAP_YEAR_INTERVAL = 4
private const val NON_LEAP_CENTURY_INTERVAL = 100
private const val DAYS_PER_ERA = 146_097
private const val MILLIS_PER_DAY = 86_400_000L
private const val DAYS_FROM_CIVIL_TO_EPOCH = 719_468
