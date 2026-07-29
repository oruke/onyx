package com.oruke.onyx.vfs.archive

import java.nio.file.Path

/**
 * 判断路径是否为 zstd 压缩的 tar 包。
 *
 * @return `true` 表示应走系统 tar 处理。
 */
internal fun String.isTarZstdArchive(): Boolean {
    val lower = lowercase()
    return lower.endsWith(".tar.zst") || lower.endsWith(".tzst")
}

/**
 * 判断路径是否为当前支持内部写入的 ZIP 系列压缩包。
 *
 * @return `true` 表示可使用 ZIP 写入服务修改内部条目。
 */
internal fun String.isMutableZipArchive(): Boolean {
    val lower = lowercase()
    return lower.endsWith(".zip") || lower.endsWith(".cbz") || lower.endsWith(".epub")
}

/**
 * 返回压缩包源位置的父位置，同时兼容本地路径与 VFS 协议位置。
 *
 * @return 父位置；源位置没有可导航父级时返回 `null`。
 */
internal fun String.archiveSourceParentLocation(): String? {
    val normalized = trimEnd('/')
    val schemeEnd = normalized.indexOf("://")
    return if (schemeEnd < 0) {
        Path.of(normalized).parent?.toString()
    } else {
        val authorityEnd = normalized.indexOf('/', startIndex = schemeEnd + SCHEME_SEPARATOR_LENGTH)
        val parentSeparator = normalized.lastIndexOf('/')
        normalized.substring(0, parentSeparator)
            .takeIf { parent -> authorityEnd >= 0 && parent.length >= authorityEnd }
    }
}

/**
 * 返回压缩包源位置中适合界面展示的文件名。
 *
 * @return 本地或 VFS 位置的末级名称；无法取得时返回原位置。
 */
internal fun String.archiveSourceFileName(): String {
    val normalized = trimEnd('/')
    return if (normalized.contains("://")) {
        normalized.substringAfterLast('/').takeIf(String::isNotBlank) ?: this
    } else {
        Path.of(normalized).fileName?.toString() ?: this
    }
}

/** VFS 协议分隔符 `://` 的字符数。 */
private const val SCHEME_SEPARATOR_LENGTH = 3
