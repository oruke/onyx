package com.oruke.onyx.shared.usecase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path
import java.util.Locale

/**
 * 在 JVM 平台通过 `Dispatchers.IO` 解析本地路径的真实规范路径以拦截符号链接与目录联接循环；
 * 对远程 VFS 或无法解析的虚拟路径直接返回字符串规范化键，避免在 `Dispatchers.Default` 上阻塞磁盘 syscall。
 *
 * @param location 待解析的目录位置。
 * @return 用于去重的规范目录键。
 */
internal actual suspend fun resolveCanonicalSearchDirectoryKey(location: String): String {
    val trimmed = location.trim()
    if (trimmed.isEmpty() || trimmed.contains("://")) {
        return normalizeFallbackSearchDirectoryKey(trimmed)
    }
    return withContext(Dispatchers.IO) {
        val resolved = runCatching {
            Path.of(trimmed).toRealPath().toString()
        }.recoverCatching {
            Path.of(trimmed).toAbsolutePath().normalize().toString()
        }.getOrElse {
            normalizeFallbackSearchDirectoryKey(trimmed)
        }
        if (File.separatorChar == '\\') {
            resolved.lowercase(Locale.ROOT)
        } else {
            resolved
        }
    }
}
