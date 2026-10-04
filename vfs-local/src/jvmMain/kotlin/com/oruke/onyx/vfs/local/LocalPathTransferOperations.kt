package com.oruke.onyx.vfs.local

import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.vfs.api.FileTransferProgressSink
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * 本地路径目标解析、移动策略与冲突命名，删除统一交由入口感知的删除器执行。
 */
internal object LocalPathTransferOperations {
    /**
     * 按冲突策略复制路径到目录。
     *
     * @param source 源路径。
     * @param targetDirectory 目标目录。
     * @param conflictStrategy 名称冲突处理策略。
     * @param progressSink 实际复制内容的字节增量接收器。
     * @param checkActive 提交最终目标前检查任务是否已取消。
     */
    fun copyPathToDirectory(
        source: Path,
        targetDirectory: Path,
        conflictStrategy: TransferConflictStrategy,
        progressSink: FileTransferProgressSink = FileTransferProgressSink.NoOp,
        checkActive: () -> Unit = {},
    ) {
        val normalizedSource = source.normalize().toAbsolutePath()
        val target = buildTargetPath(normalizedSource, targetDirectory, conflictStrategy) ?: return
        LocalTransferCommitOperations.copy(normalizedSource, target, conflictStrategy, progressSink, checkActive)
    }

    /**
     * 按冲突策略移动路径到目录，跨文件系统时回退为复制后删除。
     *
     * @param source 源路径。
     * @param targetDirectory 目标目录。
     * @param conflictStrategy 名称冲突处理策略。
     * @param progressSink 跨文件系统回退复制时的字节增量接收器。
     * @param checkActive 提交最终目标前检查任务是否已取消。
     */
    fun movePathToDirectory(
        source: Path,
        targetDirectory: Path,
        conflictStrategy: TransferConflictStrategy,
        progressSink: FileTransferProgressSink = FileTransferProgressSink.NoOp,
        checkActive: () -> Unit = {},
    ) {
        val normalizedSource = source.normalize().toAbsolutePath()
        val target = buildTargetPath(normalizedSource, targetDirectory, conflictStrategy) ?: return
        LocalTransferCommitOperations.move(normalizedSource, target, conflictStrategy, progressSink, checkActive)
    }

    /**
     * 根据名称和类型生成不冲突的副本路径。
     *
     * @param originalName 原始名称。
     * @param isDirectory 是否为目录。
     * @param targetDirectory 目标目录。
     * @return 可用目标路径。
     */
    fun availableTargetPath(
        originalName: String,
        isDirectory: Boolean,
        targetDirectory: Path,
    ): Path {
        val dotIndex = originalName.lastIndexOf('.')
        val hasExtension = !isDirectory && dotIndex > 0 && dotIndex < originalName.lastIndex
        val baseName = if (hasExtension) originalName.substring(0, dotIndex) else originalName
        val extension = if (hasExtension) originalName.substring(dotIndex) else ""
        var candidate = targetDirectory.resolve(originalName).normalize().toAbsolutePath()
        var copyIndex = 1
        while (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            val suffix = if (copyIndex == 1) " copy" else " copy $copyIndex"
            candidate = targetDirectory.resolve("$baseName$suffix$extension").normalize().toAbsolutePath()
            copyIndex += 1
        }
        return candidate
    }

    /**
     * 删除文件或目录树，目录联接和符号链接仅删除入口。
     *
     * @param path 待删除路径。
     */
    fun deletePathRecursively(path: Path) {
        LocalPathDeletion.delete(path)
    }

    /**
     * 按冲突策略构建文件传输目标路径。
     *
     * @param source 源路径。
     * @param targetDirectory 目标目录。
     * @param conflictStrategy 名称冲突处理策略。
     * @return 目标路径；按 SKIP 跳过时返回 null。
     */
    private fun buildTargetPath(
        source: Path,
        targetDirectory: Path,
        conflictStrategy: TransferConflictStrategy,
    ): Path? {
        LocalPathOperations.ensurePathExists(source)
        val directTarget = targetDirectory.resolve(source.fileName.toString()).normalize().toAbsolutePath()
        val target = when {
            !Files.exists(directTarget, LinkOption.NOFOLLOW_LINKS) -> directTarget
            conflictStrategy == TransferConflictStrategy.KEEP_BOTH -> availableTargetPath(
                originalName = source.fileName.toString(),
                isDirectory = Files.isDirectory(source),
                targetDirectory = targetDirectory,
            )

            conflictStrategy == TransferConflictStrategy.OVERWRITE -> directTarget
            conflictStrategy == TransferConflictStrategy.SKIP -> null
            else -> directTarget
        }
        target?.let { candidate -> validateTransferTarget(source, targetDirectory, candidate) }
        return target
    }

    /**
     * 校验传输目标不会覆盖源本身或把目录放入自身。
     *
     * @param source 规范化源路径。
     * @param targetDirectory 规范化目标目录。
     * @param target 解析后的目标路径。
     */
    private fun validateTransferTarget(
        source: Path,
        targetDirectory: Path,
        target: Path,
    ) {
        if (target == source) throw LocalizedException(I18nMessage(MessageKey.MSG_TRANSFER_SAME_PATH))
        if (Files.isDirectory(source) && targetDirectory.startsWith(source)) {
            throw LocalizedException(I18nMessage(MessageKey.MSG_TRANSFER_INTO_SELF))
        }
    }

}
