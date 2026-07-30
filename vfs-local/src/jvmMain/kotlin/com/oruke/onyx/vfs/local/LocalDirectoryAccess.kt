package com.oruke.onyx.vfs.local

import java.nio.file.Files
import java.nio.file.Path

/**
 * 解析本地目录实际用于枚举与监听的位置。
 *
 * Windows 兼容联接可能禁止直接读取联接本身，但其真实目标仍允许访问。
 */
object LocalDirectoryAccess {
    /**
     * 返回可直接枚举的目录路径。
     *
     * 普通目录保持原路径；不可读联接会通过 `toRealPath()` 跟随到可读目标。
     *
     * @param path 用户请求的本地目录路径。
     * @return 可枚举目录路径；无法解析或目标仍不可读时返回失败。
     */
    fun resolveForListing(path: Path): Result<Path> {
        return runCatching {
            val normalizedPath = path.toAbsolutePath().normalize()
            require(Files.isDirectory(normalizedPath)) {
                "$normalizedPath is not a directory"
            }
            if (Files.isReadable(normalizedPath)) {
                normalizedPath
            } else {
                val resolvedPath = normalizedPath.toRealPath()
                val isReadableTarget = resolvedPath != normalizedPath &&
                    Files.isDirectory(resolvedPath) &&
                    Files.isReadable(resolvedPath)
                require(isReadableTarget) {
                    "$normalizedPath is not a readable directory"
                }
                resolvedPath
            }
        }
    }

    /**
     * 判断目录自身或其真实联接目标是否允许枚举。
     *
     * @param path 待判断目录路径。
     * @return 存在可读枚举位置时返回 `true`。
     */
    fun isListable(path: Path): Boolean {
        return resolveForListing(path).isSuccess
    }
}
