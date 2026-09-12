package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.filesystem.RemoteVfsLocationParser
import com.oruke.onyx.core.model.SearchScope
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.archive.ArchiveService
import java.nio.file.FileSystems

/**
 * 依据搜索范围构造搜索根列表。
 *
 * @param scope 搜索范围。
 * @param baseRoot 当前会话显式绑定的位置。
 * @param favorites 用户收藏位置。
 * @param allRoots 文件系统根；仅 [SearchScope.ALL_ROOTS] 使用。
 * @param includeRemoteLocations 是否允许扫描远程位置。
 * @return 已授权的去重搜索根；范围为空时不隐式切换到其他目录。
 */
internal fun buildSearchRoots(
    scope: SearchScope,
    baseRoot: String,
    favorites: List<String>,
    allRoots: List<String>,
    includeRemoteLocations: Boolean = false,
): List<String> {
    val candidates = when (scope) {
        SearchScope.CURRENT_DIRECTORY -> listOf(baseRoot)
        SearchScope.FAVORITES -> favorites
        SearchScope.ALL_ROOTS -> allRoots
    }
    return candidates.map(String::trim).filter(String::isNotEmpty).distinct()
        .filter { location -> includeRemoteLocations || !location.isRemoteSearchLocation() }
}

/**
 * 依据现有协议解析器和网络路径结构判断搜索位置是否需要远程访问授权。
 *
 * @receiver 已去除首尾空白的 VFS 位置。
 * @return 远程协议、UNC、file 网络地址或以它们为来源的压缩包返回 `true`。
 */
internal fun String.isRemoteSearchLocation(): Boolean {
    val source = ArchiveService.parseArchiveLocation(this)?.first
    return when {
        source != null && source != this -> source.isRemoteSearchLocation()
        RemoteVfsLocationParser.parse(this) != null -> true
        startsWith("\\\\") || startsWith("//") -> true
        startsWith("file://", ignoreCase = true) -> substringAfter("://").let { fileLocation ->
            // 空 authority 后的双斜线仍表示 UNC，单斜线才是本地绝对路径。
            fileLocation.startsWith("//") || fileLocation.substringBefore('/').isNotEmpty()
        }
        else -> false
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
