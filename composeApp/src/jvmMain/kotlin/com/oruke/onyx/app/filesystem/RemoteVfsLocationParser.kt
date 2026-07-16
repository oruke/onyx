package com.oruke.onyx.app.filesystem

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 远程 VFS 位置解析器，负责把用户输入或协议返回的路径转换为结构化 URI。
 *
 * 远程文件名允许出现空格、方括号、Unicode 和百分号，而 `URI(String)` 要求这些字符已经正确转义。
 * 本解析器先独立提取 scheme 与 authority，再仅对路径部分执行百分号解码和 URI 组件编码。
 */
internal object RemoteVfsLocationParser {
    /** URI scheme 与 authority 之间的固定分隔符。 */
    private const val SCHEME_SEPARATOR = "://"

    /** 百分号转义中高位十六进制字符相对 `%` 的偏移。 */
    private const val PERCENT_HIGH_HEX_OFFSET = 1

    /** 百分号转义中低位十六进制字符相对 `%` 的偏移。 */
    private const val PERCENT_LOW_HEX_OFFSET = 2

    /** 百分号转义使用的十六进制基数。 */
    private const val HEX_RADIX = 16

    /** 当前统一路径服务支持的远程协议。 */
    private val supportedSchemes = setOf("smb", "webdav", "webdavs", "s3")

    /**
     * 解析远程 VFS 位置。
     *
     * @param location 用户输入、会话缓存或协议实现返回的位置。
     * @return 可安全读取路径段的 URI；不是受支持远程位置或 authority 非法时返回 `null`。
     */
    fun parse(location: String): URI? {
        val trimmedLocation = location.trim()
        val separatorIndex = trimmedLocation.indexOf(SCHEME_SEPARATOR)
        return separatorIndex
            .takeIf { index -> index > 0 }
            ?.let { index -> parseRemoteLocation(trimmedLocation, index) }
    }

    /**
     * 解析已经确认包含 scheme 分隔符的远程位置。
     *
     * @param location 去除首尾空白后的位置。
     * @param separatorIndex scheme 分隔符起始索引。
     * @return 结构化远程 URI；协议或 authority 不受支持时返回 `null`。
     */
    private fun parseRemoteLocation(location: String, separatorIndex: Int): URI? {
        val scheme = location.substring(0, separatorIndex).lowercase()
        if (scheme !in supportedSchemes) return null

        val remainder = location.substring(separatorIndex + SCHEME_SEPARATOR.length)
        val pathStartIndex = remainder.indexOf('/')
        val authority = if (pathStartIndex >= 0) remainder.substring(0, pathStartIndex) else remainder
        val rawPath = if (pathStartIndex >= 0) remainder.substring(pathStartIndex) else "/"
        return authority
            .takeIf(String::isNotBlank)
            ?.let { value -> buildRemoteUri(scheme, value, rawPath) }
    }

    /**
     * 使用独立解析的 authority 与路径创建 URI。
     *
     * @param scheme 已验证的远程协议。
     * @param authority 主机与可选端口。
     * @param rawPath 尚未规范化的远程路径。
     * @return 结构化 URI；主机无效或包含用户信息时返回 `null`。
     */
    private fun buildRemoteUri(scheme: String, authority: String, rawPath: String): URI? {
        return runCatching {
            val authorityUri = URI("$scheme://$authority/")
            val host = authorityUri.host?.takeIf(String::isNotBlank)
            if (host != null && authorityUri.userInfo == null) {
                URI(
                    scheme,
                    null,
                    host,
                    authorityUri.port,
                    rawPath.decodeRemotePath(),
                    null,
                    null,
                )
            } else {
                null
            }
        }.getOrNull()
    }

    /**
     * 解码已有百分号转义，同时保留路径中的原始加号和不完整百分号。
     *
     * @return 可交给 URI 组件构造器重新规范化的 Unicode 路径。
     */
    private fun String.decodeRemotePath(): String {
        return URLDecoder.decode(escapeFormDecoderSpecialCharacters(), StandardCharsets.UTF_8)
    }

    /**
     * 保护表单解码器会误处理的加号，并把原始百分号改写为字面量转义。
     *
     * @return 可由 [URLDecoder] 安全处理的路径文本。
     */
    private fun String.escapeFormDecoderSpecialCharacters(): String {
        return buildString(length) {
            this@escapeFormDecoderSpecialCharacters.forEachIndexed { index, character ->
                when {
                    character == '+' -> append("%2B")
                    character == '%' && !this@escapeFormDecoderSpecialCharacters.hasPercentEscapeAt(index) ->
                        append("%25")

                    else -> append(character)
                }
            }
        }
    }

    /**
     * 判断指定位置是否以完整的两位十六进制百分号转义开头。
     *
     * @param index 待检查字符索引。
     * @return 当前索引是合法百分号转义起点时返回 `true`。
     */
    private fun String.hasPercentEscapeAt(index: Int): Boolean {
        return getOrNull(index) == '%' &&
            getOrNull(index + PERCENT_HIGH_HEX_OFFSET)?.digitToIntOrNull(HEX_RADIX) != null &&
            getOrNull(index + PERCENT_LOW_HEX_OFFSET)?.digitToIntOrNull(HEX_RADIX) != null
    }
}
