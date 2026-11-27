package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.RoutableVfsContentService
import com.oruke.onyx.vfs.api.SystemFileMaterializer
import com.oruke.onyx.vfs.api.VfsProviderNotFoundException
import com.oruke.onyx.vfs.archive.ArchiveService
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/**
 * JVM 平台系统文件物化服务。
 *
 * 负责把本地文件、压缩包条目以及支持内容读取的远程 VFS 文件转换为系统 API 可接收的本地文件。
 * 使用有界 LRU 缓存与显式临时目录清理替代 `File.deleteOnExit()`，避免长驻进程中静态钩子集合与缓存无限增长。
 *
 * @param archiveService 压缩包条目导出服务。
 * @param contentServices 可按位置路由的内容读取服务列表。
 * @param maxCachedEntries 允许缓存在磁盘与内存中的最大物化文件数量。
 */
class JvmSystemFileMaterializer(
    /** 压缩包条目导出服务。 */
    private val archiveService: ArchiveService,
    contentServices: List<RoutableVfsContentService>,
    /** 物化临时文件的有界缓存容量上限。 */
    private val maxCachedEntries: Int = DEFAULT_MAX_CACHED_ENTRIES,
) : SystemFileMaterializer, Closeable {
    /** 已物化文件的内部记录，关联可回收的临时目录。 */
    private data class MaterializedEntry(
        /** 指向本地临时文件的 VFS 条目。 */
        val file: VFile,
        /** 本次物化创建的独立临时目录。 */
        val tempDirectory: Path,
    )

    /** 路由内容服务快照。 */
    private val contentServices = contentServices.toList()

    /** 串行保护物化缓存与待回收目录表的锁。 */
    private val cacheLock = Any()

    /** 按最近使用顺序维护的物化条目表。 */
    private val materializedFiles = LinkedHashMap<String, MaterializedEntry>(INITIAL_CACHE_CAPACITY, LOAD_FACTOR, true)

    /** 当前受本实例管理的临时目录集合，用于进程退出或关闭时统一回收。 */
    private val managedTempDirs = linkedSetOf<Path>()

    /** 单一 JVM 退出清理钩子，取代逐文件追加到 JDK 静态 `DeleteOnExitHook` 的泄露写法。 */
    private val shutdownHook = Thread(::cleanupManagedTempDirsQuietly, "onyx-materializer-cleanup")

    /** 是否已向 JVM 注册退出清理钩子。 */
    private var shutdownHookRegistered = false

    /**
     * 判断条目是否能被物化为系统本地文件。
     *
     * @param entry 待判断的 VFS 条目。
     * @return `true` 表示可作为本地文件交给系统菜单、打开方式或外部拖拽。
     */
    override fun supports(entry: VFile): Boolean {
        if (entry.systemLocalPathOrNull() != null) return true
        if (entry.kind == VFileKind.DIRECTORY) return false
        if (ArchiveService.parseArchiveLocation(entry.location)?.second?.isNotBlank() == true) return true
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
            val cached = synchronized(cacheLock) {
                materializedFiles[entry.location]?.takeIf { record ->
                    record.file.systemLocalPathOrNull()?.let(Files::isRegularFile) == true
                }?.file
            }
            if (cached != null) return@runCatching cached

            materializeArchiveEntry(entry)?.let { record ->
                cacheMaterializedEntry(entry.location, record)
                return@runCatching record.file
            }

            val service = contentServices.firstOrNull { candidate -> candidate.supports(entry.location) }
                ?: throw VfsProviderNotFoundException(entry.location)
            val source = service.readFile(entry).getOrThrow()
            val tempDir = Files.createTempDirectory(TEMP_DIR_PREFIX)
            val tempFile = tempDir.resolve(entry.safeSystemFileName())
            try {
                Files.newOutputStream(tempFile).use { output ->
                    source.chunks.collect { chunk -> output.write(chunk) }
                }
            } catch (failure: Throwable) {
                deleteDirectoryQuietly(tempDir)
                throw failure
            }

            val materialized = entry.copy(location = tempFile.toString())
            cacheMaterializedEntry(entry.location, MaterializedEntry(materialized, tempDir))
            materialized
        }
    }

    /**
     * 关闭服务并立即清理全部由本实例创建的临时目录，同时注销 JVM 退出钩子。
     */
    override fun close() {
        val registered = synchronized(cacheLock) {
            val wasRegistered = shutdownHookRegistered
            shutdownHookRegistered = false
            wasRegistered
        }
        if (registered) {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }
        cleanupManagedTempDirsQuietly()
    }

    /**
     * 写入物化缓存并在超过容量上限时淘汰最久未使用条目的临时目录。
     *
     * @param sourceLocation 原始 VFS 位置键。
     * @param record 新生成的物化条目与临时目录。
     */
    private fun cacheMaterializedEntry(sourceLocation: String, record: MaterializedEntry) {
        val evictedDirs = mutableListOf<Path>()
        synchronized(cacheLock) {
            if (!shutdownHookRegistered) {
                runCatching {
                    Runtime.getRuntime().addShutdownHook(shutdownHook)
                    shutdownHookRegistered = true
                }
            }
            materializedFiles.remove(sourceLocation)?.let { previous ->
                if (previous.tempDirectory != record.tempDirectory) {
                    managedTempDirs.remove(previous.tempDirectory)
                    evictedDirs.add(previous.tempDirectory)
                }
            }
            materializedFiles[sourceLocation] = record
            managedTempDirs.add(record.tempDirectory)
            val limit = maxCachedEntries.coerceAtLeast(1)
            val iterator = materializedFiles.entries.iterator()
            while (materializedFiles.size > limit && iterator.hasNext()) {
                val eldest = iterator.next().value
                iterator.remove()
                managedTempDirs.remove(eldest.tempDirectory)
                evictedDirs.add(eldest.tempDirectory)
            }
        }
        evictedDirs.forEach(::deleteDirectoryQuietly)
    }

    /**
     * 将压缩包内部条目解压到临时目录。
     *
     * @param entry 待物化的压缩包内部条目。
     * @return 解压后的本地文件记录；非压缩包内部文件返回 `null`。
     */
    private suspend fun materializeArchiveEntry(entry: VFile): MaterializedEntry? {
        val (archivePath, innerPath) = ArchiveService.parseArchiveLocation(entry.location) ?: return null
        if (innerPath.isBlank()) return null
        val tempDir = Files.createTempDirectory(TEMP_DIR_PREFIX)
        return try {
            archiveService.extractEntriesToTemp(
                archivePath = archivePath,
                entryPaths = listOf(innerPath),
                targetDir = tempDir.toString(),
            ).getOrThrow()
            val extractedName = innerPath.trimEnd('/').substringAfterLast('/')
            val extractedCandidate = runCatching { tempDir.resolve(extractedName) }.getOrNull()
            val tempFile = if (extractedCandidate != null && Files.exists(extractedCandidate)) {
                extractedCandidate
            } else {
                tempDir.resolve(entry.safeSystemFileName())
            }
            MaterializedEntry(
                file = entry.copy(location = tempFile.toString()),
                tempDirectory = tempDir,
            )
        } catch (failure: Throwable) {
            deleteDirectoryQuietly(tempDir)
            throw failure
        }
    }

    /** 清空缓存表并删除当前实例仍托管的全部临时目录。 */
    private fun cleanupManagedTempDirsQuietly() {
        val dirsToDelete = synchronized(cacheLock) {
            materializedFiles.clear()
            managedTempDirs.toList().also { managedTempDirs.clear() }
        }
        dirsToDelete.forEach(::deleteDirectoryQuietly)
    }

    /**
     * 递归删除单个临时目录，吞掉单文件被外部应用占用时的删除异常以免影响其余清理。
     *
     * @param directory 待清理的临时目录。
     */
    private fun deleteDirectoryQuietly(directory: Path) {
        runCatching {
            if (!Files.exists(directory)) return
            Files.walk(directory).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { path ->
                    runCatching { Files.deleteIfExists(path) }
                }
            }
        }
    }

    /**
     * 生成可用于临时文件的安全文件名，替换平台非法路径字符并去除尾随点与空格。
     *
     * @return 去除非法字符后的文件名。
     */
    private fun VFile.safeSystemFileName(): String {
        return name
            .replace(UNSAFE_FILE_NAME_CHARS, "_")
            .trimEnd('.', ' ')
            .takeIf { value -> value.isNotBlank() }
            ?: "item"
    }

    private companion object {
        /** 物化临时目录名称前缀。 */
        private const val TEMP_DIR_PREFIX = "onyx-system-file-"

        /** 默认最大物化缓存条目数。 */
        private const val DEFAULT_MAX_CACHED_ENTRIES = 64

        /** LRU 映射初始容量。 */
        private const val INITIAL_CACHE_CAPACITY = 32

        /** LRU 映射负载因子。 */
        private const val LOAD_FACTOR = 0.75f

        /** 跨平台文件名非法字符正则。 */
        private val UNSAFE_FILE_NAME_CHARS = Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]")
    }
}
