package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.SortDirection
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind

/**
 * 文件条目排序工具 — 纯函数，支持自然数字排序与目录优先配置。
 */
object EntrySorter {
    /** 全局默认是否启用自然数字排序（随应用设置同步）。 */
    @Volatile
    var defaultNaturalSortEnabled: Boolean = true

    /** 全局默认是否将文件夹始终排在文件前面（随应用设置同步）。 */
    @Volatile
    var defaultDirectoriesFirst: Boolean = true

    /**
     * 对给定条目列表按指定排序规则排序。
     *
     * @param entries 待排序文件条目列表。
     * @param sort 当前排序列与方向。
     * @param naturalSortEnabled 是否按自然数字顺序比较文件名（例如 `2.txt` 排在 `10.txt` 前）。
     * @param directoriesFirst 是否始终将目录排在文件前方。
     * @return 已排序的条目列表。
     */
    fun sortEntries(
        entries: List<VFile>,
        sort: DetailsSort,
        naturalSortEnabled: Boolean = defaultNaturalSortEnabled,
        directoriesFirst: Boolean = defaultDirectoriesFirst,
    ): List<VFile> {
        val comparator: Comparator<VFile> = when (sort.column) {
            DetailsColumn.NAME -> Comparator { left, right ->
                if (naturalSortEnabled) {
                    compareNaturalFileName(left.name, right.name)
                } else {
                    left.name.lowercase().compareTo(right.name.lowercase())
                }
            }

            DetailsColumn.TYPE -> compareBy<VFile> {
                if (it.kind == VFileKind.DIRECTORY) "" else it.name.substringAfterLast('.', "").lowercase()
            }

            DetailsColumn.SIZE -> compareBy<VFile> { it.sizeBytes ?: -1L }
            DetailsColumn.MODIFIED -> compareBy<VFile> { it.modifiedAtEpochMillis ?: Long.MIN_VALUE }
        }

        if (!directoriesFirst) {
            val sorted = entries.sortedWith(comparator)
            return when (sort.direction) {
                SortDirection.ASCENDING -> sorted
                SortDirection.DESCENDING -> sorted.reversed()
            }
        }

        val sorted = entries.sortedWith(
            compareByDescending<VFile> { it.kind == VFileKind.DIRECTORY }
                .then(comparator),
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
     *
     * @param allEntries 目录全部条目。
     * @param showHiddenItems 是否包含隐藏条目。
     * @param sort 排序列与方向。
     * @param filterQuery 过滤文本。
     * @param naturalSortEnabled 是否启用自然数字排序。
     * @param directoriesFirst 是否将文件夹始终排在前面。
     * @return 可见且已排序的条目列表。
     */
    fun visibleSortedEntries(
        allEntries: List<VFile>,
        showHiddenItems: Boolean,
        sort: DetailsSort,
        filterQuery: String,
        naturalSortEnabled: Boolean = defaultNaturalSortEnabled,
        directoriesFirst: Boolean = defaultDirectoriesFirst,
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
        return sortEntries(
            entries = filteredEntries,
            sort = sort,
            naturalSortEnabled = naturalSortEnabled,
            directoriesFirst = directoriesFirst,
        )
    }

    /**
     * 按自然数字顺序（Natural Sort）比较两个文件名，忽略大小写。
     *
     * 连续数字片段按数值大小比较（支持任意长度数字而不溢出），例如 `file2.txt < file10.txt`。
     *
     * @param a 左侧文件名。
     * @param b 右侧文件名。
     * @return 比较结果（负数表示 `a < b`，正数表示 `a > b`，0 表示相等）。
     */
    internal fun compareNaturalFileName(a: String, b: String): Int {
        var ia = 0
        var ib = 0
        var leadingZeroTieBreaker = 0
        var result = 0
        while (result == 0 && ia < a.length && ib < b.length) {
            val ca = a[ia]
            val cb = b[ib]
            if (ca.isDigit() && cb.isDigit()) {
                val endA = findDigitRunEnd(a, ia)
                val endB = findDigitRunEnd(b, ib)
                result = compareDigitSlices(a, ia, endA, b, ib, endB)
                if (result == 0 && leadingZeroTieBreaker == 0) {
                    leadingZeroTieBreaker = (endA - ia).compareTo(endB - ib)
                }
                ia = endA
                ib = endB
            } else {
                result = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                ia++
                ib++
            }
        }
        return when {
            result != 0 -> result
            ia < a.length || ib < b.length -> (a.length - ia).compareTo(b.length - ib)
            leadingZeroTieBreaker != 0 -> leadingZeroTieBreaker
            else -> a.compareTo(b)
        }
    }

    private fun findDigitRunEnd(text: String, start: Int): Int {
        var idx = start
        while (idx < text.length && text[idx].isDigit()) {
            idx++
        }
        return idx
    }

    private fun compareDigitSlices(
        a: String,
        startA: Int,
        endA: Int,
        b: String,
        startB: Int,
        endB: Int,
    ): Int {
        var nzA = startA
        while (nzA < endA && a[nzA] == '0') nzA++
        var nzB = startB
        while (nzB < endB && b[nzB] == '0') nzB++
        val sigLenA = endA - nzA
        val sigLenB = endB - nzB
        if (sigLenA != sigLenB) {
            return sigLenA.compareTo(sigLenB)
        }
        var diff = 0
        for (k in 0 until sigLenA) {
            diff = a[nzA + k].compareTo(b[nzB + k])
            if (diff != 0) break
        }
        return diff
    }
}
