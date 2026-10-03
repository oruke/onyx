package com.oruke.onyx.vfs.local

import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
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
        if (target == parentDirectory) throw LocalizedException(I18nMessage(MessageKey.MSG_FILE_NAME_EMPTY))
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
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
        if (!target.startsWith(parentDirectory)) {
            throw LocalizedException(I18nMessage(MessageKey.MSG_PATH_OUTSIDE_CURRENT_LOCATION))
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
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
        if (!Files.isDirectory(targetDirectory)) {
            throw LocalizedException(I18nMessage(MessageKey.MSG_LOCATION_NOT_DIRECTORY, targetDirectoryLocation))
        }
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
        if (!directTarget.startsWith(targetDirectory)) {
            throw LocalizedException(I18nMessage(MessageKey.MSG_PATH_OUTSIDE_CURRENT_LOCATION))
        }
        return when {
            !Files.exists(directTarget, LinkOption.NOFOLLOW_LINKS) -> directTarget
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
        if (targetName.isBlank()) throw LocalizedException(I18nMessage(MessageKey.MSG_FILE_NAME_EMPTY))
        if ('/' in targetName || '\\' in targetName) {
            throw LocalizedException(I18nMessage(MessageKey.MSG_FILE_NAME_PATH_SEPARATOR))
        }
    }

    /**
     * 规范化安全的相对目录路径。
     *
     * @param rawPath 原始相对路径。
     * @return 使用 `/` 分隔的规范化相对路径。
     */
    private fun normalizeRelativeDirectoryPath(rawPath: String): String {
        val trimmed = rawPath.trim()
        val normalized = trimmed.replace('\\', '/').trim('/')
        val segments = normalized.split('/')
        // 按原有校验顺序选择最具体的原因，统一抛出可本地化错误。
        val errorKey = when {
            trimmed.startsWith('/') || trimmed.startsWith('\\') || trimmed.matches(windowsAbsolutePathPattern) ->
                MessageKey.MSG_DIRECTORY_PATH_RELATIVE_REQUIRED
            normalized.isBlank() -> MessageKey.MSG_DIRECTORY_PATH_EMPTY
            segments.any { segment -> segment.isBlank() || segment == "." || segment == ".." } ->
                MessageKey.MSG_DIRECTORY_PATH_INVALID_SEGMENTS
            else -> null
        }
        if (errorKey != null) throw LocalizedException(I18nMessage(errorKey))
        return segments.joinToString("/")
    }

    /** Windows 盘符绝对路径格式。 */
    private val windowsAbsolutePathPattern = Regex("^[A-Za-z]:.*")
}
