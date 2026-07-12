package com.oruke.onyx.vfs.api

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.transform
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * 使用 VFS 内容流执行跨 Provider 文件与目录传输。
 */
internal class CrossProviderFileTransferService(
    /** 可路由命令服务，用于创建目标目录和删除移动源。 */
    commandServices: List<RoutableFileCommandService>,
    /** 可路由内容服务，用于读取源内容并写入目标。 */
    contentServices: List<RoutableVfsContentService>,
    /** Provider 注册表，用于递归列出目录和构建错误。 */
    private val providerRegistry: VfsProviderRegistry?,
    /** 跨 Provider 阶段进度接收器。 */
    private val progressSink: CrossProviderTransferProgressSink,
) {
    /** 固定的命令服务快照。 */
    private val commandServices = commandServices.toList()

    /** 固定的内容服务快照。 */
    private val contentServices = contentServices.toList()

    /**
     * 使用内容流执行跨 Provider 复制。
     *
     * @param entries 待复制条目。
     * @param targetDirectoryLocation 目标目录位置。
     * @param conflictStrategy 名称冲突处理策略。
     * @param byteProgressSink 字节增量接收器。
     * @return true 表示已完成，false 表示当前配置不支持该路径。
     */
    suspend fun copy(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
        byteProgressSink: FileTransferProgressSink,
    ): Result<Boolean> {
        val hasDirectories = entries.any { entry -> entry.kind == VFileKind.DIRECTORY }
        val canCopy = contentServices.isNotEmpty() && (!hasDirectories || providerRegistry != null)
        return if (!canCopy) {
            Result.success(false)
        } else {
            copyWithContentService(
                entries = entries,
                targetDirectoryLocation = targetDirectoryLocation,
                conflictStrategy = conflictStrategy,
                hasDirectories = hasDirectories,
                byteProgressSink = byteProgressSink,
            )
        }
    }

    /**
     * 通过复制成功后删除源条目的语义执行跨 Provider 移动。
     *
     * @param sourceCommandService 源 Provider 命令服务。
     * @param entries 待移动条目。
     * @param targetDirectoryLocation 目标目录位置。
     * @param conflictStrategy 名称冲突处理策略。
     * @param byteProgressSink 字节增量接收器。
     * @return true 表示已完成，false 表示当前配置不支持该路径。
     */
    suspend fun move(
        sourceCommandService: RoutableFileCommandService,
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
        byteProgressSink: FileTransferProgressSink,
    ): Result<Boolean> {
        return copy(
            entries = entries,
            targetDirectoryLocation = targetDirectoryLocation,
            conflictStrategy = conflictStrategy,
            byteProgressSink = byteProgressSink,
        ).mapCatching { copied ->
            if (copied) {
                sourceCommandService.delete(entries).getOrThrow()
            }
            copied
        }
    }

    /**
     * 执行已经通过能力检查的跨 Provider 复制。
     *
     * @param entries 待复制条目。
     * @param targetDirectoryLocation 目标目录位置。
     * @param conflictStrategy 名称冲突处理策略。
     * @param hasDirectories 是否包含目录。
     * @param byteProgressSink 字节增量接收器。
     * @return 跨 Provider 复制结果。
     */
    private suspend fun copyWithContentService(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
        hasDirectories: Boolean,
        byteProgressSink: FileTransferProgressSink,
    ): Result<Boolean> {
        return contentServiceFor(targetDirectoryLocation, VfsProviderCapability.WRITE_CONTENT).fold(
            onSuccess = { targetContentService ->
                runCatching {
                    val targetCommandService = if (hasDirectories) {
                        commandServiceFor(
                            targetDirectoryLocation,
                            VfsProviderCapability.CREATE_DIRECTORY,
                        ).getOrThrow()
                    } else {
                        null
                    }
                    val recorder = CrossProviderTransferRecorder(progressSink)
                    entries.forEach { entry ->
                        coroutineContext.ensureActive()
                        runCatching {
                            copyEntry(
                                entry = entry,
                                targetDirectoryLocation = targetDirectoryLocation,
                                targetContentService = targetContentService,
                                targetCommandService = targetCommandService,
                                conflictStrategy = conflictStrategy,
                                recorder = recorder,
                                byteProgressSink = byteProgressSink,
                            )
                        }.onFailure { failure ->
                            failure.throwIfCancellation()
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
     * 递归复制一个跨 Provider 条目。
     *
     * @param entry 当前源条目。
     * @param targetDirectoryLocation 目标父目录位置。
     * @param targetContentService 目标内容写入服务。
     * @param targetCommandService 目标目录命令服务。
     * @param conflictStrategy 名称冲突处理策略。
     * @param recorder 传输阶段记录器。
     * @param byteProgressSink 字节增量接收器。
     */
    private suspend fun copyEntry(
        entry: VFile,
        targetDirectoryLocation: String,
        targetContentService: RoutableVfsContentService,
        targetCommandService: RoutableFileCommandService?,
        conflictStrategy: TransferConflictStrategy,
        recorder: CrossProviderTransferRecorder,
        byteProgressSink: FileTransferProgressSink,
    ) {
        coroutineContext.ensureActive()
        when (entry.kind) {
            VFileKind.FILE -> copyFile(
                entry = entry,
                targetDirectoryLocation = targetDirectoryLocation,
                targetContentService = targetContentService,
                conflictStrategy = conflictStrategy,
                recorder = recorder,
                byteProgressSink = byteProgressSink,
            )

            VFileKind.DIRECTORY -> {
                val directoryService = targetCommandService
                    ?: throw unsupportedFor(
                        targetDirectoryLocation,
                        VfsProviderCapability.CREATE_DIRECTORY,
                    )
                val targetDirectory = resolveTargetDirectory(
                    entry = entry,
                    targetDirectoryLocation = targetDirectoryLocation,
                    directoryService = directoryService,
                    conflictStrategy = conflictStrategy,
                    recorder = recorder,
                ) ?: return
                val children = childrenFor(entry, targetDirectoryLocation)
                children.forEach { child ->
                    coroutineContext.ensureActive()
                    runCatching {
                        copyEntry(
                            entry = child,
                            targetDirectoryLocation = targetDirectory.location,
                            targetContentService = targetContentService,
                            targetCommandService = targetCommandService,
                            conflictStrategy = conflictStrategy,
                            recorder = recorder,
                            byteProgressSink = byteProgressSink,
                        )
                    }.onFailure { failure ->
                        failure.throwIfCancellation()
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
     * 通过 Provider 注册表列出源目录子项。
     *
     * @param entry 当前源目录。
     * @param targetDirectoryLocation 目标父目录，用于构建失败语义。
     * @return 当前目录的直接子项。
     */
    private suspend fun childrenFor(
        entry: VFile,
        targetDirectoryLocation: String,
    ): List<VFile> {
        return providerRegistry
            ?.list(entry.location)
            ?.getOrThrow()
            ?: throw crossProviderUnsupportedFor(
                sourceLocation = entry.location,
                targetLocation = targetDirectoryLocation,
                capability = VfsProviderCapability.COPY,
            )
    }

    /**
     * 复制单个跨 Provider 文件并在内容流被消费时记录字节增量。
     *
     * @param entry 当前源文件。
     * @param targetDirectoryLocation 目标父目录位置。
     * @param targetContentService 目标内容写入服务。
     * @param conflictStrategy 名称冲突处理策略。
     * @param recorder 传输阶段记录器。
     * @param byteProgressSink 字节增量接收器。
     */
    private suspend fun copyFile(
        entry: VFile,
        targetDirectoryLocation: String,
        targetContentService: RoutableVfsContentService,
        conflictStrategy: TransferConflictStrategy,
        recorder: CrossProviderTransferRecorder,
        byteProgressSink: FileTransferProgressSink,
    ) {
        val sourceContentService = contentServiceFor(
            location = entry.location,
            capability = VfsProviderCapability.READ_CONTENT,
        ).getOrThrow()
        val source = sourceContentService.readFile(entry).getOrThrow()
        val monitoredChunks = source.chunks.transform { chunk ->
            emit(chunk)
            if (chunk.isNotEmpty()) {
                byteProgressSink.onBytesTransferred(chunk.size.toLong())
            }
        }
        val written = targetContentService.writeFile(
            parentLocation = targetDirectoryLocation,
            name = source.name,
            chunks = monitoredChunks,
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
     * 按冲突策略解析跨 Provider 目录复制的目标目录。
     *
     * @param entry 当前源目录。
     * @param targetDirectoryLocation 目标父目录位置。
     * @param directoryService 目标目录命令服务。
     * @param conflictStrategy 名称冲突处理策略。
     * @param recorder 传输阶段记录器。
     * @return 目标目录；按 SKIP 跳过时返回 null。
     */
    private suspend fun resolveTargetDirectory(
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
                recorder.recordSkipped(entry.location, existing.location)
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
        recorder.recordDirectory(entry.location, targetDirectory.location)
        return targetDirectory
    }

    /**
     * 查找能处理指定位置的命令服务。
     *
     * @param location VFS 位置。
     * @param capability 当前操作需要的 Provider 能力。
     * @return 命令服务或明确错误。
     */
    private fun commandServiceFor(
        location: String,
        capability: VfsProviderCapability,
    ): Result<RoutableFileCommandService> {
        val service = commandServices.firstOrNull { candidate -> candidate.supports(location) }
        return service?.let(Result.Companion::success)
            ?: Result.failure(unsupportedFor(location, capability))
    }

    /**
     * 查找能处理指定位置的内容服务。
     *
     * @param location VFS 位置。
     * @param capability 当前操作需要的 Provider 能力。
     * @return 内容服务或明确错误。
     */
    private fun contentServiceFor(
        location: String,
        capability: VfsProviderCapability,
    ): Result<RoutableVfsContentService> {
        val service = contentServices.firstOrNull { candidate -> candidate.supports(location) }
        return service?.let(Result.Companion::success)
            ?: Result.failure(unsupportedFor(location, capability))
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
    ): String = "${parentLocation.trimEnd('/')}/$name"
}

/**
 * 在聚合失败前重新抛出协程取消，避免把取消误报为传输失败。
 */
private fun Throwable.throwIfCancellation() {
    if (this is CancellationException) throw this
}
