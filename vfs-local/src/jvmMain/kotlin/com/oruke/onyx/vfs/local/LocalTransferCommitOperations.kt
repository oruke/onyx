package com.oruke.onyx.vfs.local

import com.oruke.onyx.vfs.api.FileTransferProgressSink
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** 将完整内容提交到最终位置，提交失败时保留旧目标和移动源的完整副本。 */
internal object LocalTransferCommitOperations {
    /**
     * 先在目标文件系统复制，再提交完整副本；读取失败或取消不会触碰旧目标。
     *
     * @param source 源路径。
     * @param target 最终目标路径。
     * @param strategy 冲突处理策略。
     * @param progressSink 实际复制字节接收器。
     * @param checkActive 提交前取消检查；最终交换期间不打断，以保证目标完整。
     */
    @Suppress("TooGenericExceptionCaught") // 必须在任意复制或取消失败时保护旧内容，并原样传播异常。
    fun copy(
        source: Path, target: Path, strategy: TransferConflictStrategy,
        progressSink: FileTransferProgressSink, checkActive: () -> Unit,
    ) {
        val staging = Files.createTempDirectory(target.parent, ".onyx-transfer-")
        try {
            val content = staging.resolve("content")
            // 完整复制之前不改动最终目标，确保失败和取消都能保留旧内容。
            LocalFileCopyOperations.copyPathWithRollback(source, content, progressSink)
            checkActive()
            commit(content, target, staging.resolve("previous"), strategy)
        } catch (failure: Exception) {
            cleanup(staging, failure)
            throw failure
        }
        LocalPathDeletion.delete(staging)
    }

    /**
     * 优先同文件系统暂存移动；跨文件系统复制后提交，最后才删除源。
     *
     * @param source 源路径。
     * @param target 最终目标路径。
     * @param strategy 冲突处理策略。
     * @param progressSink 跨文件系统复制字节接收器。
     * @param checkActive 提交前取消检查；暂存移动被取消时恢复源。
     */
    @Suppress("TooGenericExceptionCaught") // 提交与恢复边界需保留任意失败，并附加回滚异常。
    fun move(
        source: Path, target: Path, strategy: TransferConflictStrategy,
        progressSink: FileTransferProgressSink, checkActive: () -> Unit,
    ) {
        val staging = Files.createTempDirectory(target.parent, ".onyx-transfer-")
        val content = staging.resolve("content")
        var sourceMoved = false
        var committed = false
        var preserveStaging = false
        try {
            try {
                Files.move(source, content)
                sourceMoved = true
            } catch (failure: IOException) {
                // 如果移动已留下目标，不能再次复制或删除这个可能唯一完整的副本。
                if (Files.exists(content, LinkOption.NOFOLLOW_LINKS)) {
                    preserveStaging = true
                    throw failure
                }
                LocalFileCopyOperations.copyPathWithRollback(source, content, progressSink)
            }
            checkActive()
            commit(content, target, staging.resolve("previous"), strategy)
            committed = true
            // 删除源目录可能只完成一部分，失败时必须保留已经提交的完整目标。
            if (!sourceMoved) LocalPathDeletion.delete(source)
        } catch (failure: Exception) {
            if (sourceMoved && !committed) {
                runCatching { Files.move(content, source) }
                    .onFailure { rollbackFailure -> failure.addSuppressed(rollbackFailure) }
            }
            // 恢复源失败时，保留暂存副本；异常同时包含恢复失败的位置。
            val safeToClean = !sourceMoved || committed || !Files.exists(content, LinkOption.NOFOLLOW_LINKS)
            if (!preserveStaging && safeToClean) {
                cleanup(staging, failure)
            }
            throw failure
        }
        LocalPathDeletion.delete(staging)
    }

    /**
     * 在同一文件系统中交换完整新内容与旧目标，提交失败时恢复旧目标。
     *
     * @param content 完整的暂存新内容。
     * @param target 最终目标位置。
     * @param previous 旧目标备份位置。
     * @param strategy 是否允许覆盖新出现的同名目标。
     */
    @Suppress("TooGenericExceptionCaught") // 任何提交错误均须尝试恢复旧目标，恢复失败不能被覆盖。
    private fun commit(content: Path, target: Path, previous: Path, strategy: TransferConflictStrategy) {
        val hasPrevious = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
        if (hasPrevious) {
            if (strategy != TransferConflictStrategy.OVERWRITE) throw FileAlreadyExistsException(target.toString())
            Files.move(target, previous)
        }
        try {
            Files.move(content, target)
        } catch (failure: Exception) {
            if (hasPrevious) {
                runCatching { Files.move(previous, target) }
                    .onFailure { rollbackFailure -> failure.addSuppressed(rollbackFailure) }
            }
            throw failure
        }
    }

    /**
     * 清理本次操作的暂存内容，旧目标恢复失败时保留备份供恢复。
     *
     * @param staging 本次操作独占的暂存目录。
     * @param failure 原始失败，用于附加清理异常。
     */
    private fun cleanup(staging: Path, failure: Exception) {
        if (Files.exists(staging.resolve("previous"), LinkOption.NOFOLLOW_LINKS)) return
        runCatching { LocalPathDeletion.delete(staging) }
            .onFailure { cleanupFailure -> failure.addSuppressed(cleanupFailure) }
    }
}
