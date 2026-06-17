package com.oruke.onyx.shared.usecase

import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 重复文件匹配模式。
 */
enum class DuplicateMatchMode {
    NAME,
    SIZE,
    NAME_AND_SIZE,
    HASH,
}

/**
 * 重复文件查找请求。
 *
 * @property roots 查找根目录列表。
 * @property mode 匹配模式。
 * @property recursive 是否递归子目录。
 * @property minSizeBytes 小于该大小的文件不参与比较。
 * @property maxGroups 最多返回的重复组数量。
 */
data class DuplicateFileFindRequest(
    val roots: List<String>,
    val mode: DuplicateMatchMode = DuplicateMatchMode.NAME_AND_SIZE,
    val recursive: Boolean = true,
    val minSizeBytes: Long = 1L,
    val maxGroups: Int = 200,
)

/**
 * 重复文件组。
 *
 * @property key 匹配键。
 * @property entries 命中的重复文件。
 */
data class DuplicateFileGroup(
    val key: String,
    val entries: List<VFile>,
)

/**
 * 重复文件查找结果。
 *
 * @property scannedFileCount 已扫描文件数。
 * @property groups 重复文件分组。
 * @property limitReached 是否达到返回上限。
 */
data class DuplicateFileFindResult(
    val scannedFileCount: Int,
    val groups: List<DuplicateFileGroup>,
    val limitReached: Boolean,
)

/**
 * 文件指纹读取接口，用于哈希模式。
 */
interface DuplicateFileFingerprintReader {
    /**
     * 读取文件指纹。
     *
     * @param entry 待读取的文件条目。
     * @return 指纹文本；无法读取时返回 `null`。
     */
    suspend fun fingerprint(entry: VFile): Result<String?>
}

/**
 * 默认不读取哈希的指纹实现。
 */
object NoOpDuplicateFileFingerprintReader : DuplicateFileFingerprintReader {
    override suspend fun fingerprint(entry: VFile): Result<String?> {
        return Result.success(null)
    }
}

/**
 * 重复文件查找用例。
 *
 * @param fileRepository 文件列表仓库。
 * @param fingerprintReader 文件指纹读取器。
 */
class DuplicateFileFinderUseCase(
    private val fileRepository: FileRepository,
    private val fingerprintReader: DuplicateFileFingerprintReader = NoOpDuplicateFileFingerprintReader,
) {
    /**
     * 查找重复文件。
     *
     * @param request 查找请求。
     * @return 重复文件查找结果。
     */
    suspend fun find(request: DuplicateFileFindRequest): Result<DuplicateFileFindResult> =
        withContext(Dispatchers.Default) {
            runCatching {
                val files = collectFiles(request)
                val groups = files
                    .mapNotNull { file -> file.duplicateKey(request.mode)?.let { key -> key to file } }
                    .groupBy(keySelector = { item -> item.first }, valueTransform = { item -> item.second })
                    .filterValues { entries -> entries.size > 1 }
                    .map { (key, entries) -> DuplicateFileGroup(key = key, entries = entries) }
                    .sortedByDescending { group -> group.entries.size }
                DuplicateFileFindResult(
                    scannedFileCount = files.size,
                    groups = groups.take(request.maxGroups),
                    limitReached = groups.size > request.maxGroups,
                )
            }
        }

    /**
     * 收集参与重复检测的文件。
     *
     * @param request 查找请求。
     * @return 文件条目列表。
     */
    private suspend fun collectFiles(request: DuplicateFileFindRequest): List<VFile> {
        val files = mutableListOf<VFile>()
        val directories = ArrayDeque<String>()
        directories.addAll(request.roots.distinct())
        val visited = mutableSetOf<String>()
        while (directories.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val location = directories.removeFirst()
            if (!visited.add(location)) {
                continue
            }
            val entries = fileRepository.list(location).getOrThrow()
            entries.forEach { entry ->
                when (entry.kind) {
                    VFileKind.FILE -> if ((entry.sizeBytes ?: 0L) >= request.minSizeBytes) {
                        files += entry
                    }
                    VFileKind.DIRECTORY -> if (request.recursive) {
                        directories += entry.location
                    }
                }
            }
        }
        return files
    }

    /**
     * 计算文件在指定匹配模式下的重复键。
     *
     * @param mode 匹配模式。
     * @return 重复键；无法参与比较时返回 `null`。
     */
    private suspend fun VFile.duplicateKey(mode: DuplicateMatchMode): String? {
        return when (mode) {
            DuplicateMatchMode.NAME -> "name:${name.lowercase()}"
            DuplicateMatchMode.SIZE -> sizeBytes?.let { size -> "size:$size" }
            DuplicateMatchMode.NAME_AND_SIZE -> sizeBytes?.let { size -> "name-size:${name.lowercase()}:$size" }
            DuplicateMatchMode.HASH -> fingerprintReader.fingerprint(this).getOrThrow()?.let { hash -> "hash:$hash" }
        }
    }
}
