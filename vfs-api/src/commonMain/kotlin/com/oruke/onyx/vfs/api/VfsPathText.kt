package com.oruke.onyx.vfs.api

/**
 * 解析后的轻量 VFS URI 结构，避免直接依赖易截断 `#`/`?` 或拒绝 `[`/`]` 的单参 `URI` 构造。
 *
 * @property scheme 小写协议名（如 `webdav`、`webdavs`、`s3`、`smb`）。
 * @property userInfo 可选用户信息部分（不含 `@`）。
 * @property host 主机名（或 S3 Bucket 名，IPv6 字面量保留外层 `[...]`）。
 * @property port 显式端口；未指定时为 `-1`。
 * @property rawPath 以 `/` 开头的路径部分（保留原始编码或原始字符）。
 */
data class ParsedVfsUri(
    val scheme: String,
    val userInfo: String?,
    val host: String,
    val port: Int,
    val rawPath: String,
) {
    /** 由 `userInfo`、`host`、`port` 拼装出的 authority 文本。 */
    val authority: String
        get() = buildString {
            if (!userInfo.isNullOrEmpty()) {
                append(userInfo).append('@')
            }
            append(host)
            if (port >= 0) {
                append(':').append(port)
            }
        }

    /** 逐段百分号解码后的路径文本（保留 `/` 分隔符，`+` 保持原样不转为空格）。 */
    val decodedPath: String
        get() = rawPath.split('/').joinToString("/") { segment -> decodeVfsPercentEncoding(segment) }

    /**
     * 按 RFC 3986 规范重新拼装 ASCII URI 字符串。
     *
     * @param overrideScheme 可选覆盖协议名。
     * @param includeUserInfo 是否保留用户信息，默认保留。
     * @param overrideDecodedPath 可选使用新的未编码路径替换原路径（逐段强制编码）。
     * @return 标准 RFC 3986 ASCII URI 文本。
     */
    fun toAsciiString(
        overrideScheme: String = scheme,
        includeUserInfo: Boolean = true,
        overrideDecodedPath: String? = null,
    ): String {
        val encodedPath = if (overrideDecodedPath != null) {
            encodeVfsPath(overrideDecodedPath)
        } else {
            encodeVfsPathPreservingPercent(rawPath)
        }
        return buildString {
            append(overrideScheme.lowercase())
            append("://")
            if (includeUserInfo && !userInfo.isNullOrEmpty()) {
                append(userInfo).append('@')
            }
            append(host)
            if (port >= 0) {
                append(':').append(port)
            }
            append(if (encodedPath.startsWith('/')) encodedPath else "/$encodedPath")
        }
    }
}

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
 * 按 RFC 3986 规则对单个 VFS 路径段进行 UTF-8 百分号编码。
 *
 * 保留 `unreserved` 与路径安全子分隔符（含 `+`），对空格、`#`、`?`、`%`、`[`、`]` 以及非 ASCII 字符
 * 编码为大写 `%XX`，确保往返编解码零信息损失且不会将 `+` 与空格混淆。
 *
 * @param segment 未编码的原始文件名或目录段。
 * @return 可安全放入 URI 路径中的编码片段。
 */
fun encodeVfsPathSegment(segment: String): String {
    if (segment.isEmpty()) return ""
    val bytes = segment.encodeToByteArray()
    val builder = StringBuilder(bytes.size * 2)
    for (raw in bytes) {
        val unsigned = raw.toInt() and 0xFF
        if (isSafePathSegmentByte(unsigned)) {
            builder.append(unsigned.toChar())
        } else {
            appendPercentEncodedByte(builder, unsigned)
        }
    }
    return builder.toString()
}

/**
 * 按 `/` 分隔逐段编码未编码的完整 VFS 路径。
 *
 * @param decodedPath 未编码的路径文本（如 `/docs/c++ [2026]/v1#draft.txt`）。
 * @return 保留 `/` 结构且各段按 RFC 3986 编码后的 ASCII 路径。
 */
fun encodeVfsPath(decodedPath: String): String {
    if (decodedPath.isEmpty()) return "/"
    return decodedPath.split('/').joinToString("/") { segment -> encodeVfsPathSegment(segment) }
}

/**
 * 规范化可能已包含合法 `%XX` 转义的路径文本：保留现有合法 `%XX`，并对其余不安全字符做 RFC 3986 编码。
 *
 * 避免对已规范化的路径发生二次编码（`%20` → `%2520`），同时安全转义孤立 `%`、`#`、`?`、`[`、`]`、空格与中文。
 *
 * @param rawPath 原始或半编码路径文本。
 * @return 标准 RFC 3986 ASCII 路径。
 */
fun encodeVfsPathPreservingPercent(rawPath: String): String {
    if (rawPath.isEmpty()) return "/"
    val normalized = if (rawPath.startsWith('/')) rawPath else "/$rawPath"
    return normalized.split('/').joinToString("/") { segment -> encodeSegmentPreservingPercent(segment) }
}

/**
 * 按 RFC 3986 规则对路径文本执行 UTF-8 百分号解码。
 *
 * 与表单解码器 `URLDecoder` 不同，本函数严格保留字面量 `+`（不转为空格），
 * 并对孤立非法 `%` 宽容保留原字面字符而不抛出异常。
 *
 * @param encoded 百分号编码文本。
 * @return UTF-8 解码后的原始文本。
 */
fun decodeVfsPercentEncoding(encoded: String): String {
    if ('%' !in encoded) return encoded
    val output = StringBuilder(encoded.length)
    val buffer = ByteArray(encoded.length)
    var index = 0
    while (index < encoded.length) {
        if (encoded[index] == '%' && index + 2 < encoded.length) {
            val high = hexValue(encoded[index + 1])
            val low = hexValue(encoded[index + 2])
            if (high >= 0 && low >= 0) {
                var byteCount = 0
                while (index + 2 < encoded.length && encoded[index] == '%') {
                    val h = hexValue(encoded[index + 1])
                    val l = hexValue(encoded[index + 2])
                    if (h < 0 || l < 0) break
                    buffer[byteCount++] = ((h shl 4) or l).toByte()
                    index += 3
                }
                output.append(buffer.decodeToString(0, byteCount))
                continue
            }
        }
        output.append(encoded[index])
        index += 1
    }
    return output.toString()
}

/**
 * 将 `scheme://authority/path` 形式的 VFS 位置拆分为结构化组件。
 *
 * 整个路径部分（含 `#`、`?`、`%`、`[`、`]` 等字符）均视为 VFS 路径而非 HTTP query/fragment，
 * 避免含特殊字符的远程文件名被错误截断。
 *
 * @param location VFS 位置文本。
 * @return 解析成功返回 [ParsedVfsUri]，格式非法（缺少 scheme 或 host）时返回 `null`。
 */
fun parseVfsUriOrNull(location: String): ParsedVfsUri? {
    val schemeSep = location.indexOf("://")
    if (schemeSep <= 0) return null
    val scheme = location.substring(0, schemeSep).trim().lowercase()
    if (scheme.isEmpty() || !scheme.all { ch -> ch.isLetterOrDigit() || ch == '+' || ch == '-' || ch == '.' }) {
        return null
    }
    val afterScheme = location.substring(schemeSep + 3)
    val pathStart = afterScheme.indexOf('/')
    val rawAuthority = if (pathStart >= 0) afterScheme.substring(0, pathStart) else afterScheme
    val rawPath = if (pathStart >= 0) afterScheme.substring(pathStart) else "/"
    if (rawAuthority.isEmpty()) return null

    val atIndex = rawAuthority.lastIndexOf('@')
    val userInfo = if (atIndex >= 0) rawAuthority.substring(0, atIndex) else null
    val hostPort = if (atIndex >= 0) rawAuthority.substring(atIndex + 1) else rawAuthority
    if (hostPort.isEmpty()) return null

    val host: String
    val port: Int
    if (hostPort.startsWith('[')) {
        val closeBracket = hostPort.indexOf(']')
        if (closeBracket <= 1) return null
        host = hostPort.substring(0, closeBracket + 1)
        val remainder = hostPort.substring(closeBracket + 1)
        if (remainder.isEmpty()) {
            port = -1
        } else if (remainder.startsWith(':')) {
            port = remainder.substring(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        } else {
            return null
        }
    } else {
        val colonIndex = hostPort.lastIndexOf(':')
        if (colonIndex >= 0) {
            host = hostPort.substring(0, colonIndex)
            val portText = hostPort.substring(colonIndex + 1)
            port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        } else {
            host = hostPort
            port = -1
        }
    }
    if (host.isBlank() || host.any { ch -> ch.isWhitespace() || ch == '?' || ch == '#' }) {
        return null
    }
    return ParsedVfsUri(
        scheme = scheme,
        userInfo = userInfo,
        host = host,
        port = port,
        rawPath = rawPath.ifEmpty { "/" },
    )
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

/** 对单段路径保留已合法 `%XX` 并编码其余不安全字符（完整支持 UTF-16 代理对 / Emoji）。 */
private fun encodeSegmentPreservingPercent(segment: String): String {
    if (segment.isEmpty()) return ""
    val builder = StringBuilder(segment.length * 2)
    var index = 0
    while (index < segment.length) {
        val ch = segment[index]
        if (ch == '%' && index + 2 < segment.length &&
            hexValue(segment[index + 1]) >= 0 && hexValue(segment[index + 2]) >= 0
        ) {
            builder.append('%')
            builder.append(segment[index + 1].uppercaseChar())
            builder.append(segment[index + 2].uppercaseChar())
            index += 3
        } else {
            val code = ch.code
            if (code < 128 && isSafePathSegmentByte(code)) {
                builder.append(ch)
                index += 1
            } else {
                val endIndex = if (ch.isHighSurrogate() && index + 1 < segment.length && segment[index + 1].isLowSurrogate()) {
                    index + 2
                } else {
                    index + 1
                }
                val utf8 = segment.substring(index, endIndex).encodeToByteArray()
                for (b in utf8) {
                    appendPercentEncodedByte(builder, b.toInt() and 0xFF)
                }
                index = endIndex
            }
        }
    }
    return builder.toString()
}

/** 判断单字节是否属于 RFC 3986 路径段可直接保留的安全 ASCII 字符（含 `+`）。 */
private fun isSafePathSegmentByte(unsigned: Int): Boolean {
    return (unsigned in 'A'.code..'Z'.code) ||
        (unsigned in 'a'.code..'z'.code) ||
        (unsigned in '0'.code..'9'.code) ||
        unsigned == '-'.code ||
        unsigned == '.'.code ||
        unsigned == '_'.code ||
        unsigned == '~'.code ||
        unsigned == '!'.code ||
        unsigned == '$'.code ||
        unsigned == '&'.code ||
        unsigned == '\''.code ||
        unsigned == '('.code ||
        unsigned == ')'.code ||
        unsigned == '*'.code ||
        unsigned == '+'.code ||
        unsigned == ','.code ||
        unsigned == ';'.code ||
        unsigned == '='.code ||
        unsigned == ':'.code ||
        unsigned == '@'.code
}

/** 将无符号字节追加为大写 `%XX` 十六进制转义。 */
private fun appendPercentEncodedByte(builder: StringBuilder, unsigned: Int) {
    val hexChars = "0123456789ABCDEF"
    builder.append('%')
    builder.append(hexChars[(unsigned ushr 4) and 0x0F])
    builder.append(hexChars[unsigned and 0x0F])
}

/** 解析单个十六进制字符数值，非法字符返回 `-1`。 */
private fun hexValue(ch: Char): Int = when (ch) {
    in '0'..'9' -> ch - '0'
    in 'a'..'f' -> ch - 'a' + 10
    in 'A'..'F' -> ch - 'A' + 10
    else -> -1
}
