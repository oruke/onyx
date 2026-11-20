package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.SearchFilterFileType
import com.oruke.onyx.app.component.SearchFilters
import com.oruke.onyx.app.component.SearchModifiedPreset
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import kotlin.math.roundToLong
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_reset
import onyx.composeapp.generated.resources.label_search_size_custom_max_mb
import onyx.composeapp.generated.resources.label_search_size_custom_min_mb
import onyx.composeapp.generated.resources.search_filter_content
import onyx.composeapp.generated.resources.search_filter_directory
import onyx.composeapp.generated.resources.search_filter_file
import onyx.composeapp.generated.resources.search_filter_modified
import onyx.composeapp.generated.resources.search_filter_month
import onyx.composeapp.generated.resources.search_filter_size
import onyx.composeapp.generated.resources.search_filter_size_1_to_100_mb
import onyx.composeapp.generated.resources.search_filter_size_over_100_mb
import onyx.composeapp.generated.resources.search_filter_size_under_1_mb
import onyx.composeapp.generated.resources.search_filter_today
import onyx.composeapp.generated.resources.search_filter_type
import onyx.composeapp.generated.resources.search_filter_week
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/** 1 MB 对应的字节数。 */
internal const val SEARCH_SIZE_1_MB_BYTES = 1_048_576L

/** 100 MB 对应的字节数。 */
internal const val SEARCH_SIZE_100_MB_BYTES = 104_857_600L

/** `< 1MB` 预设上限（严格小于 1MB，避免与 `1–100MB` 预设边界重叠）。 */
internal const val SEARCH_SIZE_UNDER_1_MB_MAX_BYTES = SEARCH_SIZE_1_MB_BYTES - 1L

/** `> 100MB` 预设下限（严格大于 100MB，避免与 `1–100MB` 预设边界重叠）。 */
internal const val SEARCH_SIZE_OVER_100_MB_MIN_BYTES = SEARCH_SIZE_100_MB_BYTES + 1L

/** 自定义 MB 大小输入上限（避免乘以 1MB 后发生 Long 溢出）。 */
private const val SEARCH_SIZE_MAX_MB_VALUE = 1_000_000_000.0

/**
 * 渲染高级条件的紧凑入口，收起整个高级区域时仍保留组件中的筛选值。
 *
 * @param filters 当前生效的结构化筛选条件。
 * @param activeFilterMenu 当前展开的筛选器。
 * @param onToggleFilterMenu 展开或收起筛选器。
 * @param onUpdateFilters 更新组件中的结构化条件。
 * @return 无返回值。
 */
@Composable
internal fun SearchAdvancedFilters(
    filters: SearchFilters,
    activeFilterMenu: SearchFilterMenu,
    onToggleFilterMenu: (SearchFilterMenu) -> Unit,
    onUpdateFilters: (SearchFilters) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SearchFilterPill(stringResource(Res.string.search_filter_type), filters.types.isNotEmpty(),
            activeFilterMenu == SearchFilterMenu.TYPE) { onToggleFilterMenu(SearchFilterMenu.TYPE) }
        SearchFilterPill(
            stringResource(Res.string.search_filter_modified),
            filters.modifiedPreset != null || filters.modifiedSinceEpochMillis != null,
            activeFilterMenu == SearchFilterMenu.MODIFIED,
        ) { onToggleFilterMenu(SearchFilterMenu.MODIFIED) }
        SearchFilterPill(stringResource(Res.string.search_filter_size),
            filters.minSizeBytes != null || filters.maxSizeBytes != null,
            activeFilterMenu == SearchFilterMenu.SIZE) { onToggleFilterMenu(SearchFilterMenu.SIZE) }
        SearchFilterPill(stringResource(Res.string.search_filter_content), filters.searchInContent, false) {
            onUpdateFilters(filters.copy(searchInContent = !filters.searchInContent))
        }
    }
}

/**
 * 渲染单个筛选器入口按钮。
 *
 * @param label 按钮展示名称。
 * @param isActive 筛选器是否已生效。
 * @param isExpanded 筛选器菜单是否已展开。
 * @param onClick 点击筛选器入口后的处理。
 */
@Composable
private fun SearchFilterPill(
    label: String,
    isActive: Boolean,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val borderColor = when {
        isExpanded -> palette.accent
        isActive -> palette.accent.copy(alpha = 0.5f)
        else -> palette.outlineVariant
    }
    val backgroundColor = when {
        isExpanded -> palette.surfaceVariant
        isActive -> palette.accent.copy(alpha = 0.12f)
        else -> palette.inputBackground
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (isActive || isExpanded) palette.accent else palette.foreground,
            fontWeight = if (isActive || isExpanded) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/**
 * 渲染已展开的结构化筛选条件选项。
 *
 * @param activeMenu 当前展开的筛选器菜单。
 * @param filters 当前生效的结构化筛选条件。
 * @param onUpdateFilters 更新结构化筛选条件。
 */
@Composable
internal fun SearchFilterExpandablePanel(
    activeMenu: SearchFilterMenu,
    filters: SearchFilters,
    onUpdateFilters: (SearchFilters) -> Unit,
) {
    if (activeMenu == SearchFilterMenu.NONE || activeMenu == SearchFilterMenu.CONTENT) return
    val palette = LocalOnyxPalette.current

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surfaceVariant, RoundedCornerShape(6.dp))
            .border(1.dp, palette.outlineVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (activeMenu) {
            SearchFilterMenu.TYPE -> SearchTypeFilterOptions(filters, onUpdateFilters)
            SearchFilterMenu.MODIFIED -> SearchModifiedFilterOptions(filters, onUpdateFilters)
            SearchFilterMenu.SIZE -> SearchSizeFilterOptions(filters, onUpdateFilters)

            SearchFilterMenu.NONE,
            SearchFilterMenu.CONTENT -> Unit
        }
    }
}

/**
 * 渲染文件类型筛选条件。
 *
 * @param filters 当前生效的结构化筛选条件。
 * @param onUpdateFilters 更新结构化筛选条件。
 */
@Composable
private fun SearchTypeFilterOptions(
    filters: SearchFilters,
    onUpdateFilters: (SearchFilters) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Text(
        text = stringResource(Res.string.search_filter_type) + ":",
        fontSize = 11.sp,
        color = palette.mutedForeground,
    )
    SearchChipOption(
        label = stringResource(Res.string.search_filter_file),
        selected = SearchFilterFileType.FILE in filters.types,
        onClick = {
            val next = if (SearchFilterFileType.FILE in filters.types) {
                filters.types - SearchFilterFileType.FILE
            } else {
                filters.types + SearchFilterFileType.FILE
            }
            onUpdateFilters(filters.copy(types = next))
        },
    )
    SearchChipOption(
        label = stringResource(Res.string.search_filter_directory),
        selected = SearchFilterFileType.DIRECTORY in filters.types,
        onClick = {
            val next = if (SearchFilterFileType.DIRECTORY in filters.types) {
                filters.types - SearchFilterFileType.DIRECTORY
            } else {
                filters.types + SearchFilterFileType.DIRECTORY
            }
            onUpdateFilters(filters.copy(types = next))
        },
    )
    if (filters.types.isNotEmpty()) {
        SearchChipOption(
            label = stringResource(Res.string.action_reset),
            selected = false,
            onClick = { onUpdateFilters(filters.copy(types = emptySet())) },
        )
    }
}

/**
 * 渲染按最近修改时间筛选的条件。
 *
 * @param filters 当前生效的结构化筛选条件。
 * @param onUpdateFilters 更新结构化筛选条件。
 */
@Composable
private fun SearchModifiedFilterOptions(
    filters: SearchFilters,
    onUpdateFilters: (SearchFilters) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val hasModifiedFilter = filters.modifiedPreset != null || filters.modifiedSinceEpochMillis != null
    fun selectPreset(preset: SearchModifiedPreset) {
        val nextPreset = if (filters.modifiedPreset == preset) null else preset
        onUpdateFilters(filters.copy(modifiedPreset = nextPreset, modifiedSinceEpochMillis = null))
    }
    Text(
        text = stringResource(Res.string.search_filter_modified) + ":",
        fontSize = 11.sp,
        color = palette.mutedForeground,
    )
    SearchChipOption(
        label = stringResource(Res.string.search_filter_today),
        selected = filters.modifiedPreset == SearchModifiedPreset.TODAY,
        onClick = { selectPreset(SearchModifiedPreset.TODAY) },
    )
    SearchChipOption(
        label = stringResource(Res.string.search_filter_week),
        selected = filters.modifiedPreset == SearchModifiedPreset.LAST_7_DAYS,
        onClick = { selectPreset(SearchModifiedPreset.LAST_7_DAYS) },
    )
    SearchChipOption(
        label = stringResource(Res.string.search_filter_month),
        selected = filters.modifiedPreset == SearchModifiedPreset.LAST_30_DAYS,
        onClick = { selectPreset(SearchModifiedPreset.LAST_30_DAYS) },
    )
    if (hasModifiedFilter) {
        SearchChipOption(
            label = stringResource(Res.string.action_reset),
            selected = false,
            onClick = { onUpdateFilters(filters.copy(modifiedPreset = null, modifiedSinceEpochMillis = null)) },
        )
    }
}

/**
 * 渲染按文件大小筛选的条件：三个大小预设区间严格互斥且再次点击可取消，并提供自定义最小/最大 MB 输入框。
 *
 * @param filters 当前生效的结构化筛选条件。
 * @param onUpdateFilters 更新结构化筛选条件。
 */
@Composable
private fun SearchSizeFilterOptions(
    filters: SearchFilters,
    onUpdateFilters: (SearchFilters) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val under1MbSelected =
        filters.minSizeBytes == null && filters.maxSizeBytes == SEARCH_SIZE_UNDER_1_MB_MAX_BYTES
    val range1To100MbSelected =
        filters.minSizeBytes == SEARCH_SIZE_1_MB_BYTES && filters.maxSizeBytes == SEARCH_SIZE_100_MB_BYTES
    val over100MbSelected =
        filters.minSizeBytes == SEARCH_SIZE_OVER_100_MB_MIN_BYTES && filters.maxSizeBytes == null

    var minMbText by remember { mutableStateOf(formatSizeBytesToMbText(filters.minSizeBytes)) }
    var maxMbText by remember { mutableStateOf(formatSizeBytesToMbText(filters.maxSizeBytes)) }

    LaunchedEffect(filters.minSizeBytes) {
        if (parseMbTextToBytes(minMbText) != filters.minSizeBytes) {
            minMbText = formatSizeBytesToMbText(filters.minSizeBytes)
        }
    }
    LaunchedEffect(filters.maxSizeBytes) {
        if (parseMbTextToBytes(maxMbText) != filters.maxSizeBytes) {
            maxMbText = formatSizeBytesToMbText(filters.maxSizeBytes)
        }
    }

    fun togglePreset(selected: Boolean, minBytes: Long?, maxBytes: Long?) {
        if (selected) {
            onUpdateFilters(filters.copy(minSizeBytes = null, maxSizeBytes = null))
        } else {
            onUpdateFilters(filters.copy(minSizeBytes = minBytes, maxSizeBytes = maxBytes))
        }
    }

    Text(
        text = stringResource(Res.string.search_filter_size) + ":",
        fontSize = 11.sp,
        color = palette.mutedForeground,
    )
    SearchChipOption(
        label = stringResource(Res.string.search_filter_size_under_1_mb),
        selected = under1MbSelected,
        modifier = Modifier.testTag("search-size-under-1mb"),
        onClick = { togglePreset(under1MbSelected, null, SEARCH_SIZE_UNDER_1_MB_MAX_BYTES) },
    )
    SearchChipOption(
        label = stringResource(Res.string.search_filter_size_1_to_100_mb),
        selected = range1To100MbSelected,
        modifier = Modifier.testTag("search-size-1-to-100mb"),
        onClick = { togglePreset(range1To100MbSelected, SEARCH_SIZE_1_MB_BYTES, SEARCH_SIZE_100_MB_BYTES) },
    )
    SearchChipOption(
        label = stringResource(Res.string.search_filter_size_over_100_mb),
        selected = over100MbSelected,
        modifier = Modifier.testTag("search-size-over-100mb"),
        onClick = { togglePreset(over100MbSelected, SEARCH_SIZE_OVER_100_MB_MIN_BYTES, null) },
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SearchSizeMbInput(
            value = minMbText,
            placeholder = stringResource(Res.string.label_search_size_custom_min_mb),
            testTag = "search-size-min-mb",
            onValueChange = { raw ->
                val sanitized = sanitizeDecimalMbInput(raw)
                minMbText = sanitized
                if (sanitized.isBlank()) {
                    onUpdateFilters(filters.copy(minSizeBytes = null))
                } else {
                    parseMbTextToBytes(sanitized)?.let { parsedBytes ->
                        onUpdateFilters(filters.copy(minSizeBytes = parsedBytes))
                    }
                }
            },
        )
        Text(text = "-", fontSize = 10.sp, color = palette.mutedForeground)
        SearchSizeMbInput(
            value = maxMbText,
            placeholder = stringResource(Res.string.label_search_size_custom_max_mb),
            testTag = "search-size-max-mb",
            onValueChange = { raw ->
                val sanitized = sanitizeDecimalMbInput(raw)
                maxMbText = sanitized
                if (sanitized.isBlank()) {
                    onUpdateFilters(filters.copy(maxSizeBytes = null))
                } else {
                    parseMbTextToBytes(sanitized)?.let { parsedBytes ->
                        onUpdateFilters(filters.copy(maxSizeBytes = parsedBytes))
                    }
                }
            },
        )
    }
    if (filters.minSizeBytes != null || filters.maxSizeBytes != null) {
        SearchChipOption(
            label = stringResource(Res.string.action_reset),
            selected = false,
            modifier = Modifier.testTag("search-size-reset"),
            onClick = {
                minMbText = ""
                maxMbText = ""
                onUpdateFilters(filters.copy(minSizeBytes = null, maxSizeBytes = null))
            },
        )
    }
}

/**
 * 渲染紧凑的 MB 数值输入框，用于自定义大小上下限。
 */
@Composable
private fun SearchSizeMbInput(
    value: String,
    placeholder: String,
    testTag: String,
    onValueChange: (String) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 10.sp, color = palette.foreground),
        cursorBrush = SolidColor(palette.accent),
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(palette.inputBackground)
            .border(1.dp, palette.outlineVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag(testTag),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        fontSize = 10.sp,
                        color = palette.disabledForeground,
                        maxLines = 1,
                    )
                }
                innerTextField()
            }
        },
    )
}

/**
 * 仅保留数字和首个小数点，过滤无效字符。
 */
private fun sanitizeDecimalMbInput(raw: String): String {
    val builder = StringBuilder(raw.length)
    var hasDot = false
    for (ch in raw) {
        when {
            ch in '0'..'9' -> builder.append(ch)
            ch == '.' && !hasDot -> {
                hasDot = true
                builder.append(ch)
            }
        }
    }
    return builder.toString()
}

/**
 * 将用户输入的 MB 字符串解析为非负字节数；空白或非法文本返回 null。
 */
internal fun parseMbTextToBytes(input: String): Long? {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed == ".") return null
    val mb = trimmed.toDoubleOrNull() ?: return null
    if (!mb.isFinite() || mb < 0.0 || mb > SEARCH_SIZE_MAX_MB_VALUE) return null
    return (mb * SEARCH_SIZE_1_MB_BYTES).roundToLong().coerceAtLeast(0L)
}

/**
 * 将字节数格式化为简洁的 MB 数值文本，用于反显外部设置的大小边界。
 */
internal fun formatSizeBytesToMbText(bytes: Long?): String {
    if (bytes == null) return ""
    if (bytes % SEARCH_SIZE_1_MB_BYTES == 0L) {
        return (bytes / SEARCH_SIZE_1_MB_BYTES).toString()
    }
    val mb = bytes.toDouble() / SEARCH_SIZE_1_MB_BYTES.toDouble()
    val formatted = String.format(java.util.Locale.ROOT, "%.2f", mb)
        .trimEnd('0')
        .trimEnd('.')
    return formatted
}

/**
 * 渲染筛选器面板中的单个可选条件。
 *
 * @param label 条件展示名称。
 * @param selected 条件是否已选中。
 * @param modifier 附加布局或测试标识修饰符。
 * @param onClick 点击条件后的处理。
 */
@Composable
private fun SearchChipOption(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) palette.accent else palette.inputBackground)
            .border(1.dp, if (selected) palette.accent else palette.outlineVariant, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = if (selected) palette.onAccent else palette.foreground,
        )
    }
}
