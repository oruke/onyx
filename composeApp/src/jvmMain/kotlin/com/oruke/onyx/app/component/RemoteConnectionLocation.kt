package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.core.model.S3ConnectionConfig
import com.oruke.onyx.vfs.api.encodeVfsSpaces
import java.net.URI

/** 网络位置协议切换、地址规范化与结构校验。 */
internal object RemoteConnectionLocation {
    /**
     * 将用户输入规范化为 Provider 可消费的目录 URI。
     *
     * SMB 接受 Windows UNC，WebDAV 接受对应安全级别的 HTTP(S) URL；输出统一使用内部 VFS scheme。
     *
     * @param protocol 目标连接协议。
     * @param value 用户输入地址。
     * @return 带协议和目录尾斜杠的 ASCII URI。
     */
    fun normalize(
        protocol: RemoteConnectionProtocol,
        value: String,
    ): String {
        val uri = URI(prepareUriText(protocol, value).encodeVfsSpaces())
        val authority = requireNotNull(uri.rawAuthority?.takeIf { it.isNotBlank() }) {
            "Remote connection authority is required"
        }
        val rawPath = uri.rawPath.orEmpty().ifBlank { "/" }
        val directoryPath = if (rawPath.endsWith('/')) rawPath else "$rawPath/"
        val normalized = buildString {
            append(uri.scheme.lowercase())
            append("://")
            append(authority)
            append(directoryPath)
            uri.rawQuery?.let { query -> append('?').append(query) }
            uri.rawFragment?.let { fragment -> append('#').append(fragment) }
        }
        return URI(normalized).toASCIIString()
    }

    /**
     * 校验地址是否符合所选协议及当前 Provider 的能力边界。
     *
     * @param protocol 目标连接协议。
     * @param value 用户输入地址。
     * @return 地址可被对应 Provider 解析时返回 true。
     */
    fun isValid(
        protocol: RemoteConnectionProtocol,
        value: String,
    ): Boolean = runCatching {
        val uri = URI(normalize(protocol, value))
        uri.scheme.equals(protocol.defaultScheme(), ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.query == null &&
            uri.fragment == null &&
            (protocol != RemoteConnectionProtocol.S3 || uri.port == -1)
    }.getOrDefault(false)

    /**
     * 按用户选择切换协议，并隔离不兼容的连接地址和凭据。
     *
     * WebDAV 与 WebDAVS 只切换传输安全级别，可以保留其余配置；跨协议族切换时清空地址和凭据，
     * 防止已有 SMB 或 WebDAV 密码被隐式解释为 AWS Secret Key。
     *
     * @param draft 当前连接草稿。
     * @param protocol 新协议。
     * @return 完成协议隔离后的草稿。
     */
    fun switchProtocol(
        draft: RemoteConnectionDraft,
        protocol: RemoteConnectionProtocol,
    ): RemoteConnectionDraft {
        if (draft.protocol == protocol) return draft
        val remainsWebDav = draft.protocol.isWebDav() && protocol.isWebDav()
        return if (remainsWebDav) {
            draft.copy(
                protocol = protocol,
                location = rewriteWebDavScheme(draft.location, protocol),
                domain = "",
            )
        } else {
            draft.copy(
                protocol = protocol,
                location = "",
                username = "",
                secret = "",
                secretChanged = true,
                domain = "",
                s3Config = S3ConnectionConfig(),
            )
        }
    }

    /**
     * 将宽松输入转换为可交给 [URI] 的协议地址。
     *
     * @param protocol 目标连接协议。
     * @param value 用户输入地址。
     * @return 已补齐或转换 scheme 的文本。
     */
    private fun prepareUriText(
        protocol: RemoteConnectionProtocol,
        value: String,
    ): String {
        val trimmed = value.trim()
        val smbCompatible = if (protocol == RemoteConnectionProtocol.SMB) {
            trimmed.replace('\\', '/').trimStart('/')
        } else {
            trimmed
        }
        val aliased = when (protocol) {
            RemoteConnectionProtocol.WEBDAV -> smbCompatible.replaceSchemeAlias("http", "webdav")
            RemoteConnectionProtocol.WEBDAVS -> smbCompatible.replaceSchemeAlias("https", "webdavs")
            RemoteConnectionProtocol.SMB,
            RemoteConnectionProtocol.S3,
            -> smbCompatible
        }
        return if ("://" in aliased) {
            aliased
        } else {
            "${protocol.defaultScheme()}://${aliased.trimStart('/')}"
        }
    }

    /**
     * 切换 WebDAV 安全级别时重写已有地址 scheme。
     *
     * @param value 原地址。
     * @param protocol 新 WebDAV 协议。
     * @return 重写后的地址；无法识别时保留原值。
     */
    private fun rewriteWebDavScheme(
        value: String,
        protocol: RemoteConnectionProtocol,
    ): String {
        if (value.isBlank()) return value
        val separatorIndex = value.indexOf("://")
        return if (separatorIndex >= 0) {
            protocol.defaultScheme() + value.substring(separatorIndex)
        } else {
            value
        }
    }
}

/**
 * 返回草稿的 Provider 目录地址。
 *
 * @return 规范化 VFS URI。
 */
internal fun RemoteConnectionDraft.normalizedLocation(): String {
    return RemoteConnectionLocation.normalize(protocol, location)
}

/**
 * 返回协议对应的内部 VFS scheme。
 *
 * @return 小写 scheme 名称。
 */
internal fun RemoteConnectionProtocol.defaultScheme(): String {
    return when (this) {
        RemoteConnectionProtocol.SMB -> "smb"
        RemoteConnectionProtocol.WEBDAV -> "webdav"
        RemoteConnectionProtocol.WEBDAVS -> "webdavs"
        RemoteConnectionProtocol.S3 -> "s3"
    }
}

/**
 * 判断协议是否属于 WebDAV 协议族。
 *
 * @return WebDAV 或 WebDAVS 返回 true。
 */
private fun RemoteConnectionProtocol.isWebDav(): Boolean {
    return this == RemoteConnectionProtocol.WEBDAV || this == RemoteConnectionProtocol.WEBDAVS
}

/**
 * 将匹配的外部 scheme 别名替换为内部 VFS scheme。
 *
 * @param alias 外部 scheme。
 * @param replacement 内部 scheme。
 * @return 完成替换后的地址。
 */
private fun String.replaceSchemeAlias(
    alias: String,
    replacement: String,
): String {
    val prefix = "$alias://"
    return if (startsWith(prefix, ignoreCase = true)) {
        "$replacement://${substring(prefix.length)}"
    } else {
        this
    }
}
