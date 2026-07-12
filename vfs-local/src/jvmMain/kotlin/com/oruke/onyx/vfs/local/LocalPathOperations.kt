package com.oruke.onyx.vfs.local

import com.oruke.onyx.vfs.api.TransferConflictStrategy
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * 本地路径校验、创建路径解析与内容写入目标解析。
 */
internal object LocalPathOperations {
    /**
     * 校验路径存在。
     *
     * @param path 待校验路径。
     */
    fun ensurePathExists(path: Path) {
        if (!Files.exists(path)) {
            throw NoSuchFileException(path.pathString)
        }
    }

    /**
     * 解析新建文件的目标路径。
     *
     * @param parentDirectory 父目录。
     * @param name 文件名。
     * @return 可创建的目标路径。
     */
    fun resolveCreateTarget(
        parentDirectory: Path,
        name: String,
    ): Path {
        val sanitizedName = name.trim()
        validateTargetName(sanitizedName)
        val target = parentDirectory.resolve(sanitizedName).normalize().toAbsolutePath()
        require(target != parentDirectory) { "Cannot create entry with empty name" }
        if (Files.exists(target)) {
            throw FileAlreadyExistsException(target.pathString)
        }
        return target
    }

    /**
     * 解析新建目录的安全目标路径。
     *
     * @param parentDirectory 父目录。
     * @param rawName 相对目录路径。
     * @return 可创建的目标目录路径。
     */
    fun resolveCreateDirectoryTarget(
        parentDirectory: Path,
        rawName: String,
    ): Path {
        val normalizedRelativePath = normalizeRelativeDirectoryPath(rawName)
        val target = parentDirectory.resolve(normalizedRelativePath).normalize().toAbsolutePath()
        require(target.startsWith(parentDirectory)) { "Directory path must stay inside the current location" }
        if (Files.exists(target)) {
            throw FileAlreadyExistsException(target.pathString)
        }
        return target
    }

    /**
     * 校验并解析目标目录。
     *
     * @param targetDirectoryLocation 目标目录位置。
     * @return 规范化后的现有目录路径。
     */
    fun resolveTargetDirectory(targetDirectoryLocation: String): Path {
        val targetDirectory = Path.of(targetDirectoryLocation).normalize().toAbsolutePath()
        ensurePathExists(targetDirectory)
        require(Files.isDirectory(targetDirectory)) { "$targetDirectoryLocation is not a directory" }
        return targetDirectory
    }

    /**
     * 按冲突策略构建内容写入目标路径。
     *
     * @param name 目标文件名。
     * @param targetDirectory 目标目录。
     * @param conflictStrategy 名称冲突处理策略。
     * @return 目标路径；按 SKIP 跳过时返回 `null`。
     */
    fun buildContentTargetPath(
        name: String,
        targetDirectory: Path,
        conflictStrategy: TransferConflictStrategy,
    ): Path? {
        val sanitizedName = name.trim()
        validateTargetName(sanitizedName)
        val directTarget = targetDirectory.resolve(sanitizedName).normalize().toAbsolutePath()
        require(directTarget.startsWith(targetDirectory)) { "Target path must stay inside the current location" }
        return when {
            !Files.exists(directTarget) -> directTarget
            conflictStrategy == TransferConflictStrategy.KEEP_BOTH -> LocalPathTransferOperations.availableTargetPath(
                originalName = sanitizedName,
                isDirectory = false,
                targetDirectory = targetDirectory,
            )
            conflictStrategy == TransferConflictStrategy.OVERWRITE -> directTarget
            conflictStrategy == TransferConflictStrategy.SKIP -> null
            else -> directTarget
        }
    }

    /**
     * 校验目标名称不为空且不含路径分隔符。
     *
     * @param targetName 待校验名称。
     */
    fun validateTargetName(targetName: String) {
        require(targetName.isNotBlank()) { "Name cannot be blank" }
        require('/' !in targetName && '\\' !in targetName) { "Name cannot contain path separators" }
    }

    /**
     * 规范化安全的相对目录路径。
     *
     * @param rawPath 原始相对路径。
     * @return 使用 `/` 分隔的规范化相对路径。
     */
    private fun normalizeRelativeDirectoryPath(rawPath: String): String {
        val trimmed = rawPath.trim()
        require(!trimmed.startsWith('/') && !trimmed.startsWith('\\')) { "Directory path must be relative" }
        require(!trimmed.matches(windowsAbsolutePathPattern)) { "Directory path must be relative" }
        val normalized = trimmed.replace('\\', '/').trim('/')
        require(normalized.isNotBlank()) { "Directory path cannot be blank" }
        val segments = normalized.split('/')
        require(segments.all { segment -> segment.isNotBlank() && segment != "." && segment != ".." }) {
            "Directory path contains invalid segments"
        }
        return segments.joinToString("/")
    }

    /** Windows 盘符绝对路径格式。 */
    private val windowsAbsolutePathPattern = Regex("^[A-Za-z]:.*")
}
