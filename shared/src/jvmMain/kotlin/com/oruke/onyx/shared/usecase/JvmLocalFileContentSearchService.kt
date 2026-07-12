package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** 使用 `java.nio` 在 JVM 本地文件中执行受大小限制的 UTF-8 内容搜索。 */
class JvmLocalFileContentSearchService : FileContentSearchService {
    /**
     * 检查位置是否为本地路径。
     *
     * @param location VFS 或本地位置。
     * @return 不含 URI scheme 时返回 true。
     */
    override fun supportsLocation(location: String): Boolean {
        return !location.contains("://")
    }

    /**
     * 检查条目是否为可读取的本地文件。
     *
     * @param entry 待检查条目。
     * @return 本地文件条目返回 true。
     */
    override fun supports(entry: VFile): Boolean {
        return entry.kind == VFileKind.FILE && !entry.location.contains("://")
    }

    /**
     * 在 IO 调度器读取受大小限制的 UTF-8 文件并执行小写包含匹配。
     *
     * @param entry 待检索本地文件。
     * @param query 已规范化的小写查询文本。
     * @param maxBytes 最大读取字节数。
     * @return 成功时携带是否包含，读取失败时携带异常。
     */
    override suspend fun contains(
        entry: VFile,
        query: String,
        maxBytes: Long,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val path = Path.of(entry.location).normalize().toAbsolutePath()
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return@runCatching false
            }
            val size = Files.size(path)
            if (size > maxBytes) {
                return@runCatching false
            }
            val content = String(Files.readAllBytes(path), Charsets.UTF_8)
            content.lowercase().contains(query)
        }
    }
}
