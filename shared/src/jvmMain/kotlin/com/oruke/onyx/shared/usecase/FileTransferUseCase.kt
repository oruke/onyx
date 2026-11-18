package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.DirectoryChange
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.FileTransferProgressSink
import com.oruke.onyx.vfs.api.FileTransferResult
import com.oruke.onyx.vfs.api.ProgressAwareFileCommandService
import com.oruke.onyx.vfs.api.ResultAwareFileCommandService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/** 按条目执行跨 Provider 复制、移动或提取的后台任务用例。 */
class FileTransferUseCase(
    /** 统一文件命令服务。 */
    private val fileCommandService: FileCommandService,
    /** 用于计算跨 Provider 条目总大小的注册表。 */
    private val providerRegistry: VfsProviderRegistry,
    /** 速度采样使用的单调时间源。 */
    private val clockMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
) {
    /**
     * 顺序传输条目并按真实内容字节发送节流后的进度；单条目失败时记录错误并继续传输后续条目。
     *
     * @param request 传输条目、目标、操作和冲突策略。
     * @param awaitReady 每个条目开始前等待任务调度器放行的回调。
     * @param isPaused 返回任务当前是否处于暂停状态。
     * @return 任务进度冷流。
     */
    fun execute(
        request: FileTransferRequest,
        awaitReady: suspend () -> Unit,
        isPaused: () -> Boolean = { false },
    ): Flow<TaskProgress> = channelFlow {
        val taskDetail = I18nMessage(
            MessageKey.MSG_STRING_LITERAL,
            buildTransferTaskDetail(request.entries, request.targetDirectoryLocation),
        )
        send(
            TaskProgress(
                status = BackgroundTaskStatus.RUNNING,
                detail = taskDetail,
                progress = if (request.entries.isEmpty()) 1f else null,
            )
        )
        val entryBytesById = resolveEntrySizesSafely(request.entries)
        val totalBytes = entryBytesById.values.sum()
        send(
            TaskProgress(
                status = BackgroundTaskStatus.RUNNING,
                detail = taskDetail,
                progress = if (request.entries.isEmpty()) 1f else null,
                totalBytes = totalBytes,
            )
        )

        var accumulatedBytes = 0L
        var failedBytes = 0L
        var completedCount = 0
        val ensuredDirectories = mutableSetOf<String>()
        for (entry in request.entries) {
            val entryBytes = entryBytesById[entry.id] ?: entry.sizeBytes ?: 0L
            val step = FileTransferStep(
                request = request,
                entry = entry,
                completedCount = completedCount,
                entryBytes = entryBytes,
                accumulatedBytes = accumulatedBytes,
                failedBytes = failedBytes,
                totalBytes = totalBytes,
            )
            val succeeded = transferEntrySafely(
                step = step,
                ensuredDirectories = ensuredDirectories,
                awaitReady = awaitReady,
                isPaused = isPaused,
            )
            if (succeeded) {
                accumulatedBytes += entryBytes
                completedCount += 1
            } else {
                failedBytes += entryBytes
            }
        }
    }

    /**
     * 安全计算各顶层条目字节数；单个坏条目读取失败时回退其自身已知大小，不提前炸毁整批任务。
     */
    private suspend fun resolveEntrySizesSafely(entries: List<VFile>): Map<String, Long> {
        return entries.associate { entry ->
            currentCoroutineContext().ensureActive()
            val resolvedBytes = providerRegistry.totalSizeBytes(listOf(entry)).getOrElse { failure ->
                if (failure is CancellationException) throw failure
                currentCoroutineContext().ensureActive()
                entry.sizeBytes ?: 0L
            }
            entry.id to resolvedBytes
        }
    }

    /**
     * 执行单个顶层条目并隔离非取消失败，确保同批其余正常条目继续完成传输。
     *
     * @param step 当前条目传输上下文。
     * @param ensuredDirectories 任务内已确认存在的中间目录集合。
     * @param awaitReady 条目开始前的暂停等待回调。
     * @param isPaused 返回任务当前是否暂停。
     * @return 当前条目完成（提交或按冲突跳过）时返回 `true`，单条目失败时返回 `false`。
     */
    @Suppress("TooGenericExceptionCaught") // 单条目边界仅隔离业务与 I/O 异常，协程取消始终原样抛出。
    private suspend fun ProducerScope<TaskProgress>.transferEntrySafely(
        step: FileTransferStep,
        ensuredDirectories: MutableSet<String>,
        awaitReady: suspend () -> Unit,
        isPaused: () -> Boolean,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        awaitReady()
        val conflictStrategy = step.request.conflictStrategies[step.entry.id]
            ?: TransferConflictStrategy.KEEP_BOTH
        val targetDirectory = step.effectiveTargetDirectory
        val detail = I18nMessage(
            MessageKey.MSG_STRING_LITERAL,
            "${step.entry.name} -> $targetDirectory",
        )
        send(step.startProgress(detail))

        return try {
            val createdDirectories = FileTransferDirectoryOperations.ensureChain(
                chain = step.request.entryDirectoryChains[step.entry.id].orEmpty(),
                ensuredLocations = ensuredDirectories,
                providerRegistry = providerRegistry,
                fileCommandService = fileCommandService,
            )
            if (createdDirectories.isNotEmpty()) {
                send(
                    step.startProgress(detail).copy(
                        directoryChange = DirectoryChange(upserted = createdDirectories),
                        createdDirectories = createdDirectories,
                    )
                )
            }

            val transferJob = currentCoroutineContext()[Job]
            val telemetry = createStepTelemetry(step, detail, clockMillis, isPaused, transferJob)
            val result = executeTransfer(
                operation = step.request.operation,
                entry = step.entry,
                targetDirectoryLocation = targetDirectory,
                conflictStrategy = conflictStrategy,
                progressSink = telemetry.sink,
            ).getOrThrow()

            val touchedTargets = setOf(step.request.targetDirectoryLocation, targetDirectory)
            val change = when (result) {
                is FileTransferResult.Committed -> DirectoryChange(
                    removed = if (step.request.operation == FileTransferOperation.MOVE) {
                        listOf(step.entry)
                    } else {
                        emptyList()
                    },
                    upserted = listOf(result.target),
                )
                FileTransferResult.Skipped -> null
                FileTransferResult.Uncertain -> DirectoryChange(
                    invalidatedDirectories = if (step.request.operation == FileTransferOperation.MOVE) {
                        touchedTargets + setOfNotNull(step.entry.parentLocation)
                    } else {
                        touchedTargets
                    },
                )
            }
            val completedBytes = step.accumulatedBytes + step.entryBytes
            send(
                step.completedProgress(completedBytes, telemetry.latestBytesPerSecond()).copy(
                    directoryChange = change,
                    skippedCount = if (result == FileTransferResult.Skipped) 1 else 0,
                )
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            send(step.failedProgress(failure))
            false
        }
    }

    /**
     * 按操作类型调用命令服务；支持进度契约时传入字节接收器。
     */
    private suspend fun executeTransfer(
        operation: FileTransferOperation,
        entry: VFile,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
        progressSink: FileTransferProgressSink,
    ): Result<FileTransferResult> {
        val resultAware = fileCommandService as? ResultAwareFileCommandService
        if (resultAware != null) {
            return resultAware.transferEntry(entry, targetDirectoryLocation, operation, conflictStrategy, progressSink)
        }
        val progressAwareService = fileCommandService as? ProgressAwareFileCommandService
        val result = when (operation) {
            FileTransferOperation.COPY,
            FileTransferOperation.EXTRACT -> if (progressAwareService != null) {
                progressAwareService.copyWithProgress(
                    entries = listOf(entry),
                    targetDirectoryLocation = targetDirectoryLocation,
                    conflictStrategy = conflictStrategy,
                    progressSink = progressSink,
                )
            } else {
                fileCommandService.copy(
                    entries = listOf(entry),
                    targetDirectoryLocation = targetDirectoryLocation,
                    conflictStrategy = conflictStrategy,
                )
            }

            FileTransferOperation.MOVE -> if (progressAwareService != null) {
                progressAwareService.moveWithProgress(
                    entries = listOf(entry),
                    targetDirectoryLocation = targetDirectoryLocation,
                    conflictStrategy = conflictStrategy,
                    progressSink = progressSink,
                )
            } else {
                fileCommandService.move(
                    entries = listOf(entry),
                    targetDirectoryLocation = targetDirectoryLocation,
                    conflictStrategy = conflictStrategy,
                )
            }
        }
        return result.map { FileTransferResult.Uncertain }
    }

    /** 文件传输请求。 */
    data class FileTransferRequest(
        /** 待传输条目。 */
        val entries: List<VFile>,
        /** 目标目录位置。 */
        val targetDirectoryLocation: String,
        /** 复制、移动或提取操作。 */
        val operation: FileTransferOperation,
        /** 按条目 ID 指定的冲突策略。 */
        val conflictStrategies: Map<String, TransferConflictStrategy>,
        /** 按条目 ID 指定的实际落盘父目录（未指定时回退至 targetDirectoryLocation）。 */
        val entryTargetDirectories: Map<String, String> = emptyMap(),
        /** 按条目 ID 指定的需预先确保存在的相对中间目录链。 */
        val entryDirectoryChains: Map<String, List<TransferDirectoryStep>> = emptyMap(),
    )

    private companion object {
        /** 将纳秒单调时钟换算为毫秒。 */
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
