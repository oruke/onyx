package com.oruke.onyx.vfs.api

import com.oruke.onyx.core.model.VFile

/**
 * 文件命令服务，承载复制、移动、删除、重命名和创建等会改变文件系统状态的操作。
 */
interface FileCommandService {
    /**
     * 将条目复制到目标目录。
     *
     * @param entries 待复制条目。
     * @param targetDirectoryLocation 目标目录位置。
     * @param conflictStrategy 名称冲突处理策略。
     * @return 操作结果。
     */
    suspend fun copy(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy = TransferConflictStrategy.KEEP_BOTH,
    ): Result<Unit>

    /**
     * 将条目移动到目标目录。
     *
     * @param entries 待移动条目。
     * @param targetDirectoryLocation 目标目录位置。
     * @param conflictStrategy 名称冲突处理策略。
     * @return 操作结果。
     */
    suspend fun move(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy = TransferConflictStrategy.KEEP_BOTH,
    ): Result<Unit>

    /**
     * 删除指定条目。
     *
     * @param entries 待删除条目。
     * @return 操作结果。
     */
    suspend fun delete(entries: List<VFile>): Result<Unit>

    /**
     * 重命名单个条目。
     *
     * @param entry 待重命名条目。
     * @param targetName 新名称。
     * @return 重命名后的条目。
     */
    suspend fun rename(
        entry: VFile,
        targetName: String,
    ): Result<VFile>

    /**
     * 在父目录下创建文件。
     *
     * @param parentLocation 父目录位置。
     * @param name 文件名称。
     * @return 创建后的文件条目。
     */
    suspend fun createFile(
        parentLocation: String,
        name: String,
    ): Result<VFile>

    /**
     * 在父目录下创建目录。
     *
     * @param parentLocation 父目录位置。
     * @param name 目录名称。
     * @return 创建后的目录条目。
     */
    suspend fun createDirectory(
        parentLocation: String,
        name: String,
    ): Result<VFile>
}

/**
 * 文件传输字节进度接收器。
 *
 * 回调参数表示本次新增传输的字节数，调用方负责累加并节流 UI 更新。
 */
fun interface FileTransferProgressSink {
    /**
     * 接收本次完成的字节增量。
     *
     * @param byteCount 本次新增完成的字节数，必须为非负值。
     */
    fun onBytesTransferred(byteCount: Long)

    /** 无需接收字节进度时使用的空实现。 */
    object NoOp : FileTransferProgressSink {
        /**
         * 忽略传输字节增量。
         *
         * @param byteCount 本次新增完成的字节数。
         */
        override fun onBytesTransferred(byteCount: Long) = Unit
    }
}

/**
 * 支持逐块上报字节进度的文件命令服务。
 *
 * 未实现该接口的 Provider 仍可通过 [FileCommandService] 执行操作，任务中心会使用不确定进度展示。
 */
interface ProgressAwareFileCommandService : FileCommandService {
    /**
     * 复制文件或目录并上报已传输字节增量。
     *
     * @param entries 待复制条目。
     * @param targetDirectoryLocation 目标目录位置。
     * @param conflictStrategy 名称冲突处理策略。
     * @param progressSink 字节进度接收器。
     * @return 操作结果。
     */
    suspend fun copyWithProgress(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy = TransferConflictStrategy.KEEP_BOTH,
        progressSink: FileTransferProgressSink,
    ): Result<Unit>

    /**
     * 移动文件或目录并在发生实际内容复制时上报字节增量。
     *
     * @param entries 待移动条目。
     * @param targetDirectoryLocation 目标目录位置。
     * @param conflictStrategy 名称冲突处理策略。
     * @param progressSink 字节进度接收器。
     * @return 操作结果。
     */
    suspend fun moveWithProgress(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy = TransferConflictStrategy.KEEP_BOTH,
        progressSink: FileTransferProgressSink,
    ): Result<Unit>

    /**
     * 在无需进度时复用带进度复制实现。
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
    ): Result<Unit> = copyWithProgress(
        entries = entries,
        targetDirectoryLocation = targetDirectoryLocation,
        conflictStrategy = conflictStrategy,
        progressSink = FileTransferProgressSink.NoOp,
    )

    /**
     * 在无需进度时复用带进度移动实现。
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
    ): Result<Unit> = moveWithProgress(
        entries = entries,
        targetDirectoryLocation = targetDirectoryLocation,
        conflictStrategy = conflictStrategy,
        progressSink = FileTransferProgressSink.NoOp,
    )
}

/**
 * 可按位置路由的文件命令服务，用于多个 VFS provider 共存时选择具体实现。
 */
interface RoutableFileCommandService : FileCommandService {
    /**
     * 判断当前服务是否支持指定位置。
     *
     * @param location 待检查 VFS 位置。
     * @return 支持时返回 true。
     */
    fun supports(location: String): Boolean
}

/** 可按位置路由且支持字节级进度的文件命令服务。 */
interface ProgressAwareRoutableFileCommandService :
    RoutableFileCommandService,
    ProgressAwareFileCommandService

/**
 * 文件传输冲突策略。
 */
enum class TransferConflictStrategy {
    KEEP_BOTH,
    OVERWRITE,
    SKIP,
}
