package com.oruke.onyx.shared.usecase

import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 目录比较请求。
 *
 * @property leftLocation 左侧目录位置。
 * @property rightLocation 右侧目录位置。
 * @property recursive 是否递归比较子目录。
 */
data class DirectoryComparisonRequest(
    val leftLocation: String,
    val rightLocation: String,
    val recursive: Boolean = false,
)

/**
 * 目录差异类型。
 */
enum class DirectoryDifferenceKind {
    LEFT_ONLY,
    RIGHT_ONLY,
    LEFT_NEWER,
    RIGHT_NEWER,
    SIZE_DIFFERENT,
    SAME,
}

/**
 * 单个相对路径的目录比较结果。
 *
 * @property relativePath 相对比较根目录的路径。
 * @property kind 差异类型。
 * @property left 左侧条目。
 * @property right 右侧条目。
 */
data class DirectoryDifference(
    val relativePath: String,
    val kind: DirectoryDifferenceKind,
    val left: VFile?,
    val right: VFile?,
)

/**
 * 目录比较结果。
 *
 * @property request 原始比较请求。
 * @property differences 差异明细。
 */
data class DirectoryComparisonResult(
    val request: DirectoryComparisonRequest,
    val differences: List<DirectoryDifference>,
)

/**
 * Directory Opus 风格的目录比较用例。
 *
 * @param fileRepository 文件列表仓库。
 */
class DirectoryComparisonUseCase(
    private val fileRepository: FileRepository,
) {
    /**
     * 比较左右两个目录并生成差异明细。
     *
     * @param request 目录比较请求。
     * @return 目录比较结果。
     */
    suspend fun compare(request: DirectoryComparisonRequest): Result<DirectoryComparisonResult> =
        withContext(Dispatchers.Default) {
            runCatching {
                val leftEntries = collectEntries(request.leftLocation, request.recursive)
                val rightEntries = collectEntries(request.rightLocation, request.recursive)
                val paths = (leftEntries.keys + rightEntries.keys).toSortedSet()
                DirectoryComparisonResult(
                    request = request,
                    differences = paths.map { path ->
                        compareEntry(path, leftEntries[path], rightEntries[path])
                    },
                )
            }
        }

    /**
     * 收集指定目录下参与比较的条目。
     *
     * @param rootLocation 比较根目录。
     * @param recursive 是否递归读取子目录。
     * @return 以相对路径索引的条目。
     */
    private suspend fun collectEntries(
        rootLocation: String,
        recursive: Boolean,
    ): Map<String, VFile> {
        val result = linkedMapOf<String, VFile>()
        val directories = ArrayDeque<String>()
        directories += rootLocation
        while (directories.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val current = directories.removeFirst()
            val entries = fileRepository.list(current).getOrThrow()
            entries.forEach { entry ->
                val key = entry.relativeKey(rootLocation)
                result[key] = entry
                if (recursive && entry.kind == VFileKind.DIRECTORY) {
                    directories += entry.location
                }
            }
        }
        return result
    }

    /**
     * 比较同一相对路径下的左右条目。
     *
     * @param relativePath 相对路径。
     * @param left 左侧条目。
     * @param right 右侧条目。
     * @return 单条差异结果。
     */
    private fun compareEntry(
        relativePath: String,
        left: VFile?,
        right: VFile?,
    ): DirectoryDifference {
        val leftModified = left?.modifiedAtEpochMillis ?: 0L
        val rightModified = right?.modifiedAtEpochMillis ?: 0L
        val kind = when {
            left != null && right == null -> DirectoryDifferenceKind.LEFT_ONLY
            left == null && right != null -> DirectoryDifferenceKind.RIGHT_ONLY
            left == null && right == null -> DirectoryDifferenceKind.SAME
            left?.kind != right?.kind -> DirectoryDifferenceKind.SIZE_DIFFERENT
            left?.sizeBytes != right?.sizeBytes -> DirectoryDifferenceKind.SIZE_DIFFERENT
            leftModified > rightModified -> DirectoryDifferenceKind.LEFT_NEWER
            rightModified > leftModified -> DirectoryDifferenceKind.RIGHT_NEWER
            else -> DirectoryDifferenceKind.SAME
        }
        return DirectoryDifference(
            relativePath = relativePath,
            kind = kind,
            left = left,
            right = right,
        )
    }
}

/**
 * 计算条目相对比较根目录的稳定 key。
 *
 * @param rootLocation 比较根目录位置。
 * @return 相对路径；无法基于位置计算时退回文件名。
 */
private fun VFile.relativeKey(rootLocation: String): String {
    return location
        .removePrefix(rootLocation.trimEnd('/', '\\'))
        .trimStart('/', '\\')
        .ifBlank { name }
}
