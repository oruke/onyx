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

/**
 * 文件传输冲突策略。
 */
enum class TransferConflictStrategy {
    KEEP_BOTH,
    OVERWRITE,
    SKIP,
}
