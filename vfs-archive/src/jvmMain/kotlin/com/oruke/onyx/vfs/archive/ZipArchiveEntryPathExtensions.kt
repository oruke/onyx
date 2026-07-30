package com.oruke.onyx.vfs.archive

/**
 * 将 VFS 条目名称规范化为单个 ZIP 路径段。
 *
 * @return 不含路径分隔符的 ZIP 路径段。
 */
internal fun String.toZipArchiveEntrySegment(): String {
    val segment = trim()
    require(segment.isNotBlank()) { "压缩包条目名称不能为空" }
    require(segment != "." && segment != "..") { "压缩包条目名称不能为相对路径: $segment" }
    require('/' !in segment && '\\' !in segment) { "压缩包条目名称不能包含路径分隔符: $segment" }
    return segment
}

/**
 * 向现有 ZIP 路径追加一个子条目名称。
 *
 * @param childName 子条目的原始名称。
 * @return 拼接后的 ZIP 路径。
 */
internal fun String.appendZipArchiveEntrySegment(childName: String): String {
    return "${trimEnd('/')}/${childName.toZipArchiveEntrySegment()}"
}

/**
 * 为 ZIP 根条目生成不与既有根路径冲突的名称。
 *
 * @param existingPaths 已分配的根 ZIP 路径。
 * @return 可安全写入 ZIP 的根路径。
 */
internal fun String.nextAvailableZipArchiveRootPath(existingPaths: Set<String>): String {
    val normalized = toZipArchiveEntrySegment()
    if (normalized !in existingPaths) return normalized
    val extensionIndex = normalized.lastIndexOf('.').takeIf { index -> index > 0 }
    val baseName = extensionIndex?.let { index -> normalized.substring(0, index) } ?: normalized
    val extension = extensionIndex?.let { index -> normalized.substring(index) }.orEmpty()
    var copyIndex = 2
    while (true) {
        val candidate = "$baseName ($copyIndex)$extension"
        if (candidate !in existingPaths) return candidate
        copyIndex += 1
    }
}
