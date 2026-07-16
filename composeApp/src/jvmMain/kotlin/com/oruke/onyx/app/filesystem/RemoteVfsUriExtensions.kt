package com.oruke.onyx.app.filesystem

import java.net.URI

/**
 * 将结构化远程 URI 转换为对应 provider 可直接消费的位置。
 *
 * @param directory 是否按目录补齐结尾斜杠。
 * @return 规范化后的远程 VFS 位置。
 */
internal fun URI.toRemoteLocation(directory: Boolean): String {
    val normalizedPath = remotePathSegments()
        .joinToString(separator = "/", prefix = "/")
        .let { path ->
            when {
                path == "/" -> path
                directory -> path.withTrailingSlash()
                else -> path
            }
        }
    return buildRemoteLocation(normalizedPath)
}

/**
 * 返回远程 URI 的父目录位置。
 *
 * @return 规范化父目录；当前已是远程根时返回 `null`。
 */
internal fun URI.remoteParentLocation(): String? {
    val segments = remotePathSegments()
    if (segments.isEmpty()) return null
    val parentPath = segments.dropLast(1)
        .joinToString(separator = "/", prefix = "/")
        .let { path -> if (path == "/") path else path.withTrailingSlash() }
    return buildRemoteLocation(parentPath)
}

/**
 * 返回远程 URI 的最后一级路径名称。
 *
 * @return 最后一级非空路径段；远程根返回 `null`。
 */
internal fun URI.remoteBaseName(): String? {
    return remotePathSegments()
        .lastOrNull()
        ?.takeIf { it.isNotBlank() }
}

/**
 * 构建从远程主机到当前目录的面包屑。
 *
 * @return 保持 provider 路径格式的面包屑列表。
 */
internal fun URI.remoteBreadcrumbs(): List<VfsBreadcrumb> {
    val breadcrumbs = mutableListOf(
        VfsBreadcrumb(
            label = host,
            location = buildRemoteLocation("/"),
        )
    )
    var currentPath = ""
    remotePathSegments().forEach { segment ->
        currentPath = "$currentPath/$segment"
        breadcrumbs += VfsBreadcrumb(
            label = segment,
            location = buildRemoteLocation(currentPath.withTrailingSlash()),
        )
    }
    return breadcrumbs.distinctBy { it.location }
}

/**
 * 返回后代 URI 相对当前 URI 的直接子级名称。
 *
 * @param descendant 待判断后代 URI。
 * @return 直接子级路径段；不是同一远程根下的后代时返回 `null`。
 */
internal fun URI.remoteDirectChildName(descendant: URI): String? {
    val ancestorSegments = remotePathSegments()
    val descendantSegments = descendant.remotePathSegments()
    val isDirectDescendant = hasSameRemoteRoot(descendant) &&
        descendantSegments.size > ancestorSegments.size &&
        descendantSegments.take(ancestorSegments.size) == ancestorSegments
    return descendantSegments.getOrNull(ancestorSegments.size).takeIf { isDirectDescendant }
}

/**
 * 判断当前 URI 是否等于或位于指定远程父级之下。
 *
 * @param parent 待判断父级 URI。
 * @return 同一远程根且路径相等或属于其后代时返回 `true`。
 */
internal fun URI.isSameOrChildOfRemote(parent: URI): Boolean {
    if (!hasSameRemoteRoot(parent)) return false
    val segments = remotePathSegments()
    val parentSegments = parent.remotePathSegments()
    return segments == parentSegments || segments.take(parentSegments.size) == parentSegments
}

/**
 * 根据协议生成可交给对应 provider 的规范位置。
 *
 * jcifs-ng 不会解码 URL 路径中的百分号转义，因此 SMB 必须保留服务器返回的原始文件名；
 * WebDAV 与 S3 继续使用标准 URI 组件编码。
 *
 * @param path 已规范化的 Unicode 绝对路径。
 * @return 对应远程 provider 可直接消费的位置。
 */
private fun URI.buildRemoteLocation(path: String): String {
    return if (scheme.equals("smb", ignoreCase = true)) {
        "${scheme.lowercase()}://${rawAuthority.orEmpty()}$path"
    } else {
        URI(scheme.lowercase(), null, host, port, path, null, null).toString()
    }
}

/**
 * 判断两个 URI 是否指向相同协议、主机和端口。
 *
 * @param other 待比较远程 URI。
 * @return 远程根一致时返回 `true`。
 */
internal fun URI.hasSameRemoteRoot(other: URI): Boolean {
    return scheme.equals(other.scheme, ignoreCase = true) &&
        host.equals(other.host, ignoreCase = true) &&
        effectivePort() == other.effectivePort()
}

/**
 * 返回用于远程根比较的有效端口。
 *
 * @return 显式端口；未指定时返回 `-1`。
 */
private fun URI.effectivePort(): Int {
    return if (port >= 0) port else -1
}

/**
 * 返回已解码且去除空段的远程路径段。
 *
 * @return 按目录层级排列的路径段。
 */
private fun URI.remotePathSegments(): List<String> {
    return path
        ?.trim('/')
        ?.split('/')
        ?.filter { segment -> segment.isNotBlank() }
        .orEmpty()
}

/**
 * 确保远程目录路径以斜杠结尾。
 *
 * @return 带目录结尾斜杠的路径。
 */
private fun String.withTrailingSlash(): String = if (endsWith('/')) this else "$this/"
