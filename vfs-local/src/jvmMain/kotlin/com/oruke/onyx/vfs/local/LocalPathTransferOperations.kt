package com.oruke.onyx.vfs.local

import com.oruke.onyx.vfs.api.FileTransferProgressSink
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * 本地路径目标解析、移动策略、冲突命名与递归删除操作。
 */
internal object LocalPathTransferOperations {
    /**
     * 按冲突策略复制路径到目录。
     *
     * @param source 源路径。
     * @param targetDirectory 目标目录。
     * @param conflictStrategy 名称冲突处理策略。
     * @param progressSink 实际复制内容的字节增量接收器。
     */
    fun copyPathToDirectory(
        source: Path,
        targetDirectory: Path,
        conflictStrategy: TransferConflictStrategy,
        progressSink: FileTransferProgressSink = FileTransferProgressSink.NoOp,
    ) {
        val normalizedSource = source.normalize().toAbsolutePath()
        val target = buildTargetPath(normalizedSource, targetDirectory, conflictStrategy) ?: return
        if (conflictStrategy == TransferConflictStrategy.OVERWRITE && Files.exists(target)) {
            deletePathRecursively(target)
        }
        LocalFileCopyOperations.copyPathWithRollback(normalizedSource, target, progressSink)
    }

    /**
     * 按冲突策略移动路径到目录，跨文件系统时回退为复制后删除。
     *
     * @param source 源路径。
     * @param targetDirectory 目标目录。
     * @param conflictStrategy 名称冲突处理策略。
     * @param progressSink 跨文件系统回退复制时的字节增量接收器。
     */
    fun movePathToDirectory(
        source: Path,
        targetDirectory: Path,
        conflictStrategy: TransferConflictStrategy,
        progressSink: FileTransferProgressSink = FileTransferProgressSink.NoOp,
    ) {
        val normalizedSource = source.normalize().toAbsolutePath()
        val target = buildTargetPath(normalizedSource, targetDirectory, conflictStrategy) ?: return
        if (conflictStrategy == TransferConflictStrategy.OVERWRITE && Files.exists(target)) {
            deletePathRecursively(target)
        }
        try {
            Files.move(normalizedSource, target)
        } catch (_: IOException) {
            LocalFileCopyOperations.copyPathWithRollback(normalizedSource, target, progressSink)
            deleteSourceAfterFallback(normalizedSource, target)
        }
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
        while (Files.exists(candidate)) {
            val suffix = if (copyIndex == 1) " copy" else " copy $copyIndex"
            candidate = targetDirectory.resolve("$baseName$suffix$extension").normalize().toAbsolutePath()
            copyIndex += 1
        }
        return candidate
    }

    /**
     * 递归删除文件或目录。
     *
     * @param path 待删除路径。
     */
    fun deletePathRecursively(path: Path) {
        if (!Files.exists(path)) return
        if (Files.isDirectory(path)) {
            Files.walk(path).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        } else {
            Files.deleteIfExists(path)
        }
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
            !Files.exists(directTarget) -> directTarget
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
        require(target != source) { "Source and target cannot be the same path" }
        require(!Files.isDirectory(source) || !targetDirectory.startsWith(source)) {
            "Cannot place a directory into itself"
        }
    }

    /**
     * 跨文件系统移动复制成功后删除源；删除失败时回滚目标，避免产生两个不确定副本。
     *
     * @param source 源路径。
     * @param target 已复制目标路径。
     */
    private fun deleteSourceAfterFallback(
        source: Path,
        target: Path,
    ) {
        val deleteFailure = runCatching { deletePathRecursively(source) }.exceptionOrNull() ?: return
        runCatching { deletePathRecursively(target) }
            .onFailure { rollbackFailure -> deleteFailure.addSuppressed(rollbackFailure) }
        throw deleteFailure
    }
}
