package com.oruke.onyx.vfs.local

import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.OnyxError
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProtocol
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.name
import kotlin.io.path.pathString

/**
 * 使用入口自身的元数据构造统一文件对象，挂载目标失效不影响父目录列项。
 *
 * @param parent 父目录路径。
 * @return 对应的 VFS 文件对象。
 */
internal fun Path.toLocalVFile(parent: Path): VFile {
    // Junction 的目标可能离线，不能用跟随目标的 size、时间或存在性判断替代入口属性。
    val attributes = Files.readAttributes(this, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    val directory = attributes.isDirectory || (attributes.isSymbolicLink && Files.isDirectory(this))
    val isListableDirectory = directory && LocalDirectoryAccess.isListable(this)
    val normalizedPath = toAbsolutePath().normalize()
    return VFile(
        id = normalizedPath.pathString,
        // 使用文件系统对象身份追踪路径变化；硬链接歧义由共享目录层排除。
        identityKey = normalizedPath.readLocalIdentityKey(attributes),
        name = name.ifBlank { pathString },
        location = normalizedPath.pathString,
        parentLocation = parent.toAbsolutePath().normalize().pathString,
        kind = if (directory) VFileKind.DIRECTORY else VFileKind.FILE,
        sizeBytes = if (directory || attributes.isSymbolicLink || attributes.isOther) null else attributes.size(),
        modifiedAtEpochMillis = attributes.lastModifiedTime().toMillis(),
        hidden = runCatching { Files.isHidden(this) }.getOrDefault(false) || name.startsWith("."),
        capabilities = buildSet {
            add(VFileCapability.READ_METADATA)
            add(VFileCapability.RENAME)
            add(VFileCapability.DELETE)
            if (isListableDirectory) {
                add(VFileCapability.LIST_CHILDREN)
            } else if (
                attributes.isRegularFile || (attributes.isSymbolicLink && Files.isRegularFile(this@toLocalVFile))
            ) {
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
        Result.failure(failure.toLocalFailure())
    } ?: this
}

/**
 * 将可识别的本地文件异常转换为结构化 VFS 异常。
 *
 * @return 可本地化业务异常、结构化 VFS 异常或兼容现有调用方的通用本地异常。
 */
private fun Throwable.toLocalFailure(): Throwable {
    val errorMessage = message ?: localizedMessage
    return when (this) {
        // 业务校验已经携带消息键，重新封装为普通 I/O 异常会丢失本地化信息。
        is LocalizedException -> this
        is AccessDeniedException -> VfsProviderException(
            VfsProviderError.PermissionDenied(
                protocol = VfsProtocol.LOCAL,
                location = file ?: errorMessage,
                reason = reason,
            )
        )

        is NoSuchFileException -> VfsProviderException(
            VfsProviderError.NotFound(
                protocol = VfsProtocol.LOCAL,
                location = file ?: errorMessage,
            )
        )

        is FileAlreadyExistsException -> VfsProviderException(
            VfsProviderError.AlreadyExists(
                protocol = VfsProtocol.LOCAL,
                location = file ?: errorMessage,
            )
        )

        else -> toLocalOnyxError().toException()
    }
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
