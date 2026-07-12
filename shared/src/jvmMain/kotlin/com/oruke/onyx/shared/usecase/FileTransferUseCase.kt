package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.FileTransferProgressSink
import com.oruke.onyx.vfs.api.ProgressAwareFileCommandService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.util.concurrent.locks.LockSupport

/** 暂停期间检查恢复和取消状态的间隔。 */
private const val TRANSFER_PAUSE_POLL_INTERVAL_NANOS = 50_000_000L

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
     * 顺序传输条目并按真实内容字节发送节流后的进度。
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
        val entryBytesById = request.entries.associate { entry ->
            entry.id to providerRegistry.totalSizeBytes(listOf(entry)).getOrThrow()
        }
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
        request.entries.forEachIndexed { index, entry ->
            accumulatedBytes = transferEntry(
                step = FileTransferStep(
                    request = request,
                    entry = entry,
                    index = index,
                    entryBytes = entryBytesById[entry.id] ?: entry.sizeBytes ?: 0L,
                    accumulatedBytes = accumulatedBytes,
                    totalBytes = totalBytes,
                ),
                awaitReady = awaitReady,
                isPaused = isPaused,
            )
        }
    }

    /**
     * 执行单个顶层条目并发送节流后的字节进度。
     *
     * @param step 当前条目传输上下文。
     * @param awaitReady 条目开始前的暂停等待回调。
     * @param isPaused 返回任务当前是否暂停。
     * @return 当前条目完成后的累计字节数。
     */
    private suspend fun ProducerScope<TaskProgress>.transferEntry(
        step: FileTransferStep,
        awaitReady: suspend () -> Unit,
        isPaused: () -> Boolean,
    ): Long {
        currentCoroutineContext().ensureActive()
        awaitReady()
        val conflictStrategy = step.request.conflictStrategies[step.entry.id]
            ?: TransferConflictStrategy.KEEP_BOTH
        val detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, step.entry.name)
        send(step.startProgress(detail))

        var entryTransferredBytes = 0L
        val transferJob = currentCoroutineContext()[Job]
        var lastEmissionMillis = clockMillis()
        val rateEstimator = TransferRateEstimator()
        rateEstimator.record(step.accumulatedBytes, lastEmissionMillis)
        var latestBytesPerSecond: Long? = null
        val telemetryLock = Any()
        val byteProgressSink = FileTransferProgressSink { byteCount ->
            awaitTransferResume(isPaused, transferJob)
            if (byteCount > 0L) {
                synchronized(telemetryLock) {
                    entryTransferredBytes += byteCount
                    val entryProgress = if (step.entryBytes > 0L) {
                        entryTransferredBytes.coerceAtMost(step.entryBytes)
                    } else {
                        entryTransferredBytes
                    }
                    val processedBytes = step.accumulatedBytes + entryProgress
                    val nowMillis = clockMillis()
                    latestBytesPerSecond = rateEstimator.record(processedBytes, nowMillis)
                    if (nowMillis - lastEmissionMillis >= PROGRESS_EMISSION_INTERVAL_MILLIS) {
                        lastEmissionMillis = nowMillis
                        trySend(step.byteProgress(detail, processedBytes, latestBytesPerSecond))
                    }
                }
            }
        }

        executeTransfer(
            request = step.request,
            entry = step.entry,
            conflictStrategy = conflictStrategy,
            progressSink = byteProgressSink,
        ).getOrThrow()

        val completedBytes = step.accumulatedBytes + step.entryBytes
        send(step.completedProgress(completedBytes, latestBytesPerSecond))
        return completedBytes
    }

    /**
     * 按操作类型调用命令服务；支持进度契约时传入字节接收器。
     *
     * @param request 当前传输请求。
     * @param entry 当前顶层条目。
     * @param conflictStrategy 当前条目的冲突策略。
     * @param progressSink 字节增量接收器。
     * @return 操作结果。
     */
    private suspend fun executeTransfer(
        request: FileTransferRequest,
        entry: VFile,
        conflictStrategy: TransferConflictStrategy,
        progressSink: FileTransferProgressSink,
    ): Result<Unit> {
        val progressAwareService = fileCommandService as? ProgressAwareFileCommandService
        return when (request.operation) {
            FileTransferOperation.COPY,
            FileTransferOperation.EXTRACT -> if (progressAwareService != null) {
                progressAwareService.copyWithProgress(
                    entries = listOf(entry),
                    targetDirectoryLocation = request.targetDirectoryLocation,
                    conflictStrategy = conflictStrategy,
                    progressSink = progressSink,
                )
            } else {
                fileCommandService.copy(
                    entries = listOf(entry),
                    targetDirectoryLocation = request.targetDirectoryLocation,
                    conflictStrategy = conflictStrategy,
                )
            }

            FileTransferOperation.MOVE -> if (progressAwareService != null) {
                progressAwareService.moveWithProgress(
                    entries = listOf(entry),
                    targetDirectoryLocation = request.targetDirectoryLocation,
                    conflictStrategy = conflictStrategy,
                    progressSink = progressSink,
                )
            } else {
                fileCommandService.move(
                    entries = listOf(entry),
                    targetDirectoryLocation = request.targetDirectoryLocation,
                    conflictStrategy = conflictStrategy,
                )
            }
        }
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
    )

    private companion object {
        /** UI 进度事件的最小间隔。 */
        const val PROGRESS_EMISSION_INTERVAL_MILLIS = 125L
        /** 将纳秒单调时钟换算为毫秒。 */
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

/** 单个顶层条目的传输进度上下文。 */
private data class FileTransferStep(
    /** 整体文件传输请求。 */
    val request: FileTransferUseCase.FileTransferRequest,
    /** 当前顶层条目。 */
    val entry: VFile,
    /** 当前条目索引。 */
    val index: Int,
    /** 当前条目递归总字节数。 */
    val entryBytes: Long,
    /** 当前条目前已完成累计字节数。 */
    val accumulatedBytes: Long,
    /** 整个任务总字节数。 */
    val totalBytes: Long,
) {
    /**
     * 构造当前条目开始前的任务快照。
     *
     * @param detail 当前条目详情。
     * @return 开始任务快照。
     */
    fun startProgress(detail: I18nMessage): TaskProgress = TaskProgress(
        status = BackgroundTaskStatus.RUNNING,
        detail = detail,
        progress = progressBeforeEntry(accumulatedBytes, totalBytes, index, request.entries.size),
        currentFileName = entry.name,
        processedCount = index,
        processedBytes = accumulatedBytes,
        totalBytes = totalBytes,
    )

    /**
     * 构造当前条目传输中的任务快照。
     *
     * @param detail 当前条目详情。
     * @param processedBytes 当前任务已完成字节数。
     * @param bytesPerSecond 当前滚动速度。
     * @return 传输中任务快照。
     */
    fun byteProgress(
        detail: I18nMessage,
        processedBytes: Long,
        bytesPerSecond: Long?,
    ): TaskProgress = TaskProgress(
        status = BackgroundTaskStatus.RUNNING,
        detail = detail,
        progress = byteProgress(processedBytes, totalBytes),
        currentFileName = entry.name,
        processedCount = index,
        processedBytes = processedBytes,
        totalBytes = totalBytes,
        bytesPerSecond = bytesPerSecond,
        estimatedRemainingSeconds = estimateRemainingSeconds(
            processedBytes = processedBytes,
            totalBytes = totalBytes,
            bytesPerSecond = bytesPerSecond,
        ),
    )

    /**
     * 构造当前条目完成后的任务快照。
     *
     * @param completedBytes 当前任务累计完成字节数。
     * @param bytesPerSecond 最近滚动速度。
     * @return 条目完成任务快照。
     */
    fun completedProgress(
        completedBytes: Long,
        bytesPerSecond: Long?,
    ): TaskProgress = TaskProgress(
        status = BackgroundTaskStatus.RUNNING,
        detail = I18nMessage(
            MessageKey.MSG_STRING_LITERAL,
            "${entry.name} -> ${request.targetDirectoryLocation}",
        ),
        progress = completedEntryProgress(
            accumulatedBytes = completedBytes,
            totalBytes = totalBytes,
            processedCount = index + 1,
            totalCount = request.entries.size,
        ),
        currentFileName = entry.name,
        processedCount = index + 1,
        processedBytes = completedBytes,
        totalBytes = totalBytes,
        bytesPerSecond = bytesPerSecond,
        estimatedRemainingSeconds = estimateRemainingSeconds(
            processedBytes = completedBytes,
            totalBytes = totalBytes,
            bytesPerSecond = bytesPerSecond,
        ),
    )
}

/**
 * 在内容块边界等待任务恢复，并持续检查原传输协程是否已取消。
 *
 * @param isPaused 返回任务当前是否暂停。
 * @param transferJob 原传输协程 Job。
 */
private fun awaitTransferResume(
    isPaused: () -> Boolean,
    transferJob: Job?,
) {
    while (isPaused()) {
        transferJob?.ensureActive()
        LockSupport.parkNanos(TRANSFER_PAUSE_POLL_INTERVAL_NANOS)
    }
}

/**
 * 计算当前条目开始前的总体进度；首项尚未产生字节时返回不确定进度。
 *
 * @param accumulatedBytes 已完成条目的累计字节数。
 * @param totalBytes 任务总字节数。
 * @param processedCount 已完成条目数。
 * @param totalCount 总条目数。
 * @return 归一化进度或 null。
 */
private fun progressBeforeEntry(
    accumulatedBytes: Long,
    totalBytes: Long,
    processedCount: Int,
    totalCount: Int,
): Float? {
    return when {
        totalBytes > 0L && accumulatedBytes > 0L -> byteProgress(accumulatedBytes, totalBytes)
        processedCount > 0 -> progressValue(processedCount, totalCount)
        else -> null
    }
}

/**
 * 计算单个条目完成后的总体进度。
 *
 * @param accumulatedBytes 已完成累计字节数。
 * @param totalBytes 任务总字节数。
 * @param processedCount 已完成条目数。
 * @param totalCount 总条目数。
 * @return 归一化进度。
 */
private fun completedEntryProgress(
    accumulatedBytes: Long,
    totalBytes: Long,
    processedCount: Int,
    totalCount: Int,
): Float {
    return if (totalBytes > 0L) {
        byteProgress(accumulatedBytes, totalBytes) ?: 0f
    } else {
        progressValue(processedCount, totalCount)
    }
}

/**
 * 将字节完成量换算为归一化进度。
 *
 * @param processedBytes 已完成字节数。
 * @param totalBytes 总字节数。
 * @return 归一化进度；总字节未知时返回 null。
 */
private fun byteProgress(
    processedBytes: Long,
    totalBytes: Long,
): Float? {
    if (totalBytes <= 0L) return null
    return (processedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
}

/**
 * 按当前滚动速度估算剩余秒数。
 *
 * @param processedBytes 已完成字节数。
 * @param totalBytes 总字节数。
 * @param bytesPerSecond 当前速度。
 * @return 向上取整的剩余秒数；信息不足时返回 null。
 */
private fun estimateRemainingSeconds(
    processedBytes: Long,
    totalBytes: Long,
    bytesPerSecond: Long?,
): Long? {
    val speed = bytesPerSecond?.takeIf { value -> value > 0L } ?: return null
    val remainingBytes = (totalBytes - processedBytes).coerceAtLeast(0L)
    if (remainingBytes == 0L) return null
    return (remainingBytes + speed - 1L) / speed
}
