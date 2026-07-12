package com.oruke.onyx.vfs.archive

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
