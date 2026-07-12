package com.oruke.onyx.vfs.webdav

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProviderNotFoundException
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.encodeVfsSpaces
import com.oruke.onyx.vfs.api.withVfsTrailingSlash
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 将 WebDAV VFS 位置转换为 HTTP 请求地址。
 *
 * @return HTTP 或 HTTPS 地址。
 */
internal fun String.toHttpWebDavUrl(): String {
    val uri = URI(encodeVfsSpaces())
    val httpScheme = when (uri.scheme.lowercase()) {
        "webdav" -> "http"
        "webdavs" -> "https"
        else -> throw VfsProviderNotFoundException(this)
    }
    val path = uri.path.ifBlank { "/" }
    return URI(httpScheme, uri.userInfo, uri.host, uri.port, path, uri.query, uri.fragment).toASCIIString()
}

/**
 * URL 解码文本。
 *
 * @return UTF-8 解码结果。
 */
internal fun String.urlDecode(): String {
    return URLDecoder.decode(this, StandardCharsets.UTF_8)
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
 * @param parentLocation 父目录位置。
 * @param name 子条目名称。
 * @param directory 是否为目录。
 * @return 子条目 VFS 位置。
 */
internal fun webDavChildLocation(
    parentLocation: String,
    name: String,
    directory: Boolean,
): String {
    val parentUri = URI(parentLocation.withVfsTrailingSlash().encodeVfsSpaces())
    val childPath = parentUri.path.withVfsTrailingSlash() + name + if (directory) "/" else ""
    return URI(parentUri.scheme, null, parentUri.host, parentUri.port, childPath, null, null).toASCIIString()
}

/**
 * 从 WebDAV VFS 位置提取文件名。
 *
 * @return 解码后的文件名。
 */
internal fun String.fileNameFromWebDavLocation(): String {
    return URI(encodeVfsSpaces()).path.trimEnd('/').substringAfterLast('/').urlDecode()
}

/**
 * 计算 WebDAV VFS 位置的父目录。
 *
 * @return 父目录 VFS 位置。
 */
internal fun String.parentWebDavLocation(): String {
    val uri = URI(encodeVfsSpaces())
    val path = uri.path.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "").ifBlank { "/" }
    val parentPath = if (path == "/") path else path.withVfsTrailingSlash()
    return URI(uri.scheme, null, uri.host, uri.port, parentPath, null, null).toASCIIString()
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
