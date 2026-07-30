package com.oruke.onyx.vfs.archive

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.RoutableVfsContentService
import com.oruke.onyx.vfs.api.VfsProviderNotFoundException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 将统一 VFS 条目递归写入 ZIP 输出流的写入器。
 *
 * @param fileRepository 用于列出目录直接子项的统一文件仓库。
 * @param contentServices 用于读取文件内容的可路由服务集合。
 * @param output 已打开的 ZIP 输出流。
 * @param progressSink 文件写入完成时的进度接收器。
 */
internal class ZipArchiveEntryWriter(
    private val fileRepository: FileRepository,
    private val contentServices: List<RoutableVfsContentService>,
    private val output: ZipOutputStream,
    private val progressSink: ZipArchiveCreationProgressSink,
) {
    /** 已写入的 ZIP 条目路径，用于避免重复条目破坏归档。 */
    private val writtenEntryPaths = mutableSetOf<String>()

    /** 已递归枚举的目录位置，用于阻止异常 provider 返回目录循环。 */
    private val visitedDirectoryLocations = mutableSetOf<String>()

    /** 已完整写入的文件数量。 */
    private var writtenFileCount = 0

    /** 已写入 ZIP 文件内容的原始字节数。 */
    private var writtenBytes = 0L

    /**
     * 递归写入根选择项。
     *
     * @param entries 待写入 ZIP 的根文件或目录。
     * @return 最终写入的文件与目录条目总数。
     */
    suspend fun writeEntries(entries: List<VFile>): Int {
        val rootPaths = mutableSetOf<String>()
        entries.distinctBy { entry -> entry.location }.forEach { entry ->
            currentCoroutineContext().ensureActive()
            val entryPath = entry.name.nextAvailableZipArchiveRootPath(rootPaths)
            rootPaths += entryPath
            writeEntry(entry, entryPath)
        }
        return writtenEntryPaths.size
    }

    /**
     * 按 VFS 条目类型将文件或目录写入指定 ZIP 路径。
     *
     * @param entry 当前待处理的 VFS 条目。
     * @param entryPath 当前条目在 ZIP 内的规范化路径。
     * @return 无返回值。
     */
    private suspend fun writeEntry(
        entry: VFile,
        entryPath: String,
    ) {
        currentCoroutineContext().ensureActive()
        when (entry.kind) {
            VFileKind.DIRECTORY -> writeDirectory(entry, entryPath)
            VFileKind.FILE -> writeFile(entry, entryPath)
        }
    }

    /**
     * 写入目录条目并递归处理其子项。
     *
     * 对已访问目录位置停止递归，是为了防止异常 provider 或循环挂载导致无限遍历。
     *
     * @param entry 当前待写入的目录条目。
     * @param entryPath 当前目录在 ZIP 内的规范化路径。
     * @return 无返回值。
     */
    private suspend fun writeDirectory(
        entry: VFile,
        entryPath: String,
    ) {
        writeDirectoryEntry(entryPath)
        if (!visitedDirectoryLocations.add(entry.location)) return
        fileRepository.list(entry.location).getOrThrow()
            .sortedWith(compareBy<VFile>({ child -> child.name }, { child -> child.location }))
            .forEach { child ->
                writeEntry(
                    entry = child,
                    entryPath = entryPath.appendZipArchiveEntrySegment(child.name),
                )
            }
    }

    /**
     * 写入单个文件的内容流。
     *
     * @param entry 当前待写入的文件条目。
     * @param entryPath 当前文件在 ZIP 内的规范化路径。
     * @return 无返回值。
     */
    private suspend fun writeFile(
        entry: VFile,
        entryPath: String,
    ) {
        val contentService = contentServices.firstOrNull { service -> service.supports(entry.location) }
            ?: throw VfsProviderNotFoundException(entry.location)
        val source = contentService.readFile(entry).getOrThrow()
        require(writtenEntryPaths.add(entryPath)) { "ZIP 内存在重复条目: $entryPath" }
        output.putNextEntry(ZipEntry(entryPath))
        try {
            source.chunks.collect { chunk ->
                currentCoroutineContext().ensureActive()
                output.write(chunk)
                writtenBytes += chunk.size
            }
        } finally {
            output.closeEntry()
        }
        writtenFileCount += 1
        progressSink.onProgress(
            ZipArchiveCreationProgress(
                currentFileName = entry.name,
                writtenFileCount = writtenFileCount,
                writtenBytes = writtenBytes,
            )
        )
    }

    /**
     * 写入显式目录 ZIP 条目，从而保留空目录。
     *
     * @param entryPath 不含尾部 `/` 的目录 ZIP 路径。
     * @return 无返回值。
     */
    private fun writeDirectoryEntry(entryPath: String) {
        val directoryPath = "${entryPath.trimEnd('/')}/"
        if (!writtenEntryPaths.add(directoryPath)) return
        output.putNextEntry(ZipEntry(directoryPath))
        output.closeEntry()
    }
}

/**
 * ZIP 创建过程中的文件写入进度接收器。
 */
fun interface ZipArchiveCreationProgressSink {
    /**
     * 接收最新的 ZIP 写入进度。
     *
     * @param progress 当前压缩进度。
     * @return 无返回值。
     */
    suspend fun onProgress(progress: ZipArchiveCreationProgress)

    /** 不需要进度时使用的空实现。 */
    object NoOp : ZipArchiveCreationProgressSink {
        /**
         * 忽略 ZIP 写入进度。
         *
         * @param progress 当前压缩进度。
         * @return 无返回值。
         */
        override suspend fun onProgress(progress: ZipArchiveCreationProgress) = Unit
    }
}

/**
 * ZIP 创建过程中的文件级进度。
 *
 * @property currentFileName 刚完成写入的源文件名称。
 * @property writtenFileCount 已完整写入 ZIP 的文件数量。
 * @property writtenBytes 已写入 ZIP 的原始内容字节数。
 */
data class ZipArchiveCreationProgress(
    /** 刚完成写入的源文件名称。 */
    val currentFileName: String,
    /** 已完整写入 ZIP 的文件数量。 */
    val writtenFileCount: Int,
    /** 已写入 ZIP 的原始内容字节数。 */
    val writtenBytes: Long,
)
