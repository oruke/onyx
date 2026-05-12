package com.oruke.onyx.app.usecase

import com.oruke.onyx.app.filesystem.RoutableVfsContentService
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 基于统一 VFS 内容服务的 JVM 文件内容搜索实现。
 *
 * @param contentServices 可按 location 路由的文件内容服务列表。
 */
class JvmVfsFileContentSearchService(
    private val contentServices: List<RoutableVfsContentService>,
) : FileContentSearchService {
    /**
     * 判断指定位置是否具备内容搜索基础能力。
     *
     * @param location 搜索根位置。
     * @return `true` 表示至少有一个内容服务支持该位置。
     */
    override fun supportsLocation(location: String): Boolean {
        return contentServices.any { service -> service.supports(location) }
    }

    /**
     * 判断指定条目是否可读取内容。
     *
     * @param entry 待搜索条目。
     * @return `true` 表示条目可通过统一内容服务读取。
     */
    override fun supports(entry: VFile): Boolean {
        return entry.kind == VFileKind.FILE &&
            contentServices.any { service -> service.supports(entry.location) }
    }

    /**
     * 检查文件内容是否包含查询文本。
     *
     * @param entry 待搜索文件。
     * @param query 小写化后的查询文本。
     * @param maxBytes 单文件最多读取字节数，超过后直接视为不匹配。
     * @return 是否包含查询文本。
     */
    override suspend fun contains(
        entry: VFile,
        query: String,
        maxBytes: Long,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val entrySizeBytes = entry.sizeBytes
            if (entrySizeBytes != null && entrySizeBytes > maxBytes) {
                return@runCatching false
            }
            val contentService = contentServices.firstOrNull { service -> service.supports(entry.location) }
                ?: return@runCatching false
            val source = contentService.readFile(entry).getOrThrow()
            val sourceSizeBytes = source.sizeBytes
            if (sourceSizeBytes != null && sourceSizeBytes > maxBytes) {
                return@runCatching false
            }
            val output = ByteArrayOutputStream()
            var readBytes = 0L
            source.chunks.collect { chunk ->
                readBytes += chunk.size
                if (readBytes > maxBytes) {
                    return@collect
                }
                output.write(chunk)
            }
            if (readBytes > maxBytes) {
                false
            } else {
                output.toString(Charsets.UTF_8).lowercase().contains(query)
            }
        }
    }
}
