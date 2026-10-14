package com.oruke.onyx.vfs.api

import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.VFile

/** 单个顶层条目的实际提交结果，旧 Provider 不能通过 Unit 成功推断目标名称。 */
sealed interface FileTransferResult {
    /** 已提交到实际目标，包含 KEEP_BOTH 生成的新名称。 */
    data class Committed(
        /** 冲突处理后已经提交的真实目标条目。 */
        val target: VFile,
    ) : FileTransferResult
    /** 冲突策略明确跳过，源和目标均未改变。 */
    data object Skipped : FileTransferResult
    /** 命令返回成功但不能准确描述变化，需要重新校验相关目录。 */
    data object Uncertain : FileTransferResult
}

/** 能返回逐项提交事实的可选统一命令能力。 */
interface ResultAwareFileCommandService : FileCommandService {
    /**
     * 执行一个条目的传输并返回实际目标，失败可能包含部分提交。
     * @param entry 本次顶层源条目。
     * @param targetDirectoryLocation 目标目录。
     * @param operation 复制、移动或提取。
     * @param conflictStrategy 当前条目冲突策略。
     * @param progressSink 实际字节进度入口。
     * @return 真实提交、跳过、不确定结果或结构化错误。
     */
    suspend fun transferEntry(
        entry: VFile,
        targetDirectoryLocation: String,
        operation: FileTransferOperation,
        conflictStrategy: TransferConflictStrategy,
        progressSink: FileTransferProgressSink,
    ): Result<FileTransferResult>
}
