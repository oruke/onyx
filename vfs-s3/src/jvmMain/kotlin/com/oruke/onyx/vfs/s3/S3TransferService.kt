package com.oruke.onyx.vfs.s3

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsContentSource
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProviderNotFoundException
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.withVfsCopySuffix
import com.oruke.onyx.vfs.api.withVfsTrailingSlash
import kotlinx.coroutines.flow.Flow

/**
 * S3 递归传输访问入口，由 Provider 实现统一文件 API。
 */
internal interface S3TransferGateway {
    /**
     * 判断位置是否属于当前 S3 Provider。
     *
     * @param location 待判断位置。
     * @return 支持时返回 `true`。
     */
    fun supports(location: String): Boolean

    /**
     * 列出目录条目。
     *
     * @param location 目录位置。
     * @return 目录条目结果。
     */
    suspend fun list(location: String): Result<List<VFile>>

    /**
     * 读取文件内容。
     *
     * @param entry 文件条目。
     * @return 文件内容源。
     */
    suspend fun readFile(entry: VFile): Result<VfsContentSource>

    /**
     * 写入文件内容。
     *
     * @param parentLocation 父目录位置。
     * @param name 文件名。
     * @param chunks 内容块。
     * @param conflictStrategy 冲突处理策略。
     * @return 实际写入条目；跳过时返回 `null`。
     */
    suspend fun writeFile(
        parentLocation: String,
        name: String,
        chunks: Flow<ByteArray>,
        conflictStrategy: TransferConflictStrategy,
    ): Result<VFile?>

    /**
     * 创建目录占位对象。
     *
     * @param parentLocation 父目录位置。
     * @param name 目录名。
     * @return 创建后的目录条目。
     */
    suspend fun createDirectory(
        parentLocation: String,
        name: String,
    ): Result<VFile>

    /**
     * 删除条目。
     *
     * @param entries 待删除条目。
     * @return 删除结果。
     */
    suspend fun delete(entries: List<VFile>): Result<Unit>
}

/**
 * S3 文件与目录递归复制、移动服务。
 *
 * @param gateway 统一文件操作入口。
 */
internal class S3TransferService(
    private val gateway: S3TransferGateway,
) {
    /**
     * 在 S3 内复制或移动文件对象。
     *
     * @param entries 源文件对象列表。
     * @param targetDirectoryLocation 目标目录位置。
     * @param conflictStrategy 冲突处理策略。
     * @param deleteSource 写入完成后是否删除源对象。
     * @return 执行结果。
     */
    suspend fun transferObjects(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
        deleteSource: Boolean,
    ): Result<Unit> {
        if (entries.isEmpty()) return Result.success(Unit)
        val unsupportedLocation = if (!gateway.supports(targetDirectoryLocation)) {
            targetDirectoryLocation
        } else {
            entries.firstOrNull { entry -> !gateway.supports(entry.location) }?.location
        }
        return if (unsupportedLocation != null) {
            Result.failure(VfsProviderNotFoundException(unsupportedLocation))
        } else {
            runCatching {
                entries.forEach { entry ->
                    val transferred = transferEntry(
                        entry = entry,
                        targetDirectoryLocation = targetDirectoryLocation,
                        conflictStrategy = conflictStrategy,
                        deleteSource = deleteSource,
                    )
                    if (deleteSource && transferred) {
                        gateway.delete(listOf(entry)).getOrThrow()
                    }
                }
            }
        }
    }

    /**
     * 复制单个文件或目录。
     *
     * @param entry 源条目。
     * @param targetDirectoryLocation 目标目录位置。
     * @param conflictStrategy 冲突处理策略。
     * @param deleteSource 是否按移动语义执行。
     * @return 实际写入目标时返回 `true`。
     */
    private suspend fun transferEntry(
        entry: VFile,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
        deleteSource: Boolean,
    ): Boolean {
        return if (entry.kind == VFileKind.DIRECTORY) {
            if (isSameOrChildDirectory(targetDirectoryLocation, entry)) {
                val capability = if (deleteSource) VfsProviderCapability.MOVE else VfsProviderCapability.COPY
                throw unsupported(targetDirectoryLocation, capability)
            }
            copyDirectory(entry, targetDirectoryLocation, entry.name, conflictStrategy) != null
        } else {
            val source = gateway.readFile(entry).getOrThrow()
            gateway.writeFile(
                parentLocation = targetDirectoryLocation,
                name = entry.name,
                chunks = source.chunks,
                conflictStrategy = conflictStrategy,
            ).getOrThrow() != null
        }
    }

    /**
     * 递归复制 S3 目录，并保留空目录占位对象。
     *
     * @param entry 源目录条目。
     * @param targetDirectoryLocation 目标父目录位置。
     * @param targetName 目标目录名。
     * @param conflictStrategy 冲突处理策略。
     * @return 实际创建的目标目录；跳过冲突时返回 `null`。
     */
    suspend fun copyDirectory(
        entry: VFile,
        targetDirectoryLocation: String,
        targetName: String,
        conflictStrategy: TransferConflictStrategy,
    ): VFile? {
        val targetDirectory = createDirectoryForCopy(
            parentLocation = targetDirectoryLocation,
            name = targetName,
            conflictStrategy = conflictStrategy,
        ) ?: return null
        gateway.list(entry.location).getOrThrow().forEach { child ->
            if (child.kind == VFileKind.DIRECTORY) {
                copyDirectory(child, targetDirectory.location, child.name, conflictStrategy)
            } else {
                val source = gateway.readFile(child).getOrThrow()
                gateway.writeFile(
                    parentLocation = targetDirectory.location,
                    name = child.name,
                    chunks = source.chunks,
                    conflictStrategy = conflictStrategy,
                ).getOrThrow()
            }
        }
        return targetDirectory
    }

    /**
     * 按复制语义创建 S3 目录。
     *
     * @param parentLocation 目标父目录位置。
     * @param name 源目录名或新目录名。
     * @param conflictStrategy 冲突处理策略。
     * @return 创建或复用的目录条目；跳过冲突时返回 `null`。
     */
    private suspend fun createDirectoryForCopy(
        parentLocation: String,
        name: String,
        conflictStrategy: TransferConflictStrategy,
    ): VFile? {
        val targetName = resolveDirectoryNameForCopy(parentLocation, name, conflictStrategy) ?: return null
        val parent = S3Location.parse(parentLocation)
        return gateway.createDirectory(parentLocation, targetName).recoverCatching { failure ->
            if (conflictStrategy == TransferConflictStrategy.OVERWRITE && failure.isAlreadyExists()) {
                parent
                    .copy(prefix = parent.directoryPrefix + targetName.withVfsTrailingSlash())
                    .toDirectoryVFile(name = targetName, parentLocation = parent.directoryLocation)
            } else {
                throw failure
            }
        }.getOrThrow()
    }

    /**
     * 根据冲突策略解析目录复制的目标名称。
     *
     * @param parentLocation 目标父目录位置。
     * @param name 原始目录名。
     * @param conflictStrategy 冲突处理策略。
     * @return 目标目录名；跳过冲突时返回 `null`。
     */
    private suspend fun resolveDirectoryNameForCopy(
        parentLocation: String,
        name: String,
        conflictStrategy: TransferConflictStrategy,
    ): String? {
        val targetName = validateS3TargetName(name)
        val existingNames = gateway.list(parentLocation).getOrThrow().mapTo(mutableSetOf()) { entry -> entry.name }
        return when (conflictStrategy) {
            TransferConflictStrategy.OVERWRITE -> targetName
            TransferConflictStrategy.SKIP -> targetName.takeUnless { it in existingNames }
            TransferConflictStrategy.KEEP_BOTH -> availableDirectoryName(targetName, existingNames, parentLocation)
        }
    }

    /**
     * 生成可用目录副本名称。
     *
     * @param targetName 原始目录名。
     * @param existingNames 已有名称集合。
     * @param parentLocation 父目录位置。
     * @return 可用目录名。
     */
    private fun availableDirectoryName(
        targetName: String,
        existingNames: Set<String>,
        parentLocation: String,
    ): String {
        val candidate = (0 until MAX_KEEP_BOTH_ATTEMPTS)
            .asSequence()
            .map { index -> if (index == 0) targetName else targetName.withVfsCopySuffix(index) }
            .firstOrNull { name -> name !in existingNames }
        return candidate ?: throw VfsProviderException(
            VfsProviderError.AlreadyExists(VfsProtocol.S3, parentLocation)
        )
    }

    /**
     * 判断目标目录是否与源目录相同或位于源目录内部。
     *
     * @param targetDirectoryLocation 目标父目录位置。
     * @param sourceDirectory 源目录条目。
     * @return 相同或位于源目录内部时返回 `true`。
     */
    private fun isSameOrChildDirectory(
        targetDirectoryLocation: String,
        sourceDirectory: VFile,
    ): Boolean {
        val target = S3Location.parse(targetDirectoryLocation).directoryLocation
        val source = S3Location.parse(sourceDirectory.location).directoryLocation
        return target == source || target.startsWith(source)
    }

    /**
     * 构建 S3 不支持操作异常。
     *
     * @param location 请求位置。
     * @param capability 请求能力。
     * @return 结构化 VFS 异常。
     */
    private fun unsupported(
        location: String,
        capability: VfsProviderCapability,
    ): VfsProviderException {
        return VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.S3,
                location = location,
                capability = capability,
            )
        )
    }

    /** 最大副本名称尝试次数。 */
    private companion object {
        const val MAX_KEEP_BOTH_ATTEMPTS = 10_000
    }
}

/**
 * 判断异常是否表示 S3 目标已存在。
 *
 * @return 已存在错误返回 `true`。
 */
private fun Throwable.isAlreadyExists(): Boolean {
    return this is VfsProviderException && error is VfsProviderError.AlreadyExists
}

/**
 * 校验 S3 文件或目录名。
 *
 * @param name 待校验名称。
 * @return 去除首尾空白后的名称。
 */
private fun validateS3TargetName(name: String): String {
    val targetName = name.trim()
    if (targetName.isBlank() || '/' in targetName || '\\' in targetName) {
        throw VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.S3,
                capability = VfsProviderCapability.CREATE_FILE,
            )
        )
    }
    return targetName
}
