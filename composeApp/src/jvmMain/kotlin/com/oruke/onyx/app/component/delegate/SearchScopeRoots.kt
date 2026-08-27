package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.core.model.SearchScope
import com.oruke.onyx.core.model.VFile
import java.nio.file.FileSystems

/**
 * 依据搜索范围构造搜索根列表。
 *
 * @param scope 搜索范围。
 * @param baseRoot 当前活动面板位置；范围回退时的默认根。
 * @param favorites 用户收藏位置。
 * @param allRoots 文件系统根；仅 [SearchScope.ALL_ROOTS] 使用。
 * @return 去重后的非空搜索根列表；无法构造时回退到 [baseRoot]。
 */
internal fun buildSearchRoots(
    scope: SearchScope,
    baseRoot: String,
    favorites: List<String>,
    allRoots: List<String>,
): List<String> {
    return when (scope) {
        SearchScope.CURRENT_DIRECTORY -> listOf(baseRoot)
        SearchScope.FAVORITES -> favorites
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?: listOf(baseRoot)
        SearchScope.ALL_ROOTS -> allRoots
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?: listOf(baseRoot)
    }
}

/**
 * 读取宿主文件系统全部根位置（jvm 专用）。
 *
 * @return 规范化、去重、排序后的文件系统根字符串。
 */
internal fun jvmFilesystemRoots(): List<String> {
    return FileSystems.getDefault()
        .rootDirectories
        .map { rootPath -> rootPath.normalize().toAbsolutePath() }
        .distinct()
        .sortedBy { rootPath -> rootPath.toString() }
        .map { rootPath -> rootPath.toString().ifBlank { "/" } }
}

/**
 * 归并多根搜索结果：保持每个根内顺序，按根顺序拼接并统一按条目 ID 去重。
 *
 * @param orderedRoots 按根顺序排列的 `(根位置, 该根当前结果)` 列表。
 * @return 去重后的合并结果；重复条目保留首次出现。
 */
internal fun mergeSearchResults(orderedRoots: List<Pair<String, List<VFile>>>): List<VFile> {
    return orderedRoots.flatMap { pair -> pair.second }.distinctBy { entry -> entry.id }
}
