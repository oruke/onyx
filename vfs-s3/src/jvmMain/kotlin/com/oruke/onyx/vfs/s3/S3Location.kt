package com.oruke.onyx.vfs.s3

import com.oruke.onyx.vfs.api.VfsProviderNotFoundException
import com.oruke.onyx.vfs.api.encodeVfsSpaces
import com.oruke.onyx.vfs.api.withVfsTrailingSlash
import java.net.URI

/**
 * 解析后的 S3 位置。
 */
data class S3Location(
    /** bucket 名称。 */
    val bucket: String,

    /** 当前对象或目录前缀。 */
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
     * 将对象 key 组装回 `s3://` 位置。
     *
     * @param key 对象 key。
     * @param directory 是否按目录位置输出。
     * @return S3 VFS 位置。
     */
    fun toLocation(
        key: String,
        directory: Boolean,
    ): String {
        val path = if (directory) key.withVfsTrailingSlash() else key
        return URI("s3", bucket, "/$path", null).toASCIIString()
    }

    /**
     * S3 VFS 位置解析工具。
     */
    companion object {
        /**
         * 解析 `s3://bucket/key` 位置。
         *
         * @param location S3 VFS 位置。
         * @return 解析后的 S3 位置。
         */
        fun parse(location: String): S3Location {
            val uri = URI(location.encodeVfsSpaces())
            val bucket = uri.host
            if (bucket.isNullOrBlank()) {
                throw VfsProviderNotFoundException(location)
            }
            val prefix = uri.path
                .removePrefix("/")
                .trimStart('/')
            return S3Location(bucket = bucket, prefix = prefix)
        }
    }
}
