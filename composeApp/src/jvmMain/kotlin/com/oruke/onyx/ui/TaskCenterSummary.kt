package com.oruke.onyx.ui

import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskStatus

/**
 * 任务中心总览使用的聚合状态模型。
 */
internal data class TaskCenterSummary(
    /** 当前仍可执行、暂停或取消的任务数。 */
    val activeCount: Int,
    /** 当前正在执行的任务数。 */
    val runningCount: Int,
    /** 等待并发许可的任务数。 */
    val queuedCount: Int,
    /** 当前暂停的任务数。 */
    val pausedCount: Int,
    /** 最近保留的终态任务数。 */
    val terminalCount: Int,
    /** 活动任务已完成的累计字节数。 */
    val processedBytes: Long,
    /** 活动任务可统计的总字节数。 */
    val totalBytes: Long,
    /** 按字节加权或按任务进度回退计算的总进度。 */
    val progress: Float?,
    /** 当前所有运行任务的合计速度。 */
    val bytesPerSecond: Long?,
    /** 按合计剩余字节与速度估算的剩余秒数。 */
    val estimatedRemainingSeconds: Long?,
    /** 是否存在正在运行但无法提供确定进度的任务。 */
    val hasIndeterminateRunning: Boolean,
)

/**
 * 将任务列表聚合为任务中心总进度。
 *
 * 字节总量可用时按字节加权，避免小文件任务和大文件任务被错误地等权平均；无字节信息时回退到任务进度平均值。
 *
 * @return 任务中心聚合状态。
 */
internal fun List<BackgroundTask>.toTaskCenterSummary(): TaskCenterSummary {
    val activeTasks = filter { task -> task.status.isActiveTaskStatus() }
    val terminalCount = size - activeTasks.size
    val byteTasks = activeTasks.filter { task -> task.totalBytes > 0L }
    val totalBytes = byteTasks.sumOf { task -> task.totalBytes }
    val processedBytes = byteTasks.sumOf { task -> task.processedBytes.coerceIn(0L, task.totalBytes) }
    val progress = when {
        totalBytes > 0L -> (processedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        activeTasks.isNotEmpty() -> activeTasks.mapNotNull { task -> task.progress }.averageOrNull()?.toFloat()
        terminalCount > 0 -> 1f
        else -> null
    }
    val bytesPerSecond = activeTasks
        .filter { task -> task.status == BackgroundTaskStatus.RUNNING }
        .sumOf { task -> task.bytesPerSecond ?: 0L }
        .takeIf { speed -> speed > 0L }
    val remainingSeconds = aggregateRemainingSeconds(
        activeTasks = activeTasks,
        processedBytes = processedBytes,
        totalBytes = totalBytes,
        bytesPerSecond = bytesPerSecond,
    )
    return TaskCenterSummary(
        activeCount = activeTasks.size,
        runningCount = activeTasks.count { task -> task.status == BackgroundTaskStatus.RUNNING },
        queuedCount = activeTasks.count { task -> task.status == BackgroundTaskStatus.QUEUED },
        pausedCount = activeTasks.count { task -> task.status == BackgroundTaskStatus.PAUSED },
        terminalCount = terminalCount,
        processedBytes = processedBytes,
        totalBytes = totalBytes,
        progress = progress,
        bytesPerSecond = bytesPerSecond,
        estimatedRemainingSeconds = remainingSeconds,
        hasIndeterminateRunning = activeTasks.any { task ->
            task.status == BackgroundTaskStatus.RUNNING && task.progress == null
        },
    )
}

/**
 * 判断任务状态是否仍属于活动生命周期。
 *
 * @return 排队、运行或暂停时返回 true。
 */
internal fun BackgroundTaskStatus.isActiveTaskStatus(): Boolean {
    return this == BackgroundTaskStatus.QUEUED ||
        this == BackgroundTaskStatus.RUNNING ||
        this == BackgroundTaskStatus.PAUSED
}

/**
 * 判断展开面板是否需要单独展示聚合进度。
 *
 * 只有多个活动任务并行或排队时，总进度才提供额外信息；单任务总进度与任务进度完全重复。
 *
 * @return 活动任务超过一个时返回 true。
 */
internal fun TaskCenterSummary.shouldShowAggregateProgress(): Boolean = activeCount > 1

/**
 * 计算活动任务总体剩余时间。
 *
 * @param activeTasks 当前活动任务。
 * @param processedBytes 已完成累计字节。
 * @param totalBytes 可统计的总字节。
 * @param bytesPerSecond 合计传输速度。
 * @return 估算剩余秒数；信息不足时返回 null。
 */
private fun aggregateRemainingSeconds(
    activeTasks: List<BackgroundTask>,
    processedBytes: Long,
    totalBytes: Long,
    bytesPerSecond: Long?,
): Long? {
    val speed = bytesPerSecond?.takeIf { value -> value > 0L }
    if (speed != null && totalBytes > processedBytes) {
        val remainingBytes = totalBytes - processedBytes
        return (remainingBytes + speed - 1L) / speed
    }
    return activeTasks.mapNotNull { task -> task.estimatedRemainingSeconds }.maxOrNull()
}

/**
 * 返回非空 Double 列表的平均值。
 *
 * @return 平均值；列表为空时返回 null。
 */
private fun List<Float>.averageOrNull(): Double? {
    return if (isEmpty()) null else average()
}
