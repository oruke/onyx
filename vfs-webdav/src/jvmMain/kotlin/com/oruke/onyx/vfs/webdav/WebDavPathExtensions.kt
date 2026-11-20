package com.oruke.onyx.vfs.webdav

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProviderNotFoundException
import com.oruke.onyx.vfs.api.decodeVfsPercentEncoding
import com.oruke.onyx.vfs.api.encodeVfsPathPreservingPercent
import com.oruke.onyx.vfs.api.encodeVfsPathSegment
import com.oruke.onyx.vfs.api.parseVfsUriOrNull
import com.oruke.onyx.vfs.api.withVfsTrailingSlash
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 将 WebDAV VFS 位置转换为 RFC 3986 规范编码的 HTTP(S) 请求地址。
 *
 * 直接按结构拆解协议、主机与路径并保留合法 `%XX` 转义，避免含 `#`、`?`、`%`、`[`、`]`、`+`
 * 的文件名被单参 `URI` 截断或抛异常。
 *
 * @return HTTP 或 HTTPS 请求地址。
 */
internal fun String.toHttpWebDavUrl(): String {
    val parsed = parseVfsUriOrNull(this) ?: throw VfsProviderNotFoundException(this)
    val httpScheme = when (parsed.scheme) {
        "webdav" -> "http"
        "webdavs" -> "https"
        else -> throw VfsProviderNotFoundException(this)
    }
    return parsed.toAsciiString(overrideScheme = httpScheme, includeUserInfo = true)
}

/**
 * 按 RFC 3986 规则执行路径百分号解码。
 *
 * 与表单解码器 `URLDecoder` 不同，字面量 `+` 保持原样不转为空格。
 *
 * @return UTF-8 解码结果。
 */
internal fun String.urlDecode(): String {
    return decodeVfsPercentEncoding(this)
}

/**
 * 解析 RFC 1123 时间。
 *
 * @return epoch 毫秒；无法解析时返回 `null`。
 */
internal fun String.toEpochMillisOrNull(): Long? {
    return runCatching {
        ZonedDateTime.parse(this, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()
}

/**
 * 校验 WebDAV 文件或目录名。
 *
 * @param targetName 待校验名称。
 */
internal fun validateTargetName(targetName: String) {
    val sanitized = targetName.trim()
    if (sanitized.isBlank() || '/' in sanitized || '\\' in sanitized) {
        throw VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.WEBDAV,
                capability = null,
            )
        )
    }
}

/**
 * 构建 WebDAV 子条目位置。
 *
 * 对新子条目名称单独执行 RFC 3986 路径段百分号编码，保留父路径已有合法 `%XX` 避免二次编码。
 *
 * @param parentLocation 父目录位置。
 * @param name 未编码的子条目名称。
 * @param directory 是否为目录。
 * @return 子条目 VFS 位置。
 */
internal fun webDavChildLocation(
    parentLocation: String,
    name: String,
    directory: Boolean,
): String {
    val parent = parseVfsUriOrNull(parentLocation.withVfsTrailingSlash())
        ?: throw VfsProviderNotFoundException(parentLocation)
    val encodedParentPath = encodeVfsPathPreservingPercent(parent.rawPath).withVfsTrailingSlash()
    val encodedChildName = encodeVfsPathSegment(name)
    val childRawPath = encodedParentPath + encodedChildName + if (directory) "/" else ""
    return parent.copy(userInfo = null, rawPath = childRawPath).toAsciiString(includeUserInfo = false)
}

/**
 * 从 WebDAV VFS 位置提取解码后的文件名。
 *
 * @return 百分号解码后的文件名（保留 `+` 等合法字符）。
 */
internal fun String.fileNameFromWebDavLocation(): String {
    val parsed = parseVfsUriOrNull(this) ?: throw VfsProviderNotFoundException(this)
    val rawName = parsed.rawPath.trimEnd('/').substringAfterLast('/')
    return decodeVfsPercentEncoding(rawName)
}

/**
 * 计算 WebDAV VFS 位置的父目录。
 *
 * @return 父目录 VFS 位置。
 */
internal fun String.parentWebDavLocation(): String {
    val parsed = parseVfsUriOrNull(this) ?: throw VfsProviderNotFoundException(this)
    val encodedPath = encodeVfsPathPreservingPercent(parsed.rawPath).trimEnd('/')
    val parentRaw = encodedPath.substringBeforeLast('/', missingDelimiterValue = "").ifBlank { "/" }
    val parentPath = if (parentRaw == "/") "/" else parentRaw.withVfsTrailingSlash()
    return parsed.copy(userInfo = null, rawPath = parentPath).toAsciiString(includeUserInfo = false)
}

/**
 * 将 WebDAV 位置转换为统一 VFS 文件对象。
 *
 * @param name 条目名称。
 * @param parentLocation 父目录位置。
 * @param directory 是否为目录。
 * @return VFS 文件对象。
 */
internal fun String.toWebDavVFile(
    name: String,
    parentLocation: String,
    directory: Boolean,
): VFile {
    return VFile(
        id = this,
        name = name,
        location = this,
        parentLocation = parentLocation.withVfsTrailingSlash(),
        kind = if (directory) VFileKind.DIRECTORY else VFileKind.FILE,
        sizeBytes = if (directory) null else 0L,
        modifiedAtEpochMillis = null,
        hidden = name.startsWith("."),
        capabilities = buildSet {
            add(VFileCapability.READ_METADATA)
            add(VFileCapability.RENAME)
            add(VFileCapability.DELETE)
            if (directory) {
                add(VFileCapability.LIST_CHILDREN)
            } else {
                add(VFileCapability.READ_CONTENT)
                add(VFileCapability.WRITE_CONTENT)
            }
        },
    )
}
