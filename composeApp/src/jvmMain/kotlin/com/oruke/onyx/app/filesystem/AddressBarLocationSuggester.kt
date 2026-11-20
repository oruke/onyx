package com.oruke.onyx.app.filesystem

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 地址栏路径自动补全默认最大返回条数。 */
private const val DEFAULT_SUGGESTION_LIMIT = 8

/** 单次目录扫描检查的最大条目数，防止超大目录阻塞自动补全。 */
private const val MAX_SCANNED_DIRECTORY_ENTRIES = 2_000

/**
 * 为地址栏文本编辑生成路径自动补全建议：合并已知位置（收藏、最近访问、当前可见目录）与本地子目录前缀枚举。
 *
 * 所有磁盘目录枚举均切至 [Dispatchers.IO] 执行，先在内存中做文件名前缀过滤再检查目录属性以减少磁盘 stat，
 * 并跳过远程 URI 与 UNC 网络路径以避免慢 I/O 阻塞。
 *
 * @param rawInput 用户当前输入的地址栏草稿文本。
 * @param knownLocations 收藏、最近访问及当前目录已知子目录位置集合。
 * @param limit 最大建议条数。
 * @return 去重且按匹配相关度排序的建议路径列表。
 */
internal suspend fun suggestAddressBarLocations(
    rawInput: String,
    knownLocations: List<String>,
    limit: Int = DEFAULT_SUGGESTION_LIMIT,
): List<String> {
    val query = rawInput.trim()
    if (query.isEmpty() || limit <= 0) return emptyList()
    val results = LinkedHashSet<String>()
    val lowerQuery = query.lowercase()

    // 优先收集前缀匹配的已知位置（收藏、最近访问、当前目录已加载子目录）。
    knownLocations.asSequence()
        .map(String::trim)
        .filter { candidate ->
            candidate.isNotEmpty() &&
                !candidate.equals(query, ignoreCase = true) &&
                candidate.lowercase().startsWith(lowerQuery)
        }
        .forEach { candidate -> if (results.size < limit) results += candidate }

    if (results.size < limit && isSafeLocalPrefixForListing(query)) {
        val localSuggestions = withContext(Dispatchers.IO) {
            listMatchingLocalDirectories(query, limit - results.size)
        }
        localSuggestions.forEach { candidate ->
            if (results.size < limit && !candidate.equals(query, ignoreCase = true)) {
                results += candidate
            }
        }
    }

    // 补充包含匹配（如直接输入文件夹名片段）作为次优先级建议。
    if (results.size < limit) {
        knownLocations.asSequence()
            .map(String::trim)
            .filter { candidate ->
                candidate.isNotEmpty() &&
                    !candidate.equals(query, ignoreCase = true) &&
                    candidate.lowercase().contains(lowerQuery)
            }
            .forEach { candidate -> if (results.size < limit) results += candidate }
    }
    return results.toList()
}

/**
 * 判断输入是否适合在后台做本地文件系统子目录枚举，排除协议 URI 与 UNC 共享根路径。
 *
 * @param query 已去首尾空白的输入路径。
 * @return 允许安全枚举本地子目录时返回 `true`。
 */
private fun isSafeLocalPrefixForListing(query: String): Boolean {
    if (query.contains("://")) return false
    if (query.startsWith("\\\\") || query.startsWith("//")) return false
    return query.contains('\\') || query.contains('/')
}

/**
 * 在 [Dispatchers.IO] 线程安全枚举输入路径对应的父目录下匹配前缀的子目录。
 *
 * 先在内存中完成文件名前缀匹配再调用 [Files.isDirectory]，并施加扫描上限与协程取消检查。
 *
 * @param query 已去首尾空白的本地路径前缀。
 * @param maxCount 最多返回的匹配子目录数。
 * @return 按字母顺序排序的匹配目录绝对路径列表。
 */
@Suppress("TooGenericExceptionCaught") // 路径补全属于尽力而为辅助能力，任何文件系统权限或路径异常都应静默忽略。
private suspend fun listMatchingLocalDirectories(query: String, maxCount: Int): List<String> {
    if (maxCount <= 0) return emptyList()
    val endsWithSeparator = query.endsWith('\\') || query.endsWith('/')
    val (parentText, namePrefix) = if (endsWithSeparator) {
        query to ""
    } else {
        val lastSep = maxOf(query.lastIndexOf('\\'), query.lastIndexOf('/'))
        if (lastSep < 0) return emptyList()
        query.substring(0, lastSep + 1) to query.substring(lastSep + 1)
    }
    val parentPath = try {
        Path.of(parentText)
    } catch (_: InvalidPathException) {
        return emptyList()
    }
    if (!Files.isDirectory(parentPath)) return emptyList()
    val matches = mutableListOf<String>()
    var scanned = 0
    return try {
        Files.newDirectoryStream(parentPath).use { stream ->
            for (child in stream) {
                currentCoroutineContext().ensureActive()
                scanned += 1
                if (scanned > MAX_SCANNED_DIRECTORY_ENTRIES) break
                val name = child.fileName?.toString() ?: continue
                // 先在内存中做前缀匹配，避免对不匹配条目发起多余的磁盘 stat 调用。
                if (namePrefix.isNotEmpty() && !name.startsWith(namePrefix, ignoreCase = true)) continue
                if (Files.isDirectory(child)) {
                    matches += child.toString()
                    if (matches.size >= maxCount * 4) break
                }
            }
        }
        matches.sortWith(String.CASE_INSENSITIVE_ORDER)
        if (matches.size > maxCount) matches.subList(0, maxCount).toList() else matches
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }
}
