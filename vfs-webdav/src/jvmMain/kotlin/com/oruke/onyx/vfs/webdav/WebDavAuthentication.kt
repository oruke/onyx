package com.oruke.onyx.vfs.webdav

import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProtocol
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 生成 WebDAV 请求使用的 Authorization 请求头。
 *
 * @param location 请求位置，用于结构化错误。
 * @return 无认证时返回 `null`，否则返回完整请求头值。
 */
internal fun VfsAuthContext.webDavAuthorizationHeader(location: String): String? {
    return when (this) {
        VfsAuthContext.None -> null
        is VfsAuthContext.UsernamePassword -> {
            val raw = "$username:$password"
            val encoded = Base64.getEncoder().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
            "Basic $encoded"
        }

        is VfsAuthContext.BearerToken -> "Bearer $token"
        else -> throw VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.WEBDAV,
                location = location,
                capability = null,
            )
        )
    }
}
