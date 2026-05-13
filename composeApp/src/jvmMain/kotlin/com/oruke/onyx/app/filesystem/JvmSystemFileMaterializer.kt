package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.nio.file.Files

/**
 * JVM 平台系统文件物化服务。
 *
 * 负责把本地文件、压缩包条目以及支持内容读取的远程 VFS 文件转换为系统 API 可接收的本地文件。
 *
 * @property archiveService 压缩包条目导出服务。
 * @param contentServices 可按位置路由的内容读取服务列表。
 */
class JvmSystemFileMaterializer(
    private val archiveService: ArchiveService,
    contentServices: List<RoutableVfsContentService>,
) : SystemFileMaterializer {
    private val contentServices = contentServices.toList()
    private val materializedFiles = mutableMapOf<String, VFile>()

    /**
     * 判断条目是否能被物化为系统本地文件。
     *
     * @param entry 待判断的 VFS 条目。
     * @return `true` 表示可作为本地文件交给系统菜单、打开方式或外部拖拽。
     */
    override fun supports(entry: VFile): Boolean {
        if (entry.systemLocalPathOrNull() != null) return true
        if (entry.kind == VFileKind.DIRECTORY) return false
        return contentServices.any { service -> service.supports(entry.location) }
    }

    /**
     * 将 VFS 条目物化为本地临时文件。
     *
     * 本地文件直接返回；压缩包条目优先走 `ArchiveService`；其他远程条目通过统一内容服务导出。
     *
     * @param entry 待物化的 VFS 条目。
     * @return 指向系统本地文件位置的 VFS 条目。
     */
    override suspend fun materialize(entry: VFile): Result<VFile> = withContext(Dispatchers.IO) {
        runCatching {
            entry.systemLocalPathOrNull()?.let { return@runCatching entry }
            val cached = synchronized(materializedFiles) {
                materializedFiles[entry.location]?.takeIf { cachedEntry ->
                    cachedEntry.systemLocalPathOrNull()?.let(Files::isRegularFile) == true
                }
            }
            if (cached != null) return@runCatching cached

            materializeArchiveEntry(entry)?.let { materialized ->
                synchronized(materializedFiles) {
                    materializedFiles[entry.location] = materialized
                }
                return@runCatching materialized
            }

            val service = contentServices.firstOrNull { candidate -> candidate.supports(entry.location) }
                ?: throw VfsProviderNotFoundException(entry.location)
            val source = service.readFile(entry).getOrThrow()
            val tempDir = Files.createTempDirectory("onyx-system-file-")
            val tempFile = tempDir.resolve(entry.safeSystemFileName())
            Files.newOutputStream(tempFile).use { output ->
                source.chunks.collect { chunk -> output.write(chunk) }
            }
            tempDir.toFile().deleteOnExit()
            tempFile.toFile().deleteOnExit()

            entry.copy(location = tempFile.toString()).also { materialized ->
                synchronized(materializedFiles) {
                    materializedFiles[entry.location] = materialized
                }
            }
        }
    }

    /**
     * 将压缩包内部条目解压到临时目录。
     *
     * @param entry 待物化的压缩包内部条目。
     * @return 解压后的本地文件条目；非压缩包内部文件返回 `null`。
     */
    private suspend fun materializeArchiveEntry(entry: VFile): VFile? {
        val (archivePath, innerPath) = ArchiveService.parseArchiveLocation(entry.location) ?: return null
        if (innerPath.isBlank()) return null
        val tempDir = Files.createTempDirectory("onyx-system-file-")
        archiveService.extractEntriesToTemp(
            archivePath = archivePath,
            entryPaths = listOf(innerPath),
            targetDir = tempDir.toString(),
        ).getOrThrow()
        val tempFile = tempDir.resolve(entry.safeSystemFileName())
        tempDir.toFile().deleteOnExit()
        tempFile.toFile().deleteOnExit()
        return entry.copy(location = tempFile.toString())
    }

    /**
     * 生成可用于临时文件的安全文件名。
     *
     * @return 去除路径分隔符后的文件名。
     */
    private fun VFile.safeSystemFileName(): String {
        return name
            .replace('/', '_')
            .replace('\\', '_')
            .takeIf { value -> value.isNotBlank() }
            ?: "item"
    }
}
