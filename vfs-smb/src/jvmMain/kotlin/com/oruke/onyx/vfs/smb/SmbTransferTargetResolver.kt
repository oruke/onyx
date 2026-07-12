package com.oruke.onyx.vfs.smb

import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.withVfsTrailingSlash
import jcifs.smb.SmbFile

/**
 * 按冲突策略解析 SMB 传输目标。
 *
 * @param source 源 SMB 条目。
 * @param targetDirectory 目标 SMB 目录。
 * @param conflictStrategy 名称冲突处理策略。
 * @return 目标条目；按 SKIP 跳过时返回 `null`。
 */
internal fun resolveSmbTransferTarget(
    source: SmbFile,
    targetDirectory: SmbFile,
    conflictStrategy: TransferConflictStrategy,
): SmbFile? {
    ensureSmbSourceExists(source)
    val sourceName = source.name.trimEnd('/')
    val directTarget = SmbFile(targetDirectory, sourceName.withDirectoryMarker(source.isDirectory))
    validateSmbTransferTarget(source, targetDirectory, directTarget)
    return when {
        !directTarget.exists() -> directTarget
        conflictStrategy == TransferConflictStrategy.KEEP_BOTH -> availableSmbTarget(source, targetDirectory)
        conflictStrategy == TransferConflictStrategy.OVERWRITE -> directTarget
        conflictStrategy == TransferConflictStrategy.SKIP -> null
        else -> directTarget
    }
}

/**
 * 校验 SMB 源条目存在。
 *
 * @param source 源 SMB 条目。
 */
private fun ensureSmbSourceExists(source: SmbFile) {
    if (!source.exists()) {
        throw VfsProviderException(VfsProviderError.NotFound(VfsProtocol.SMB, source.canonicalPath))
    }
}

/**
 * 校验 SMB 传输不会覆盖源本身或把目录放入自身。
 *
 * @param source 源 SMB 条目。
 * @param targetDirectory 目标 SMB 目录。
 * @param directTarget 直接目标条目。
 */
private fun validateSmbTransferTarget(
    source: SmbFile,
    targetDirectory: SmbFile,
    directTarget: SmbFile,
) {
    if (directTarget.canonicalPath == source.canonicalPath) {
        throw unsupportedTransfer(source.canonicalPath)
    }
    val targetInsideSource = source.isDirectory && targetDirectory.canonicalPath.withVfsTrailingSlash()
        .startsWith(source.canonicalPath.withVfsTrailingSlash())
    if (targetInsideSource) {
        throw unsupportedTransfer(targetDirectory.canonicalPath)
    }
}

/**
 * 构建 SMB 传输不支持错误。
 *
 * @param location 发生冲突的位置。
 * @return 结构化 VFS 异常。
 */
private fun unsupportedTransfer(location: String): VfsProviderException {
    return VfsProviderException(
        VfsProviderError.UnsupportedOperation(
            protocol = VfsProtocol.SMB,
            location = location,
            capability = null,
        )
    )
}

/**
 * 为 SMB 条目生成不冲突的副本名称。
 *
 * @param source 源 SMB 条目。
 * @param targetDirectory 目标 SMB 目录。
 * @return 可用目标条目。
 */
private fun availableSmbTarget(
    source: SmbFile,
    targetDirectory: SmbFile,
): SmbFile {
    val originalName = source.name.trimEnd('/')
    val directory = source.isDirectory
    val dotIndex = originalName.lastIndexOf('.')
    val hasExtension = !directory && dotIndex > 0 && dotIndex < originalName.lastIndex
    val baseName = if (hasExtension) originalName.substring(0, dotIndex) else originalName
    val extension = if (hasExtension) originalName.substring(dotIndex) else ""
    var copyIndex = 1
    var candidate = SmbFile(targetDirectory, originalName.withDirectoryMarker(directory))
    while (candidate.exists()) {
        val suffix = if (copyIndex == 1) " copy" else " copy $copyIndex"
        candidate = SmbFile(targetDirectory, "$baseName$suffix$extension".withDirectoryMarker(directory))
        copyIndex += 1
    }
    return candidate
}

/**
 * 为目录名称补齐 SMB 目录标记。
 *
 * @param directory 是否为目录。
 * @return 规范化名称。
 */
private fun String.withDirectoryMarker(directory: Boolean): String {
    return if (directory) withVfsTrailingSlash() else trimEnd('/')
}
