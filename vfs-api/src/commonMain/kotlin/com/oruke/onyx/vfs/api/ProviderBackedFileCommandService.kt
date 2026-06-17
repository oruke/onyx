package com.oruke.onyx.vfs.api

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * 基于 VFS provider 路由的文件命令服务。
 *
 * 该服务负责在多个 provider 之间选择具体命令实现，并在 provider 边界不一致时通过内容流执行跨 provider 复制或移动。
 *
 * @param services 可路由文件命令服务列表。
 * @param contentServices 可路由内容读写服务列表。
 * @param providerRegistry provider 注册表，用于目录递归与错误语义构建。
 * @param progressSink 跨 provider 传输进度接收器。
 */
class ProviderBackedFileCommandService(
    services: List<RoutableFileCommandService>,
    contentServices: List<RoutableVfsContentService> = emptyList(),
    private val providerRegistry: VfsProviderRegistry? = null,
    private val progressSink: CrossProviderTransferProgressSink = CrossProviderTransferProgressSink.NoOp,
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
                    val recorder = CrossProviderTransferRecorder(progressSink)
                    entries.forEach { entry ->
                        coroutineContext.ensureActive()
                        runCatching {
                            copyEntryAcrossProviders(
                                entry = entry,
                                targetDirectoryLocation = targetDirectoryLocation,
                                targetContentService = targetContentService,
                                targetCommandService = targetCommandService,
                                conflictStrategy = conflictStrategy,
                                recorder = recorder,
                            )
                        }.onFailure { failure ->
                            if (failure is CancellationException) throw failure
                            recorder.recordFailure(
                                sourceLocation = entry.location,
                                targetLocation = targetDirectoryLocation,
                                cause = failure,
                            )
                        }
                    }
                    recorder.throwIfFailed()
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
     * @param recorder 跨 provider 传输记录器。
     */
    private suspend fun copyEntryAcrossProviders(
        entry: VFile,
        targetDirectoryLocation: String,
        targetContentService: RoutableVfsContentService,
        targetCommandService: RoutableFileCommandService?,
        conflictStrategy: TransferConflictStrategy,
        recorder: CrossProviderTransferRecorder,
    ) {
        coroutineContext.ensureActive()
        when (entry.kind) {
            VFileKind.FILE -> copyFileAcrossProviders(
                entry = entry,
                targetDirectoryLocation = targetDirectoryLocation,
                targetContentService = targetContentService,
                conflictStrategy = conflictStrategy,
                recorder = recorder,
            )

            VFileKind.DIRECTORY -> {
                val directoryService = targetCommandService
                    ?: throw unsupportedFor(targetDirectoryLocation, VfsProviderCapability.CREATE_DIRECTORY)
                val targetDirectory = resolveTargetDirectoryAcrossProviders(
                    entry = entry,
                    targetDirectoryLocation = targetDirectoryLocation,
                    directoryService = directoryService,
                    conflictStrategy = conflictStrategy,
                    recorder = recorder,
                ) ?: return
                val children = providerRegistry
                    ?.list(entry.location)
                    ?.getOrThrow()
                    ?: throw crossProviderUnsupportedFor(
                        sourceLocation = entry.location,
                        targetLocation = targetDirectoryLocation,
                        capability = VfsProviderCapability.COPY,
                    )
                children.forEach { child ->
                    coroutineContext.ensureActive()
                    runCatching {
                        copyEntryAcrossProviders(
                            entry = child,
                            targetDirectoryLocation = targetDirectory.location,
                            targetContentService = targetContentService,
                            targetCommandService = targetCommandService,
                            conflictStrategy = conflictStrategy,
                            recorder = recorder,
                        )
                    }.onFailure { failure ->
                        if (failure is CancellationException) throw failure
                        recorder.recordFailure(
                            sourceLocation = child.location,
                            targetLocation = targetDirectory.location,
                            cause = failure,
                        )
                    }
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
     * @param recorder 跨 provider 传输记录器。
     */
    private suspend fun copyFileAcrossProviders(
        entry: VFile,
        targetDirectoryLocation: String,
        targetContentService: RoutableVfsContentService,
        conflictStrategy: TransferConflictStrategy,
        recorder: CrossProviderTransferRecorder,
    ) {
        val sourceContentService = contentServiceFor(
            location = entry.location,
            capability = VfsProviderCapability.READ_CONTENT,
        ).getOrThrow()
        val source = sourceContentService.readFile(entry).getOrThrow()
        val written = targetContentService.writeFile(
            parentLocation = targetDirectoryLocation,
            name = source.name,
            chunks = source.chunks,
            conflictStrategy = conflictStrategy,
        ).getOrThrow()
        if (written == null) {
            recorder.recordSkipped(
                sourceLocation = entry.location,
                targetLocation = targetFileLocation(targetDirectoryLocation, source.name),
            )
        } else {
            recorder.recordCopiedFile(
                sourceLocation = entry.location,
                targetLocation = written.location,
            )
        }
    }

    /**
     * 按统一冲突策略解析跨 provider 目录复制的目标目录。
     *
     * @param entry 当前源目录。
     * @param targetDirectoryLocation 目标父目录位置。
     * @param directoryService 目标 provider 目录命令服务。
     * @param conflictStrategy 名称冲突处理策略。
     * @param recorder 跨 provider 传输记录器。
     * @return 目标目录；按 SKIP 跳过时返回 null。
     */
    private suspend fun resolveTargetDirectoryAcrossProviders(
        entry: VFile,
        targetDirectoryLocation: String,
        directoryService: RoutableFileCommandService,
        conflictStrategy: TransferConflictStrategy,
        recorder: CrossProviderTransferRecorder,
    ): VFile? {
        val existingEntries = providerRegistry
            ?.list(targetDirectoryLocation)
            ?.getOrThrow()
            ?: throw crossProviderUnsupportedFor(
                sourceLocation = entry.location,
                targetLocation = targetDirectoryLocation,
                capability = VfsProviderCapability.COPY,
            )
        val existingByName = existingEntries.associateBy { candidate -> candidate.name }
        val existing = existingByName[entry.name]
        val targetDirectory = when {
            existing == null -> directoryService.createDirectory(targetDirectoryLocation, entry.name).getOrThrow()
            conflictStrategy == TransferConflictStrategy.SKIP -> {
                recorder.recordSkipped(
                    sourceLocation = entry.location,
                    targetLocation = existing.location,
                )
                return null
            }
            conflictStrategy == TransferConflictStrategy.OVERWRITE && existing.kind == VFileKind.DIRECTORY -> existing
            conflictStrategy == TransferConflictStrategy.OVERWRITE -> {
                directoryService.delete(listOf(existing)).getOrThrow()
                directoryService.createDirectory(targetDirectoryLocation, entry.name).getOrThrow()
            }
            else -> {
                val targetName = entry.name.nextDirectoryCopyName(existingByName.keys)
                directoryService.createDirectory(targetDirectoryLocation, targetName).getOrThrow()
            }
        }
        recorder.recordDirectory(
            sourceLocation = entry.location,
            targetLocation = targetDirectory.location,
        )
        return targetDirectory
    }

    /**
     * 拼接目标文件位置，仅用于写入服务返回空结果时补齐进度位置。
     *
     * @param parentLocation 目标父目录位置。
     * @param name 目标文件名。
     * @return 目标文件位置。
     */
    private fun targetFileLocation(
        parentLocation: String,
        name: String,
    ): String {
        return "${parentLocation.trimEnd('/')}/$name"
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

/**
 * 跨 provider 传输记录器，负责统一统计进度和聚合失败。
 *
 * @property progressSink 进度事件接收器。
 */
private class CrossProviderTransferRecorder(
    private val progressSink: CrossProviderTransferProgressSink,
) {
    /** 已成功复制的文件数量。 */
    private var copiedFiles = 0

    /** 已成功创建或复用的目录数量。 */
    private var createdDirectories = 0

    /** 已按冲突策略跳过的条目数量。 */
    private var skippedEntries = 0

    /** 已聚合的条目级失败列表。 */
    private val failures = mutableListOf<CrossProviderTransferFailure>()

    /**
     * 记录文件复制成功事件。
     *
     * @param sourceLocation 源文件位置。
     * @param targetLocation 目标文件位置。
     */
    fun recordCopiedFile(
        sourceLocation: String,
        targetLocation: String,
    ) {
        copiedFiles += 1
        emit(
            stage = CrossProviderTransferStage.FILE_COPIED,
            sourceLocation = sourceLocation,
            targetLocation = targetLocation,
        )
    }

    /**
     * 记录目录创建或复用事件。
     *
     * @param sourceLocation 源目录位置。
     * @param targetLocation 目标目录位置。
     */
    fun recordDirectory(
        sourceLocation: String,
        targetLocation: String,
    ) {
        createdDirectories += 1
        emit(
            stage = CrossProviderTransferStage.DIRECTORY_CREATED,
            sourceLocation = sourceLocation,
            targetLocation = targetLocation,
        )
    }

    /**
     * 记录按冲突策略跳过的条目。
     *
     * @param sourceLocation 源条目位置。
     * @param targetLocation 已存在或推导出的目标位置。
     */
    fun recordSkipped(
        sourceLocation: String,
        targetLocation: String,
    ) {
        skippedEntries += 1
        emit(
            stage = CrossProviderTransferStage.ENTRY_SKIPPED,
            sourceLocation = sourceLocation,
            targetLocation = targetLocation,
        )
    }

    /**
     * 记录单项传输失败。
     *
     * @param sourceLocation 源条目位置。
     * @param targetLocation 目标父目录或目标条目位置。
     * @param cause 导致失败的异常。
     */
    fun recordFailure(
        sourceLocation: String,
        targetLocation: String,
        cause: Throwable,
    ) {
        failures += CrossProviderTransferFailure(
            sourceLocation = sourceLocation,
            targetLocation = targetLocation,
            cause = cause,
        )
    }

    /**
     * 如果存在已聚合失败则抛出汇总异常。
     */
    fun throwIfFailed() {
        if (failures.isNotEmpty()) {
            throw CrossProviderTransferException(snapshot())
        }
    }

    /**
     * 生成当前传输报告快照。
     *
     * @return 不可变传输报告。
     */
    private fun snapshot(): CrossProviderTransferReport {
        return CrossProviderTransferReport(
            copiedFiles = copiedFiles,
            createdDirectories = createdDirectories,
            skippedEntries = skippedEntries,
            failures = failures.toList(),
        )
    }

    /**
     * 发出传输进度事件。
     *
     * @param stage 当前传输阶段。
     * @param sourceLocation 源条目位置。
     * @param targetLocation 目标条目位置。
     */
    private fun emit(
        stage: CrossProviderTransferStage,
        sourceLocation: String,
        targetLocation: String,
    ) {
        progressSink.onProgress(
            CrossProviderTransferProgress(
                stage = stage,
                sourceLocation = sourceLocation,
                targetLocation = targetLocation,
                copiedFiles = copiedFiles,
                createdDirectories = createdDirectories,
                skippedEntries = skippedEntries,
            )
        )
    }
}

/**
 * 生成目录 KEEP_BOTH 冲突策略下的下一个可用副本名称。
 *
 * @param existingNames 当前目标目录已有名称集合。
 * @return 不与已有名称冲突的目录名。
 */
private fun String.nextDirectoryCopyName(existingNames: Set<String>): String {
    var index = 1
    while (true) {
        val candidate = withVfsCopySuffix(index)
        if (candidate !in existingNames) return candidate
        index += 1
    }
}
