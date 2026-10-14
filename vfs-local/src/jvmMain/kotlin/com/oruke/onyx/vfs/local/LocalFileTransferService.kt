package com.oruke.onyx.vfs.local

import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.FileTransferProgressSink
import com.oruke.onyx.vfs.api.FileTransferResult
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 本地逐项传输服务，负责返回冲突处理后真实提交的入口元数据。 */
internal object LocalFileTransferService {
    /**
     * 执行一次本地传输，完成真实提交后才返回目标身份。
     * @param entry 本次源条目。
     * @param targetDirectoryLocation 目标目录。
     * @param operation 传输类型。
     * @param conflictStrategy 冲突策略。
     * @param progressSink 字节增量接收器。
     * @return 真实提交、跳过或结构化失败；提交后元数据失败也由调用方校验目录。
     */
    suspend fun transfer(
        entry: VFile,
        targetDirectoryLocation: String,
        operation: FileTransferOperation,
        conflictStrategy: TransferConflictStrategy,
        progressSink: FileTransferProgressSink,
    ): Result<FileTransferResult> = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        runCatching {
            job?.ensureActive()
            val directory = LocalPathOperations.resolveTargetDirectory(targetDirectoryLocation)
            val sink = FileTransferProgressSink { count -> job?.ensureActive(); progressSink.onBytesTransferred(count) }
            val target = if (operation == FileTransferOperation.MOVE) {
                LocalPathTransferOperations.movePathToDirectory(Path.of(entry.location), directory,
                    conflictStrategy, sink) { job?.ensureActive() }
            } else {
                LocalPathTransferOperations.copyPathToDirectory(Path.of(entry.location), directory,
                    conflictStrategy, sink) { job?.ensureActive() }
            }
            if (target == null) FileTransferResult.Skipped
            else FileTransferResult.Committed(target.toLocalVFile(directory))
        }.mapLocalError()
    }
}
