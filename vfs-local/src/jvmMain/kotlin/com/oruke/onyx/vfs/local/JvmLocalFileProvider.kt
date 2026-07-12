package com.oruke.onyx.vfs.local

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.RoutableFileCommandService
import com.oruke.onyx.vfs.api.RoutableVfsContentService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsContentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * 基于 `java.nio.file` 的本地文件仓库与命令 Provider。
 */
class JvmLocalFileProvider : FileRepository, RoutableFileCommandService, RoutableVfsContentService {
    /**
     * 判断位置是否属于本地文件系统。
     *
     * @param location 待判断位置。
     * @return 不含 VFS 协议前缀时返回 `true`。
     */
    override fun supports(location: String): Boolean {
        return !location.contains("://")
    }

    /**
     * 列出本地目录的直接子项。
     *
     * @param location 本地目录位置。
     * @return 排好序的文件列表或结构化失败。
     */
    override suspend fun list(location: String): Result<List<VFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val directory = Path.of(location).normalize().toAbsolutePath()
            LocalPathOperations.ensurePathExists(directory)
            require(Files.isDirectory(directory)) { "$location is not a directory" }
            Files.newDirectoryStream(directory).use { stream ->
                stream
                    .map { child -> child.toLocalVFile(directory) }
                    .sortedWith(
                        compareByDescending<VFile> { it.kind == VFileKind.DIRECTORY }
                            .thenBy { it.name.lowercase() }
                    )
                    .toList()
            }
        }.mapLocalError()
    }

    /**
     * 返回当前用户主目录作为默认位置。
     *
     * @return 规范化后的用户主目录。
     */
    override fun defaultLocation(): String {
        return Path.of(System.getProperty("user.home")).toAbsolutePath().pathString
    }

    /**
     * 递归删除本地条目。
     *
     * @param entries 待删除条目。
     * @return 删除结果。
     */
    override suspend fun delete(entries: List<VFile>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            entries.forEach { entry ->
                LocalPathTransferOperations.deletePathRecursively(Path.of(entry.location))
            }
        }.mapLocalError()
    }

    /**
     * 复制本地条目到目标目录。
     *
     * @param entries 待复制条目。
     * @param targetDirectoryLocation 目标目录位置。
     * @param conflictStrategy 名称冲突处理策略。
     * @return 复制结果。
     */
    override suspend fun copy(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val targetDirectory = LocalPathOperations.resolveTargetDirectory(targetDirectoryLocation)
            entries.forEach { entry ->
                LocalPathTransferOperations.copyPathToDirectory(
                    source = Path.of(entry.location),
                    targetDirectory = targetDirectory,
                    conflictStrategy = conflictStrategy,
                )
            }
        }.mapLocalError()
    }

    /**
     * 移动本地条目到目标目录。
     *
     * @param entries 待移动条目。
     * @param targetDirectoryLocation 目标目录位置。
     * @param conflictStrategy 名称冲突处理策略。
     * @return 移动结果。
     */
    override suspend fun move(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val targetDirectory = LocalPathOperations.resolveTargetDirectory(targetDirectoryLocation)
            entries.forEach { entry ->
                LocalPathTransferOperations.movePathToDirectory(
                    source = Path.of(entry.location),
                    targetDirectory = targetDirectory,
                    conflictStrategy = conflictStrategy,
                )
            }
        }.mapLocalError()
    }

    /**
     * 重命名单个本地条目。
     *
     * @param entry 待重命名条目。
     * @param targetName 新名称。
     * @return 重命名后的文件对象。
     */
    override suspend fun rename(
        entry: VFile,
        targetName: String,
    ): Result<VFile> = withContext(Dispatchers.IO) {
        runCatching {
            val source = Path.of(entry.location).normalize().toAbsolutePath()
            val sanitizedTargetName = targetName.trim()
            LocalPathOperations.validateTargetName(sanitizedTargetName)
            val target = source.resolveSibling(sanitizedTargetName).normalize().toAbsolutePath()
            if (target == source) {
                return@runCatching source.toLocalVFile(source.parent ?: source.root ?: source)
            }
            if (Files.exists(target)) {
                throw FileAlreadyExistsException(target.pathString)
            }
            Files.move(source, target)
            target.toLocalVFile(target.parent ?: target.root ?: target)
        }.mapLocalError()
    }

    /**
     * 在本地目录中创建空文件。
     *
     * @param parentLocation 父目录位置。
     * @param name 文件名。
     * @return 创建后的文件对象。
     */
    override suspend fun createFile(
        parentLocation: String,
        name: String,
    ): Result<VFile> = withContext(Dispatchers.IO) {
        runCatching {
            val parentDirectory = LocalPathOperations.resolveTargetDirectory(parentLocation)
            val target = LocalPathOperations.resolveCreateTarget(parentDirectory, name)
            Files.createFile(target)
            target.toLocalVFile(parentDirectory)
        }.mapLocalError()
    }

    /**
     * 在本地目录中创建目录，可接受安全的相对层级路径。
     *
     * @param parentLocation 父目录位置。
     * @param name 相对目录路径。
     * @return 创建后的目录对象。
     */
    override suspend fun createDirectory(
        parentLocation: String,
        name: String,
    ): Result<VFile> = withContext(Dispatchers.IO) {
        runCatching {
            val parentDirectory = LocalPathOperations.resolveTargetDirectory(parentLocation)
            val target = LocalPathOperations.resolveCreateDirectoryTarget(parentDirectory, name)
            Files.createDirectories(target)
            target.toLocalVFile(target.parent ?: parentDirectory)
        }.mapLocalError()
    }

    /**
     * 以分块 Flow 读取本地文件。
     *
     * @param entry 待读取文件。
     * @return 文件内容源或结构化失败。
     */
    override suspend fun readFile(entry: VFile): Result<VfsContentSource> = withContext(Dispatchers.IO) {
        runCatching {
            val path = Path.of(entry.location).normalize().toAbsolutePath()
            LocalPathOperations.ensurePathExists(path)
            require(!Files.isDirectory(path)) { "${entry.location} is not a file" }
            VfsContentSource(
                name = path.fileName.toString(),
                sizeBytes = Files.size(path),
                chunks = path.readChunks(),
            )
        }.mapLocalError()
    }

    /**
     * 将分块内容写入本地目录。
     *
     * @param parentLocation 父目录位置。
     * @param name 目标文件名。
     * @param chunks 文件内容块。
     * @param conflictStrategy 名称冲突处理策略。
     * @return 实际写入的文件；按 SKIP 跳过时返回 `null`。
     */
    override suspend fun writeFile(
        parentLocation: String,
        name: String,
        chunks: Flow<ByteArray>,
        conflictStrategy: TransferConflictStrategy,
    ): Result<VFile?> = withContext(Dispatchers.IO) {
        runCatching {
            val parentDirectory = LocalPathOperations.resolveTargetDirectory(parentLocation)
            val target = LocalPathOperations.buildContentTargetPath(
                name = name,
                targetDirectory = parentDirectory,
                conflictStrategy = conflictStrategy,
            ) ?: return@runCatching null
            if (conflictStrategy == TransferConflictStrategy.OVERWRITE && Files.exists(target)) {
                LocalPathTransferOperations.deletePathRecursively(target)
            }
            Files.newOutputStream(target).use { output ->
                chunks.collect { chunk -> output.write(chunk) }
            }
            target.toLocalVFile(parentDirectory)
        }.mapLocalError()
    }

    /**
     * 将本地文件读取为固定大小的数据块。
     *
     * @return 在 I/O 调度器执行的内容 Flow。
     */
    private fun Path.readChunks(): Flow<ByteArray> {
        return flow {
            Files.newInputStream(this@readChunks).use { input ->
                val buffer = ByteArray(CONTENT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    emit(buffer.copyOf(read))
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    /** 本地文件内容读取块大小。 */
    private companion object {
        const val CONTENT_BUFFER_SIZE = 64 * 1024
    }
}
