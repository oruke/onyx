package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind

/**
 * 基于 VFS provider 路由的文件命令服务。
 *
 * 该服务负责在多个 provider 之间选择具体命令实现，并在 provider 边界不一致时通过内容流执行跨 provider 复制或移动。
 *
 * @param services 可路由文件命令服务列表。
 * @param contentServices 可路由内容读写服务列表。
 * @param providerRegistry provider 注册表，用于目录递归与错误语义构建。
 */
class ProviderBackedFileCommandService(
    services: List<RoutableFileCommandService>,
    contentServices: List<RoutableVfsContentService> = emptyList(),
    private val providerRegistry: VfsProviderRegistry? = null,
) : FileCommandService {
    /** 按构造入参固定下来的命令服务快照，避免运行期外部列表变化影响路由。 */
    private val services = services.toList()

    /** 按构造入参固定下来的内容服务快照，用于跨 provider 读写。 */
    private val contentServices = contentServices.toList()

    init {
        require(this.services.isNotEmpty()) {
            "At least one file command service must be registered"
        }
    }

    /**
     * 复制文件或目录到目标目录。
     *
     * @param entries 待复制条目。
     * @param targetDirectoryLocation 目标目录位置。
     * @param conflictStrategy 名称冲突处理策略。
     * @return 操作结果。
     */
    override suspend fun copy(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Unit> {
        if (entries.isEmpty()) return Result.success(Unit)
        return runCatching {
            entries.groupByCommandService(VfsProviderCapability.COPY).forEach { (service, serviceEntries) ->
                if (!service.supports(targetDirectoryLocation)) {
                    val copied = copyAcrossProviders(
                        entries = serviceEntries,
                        targetDirectoryLocation = targetDirectoryLocation,
                        conflictStrategy = conflictStrategy,
                    ).getOrThrow()
                    if (!copied) {
                        throw crossProviderUnsupportedFor(
                            sourceLocation = serviceEntries.firstOrNull()?.location,
                            targetLocation = targetDirectoryLocation,
                            capability = VfsProviderCapability.COPY,
                        )
                    }
                    return@forEach
                }
                service.copy(
                    entries = serviceEntries,
                    targetDirectoryLocation = targetDirectoryLocation,
                    conflictStrategy = conflictStrategy,
                ).getOrThrow()
            }
        }
    }

    /**
     * 移动文件或目录到目标目录。
     *
     * @param entries 待移动条目。
     * @param targetDirectoryLocation 目标目录位置。
     * @param conflictStrategy 名称冲突处理策略。
     * @return 操作结果。
     */
    override suspend fun move(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Unit> {
        if (entries.isEmpty()) return Result.success(Unit)
        return runCatching {
            entries.groupByCommandService(VfsProviderCapability.MOVE).forEach { (service, serviceEntries) ->
                if (!service.supports(targetDirectoryLocation)) {
                    val moved = moveAcrossProviders(
                        sourceCommandService = service,
                        entries = serviceEntries,
                        targetDirectoryLocation = targetDirectoryLocation,
                        conflictStrategy = conflictStrategy,
                    ).getOrThrow()
                    if (!moved) {
                        throw crossProviderUnsupportedFor(
                            sourceLocation = serviceEntries.firstOrNull()?.location,
                            targetLocation = targetDirectoryLocation,
                            capability = VfsProviderCapability.MOVE,
                        )
                    }
                    return@forEach
                }
                service.move(
                    entries = serviceEntries,
                    targetDirectoryLocation = targetDirectoryLocation,
                    conflictStrategy = conflictStrategy,
                ).getOrThrow()
            }
        }
    }

    /**
     * 删除文件或目录。
     *
     * @param entries 待删除条目。
     * @return 操作结果。
     */
    override suspend fun delete(entries: List<VFile>): Result<Unit> {
        if (entries.isEmpty()) return Result.success(Unit)
        return runCatching {
            entries.groupByCommandService(VfsProviderCapability.DELETE).forEach { (service, serviceEntries) ->
                service.delete(serviceEntries).getOrThrow()
            }
        }
    }

    /**
     * 重命名单个条目。
     *
     * @param entry 待重命名条目。
     * @param targetName 新名称。
     * @return 重命名后的条目。
     */
    override suspend fun rename(
        entry: VFile,
        targetName: String,
    ): Result<VFile> {
        return serviceFor(entry.location, VfsProviderCapability.RENAME).fold(
            onSuccess = { service -> service.rename(entry, targetName) },
            onFailure = { failure -> Result.failure(failure) },
        )
    }

    /**
     * 创建文件。
     *
     * @param parentLocation 父目录位置。
     * @param name 文件名称。
     * @return 创建后的文件条目。
     */
    override suspend fun createFile(
        parentLocation: String,
        name: String,
    ): Result<VFile> {
        return serviceFor(parentLocation, VfsProviderCapability.CREATE_FILE).fold(
            onSuccess = { service -> service.createFile(parentLocation, name) },
            onFailure = { failure -> Result.failure(failure) },
        )
    }

    /**
     * 创建目录。
     *
     * @param parentLocation 父目录位置。
     * @param name 目录名称。
     * @return 创建后的目录条目。
     */
    override suspend fun createDirectory(
        parentLocation: String,
        name: String,
    ): Result<VFile> {
        return serviceFor(parentLocation, VfsProviderCapability.CREATE_DIRECTORY).fold(
            onSuccess = { service -> service.createDirectory(parentLocation, name) },
            onFailure = { failure -> Result.failure(failure) },
        )
    }

    /**
     * 按源条目位置把条目分配到具体命令服务。
     *
     * @param capability 当前操作需要的 provider 能力。
     * @return 命令服务到条目列表的映射。
     */
    private fun List<VFile>.groupByCommandService(
        capability: VfsProviderCapability,
    ): Map<RoutableFileCommandService, List<VFile>> {
        return groupBy { entry -> serviceFor(entry.location, capability).getOrThrow() }
    }

    /**
     * 查找能处理指定位置的命令服务。
     *
     * @param location VFS 位置。
     * @param capability 当前操作需要的 provider 能力。
     * @return 命令服务或明确错误。
     */
    private fun serviceFor(
        location: String,
        capability: VfsProviderCapability,
    ): Result<RoutableFileCommandService> {
        val service = services.firstOrNull { candidate -> candidate.supports(location) }
        return if (service != null) {
            Result.success(service)
        } else {
            Result.failure(unsupportedFor(location, capability))
        }
    }

    /**
     * 使用内容服务执行跨 provider 复制。
     *
     * @param entries 待复制条目。
     * @param targetDirectoryLocation 目标目录位置。
     * @param conflictStrategy 名称冲突处理策略。
     * @return true 表示已完成跨 provider 复制，false 表示当前配置不支持该路径。
     */
    private suspend fun copyAcrossProviders(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Boolean> {
        if (contentServices.isEmpty()) {
            return Result.success(false)
        }
        val hasDirectories = entries.any { entry -> entry.kind == VFileKind.DIRECTORY }
        if (hasDirectories && providerRegistry == null) {
            return Result.success(false)
        }
        return contentServiceFor(targetDirectoryLocation, VfsProviderCapability.WRITE_CONTENT).fold(
            onSuccess = { targetContentService ->
                runCatching {
                    val targetCommandService = if (hasDirectories) {
                        serviceFor(targetDirectoryLocation, VfsProviderCapability.CREATE_DIRECTORY).getOrThrow()
                    } else {
                        null
                    }
                    entries.forEach { entry ->
                        copyEntryAcrossProviders(
                            entry = entry,
                            targetDirectoryLocation = targetDirectoryLocation,
                            targetContentService = targetContentService,
                            targetCommandService = targetCommandService,
                            conflictStrategy = conflictStrategy,
                        )
                    }
                    true
                }
            },
            onFailure = { failure ->
                if (failure is VfsProviderNotFoundException) {
                    Result.success(false)
                } else {
                    Result.failure(failure)
                }
            },
        )
    }

    /**
     * 递归复制一个跨 provider 条目。
     *
     * 目录需要依赖 provider 注册表列出子项，否则无法在不泄漏底层路径的前提下完成递归复制。
     *
     * @param entry 当前源条目。
     * @param targetDirectoryLocation 目标父目录位置。
     * @param targetContentService 目标 provider 内容写入服务。
     * @param targetCommandService 目标 provider 目录创建服务。
     * @param conflictStrategy 名称冲突处理策略。
     */
    private suspend fun copyEntryAcrossProviders(
        entry: VFile,
        targetDirectoryLocation: String,
        targetContentService: RoutableVfsContentService,
        targetCommandService: RoutableFileCommandService?,
        conflictStrategy: TransferConflictStrategy,
    ) {
        when (entry.kind) {
            VFileKind.FILE -> copyFileAcrossProviders(
                entry = entry,
                targetDirectoryLocation = targetDirectoryLocation,
                targetContentService = targetContentService,
                conflictStrategy = conflictStrategy,
            )

            VFileKind.DIRECTORY -> {
                val directoryService = targetCommandService
                    ?: throw unsupportedFor(targetDirectoryLocation, VfsProviderCapability.CREATE_DIRECTORY)
                val targetDirectory = directoryService.createDirectory(targetDirectoryLocation, entry.name).getOrThrow()
                val children = providerRegistry
                    ?.list(entry.location)
                    ?.getOrThrow()
                    ?: throw crossProviderUnsupportedFor(
                        sourceLocation = entry.location,
                        targetLocation = targetDirectoryLocation,
                        capability = VfsProviderCapability.COPY,
                    )
                children.forEach { child ->
                    copyEntryAcrossProviders(
                        entry = child,
                        targetDirectoryLocation = targetDirectory.location,
                        targetContentService = targetContentService,
                        targetCommandService = targetCommandService,
                        conflictStrategy = conflictStrategy,
                    )
                }
            }
        }
    }

    /**
     * 复制单个跨 provider 文件。
     *
     * @param entry 当前源文件。
     * @param targetDirectoryLocation 目标父目录位置。
     * @param targetContentService 目标 provider 内容写入服务。
     * @param conflictStrategy 名称冲突处理策略。
     */
    private suspend fun copyFileAcrossProviders(
        entry: VFile,
        targetDirectoryLocation: String,
        targetContentService: RoutableVfsContentService,
        conflictStrategy: TransferConflictStrategy,
    ) {
        val sourceContentService = contentServiceFor(
            location = entry.location,
            capability = VfsProviderCapability.READ_CONTENT,
        ).getOrThrow()
        val source = sourceContentService.readFile(entry).getOrThrow()
        targetContentService.writeFile(
            parentLocation = targetDirectoryLocation,
            name = source.name,
            chunks = source.chunks,
            conflictStrategy = conflictStrategy,
        ).getOrThrow()
    }

    /**
     * 使用“复制成功后删除源条目”的语义执行跨 provider 移动。
     *
     * @param sourceCommandService 源 provider 命令服务。
     * @param entries 待移动条目。
     * @param targetDirectoryLocation 目标目录位置。
     * @param conflictStrategy 名称冲突处理策略。
     * @return true 表示完成跨 provider 移动，false 表示当前配置不支持。
     */
    private suspend fun moveAcrossProviders(
        sourceCommandService: RoutableFileCommandService,
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Boolean> {
        return copyAcrossProviders(
            entries = entries,
            targetDirectoryLocation = targetDirectoryLocation,
            conflictStrategy = conflictStrategy,
        ).mapCatching { copied ->
            if (copied) {
                sourceCommandService.delete(entries).getOrThrow()
            }
            copied
        }
    }

    /**
     * 查找能处理指定位置的内容服务。
     *
     * @param location VFS 位置。
     * @param capability 当前操作需要的 provider 能力。
     * @return 内容服务或明确错误。
     */
    private fun contentServiceFor(
        location: String,
        capability: VfsProviderCapability,
    ): Result<RoutableVfsContentService> {
        val service = contentServices.firstOrNull { candidate -> candidate.supports(location) }
        return if (service != null) {
            Result.success(service)
        } else {
            Result.failure(unsupportedFor(location, capability))
        }
    }

    /**
     * 构建指定位置不支持当前能力的错误。
     *
     * @param location VFS 位置。
     * @param capability 当前操作需要的 provider 能力。
     * @return 语义化 VFS 错误。
     */
    private fun unsupportedFor(
        location: String,
        capability: VfsProviderCapability,
    ): Throwable {
        val provider = providerRegistry?.providerFor(location)?.getOrNull()
        return if (provider != null) {
            VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = provider.protocol,
                    location = location,
                    capability = capability,
                )
            )
        } else {
            VfsProviderNotFoundException(location)
        }
    }

    /**
     * 构建跨 provider 传输不支持的错误。
     *
     * @param sourceLocation 源位置。
     * @param targetLocation 目标位置。
     * @param capability 当前操作需要的 provider 能力。
     * @return 语义化 VFS 错误。
     */
    private fun crossProviderUnsupportedFor(
        sourceLocation: String?,
        targetLocation: String,
        capability: VfsProviderCapability,
    ): Throwable {
        val sourceProvider = sourceLocation?.let { location -> providerRegistry?.providerFor(location)?.getOrNull() }
        val targetProvider = providerRegistry?.providerFor(targetLocation)?.getOrNull()
        return if (sourceProvider != null && targetProvider != null) {
            VfsProviderException(
                VfsProviderError.CrossProviderTransferUnsupported(
                    protocol = targetProvider.protocol,
                    location = targetLocation,
                    sourceProtocol = sourceProvider.protocol,
                    sourceLocation = sourceLocation,
                    capability = capability,
                )
            )
        } else {
            unsupportedFor(targetLocation, capability)
        }
    }
}
