package com.oruke.onyx.vfs.local

import com.oruke.onyx.vfs.api.TransferConflictStrategy
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** POSIX / Linux / Samba 常见单文件名最大 UTF-8 字节数与字符数上限。 */
private const val POSIX_NAME_MAX_BYTES = 255

/** Windows 传统 MAX_PATH 临界长度阈值，达到或超过时提前探测目标可创建性。 */
private const val WINDOWS_NEAR_MAX_PATH_LENGTH = 248

/** 暂存目录命名前缀。 */
internal const val STAGING_DIRECTORY_PREFIX = ".onyx-transfer-"

/**
 * 本地暂存传输的目标可创建性前置探针与内部暂存路径异常脱敏工具。
 */
internal object LocalTransferStagingDiagnostics {
    /**
     * 对尚不存在且文件名或完整路径接近文件系统上限的新普通文件目标执行零字节创建探针。
     *
     * 当目标位于 Linux Samba / NAS 共享（单文件名上限 255 UTF-8 字节）或未启用长路径的卷时，
     * 可在流式复制数 GB 内容到暂存文件之前立即触发底层命名校验失败，避免无效传输。
     *
     * @param source 源路径。
     * @param target 最终目标路径。
     * @param strategy 冲突处理策略。
     */
    fun probeNearLimitNewFileTarget(
        source: Path,
        target: Path,
        strategy: TransferConflictStrategy,
    ) {
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
        if (!isNearFileSystemNameOrPathLimit(target)) return

        var probeCreated = false
        try {
            Files.newOutputStream(
                target,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use {
                probeCreated = true
            }
        } catch (alreadyExists: FileAlreadyExistsException) {
            if (strategy != TransferConflictStrategy.OVERWRITE) {
                throw alreadyExists
            }
            return
        } finally {
            if (probeCreated) {
                runCatching { Files.deleteIfExists(target) }
            }
        }
    }

    /**
     * 将暴露内部 `.onyx-transfer-*` 暂存目录的文件系统异常重写为面向最终目标路径的同类型异常。
     *
     * @param staging 本次操作独占的暂存目录。
     * @param target 最终目标路径。
     * @return 剥离内部暂存路径后的异常，保留原异常链与附加回滚异常。
     */
    fun unwrapStagingFailure(
        failure: Throwable,
        staging: Path,
        target: Path,
    ): Throwable {
        if (failure !is FileSystemException) return failure
        if (Files.exists(staging.resolve("previous"), LinkOption.NOFOLLOW_LINKS)) return failure
        val fileInStaging = isStagingPath(failure.file, staging)
        val otherInStaging = isStagingPath(failure.otherFile, staging)
        if (!fileInStaging && !otherInStaging) return failure

        val userFacingPath = when {
            fileInStaging && !otherInStaging && !failure.otherFile.isNullOrBlank() -> failure.otherFile
            otherInStaging && !fileInStaging && !failure.file.isNullOrBlank() -> failure.file
            else -> target.toString()
        }
        val sanitized = when (failure) {
            is AccessDeniedException -> AccessDeniedException(userFacingPath, null, failure.reason)
            is NoSuchFileException -> NoSuchFileException(userFacingPath, null, failure.reason)
            is FileAlreadyExistsException -> FileAlreadyExistsException(userFacingPath, null, failure.reason)
            else -> FileSystemException(userFacingPath, null, failure.reason)
        }
        sanitized.initCause(failure)
        failure.suppressed.forEach(sanitized::addSuppressed)
        return sanitized
    }

    private fun isNearFileSystemNameOrPathLimit(target: Path): Boolean {
        val fileName = target.fileName?.toString().orEmpty()
        if (fileName.isEmpty()) return false
        val utf8Bytes = fileName.toByteArray(Charsets.UTF_8).size
        return utf8Bytes > POSIX_NAME_MAX_BYTES ||
            fileName.length > POSIX_NAME_MAX_BYTES ||
            target.toString().length >= WINDOWS_NEAR_MAX_PATH_LENGTH
    }

    private fun isStagingPath(rawPath: String?, staging: Path): Boolean {
        if (rawPath.isNullOrBlank()) return false
        val normalizedStaging = staging.toAbsolutePath().normalize()
        val stagingToken = normalizedStaging.fileName?.toString() ?: STAGING_DIRECTORY_PREFIX
        val matchedByPath = runCatching {
            Path.of(rawPath).toAbsolutePath().normalize().startsWith(normalizedStaging)
        }.getOrDefault(false)
        return matchedByPath || rawPath.contains(stagingToken)
    }
}
