package com.oruke.onyx.app.filesystem

import com.oruke.onyx.vfs.api.DirectoryVerificationResult

/** 活动目录后台校验的统一频率与 I/O 占用预算。 */
internal data class DirectoryPollingPolicy(
    /** 内容变化后的最短校验间隔，单位毫秒。 */
    val activeIntervalMillis: Long = 2_000,
    /** 持续没有变化时逐步退避到的间隔，单位毫秒。 */
    val idleIntervalMillis: Long = 10_000,
    /** 目录不可用时的最长重试间隔，单位毫秒。 */
    val failureIntervalMillis: Long = 30_000,
    /** 两次读取之间至少休息上次读取耗时的倍数，避免慢挂载盘被轮询占满。 */
    val readBudgetMultiplier: Int = 4,
)

/**
 * 根据真实读取结果调节校验间隔，供观察协程与主线程反馈共同使用。
 * @param policy 当前目录的校验频率和预算。
 */
internal class DirectoryPollingSchedule(
    /** 当前目录使用的校验频率与读取预算。 */
    private val policy: DirectoryPollingPolicy,
) {
    /** 根据连续安静或失败次数计算的基础间隔。 */
    private var intervalMillis = policy.activeIntervalMillis

    /** 下一次校验前的休息时间，由主线程写入、观察协程读取。 */
    @Volatile
    var delayMillis: Long = policy.activeIntervalMillis
        private set

    /**
     * 有变化时恢复快速校验；安静和失败时退避，同时给慢 I/O 留出明确休息时间。
     * @param result 最近一次目录读取的真实结果。
     * @return 无返回值，更新后续校验前的休息时间。
     */
    fun verified(result: DirectoryVerificationResult) {
        intervalMillis = when {
            !result.succeeded -> (intervalMillis * 2).coerceAtMost(policy.failureIntervalMillis)
            result.changed -> policy.activeIntervalMillis
            else -> (intervalMillis * 2).coerceAtMost(policy.idleIntervalMillis)
        }
        delayMillis = maxOf(intervalMillis, result.elapsedMillis * policy.readBudgetMultiplier)
    }
}
