package com.oruke.onyx.app.filesystem

/**
 * 为 VFS 目录位置补齐尾部斜杠，统一 SMB/WebDAV/S3 等 provider 的目录路径表达。
 *
 * @return 以 `/` 结尾的路径文本。
 */
fun String.withVfsTrailingSlash(): String {
    return if (endsWith('/')) this else "$this/"
}

/**
 * 将路径中的空格编码为 URL 可接受的 `%20`。
 *
 * @return 空格已编码的路径文本。
 */
fun String.encodeVfsSpaces(): String {
    return replace(" ", "%20")
}

/**
 * 生成 KEEP_BOTH 冲突策略下的副本名称。
 *
 * @param index 副本序号，`1` 表示第一个副本。
 * @return 带副本后缀的名称。
 */
fun String.withVfsCopySuffix(index: Int): String {
    val dotIndex = lastIndexOf('.').takeIf { dot -> dot > 0 }
    return if (dotIndex == null) {
        "$this ($index)"
    } else {
        substring(0, dotIndex) + " ($index)" + substring(dotIndex)
    }
}
