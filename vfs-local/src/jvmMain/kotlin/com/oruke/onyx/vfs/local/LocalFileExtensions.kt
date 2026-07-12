package com.oruke.onyx.vfs.local

import com.oruke.onyx.core.model.OnyxError
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.pathString

/**
 * 将本地路径转换为统一 VFS 文件对象。
 *
 * @param parent 父目录路径。
 * @return 对应的 VFS 文件对象。
 */
internal fun Path.toLocalVFile(parent: Path): VFile {
    val directory = isDirectory()
    val normalizedPath = toAbsolutePath().normalize()
    return VFile(
        id = normalizedPath.pathString,
        name = name.ifBlank { pathString },
        location = normalizedPath.pathString,
        parentLocation = parent.toAbsolutePath().normalize().pathString,
        kind = if (directory) VFileKind.DIRECTORY else VFileKind.FILE,
        sizeBytes = if (directory) null else Files.size(this),
        modifiedAtEpochMillis = getLastModifiedTime().toMillis(),
        hidden = runCatching { Files.isHidden(this) }.getOrDefault(false) || name.startsWith("."),
        capabilities = buildSet {
            add(VFileCapability.READ_METADATA)
            add(VFileCapability.RENAME)
            add(VFileCapability.DELETE)
            if (directory) {
                add(VFileCapability.LIST_CHILDREN)
            } else {
                add(VFileCapability.READ_CONTENT)
                add(VFileCapability.WRITE_CONTENT)
            }
        },
    )
}

/**
 * 将本地文件操作失败统一映射为 Onyx 错误异常。
 *
 * @return 保留成功值或替换失败类型后的结果。
 */
internal fun <T> Result<T>.mapLocalError(): Result<T> {
    return exceptionOrNull()?.let { failure ->
        Result.failure(failure.toLocalOnyxError().toException())
    } ?: this
}

/**
 * 将 JVM 文件异常映射为统一 Onyx 错误。
 *
 * @return 统一错误模型。
 */
private fun Throwable.toLocalOnyxError(): OnyxError {
    val errorMessage = message ?: localizedMessage
    return when (this) {
        is AccessDeniedException -> OnyxError.AccessDenied(errorMessage ?: "Access denied")
        is NoSuchFileException -> OnyxError.InvalidLocation(errorMessage ?: "Invalid location")
        is IllegalArgumentException -> OnyxError.InvalidLocation(errorMessage ?: "Invalid location")
        is IOException -> OnyxError.IoFailure(errorMessage ?: "I/O failure")
        else -> OnyxError.IoFailure(errorMessage ?: "Unexpected I/O failure")
    }
}

/**
 * 将统一错误转换为当前文件仓库契约使用的异常。
 *
 * @return 携带用户可读信息的异常。
 */
private fun OnyxError.toException(): IllegalStateException {
    return IllegalStateException(message)
}
