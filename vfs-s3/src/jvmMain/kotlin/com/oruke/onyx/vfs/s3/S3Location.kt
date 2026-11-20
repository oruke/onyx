package com.oruke.onyx.vfs.s3

import com.oruke.onyx.vfs.api.VfsProviderNotFoundException
import com.oruke.onyx.vfs.api.encodeVfsPath
import com.oruke.onyx.vfs.api.parseVfsUriOrNull
import com.oruke.onyx.vfs.api.withVfsTrailingSlash

/**
 * 解析后的 S3 位置。
 */
data class S3Location(
    /** bucket 名称。 */
    val bucket: String,

    /** 当前对象或目录前缀（已解码的原始路径文本）。 */
    val prefix: String,
) {
    /** 对象 key，不包含开头 `/`。 */
    val objectKey: String = prefix.trimStart('/')

    /** 目录前缀，非根目录时以 `/` 结尾。 */
    val directoryPrefix: String = prefix.trim('/').let { value ->
        if (value.isBlank()) "" else "$value/"
    }

    /** 当前目录的 VFS 位置。 */
    val directoryLocation: String
        get() = toLocation(directoryPrefix, directory = true)

    /**
     * 将对象 key 组装回 RFC 3986 编码的 `s3://` 位置。
     *
     * 对每个路径段按 RFC 3986 逐段百分号编码，安全保留 `+` 并编码 `#`、`?`、`%`、`[`、`]` 与空格。
     *
     * @param key 未编码的对象 key。
     * @param directory 是否按目录位置输出。
     * @return S3 VFS 位置。
     */
    fun toLocation(
        key: String,
        directory: Boolean,
    ): String {
        val normalized = key.trimStart('/').let { trimmed ->
            if (directory && trimmed.isNotEmpty()) trimmed.withVfsTrailingSlash() else trimmed
        }
        val encodedPath = if (normalized.isEmpty()) "/" else encodeVfsPath("/$normalized")
        return "s3://$bucket$encodedPath"
    }

    /**
     * S3 VFS 位置解析工具。
     */
    companion object {
        /**
         * 解析 `s3://bucket/key` 位置。
         *
         * 避免单参 `URI` 把对象 key 中的 `#`、`?` 误截断为 fragment/query，也避免 `+` 误解码为空格。
         *
         * @param location S3 VFS 位置。
         * @return 解析后的 S3 位置。
         */
        fun parse(location: String): S3Location {
            val parsed = parseVfsUriOrNull(location) ?: throw VfsProviderNotFoundException(location)
            if (parsed.scheme != "s3" || parsed.host.isBlank()) {
                throw VfsProviderNotFoundException(location)
            }
            val prefix = parsed.decodedPath
                .removePrefix("/")
                .trimStart('/')
            return S3Location(bucket = parsed.host, prefix = prefix)
        }
    }
}
