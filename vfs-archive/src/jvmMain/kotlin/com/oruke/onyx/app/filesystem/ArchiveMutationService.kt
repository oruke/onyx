package com.oruke.onyx.app.filesystem

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 压缩包写入失败。
 */
class ArchiveMutationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * 压缩包运行时能力失败。
 */
class ArchiveRuntimeException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * ZIP 系列压缩包内部写入服务。
 *
 * 当前只声明 ZIP/EPUB/CBZ 这类 ZIP 容器可写，避免把 7-Zip-JBinding 只读能力伪装成全格式写入。
 */
class ZipArchiveMutationService {
    /**
     * 在 ZIP 压缩包内创建目录。
     *
     * @param archivePath 压缩包路径。
     * @param entryPath 目录内部路径。
     */
    fun createDirectory(
        archivePath: String,
        entryPath: String,
    ) {
        val directoryPath = entryPath.toZipDirectoryPath()
        mutateZipArchive(archivePath) { source, output ->
            copyEntries(source, output)
            if (directoryPath !in source.entryNames) {
                output.putNextEntry(ZipEntry(directoryPath))
                output.closeEntry()
            }
        }
    }

    /**
     * 删除 ZIP 压缩包内的条目。
     *
     * @param archivePath 压缩包路径。
     * @param entryPaths 需要删除的内部路径。
     */
    fun deleteEntries(
        archivePath: String,
        entryPaths: List<String>,
    ) {
        val targets = entryPaths.map { path -> path.toZipEntryPath() }.toSet()
        mutateZipArchive(archivePath) { source, output ->
            copyEntries(source, output) { entry ->
                entry.name.takeIf { name -> targets.none { target -> name.matchesZipTarget(target) } }
            }
        }
    }

    /**
     * 重命名 ZIP 压缩包内的条目。
     *
     * @param archivePath 压缩包路径。
     * @param sourcePath 源内部路径。
     * @param targetPath 目标内部路径。
     */
    fun renameEntry(
        archivePath: String,
        sourcePath: String,
        targetPath: String,
    ) {
        val sourceEntryPath = sourcePath.toZipEntryPath()
        val targetEntryPath = targetPath.toZipEntryPath()
        mutateZipArchive(archivePath) { source, output ->
            if (targetEntryPath in source.entryNames || "$targetEntryPath/" in source.entryNames) {
                throw ArchiveMutationException("目标条目已存在: $targetEntryPath")
            }
            copyEntries(source, output) { entry ->
                entry.name.renameZipTarget(sourceEntryPath, targetEntryPath)
            }
        }
    }

    /**
     * 向 ZIP 压缩包追加文件。
     *
     * @param archivePath 压缩包路径。
     * @param entryPath 目标内部文件路径。
     * @param bytes 文件内容。
     * @param conflictStrategy 名称冲突处理策略。
     * @return 实际写入的内部路径；SKIP 且目标存在时返回 null。
     */
    fun appendFile(
        archivePath: String,
        entryPath: String,
        bytes: ByteArray,
        conflictStrategy: TransferConflictStrategy,
    ): String? {
        val normalizedPath = entryPath.toZipEntryPath()
        var writtenPath: String? = null
        mutateZipArchive(archivePath) { source, output ->
            val targetPath = normalizedPath.resolveZipConflict(source.entryNames, conflictStrategy)
            if (targetPath == null) {
                copyEntries(source, output)
                return@mutateZipArchive
            }
            writtenPath = targetPath
            val parentDirectories = targetPath.parentZipDirectories()
                .filterNot { directory -> directory in source.entryNames }
                .toSet()
            copyEntries(source, output) { entry ->
                entry.name.takeIf { name -> conflictStrategy != TransferConflictStrategy.OVERWRITE || name != targetPath }
            }
            parentDirectories.forEach { directory ->
                output.putNextEntry(ZipEntry(directory))
                output.closeEntry()
            }
            output.putNextEntry(ZipEntry(targetPath))
            output.write(bytes)
            output.closeEntry()
        }
        return writtenPath
    }

    /**
     * 在带回滚保护的临时文件中执行 ZIP 写入。
     *
     * @param archivePath 压缩包路径。
     * @param mutation 实际写入流程。
     */
    private fun mutateZipArchive(
        archivePath: String,
        mutation: (ZipArchiveSource, ZipOutputStream) -> Unit,
    ) {
        val archive = Path.of(archivePath)
        require(Files.exists(archive)) {
            "压缩包不存在: $archivePath"
        }
        val parent = archive.parent ?: Path.of(".")
        val temp = Files.createTempFile(parent, "${archive.fileName}.", ".tmp")
        val backup = Files.createTempFile(parent, "${archive.fileName}.", ".bak")
        try {
            Files.copy(archive, backup, StandardCopyOption.REPLACE_EXISTING)
            ZipArchiveSource(archive).use { source ->
                ZipOutputStream(Files.newOutputStream(temp)).use { output ->
                    mutation(source, output)
                }
            }
            moveReplacing(temp, archive)
        } catch (failure: Throwable) {
            restoreBackup(backup, archive)
            throw ArchiveMutationException("保存压缩包失败，已回滚: $archivePath", failure)
        } finally {
            Files.deleteIfExists(temp)
            Files.deleteIfExists(backup)
        }
    }

    /**
     * 复制 ZIP 条目，并允许调用方过滤或重命名。
     *
     * @param source 源 ZIP 读取器。
     * @param output 目标 ZIP 写入器。
     * @param transform 条目名变换；返回 null 表示跳过。
     */
    private fun copyEntries(
        source: ZipArchiveSource,
        output: ZipOutputStream,
        transform: (ZipEntry) -> String? = { entry -> entry.name },
    ) {
        ZipInputStream(Files.newInputStream(source.archive)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val targetName = transform(entry)
                if (targetName != null) {
                    output.putNextEntry(entry.copyForName(targetName))
                    if (!entry.isDirectory) {
                        input.copyTo(output)
                    }
                    output.closeEntry()
                }
                input.closeEntry()
            }
        }
    }

    /**
     * 原子替换压缩包；文件系统不支持原子移动时退回普通替换。
     *
     * @param source 临时文件。
     * @param target 原压缩包。
     */
    private fun moveReplacing(
        source: Path,
        target: Path,
    ) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * 写入失败时恢复备份文件。
     *
     * @param backup 备份文件。
     * @param archive 原压缩包。
     */
    private fun restoreBackup(
        backup: Path,
        archive: Path,
    ) {
        if (Files.exists(backup)) {
            Files.copy(backup, archive, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/**
 * ZIP 压缩包源信息。
 *
 * @property archive 压缩包路径。
 */
private class ZipArchiveSource(
    val archive: Path,
) : AutoCloseable {
    private val zipFile = ZipFile(archive.toFile())

    /** 压缩包内所有条目名。 */
    val entryNames: Set<String> = zipFile.entries().asSequence().map { entry -> entry.name }.toSet()

    /**
     * 关闭底层 ZIP 文件。
     */
    override fun close() {
        zipFile.close()
    }
}

/**
 * 复制 ZIP 条目的元数据并替换条目名。
 *
 * @param name 新条目名。
 * @return 新 ZIP 条目。
 */
private fun ZipEntry.copyForName(name: String): ZipEntry {
    val copied = ZipEntry(name)
    copied.comment = comment
    if (time > 0L) {
        copied.time = time
    }
    return copied
}

/**
 * 将用户输入转换为 ZIP 文件条目路径。
 *
 * @return 规范化后的内部路径。
 */
private fun String.toZipEntryPath(): String {
    val normalized = replace('\\', '/').trim('/')
    require(normalized.isNotBlank()) {
        "压缩包内部路径不能为空"
    }
    return normalized
}

/**
 * 将用户输入转换为 ZIP 目录条目路径。
 *
 * @return 带尾部 `/` 的内部目录路径。
 */
private fun String.toZipDirectoryPath(): String {
    return "${toZipEntryPath().trimEnd('/')}/"
}

/**
 * 判断 ZIP 条目是否命中删除目标。
 *
 * @param target 删除目标路径。
 * @return 命中时返回 true。
 */
private fun String.matchesZipTarget(target: String): Boolean {
    return this == target || this == "$target/" || startsWith("$target/")
}

/**
 * 计算 ZIP 重命名后的条目名。
 *
 * @param sourcePath 源路径。
 * @param targetPath 目标路径。
 * @return 新条目名；不匹配源路径时返回原名。
 */
private fun String.renameZipTarget(
    sourcePath: String,
    targetPath: String,
): String {
    return when {
        this == sourcePath -> targetPath
        this == "$sourcePath/" -> "$targetPath/"
        startsWith("$sourcePath/") -> targetPath + removePrefix(sourcePath)
        else -> this
    }
}

/**
 * 按冲突策略解析追加文件的实际路径。
 *
 * @param existingNames 已有条目名。
 * @param conflictStrategy 冲突策略。
 * @return 实际路径；SKIP 且目标存在时返回 null。
 */
private fun String.resolveZipConflict(
    existingNames: Set<String>,
    conflictStrategy: TransferConflictStrategy,
): String? {
    if (this !in existingNames) return this
    return when (conflictStrategy) {
        TransferConflictStrategy.SKIP -> null
        TransferConflictStrategy.OVERWRITE -> this
        TransferConflictStrategy.KEEP_BOTH -> nextZipCopyName(existingNames)
    }
}

/**
 * 生成 ZIP KEEP_BOTH 冲突策略下的下一个可用文件名。
 *
 * @param existingNames 已有条目名。
 * @return 不冲突的条目名。
 */
private fun String.nextZipCopyName(existingNames: Set<String>): String {
    val directory = substringBeforeLast('/', missingDelimiterValue = "")
    val fileName = substringAfterLast('/')
    val stem = fileName.substringBeforeLast('.', fileName)
    val extension = fileName.substringAfterLast('.', "")
        .takeIf { value -> value != fileName }
        ?.let { value -> ".$value" }
        .orEmpty()
    var index = 1
    while (true) {
        val copiedName = if (index == 1) {
            "$stem - Copy$extension"
        } else {
            "$stem - Copy ($index)$extension"
        }
        val candidate = if (directory.isBlank()) copiedName else "$directory/$copiedName"
        if (candidate !in existingNames) return candidate
        index += 1
    }
}

/**
 * 返回文件条目的父目录链，供追加文件时补齐显式目录项。
 *
 * @return 从浅到深的 ZIP 目录条目。
 */
private fun String.parentZipDirectories(): List<String> {
    val segments = split('/').dropLast(1)
    if (segments.isEmpty()) return emptyList()
    val directories = mutableListOf<String>()
    for (index in segments.indices) {
        directories += segments.take(index + 1).joinToString(separator = "/") + "/"
    }
    return directories
}
