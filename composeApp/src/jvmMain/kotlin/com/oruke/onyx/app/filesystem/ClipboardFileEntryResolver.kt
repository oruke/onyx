package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import kotlinx.coroutines.CancellationException

/**
 * 用统一文件仓库把系统路径恢复为真实 VFile，同目录只读取一次，避免伪造文件元数据。
 * @param fileRepository 按协议读取真实目录的统一仓库。
 * @param pathService 统一位置规范化与父目录解析入口。
 */
internal class ClipboardFileEntryResolver(
    /** 系统路径所对应的统一文件仓库。 */
    private val fileRepository: FileRepository,
    /** 文件位置处理服务。 */
    private val pathService: VfsPathService,
) {
    /**
     * 解析整批系统文件；任何条目不可访问时整批失败，不静默遗漏用户选中的文件。
     * @param locations 当前系统剪贴板明确提供的文件路径。
     * @return 按原顺序恢复的真实条目或结构化文件错误。
     */
    suspend fun resolve(locations: List<String>): Result<List<VFile>> = runCatching {
        val normalized = locations.map(pathService::normalizeLocation).distinct()
        val entriesByParent = normalized.groupBy { location ->
            pathService.parentLocation(location) ?: throw VfsProviderException(
                VfsProviderError.NotFound(VfsProtocol.LOCAL, location),
            )
        }.mapValues { (parent, _) -> fileRepository.list(parent).getOrThrow() }
        normalized.map { location ->
            entriesByParent.getValue(checkNotNull(pathService.parentLocation(location))).firstOrNull { entry ->
                pathService.isSameOrChildOf(entry.location, location) &&
                    pathService.isSameOrChildOf(location, entry.location)
            } ?: throw VfsProviderException(VfsProviderError.NotFound(VfsProtocol.LOCAL, location))
        }
    }.onFailure { if (it is CancellationException) throw it }
}
