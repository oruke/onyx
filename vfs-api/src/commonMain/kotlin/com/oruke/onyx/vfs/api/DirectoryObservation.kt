package com.oruke.onyx.vfs.api

import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.flow.Flow

/** 单项元数据查询结果，明确区分不存在和不支持，避免误删缓存条目。 */
sealed interface FileEntryLookup {
    /** 已读取的真实条目元数据。 */
    data class Found(
        /** 当前条目。 */
        val entry: VFile,
    ) : FileEntryLookup

    /** 已确认条目不存在。 */
    data object Missing : FileEntryLookup

    /** 当前 Provider 不支持单项查询，需要校验整个目录。 */
    data object Unsupported : FileEntryLookup
}

/** 支持单项元数据读取的可选 Provider 能力。 */
interface QueryableVfsProvider : VfsProvider {
    /**
     * 查询入口自身的元数据，读取失败不得解释为条目不存在。
     * @param location 条目的 VFS 位置。
     * @return 实际条目、不存在状态或读取失败。
     */
    suspend fun query(location: String): Result<FileEntryLookup>
}

/** 目录观察事件，平台路径在 Provider 边界转换为 VFS 位置。 */
sealed interface DirectoryWatchEvent {
    /** 观察已注册，可开始初始读取。 */
    data object Ready : DirectoryWatchEvent

    /** 指定条目的当前状态需要重新查询。 */
    data class Changed(
        /** 需要查询的条目位置。 */
        val location: String,
    ) : DirectoryWatchEvent

    /** 事件不完整、监听失效或轮询到期，需要重新校验目录。 */
    data object Invalidated : DirectoryWatchEvent

    /** 定期后台校验，不代表内容已经变化，也不触发前台刷新提示。 */
    data object Verify : DirectoryWatchEvent

    /** 观察不可用，已有快照可以保留，但错误必须可见。 */
    data class Unavailable(
        /** 观察失败原因。 */
        val failure: Throwable,
    ) : DirectoryWatchEvent
}

/** 可替换的目录观察能力，由平台实现决定事件监听或有界轮询。 */
fun interface DirectoryObservationService {
    /**
     * 观察目录变化；取消收集必须释放底层观察资源。
     * @param location 目录 VFS 位置。
     * @return 注册、条目变化、失效和失败的事件流。
     */
    fun observe(location: String): Flow<DirectoryWatchEvent>

    /**
     * 接收实际校验结果，平台观察器据此调整轮询频率；默认实现无需反馈。
     * @param location 被校验的目录位置。
     * @param result 内容变化、结果与耗时。
     * @return 无返回值，默认不需要处理反馈。
     */
    fun onVerified(location: String, result: DirectoryVerificationResult) = Unit
}

/** 一次目录读取的真实结果，用于后台校验的频率和 I/O 预算控制。 */
data class DirectoryVerificationResult(
    /** 是否发现内容或条目身份变化。 */
    val changed: Boolean,
    /** 目录是否成功读取，失败不能解释为没有变化。 */
    val succeeded: Boolean,
    /** 实际读取和差异计算耗时，单位毫秒。 */
    val elapsedMillis: Long,
)
