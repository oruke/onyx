package com.oruke.onyx.vfs.archive

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.RoutableVfsContentService
import com.oruke.onyx.vfs.api.VfsContentSource
import com.oruke.onyx.vfs.api.VfsProvider
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProviderNotFoundException
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import kotlinx.coroutines.flow.flowOf

/**
 * 将 `archive://` 位置接入统一 VFS 的只读 Provider。
 *
 * @param archiveService 压缩包读取服务。
 */
class ArchiveVfsProvider(
    private val archiveService: ArchiveService,
) : VfsProvider, RoutableVfsContentService {
    override val protocol: VfsProtocol = VfsProtocol.ARCHIVE

    override val capabilities: Set<VfsProviderCapability> = setOf(
        VfsProviderCapability.READ_CONTENT,
        VfsProviderCapability.READ_PREVIEW,
        VfsProviderCapability.THUMBNAIL,
    )

    override fun supports(location: String): Boolean = ArchiveService.isArchiveLocation(location)

    override suspend fun list(location: String): Result<List<VFile>> {
        val parsed = ArchiveService.parseArchiveLocation(location)
            ?: return Result.failure(VfsProviderNotFoundException(location))
        return archiveService.list(parsed.first, parsed.second)
    }

    override suspend fun readFile(entry: VFile): Result<VfsContentSource> {
        val (archivePath, innerPath) = readableArchiveLocation(entry)
            .getOrElse { failure -> return Result.failure(failure) }
        return archiveService.extractToBytes(archivePath, innerPath).mapCatching { bytes ->
            val content = bytes ?: throw VfsProviderException(
                VfsProviderError.NotFound(VfsProtocol.ARCHIVE, entry.location)
            )
            VfsContentSource(
                name = entry.name,
                sizeBytes = content.size.toLong(),
                chunks = flowOf(content),
            )
        }
    }

    override suspend fun writeFile(
        parentLocation: String,
        name: String,
        chunks: kotlinx.coroutines.flow.Flow<ByteArray>,
        conflictStrategy: TransferConflictStrategy,
    ): Result<VFile?> {
        return Result.failure(unsupported(parentLocation, VfsProviderCapability.WRITE_CONTENT))
    }

    /**
     * 校验归档条目能否作为文件读取，并解析其物理路径和内部路径。
     *
     * @param entry 待读取的 VFS 条目。
     * @return 物理归档路径与内部文件路径，或结构化失败。
     */
    private fun readableArchiveLocation(entry: VFile): Result<Pair<String, String>> {
        val parsed = ArchiveService.parseArchiveLocation(entry.location)
        return when {
            !supports(entry.location) -> Result.failure(VfsProviderNotFoundException(entry.location))
            entry.kind == VFileKind.DIRECTORY -> Result.failure(
                unsupported(entry.location, VfsProviderCapability.READ_CONTENT)
            )
            parsed == null -> Result.failure(VfsProviderNotFoundException(entry.location))
            parsed.second.isBlank() -> Result.failure(
                unsupported(entry.location, VfsProviderCapability.READ_CONTENT)
            )
            else -> Result.success(parsed)
        }
    }

    /**
     * 构建归档 Provider 不支持指定能力的结构化异常。
     *
     * @param location 归档位置。
     * @param capability 请求的 Provider 能力。
     * @return 统一 VFS 异常。
     */
    private fun unsupported(
        location: String,
        capability: VfsProviderCapability,
    ): VfsProviderException {
        return VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.ARCHIVE,
                location = location,
                capability = capability,
            )
        )
    }
}
