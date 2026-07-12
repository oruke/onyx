package com.oruke.onyx.vfs.api

import com.oruke.onyx.core.model.VFile

/**
 * 基于 VFS Provider 路由的文件命令服务。
 *
 * 同 Provider 操作优先调用其原生命令；跨 Provider 操作交给内容流传输服务执行。
 */
class ProviderBackedFileCommandService(
    /** 可路由文件命令服务列表。 */
    services: List<RoutableFileCommandService>,
    /** 可路由内容读写服务列表。 */
    contentServices: List<RoutableVfsContentService> = emptyList(),
    /** Provider 注册表，用于目录递归与错误语义构建。 */
    private val providerRegistry: VfsProviderRegistry? = null,
    /** 跨 Provider 阶段进度接收器。 */
    progressSink: CrossProviderTransferProgressSink = CrossProviderTransferProgressSink.NoOp,
) : ProgressAwareFileCommandService {
    /** 固定的命令服务快照，避免运行期外部列表变化影响路由。 */
    private val services = services.toList()

    /** 跨 Provider 内容流传输实现。 */
    private val crossProviderTransferService = CrossProviderFileTransferService(
        commandServices = this.services,
        contentServices = contentServices,
        providerRegistry = providerRegistry,
        progressSink = progressSink,
    )

    init {
        require(this.services.isNotEmpty()) {
            "At least one file command service must be registered"
        }
    }

    /**
     * 复制文件或目录并上报可观测的字节增量。
     *
     * @param entries 待复制条目。
     * @param targetDirectoryLocation 目标目录位置。
     * @param conflictStrategy 名称冲突处理策略。
     * @param progressSink 字节进度接收器。
     * @return 操作结果。
     */
    override suspend fun copyWithProgress(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
        progressSink: FileTransferProgressSink,
    ): Result<Unit> {
        if (entries.isEmpty()) return Result.success(Unit)
        return runCatching {
            entries.groupByCommandService(VfsProviderCapability.COPY).forEach { (service, serviceEntries) ->
                if (!service.supports(targetDirectoryLocation)) {
                    val copied = crossProviderTransferService.copy(
                        entries = serviceEntries,
                        targetDirectoryLocation = targetDirectoryLocation,
                        conflictStrategy = conflictStrategy,
                        byteProgressSink = progressSink,
                    ).getOrThrow()
                    if (!copied) {
                        throw crossProviderUnsupportedFor(
                            sourceLocation = serviceEntries.firstOrNull()?.location,
                            targetLocation = targetDirectoryLocation,
                            capability = VfsProviderCapability.COPY,
                        )
                    }
                } else {
                    service.copyWithOptionalProgress(
                        entries = serviceEntries,
                        targetDirectoryLocation = targetDirectoryLocation,
                        conflictStrategy = conflictStrategy,
                        progressSink = progressSink,
                    ).getOrThrow()
                }
            }
        }
    }

    /**
     * 移动文件或目录，并在回退为内容复制时上报字节增量。
     *
     * @param entries 待移动条目。
     * @param targetDirectoryLocation 目标目录位置。
     * @param conflictStrategy 名称冲突处理策略。
     * @param progressSink 字节进度接收器。
     * @return 操作结果。
     */
    override suspend fun moveWithProgress(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
        progressSink: FileTransferProgressSink,
    ): Result<Unit> {
        if (entries.isEmpty()) return Result.success(Unit)
        return runCatching {
            entries.groupByCommandService(VfsProviderCapability.MOVE).forEach { (service, serviceEntries) ->
                if (!service.supports(targetDirectoryLocation)) {
                    val moved = crossProviderTransferService.move(
                        sourceCommandService = service,
                        entries = serviceEntries,
                        targetDirectoryLocation = targetDirectoryLocation,
                        conflictStrategy = conflictStrategy,
                        byteProgressSink = progressSink,
                    ).getOrThrow()
                    if (!moved) {
                        throw crossProviderUnsupportedFor(
                            sourceLocation = serviceEntries.firstOrNull()?.location,
                            targetLocation = targetDirectoryLocation,
                            capability = VfsProviderCapability.MOVE,
                        )
                    }
                } else {
                    service.moveWithOptionalProgress(
                        entries = serviceEntries,
                        targetDirectoryLocation = targetDirectoryLocation,
                        conflictStrategy = conflictStrategy,
                        progressSink = progressSink,
                    ).getOrThrow()
                }
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
     * @param capability 当前操作需要的 Provider 能力。
     * @return 命令服务到条目列表的映射。
     */
    private fun List<VFile>.groupByCommandService(
        capability: VfsProviderCapability,
    ): Map<RoutableFileCommandService, List<VFile>> {
        return groupBy { entry -> serviceFor(entry.location, capability).getOrThrow() }
    }

    /**
     * 在 Provider 支持进度契约时调用其进度版本，否则保留原生命令语义。
     *
     * @param entries 待复制条目。
     * @param targetDirectoryLocation 目标目录位置。
     * @param conflictStrategy 名称冲突处理策略。
     * @param progressSink 字节进度接收器。
     * @return 操作结果。
     */
    private suspend fun RoutableFileCommandService.copyWithOptionalProgress(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
        progressSink: FileTransferProgressSink,
    ): Result<Unit> {
        return if (this is ProgressAwareFileCommandService) {
            copyWithProgress(entries, targetDirectoryLocation, conflictStrategy, progressSink)
        } else {
            copy(entries, targetDirectoryLocation, conflictStrategy)
        }
    }

    /**
     * 在 Provider 支持进度契约时调用其进度版本，否则保留原生命令语义。
     *
     * @param entries 待移动条目。
     * @param targetDirectoryLocation 目标目录位置。
     * @param conflictStrategy 名称冲突处理策略。
     * @param progressSink 字节进度接收器。
     * @return 操作结果。
     */
    private suspend fun RoutableFileCommandService.moveWithOptionalProgress(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
        progressSink: FileTransferProgressSink,
    ): Result<Unit> {
        return if (this is ProgressAwareFileCommandService) {
            moveWithProgress(entries, targetDirectoryLocation, conflictStrategy, progressSink)
        } else {
            move(entries, targetDirectoryLocation, conflictStrategy)
        }
    }

    /**
     * 查找能处理指定位置的命令服务。
     *
     * @param location VFS 位置。
     * @param capability 当前操作需要的 Provider 能力。
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
     * 构建指定位置不支持当前能力的错误。
     *
     * @param location VFS 位置。
     * @param capability 当前操作需要的 Provider 能力。
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
     * 构建跨 Provider 传输不支持的错误。
     *
     * @param sourceLocation 源位置。
     * @param targetLocation 目标位置。
     * @param capability 当前操作需要的 Provider 能力。
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
