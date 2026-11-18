package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.DirectoryChange
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.FileTransferProgressSink
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.ensureActive

/** 暂停期间检查恢复和取消状态的间隔。 */
private const val TRANSFER_PAUSE_POLL_INTERVAL_NANOS = 50_000_000L

/** UI 进度事件的最小间隔。 */
internal const val PROGRESS_EMISSION_INTERVAL_MILLIS = 125L

/** 单个顶层条目的传输进度上下文。 */
internal data class FileTransferStep(
    /** 整体文件传输请求。 */
    val request: FileTransferUseCase.FileTransferRequest,
    /** 当前顶层条目。 */
    val entry: VFile,
    /** 当前条目前已确认完成的条目数。 */
    val completedCount: Int,
    /** 当前条目递归总字节数。 */
    val entryBytes: Long,
    /** 当前条目前已确认完成的累计字节数。 */
    val accumulatedBytes: Long,
    /** 当前条目前已失败跳过的累计字节数。 */
    val failedBytes: Long,
    /** 整个任务原始总字节数。 */
    val totalBytes: Long,
) {
    /** 当前条目实际落盘父目录。 */
    val effectiveTargetDirectory: String
        get() = request.entryTargetDirectories[entry.id] ?: request.targetDirectoryLocation

    /** 扣除已失败条目后实际仍需传输的目标总字节数，用于精确计算剩余时间。 */
    private val effectiveTotalBytes: Long
        get() = (totalBytes - failedBytes).coerceAtLeast(accumulatedBytes)

    /**
     * 构造当前条目开始前的任务快照。
     *
     * @param detail 当前条目详情。
     * @return 开始任务快照。
     */
    fun startProgress(detail: I18nMessage): TaskProgress = TaskProgress(
        status = BackgroundTaskStatus.RUNNING,
        detail = detail,
        progress = progressBeforeEntry(accumulatedBytes, totalBytes, completedCount, request.entries.size),
        currentFileName = entry.name,
        processedCount = completedCount,
        processedBytes = accumulatedBytes,
        totalBytes = totalBytes,
    )

    /**
     * 构造当前条目传输中的任务快照。
     *
     * @param detail 当前条目详情。
     * @param processedBytes 当前任务已完成字节数。
     * @param bytesPerSecond 当前滚动速度。
     * @param currentFileName 当前活动子文件名。
     * @return 传输中任务快照。
     */
    fun byteProgress(
        detail: I18nMessage,
        processedBytes: Long,
        bytesPerSecond: Long?,
        currentFileName: String = entry.name,
    ): TaskProgress = TaskProgress(
        status = BackgroundTaskStatus.RUNNING,
        detail = detail,
        progress = byteProgress(processedBytes, totalBytes),
        currentFileName = currentFileName,
        processedCount = completedCount,
        processedBytes = processedBytes,
        totalBytes = totalBytes,
        bytesPerSecond = bytesPerSecond,
        estimatedRemainingSeconds = estimateRemainingSeconds(
            processedBytes = processedBytes,
            totalBytes = effectiveTotalBytes.coerceAtLeast(processedBytes),
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
            "${entry.name} -> $effectiveTargetDirectory",
        ),
        progress = completedEntryProgress(
            accumulatedBytes = completedBytes,
            totalBytes = totalBytes,
            processedCount = completedCount + 1,
            totalCount = request.entries.size,
        ),
        currentFileName = entry.name,
        processedCount = completedCount + 1,
        processedBytes = completedBytes,
        totalBytes = totalBytes,
        bytesPerSecond = bytesPerSecond,
        estimatedRemainingSeconds = estimateRemainingSeconds(
            processedBytes = completedBytes,
            totalBytes = effectiveTotalBytes.coerceAtLeast(completedBytes),
            bytesPerSecond = bytesPerSecond,
        ),
        completedEntry = entry,
    )

    /**
     * 构造当前条目失败后的回退进度快照，不计入已完成条目数与已完成字节数。
     *
     * @param cause 单条目真实失败原因。
     * @return 携带单条目失败信息与待刷新目录标记的任务快照。
     */
    fun failedProgress(cause: Throwable): TaskProgress {
        val touchedTargets = setOf(request.targetDirectoryLocation, effectiveTargetDirectory)
        val invalidated = if (request.operation == FileTransferOperation.MOVE) {
            touchedTargets + setOfNotNull(entry.parentLocation)
        } else {
            touchedTargets
        }
        return TaskProgress(
            status = BackgroundTaskStatus.RUNNING,
            detail = I18nMessage(
                MessageKey.MSG_STRING_LITERAL,
                "${entry.name} -> $effectiveTargetDirectory",
            ),
            progress = progressBeforeEntry(accumulatedBytes, totalBytes, completedCount, request.entries.size),
            currentFileName = entry.name,
            processedCount = completedCount,
            processedBytes = accumulatedBytes,
            totalBytes = totalBytes,
            directoryChange = DirectoryChange(invalidatedDirectories = invalidated),
            entryFailure = TransferEntryFailure(
                entry = entry,
                targetDirectoryLocation = effectiveTargetDirectory,
                cause = cause,
            ),
        )
    }
}

/** 单个顶层条目传输期间的实时进度接收器与速率快照访问器。 */
internal class StepTelemetry(
    val sink: FileTransferProgressSink,
    val latestBytesPerSecond: () -> Long?,
)

/**
 * 为单个顶层条目创建带节流、暂停门控与速率估算的字节接收器。
 */
internal fun ProducerScope<TaskProgress>.createStepTelemetry(
    step: FileTransferStep,
    detail: I18nMessage,
    clockMillis: () -> Long,
    isPaused: () -> Boolean,
    transferJob: Job?,
): StepTelemetry {
    var entryTransferredBytes = 0L
    var lastByteEmissionMillis = clockMillis()
    var lastFileEmissionMillis = lastByteEmissionMillis - PROGRESS_EMISSION_INTERVAL_MILLIS
    val rateEstimator = TransferRateEstimator().also { it.record(step.accumulatedBytes, lastByteEmissionMillis) }
    var latestBytesPerSecond: Long? = null
    var latestFileName = step.entry.name
    val telemetryLock = Any()

    fun currentProcessedBytes(): Long {
        val entryProgress = if (step.entryBytes > 0L) {
            entryTransferredBytes.coerceAtMost(step.entryBytes)
        } else {
            entryTransferredBytes
        }
        return step.accumulatedBytes + entryProgress
    }

    val sink = object : FileTransferProgressSink {
        override fun onBytesTransferred(byteCount: Long) {
            awaitTransferResume(isPaused, transferJob)
            if (byteCount <= 0L) return
            synchronized(telemetryLock) {
                entryTransferredBytes += byteCount
                val processedBytes = currentProcessedBytes()
                val nowMillis = clockMillis()
                latestBytesPerSecond = rateEstimator.record(processedBytes, nowMillis)
                if (nowMillis - lastByteEmissionMillis >= PROGRESS_EMISSION_INTERVAL_MILLIS) {
                    lastByteEmissionMillis = nowMillis
                    lastFileEmissionMillis = nowMillis
                    trySend(step.byteProgress(detail, processedBytes, latestBytesPerSecond, latestFileName))
                }
            }
        }

        override fun onCurrentFileChanged(fileName: String) {
            awaitTransferResume(isPaused, transferJob)
            if (fileName.isBlank()) return
            synchronized(telemetryLock) {
                latestFileName = fileName
                val nowMillis = clockMillis()
                if (nowMillis - lastFileEmissionMillis >= PROGRESS_EMISSION_INTERVAL_MILLIS) {
                    lastFileEmissionMillis = nowMillis
                    trySend(
                        step.byteProgress(
                            detail = detail,
                            processedBytes = currentProcessedBytes(),
                            bytesPerSecond = latestBytesPerSecond,
                            currentFileName = latestFileName,
                        )
                    )
                }
            }
        }
    }
    return StepTelemetry(sink) { synchronized(telemetryLock) { latestBytesPerSecond } }
}

/**
 * 在内容块边界等待任务恢复，并持续检查原传输协程是否已取消。
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

internal fun progressBeforeEntry(
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

internal fun byteProgress(
    processedBytes: Long,
    totalBytes: Long,
): Float? {
    if (totalBytes <= 0L) return null
    return (processedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
}

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
