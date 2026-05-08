package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.SortDirection
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind

/**
 * 文件条目排序工具 — 纯函数，无状态。
 *
 * 排序规则：目录始终在文件前面（无论排序方向）。
 */
object EntrySorter {

    /**
     * 对给定条目列表按指定排序规则排序。
     * 目录始终排在文件前方。
     */
    fun sortEntries(
        entries: List<VFile>,
        sort: DetailsSort,
    ): List<VFile> {
        val comparator = when (sort.column) {
            DetailsColumn.NAME -> compareBy<VFile> { it.name.lowercase() }
            DetailsColumn.TYPE -> compareBy<VFile> {
                if (it.kind == VFileKind.DIRECTORY) "" else it.name.substringAfterLast('.', "").lowercase()
            }
            DetailsColumn.SIZE -> compareBy<VFile> { it.sizeBytes ?: -1L }
            DetailsColumn.MODIFIED -> compareBy<VFile> { it.modifiedAtEpochMillis ?: Long.MIN_VALUE }
        }

        val sorted = entries.sortedWith(
            compareByDescending<VFile> { it.kind == VFileKind.DIRECTORY }
                .then(comparator)
        )

        return when (sort.direction) {
            SortDirection.ASCENDING -> sorted
            SortDirection.DESCENDING -> {
                val directories = sorted.filter { it.kind == VFileKind.DIRECTORY }.reversed()
                val files = sorted.filter { it.kind == VFileKind.FILE }.reversed()
                directories + files
            }
        }
    }

    /**
     * 根据隐藏项、过滤条件和排序规则，返回最终可见的排序条目列表。
     */
    fun visibleSortedEntries(
        allEntries: List<VFile>,
        showHiddenItems: Boolean,
        sort: DetailsSort,
        filterQuery: String,
    ): List<VFile> {
        val visibleEntries = if (showHiddenItems) allEntries else allEntries.filterNot { it.hidden }
        val filteredEntries = if (filterQuery.isBlank()) {
            visibleEntries
        } else {
            val normalizedQuery = filterQuery.lowercase()
            visibleEntries.filter { entry ->
                entry.name.lowercase().contains(normalizedQuery)
            }
        }
        return sortEntries(filteredEntries, sort)
    }
}
