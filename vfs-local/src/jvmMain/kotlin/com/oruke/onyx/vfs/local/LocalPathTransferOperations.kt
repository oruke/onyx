package com.oruke.onyx.vfs.local

import com.oruke.onyx.vfs.api.TransferConflictStrategy
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * 本地路径复制、移动、冲突命名与递归删除操作。
 */
internal object LocalPathTransferOperations {
    /**
     * 按冲突策略复制路径到目录。
     *
     * @param source 源路径。
     * @param targetDirectory 目标目录。
     * @param conflictStrategy 名称冲突处理策略。
     */
    fun copyPathToDirectory(
        source: Path,
        targetDirectory: Path,
        conflictStrategy: TransferConflictStrategy,
    ) {
        val normalizedSource = source.normalize().toAbsolutePath()
        val target = buildTargetPath(normalizedSource, targetDirectory, conflictStrategy) ?: return
        if (conflictStrategy == TransferConflictStrategy.OVERWRITE && Files.exists(target)) {
            deletePathRecursively(target)
        }
        copyPathRecursively(normalizedSource, target)
    }

    /**
     * 按冲突策略移动路径到目录，跨文件系统时回退为复制后删除。
     *
     * @param source 源路径。
     * @param targetDirectory 目标目录。
     * @param conflictStrategy 名称冲突处理策略。
     */
    fun movePathToDirectory(
        source: Path,
        targetDirectory: Path,
        conflictStrategy: TransferConflictStrategy,
    ) {
        val normalizedSource = source.normalize().toAbsolutePath()
        val target = buildTargetPath(normalizedSource, targetDirectory, conflictStrategy) ?: return
        if (conflictStrategy == TransferConflictStrategy.OVERWRITE && Files.exists(target)) {
            deletePathRecursively(target)
        }
        try {
            Files.move(normalizedSource, target)
        } catch (_: IOException) {
            copyPathRecursively(normalizedSource, target)
            val deleteFailure = runCatching { deletePathRecursively(normalizedSource) }.exceptionOrNull()
            if (deleteFailure != null) {
                runCatching { deletePathRecursively(target) }
                    .onFailure { rollbackFailure -> deleteFailure.addSuppressed(rollbackFailure) }
                throw deleteFailure
            }
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
     * @return 目标路径；按 SKIP 跳过时返回 `null`。
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
     * 递归复制文件或目录并保留文件属性。
     *
     * @param source 源路径。
     * @param target 目标路径。
     */
    private fun copyPathRecursively(
        source: Path,
        target: Path,
    ) {
        if (Files.isDirectory(source)) {
            Files.walkFileTree(source, CopyFileVisitor(source, target))
        } else {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
        }
    }
}

/**
 * 递归复制目录树的文件访问器。
 *
 * @param sourceRoot 源目录根路径。
 * @param targetRoot 目标目录根路径。
 */
private class CopyFileVisitor(
    private val sourceRoot: Path,
    private val targetRoot: Path,
) : SimpleFileVisitor<Path>() {
    /**
     * 创建目标目录。
     *
     * @param dir 当前源目录。
     * @param attrs 当前目录属性。
     * @return 继续遍历。
     */
    override fun preVisitDirectory(
        dir: Path,
        attrs: BasicFileAttributes,
    ): FileVisitResult {
        Files.createDirectories(targetRoot.resolve(sourceRoot.relativize(dir)))
        return FileVisitResult.CONTINUE
    }

    /**
     * 复制单个文件并保留属性。
     *
     * @param file 当前源文件。
     * @param attrs 当前文件属性。
     * @return 继续遍历。
     */
    override fun visitFile(
        file: Path,
        attrs: BasicFileAttributes,
    ): FileVisitResult {
        Files.copy(
            file,
            targetRoot.resolve(sourceRoot.relativize(file)),
            StandardCopyOption.COPY_ATTRIBUTES,
        )
        return FileVisitResult.CONTINUE
    }
}
