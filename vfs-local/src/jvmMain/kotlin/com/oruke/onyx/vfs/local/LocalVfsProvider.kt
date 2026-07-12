package com.oruke.onyx.vfs.local

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.VfsProvider
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 将本地文件仓库接入统一 VFS，并补充本地目录容量统计能力。
 *
 * @param localProvider 本地文件仓库。
 */
class LocalVfsProvider(
    private val localProvider: JvmLocalFileProvider,
) : VfsProvider {
    override val protocol: VfsProtocol = VfsProtocol.LOCAL

    override val capabilities: Set<VfsProviderCapability> = setOf(
        VfsProviderCapability.WATCH,
        VfsProviderCapability.TRASH,
        VfsProviderCapability.EXTERNAL_OPEN,
        VfsProviderCapability.READ_PREVIEW,
        VfsProviderCapability.READ_CONTENT,
        VfsProviderCapability.WRITE_CONTENT,
        VfsProviderCapability.THUMBNAIL,
        VfsProviderCapability.CREATE_FILE,
        VfsProviderCapability.CREATE_DIRECTORY,
        VfsProviderCapability.RENAME,
        VfsProviderCapability.DELETE,
        VfsProviderCapability.COPY,
        VfsProviderCapability.MOVE,
    )

    override fun supports(location: String): Boolean {
        return localProvider.supports(location)
    }

    override fun defaultLocation(): String = localProvider.defaultLocation()

    override suspend fun list(location: String): Result<List<VFile>> = localProvider.list(location)

    override suspend fun totalSizeBytes(entries: List<VFile>): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            entries.sumOf { entry -> pathSizeBytes(Path.of(entry.location).normalize().toAbsolutePath()) }
        }
    }

    /**
     * 计算单个本地路径占用字节数。
     *
     * @param path 本地路径。
     * @return 文件大小、目录递归大小或不存在路径的零值。
     */
    private fun pathSizeBytes(path: Path): Long {
        return when {
            !Files.exists(path) -> 0L
            !Files.isDirectory(path) -> Files.size(path)
            else -> directorySizeBytes(path)
        }
    }

    /**
     * 递归统计目录内普通文件的总字节数。
     *
     * @param directory 目标目录。
     * @return 目录内文件总字节数。
     */
    private fun directorySizeBytes(directory: Path): Long {
        var total = 0L
        Files.walkFileTree(
            directory,
            object : SimpleFileVisitor<Path>() {
                /**
                 * 累加单个文件大小。
                 *
                 * @param file 当前文件。
                 * @param attrs 当前文件属性。
                 * @return 继续遍历。
                 */
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    total += attrs.size()
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return total
    }
}
