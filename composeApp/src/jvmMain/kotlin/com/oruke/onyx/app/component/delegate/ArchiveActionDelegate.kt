package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.filesystem.isSystemVirtualLocation
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.shared.usecase.ArchiveExtractionUseCase
import com.oruke.onyx.shared.usecase.TaskProgress
import com.oruke.onyx.shared.usecase.buildTaskDetail
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.archive.ArchiveProgressSink
import com.oruke.onyx.vfs.archive.ArchiveService
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/**
 * 压缩包操作委托 — 负责解压任务、多任务排队密码验证与远程 VFS 中转上传等业务。
 *
 * 从 DefaultRootComponent 剥离的纯业务逻辑。
 *
 * @property archiveService 压缩包读写与密码验证服务。
 * @property taskOrchestrator 后台任务状态编排器。
 * @property dialogState 根级对话框状态来源。
 * @property onRefreshDirectories 解压实际触及目录的刷新回调。
 * @property fileCommandService 统一文件命令服务，用于向远程 VFS 面板中转上传解压产物。
 * @property fileRepository 统一目录查询服务，用于列举临时解压目录的顶层产物。
 */
internal class ArchiveActionDelegate(
    private val archiveService: ArchiveService,
    private val taskOrchestrator: TaskOrchestrator,
    private val dialogState: MutableStateFlow<RootDialogState?>,
    private val onRefreshDirectories: (Set<String>) -> Unit,
    private val fileCommandService: FileCommandService? = null,
    private val fileRepository: FileRepository? = null,
) {
    /** 密码排队请求记录（所有可变字段仅在持有 [passwordQueueLock] 时读写）。 */
    private class PendingPasswordRequest(
        val id: String,
        val taskId: String?,
        val archivePath: String,
        val archiveName: String,
        var deferred: CompletableDeferred<String>,
        var error: I18nMessage? = null,
        var attempt: Int = 0,
        var verifying: Boolean = false,
    )

    /** 多任务并发密码请求队列同步锁。 */
    private val passwordQueueLock = Any()

    /** 按先来先服务顺序排队的归档密码请求队列，队首始终对应当前弹窗。 */
    private val passwordQueue = ArrayDeque<PendingPasswordRequest>()

    /** 压缩包批量提取业务用例。 */
    private val archiveExtractionUseCase = ArchiveExtractionUseCase(archiveService)

    /**
     * 将压缩包内的选中条目解压到目标目录（自动兼容本地目录与远程 SMB/S3/WebDAV 目录）。
     * 用于从已打开的压缩包面板拖拽文件到目标目录面板。
     *
     * @param entries 待提取的归档内部条目。
     * @param targetDirectoryLocation 目标目录位置。
     * @return 无返回值。
     */
    @Suppress("TooGenericExceptionCaught") // 后台任务边界负责将任意业务异常转换为任务失败状态。
    fun launchArchiveExtractToDirectory(
        entries: List<VFile>,
        targetDirectoryLocation: String,
    ) {
        val taskId = UUID.randomUUID().toString()
        taskOrchestrator.appendTask(
            BackgroundTask(
                id = taskId,
                kind = BackgroundTaskKind.EXTRACT,
                title = I18nMessage(MessageKey.MSG_EXTRACT_ITEMS, entries.size),
                status = BackgroundTaskStatus.QUEUED,
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, targetDirectoryLocation),
                progress = 0f,
                totalCount = entries.size,
                startTimeMillis = System.currentTimeMillis(),
            )
        )

        taskOrchestrator.launchQueuedTask(taskId) {
            var remoteStagingDir: Path? = null
            var refreshedTarget = false
            try {
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, targetDirectoryLocation),
                    progress = 0f,
                )
                val targetIsRemote = isRemoteExtractTarget(targetDirectoryLocation)
                val localExtractDir = if (targetIsRemote) {
                    requireRemoteTransferReady(targetDirectoryLocation)
                    withContext(Dispatchers.IO) {
                        Files.createTempDirectory("onyx-remote-extract-")
                    }.also { remoteStagingDir = it }.toAbsolutePath().toString()
                } else {
                    ensureLocalWritableTarget(targetDirectoryLocation)
                    targetDirectoryLocation
                }
                archiveExtractionUseCase.extractEntriesToDirectory(
                    request = ArchiveExtractionUseCase.ArchiveEntryExtractionRequest(
                        entries = entries,
                        targetDirectoryLocation = localExtractDir,
                    ),
                    resolvePassword = { request ->
                        resolveArchivePassword(
                            taskId = taskId,
                            archivePath = request.archivePath,
                            archiveName = request.archiveName,
                        )
                    },
                ).collect { progress ->
                    applyTaskProgress(taskId, progress)
                }

                remoteStagingDir?.let { staging ->
                    uploadExtractedStagingToRemote(staging, targetDirectoryLocation)
                    cleanupTempDirectoryQuietly(staging)
                    remoteStagingDir = null
                }
                onRefreshDirectories(setOf(targetDirectoryLocation))
                refreshedTarget = true

                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = I18nMessage(MessageKey.MSG_EXTRACTED_ITEMS, entries.size, targetDirectoryLocation),
                    progress = 1f,
                )
                taskOrchestrator.scheduleAutoCleanup(taskId)
            } catch (e: CancellationException) {
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(MessageKey.MSG_CANCELLED),
                    progress = 0f,
                )
                throw e
            } catch (e: Exception) {
                OnyxLogger.error("ArchiveActionDelegate", "解压失败", e)
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = e.toI18nMessage(MessageKey.MSG_EXTRACT_FAILED),
                    progress = 0f,
                )
            } finally {
                cancelPasswordRequestsForTask(taskId)
                remoteStagingDir?.let { staging -> cleanupTempDirectoryQuietly(staging) }
                if (!refreshedTarget) {
                    onRefreshDirectories(setOf(targetDirectoryLocation))
                }
            }
        }
    }

    /**
     * 通用压缩包解压任务启动器 — 支持加密压缩包多任务排队密码输入与远程目标安全中转。
     *
     * @param selectedEntries 当前选中的候选压缩文件。
     * @param currentLocation 解压目标所在的当前面板位置。
     * @param taskTitle 任务中心展示标题。
     * @param extractAction 单个压缩文件的实际提取动作。
     * @return 无返回值。
     */
    @Suppress("TooGenericExceptionCaught") // 后台任务边界负责将任意 Provider 异常转换为任务失败状态。
    fun launchArchiveExtraction(
        selectedEntries: List<VFile>,
        currentLocation: String,
        taskTitle: I18nMessage,
        extractAction: suspend (VFile, String, String?, ArchiveProgressSink) -> Result<Unit>,
    ) {
        val archiveEntries = selectedEntries.filter { entry ->
            entry.kind == VFileKind.FILE && ArchiveService.isArchive(entry.name)
        }
        if (archiveEntries.isEmpty()) return

        val taskId = UUID.randomUUID().toString()
        taskOrchestrator.appendTask(
            BackgroundTask(
                id = taskId,
                kind = BackgroundTaskKind.EXTRACT,
                title = taskTitle,
                status = BackgroundTaskStatus.QUEUED,
                detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, buildTaskDetail(archiveEntries)),
                progress = 0f,
                totalCount = archiveEntries.size,
                startTimeMillis = System.currentTimeMillis(),
            )
        )

        taskOrchestrator.launchQueuedTask(taskId) {
            var remoteStagingDir: Path? = null
            try {
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, buildTaskDetail(archiveEntries)),
                    progress = 0f,
                )
                val targetIsRemote = isRemoteExtractTarget(currentLocation)
                val localExtractDir = if (targetIsRemote) {
                    requireRemoteTransferReady(currentLocation)
                    withContext(Dispatchers.IO) {
                        Files.createTempDirectory("onyx-remote-extract-")
                    }.also { remoteStagingDir = it }.toAbsolutePath().toString()
                } else {
                    ensureLocalWritableTarget(currentLocation)
                    currentLocation
                }
                archiveExtractionUseCase.extractArchiveFiles(
                    request = ArchiveExtractionUseCase.ArchiveFileExtractionRequest(
                        archiveEntries = archiveEntries,
                        currentLocation = localExtractDir,
                        extractAction = extractAction,
                    ),
                    resolvePassword = { request ->
                        resolveArchivePassword(
                            taskId = taskId,
                            archivePath = request.archivePath,
                            archiveName = request.archiveName,
                        )
                    },
                ).collect { progress ->
                    applyTaskProgress(taskId, progress)
                }

                remoteStagingDir?.let { staging ->
                    uploadExtractedStagingToRemote(staging, currentLocation)
                    cleanupTempDirectoryQuietly(staging)
                    remoteStagingDir = null
                }
                onRefreshDirectories(setOf(currentLocation))

                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, buildTaskDetail(archiveEntries)),
                    progress = 1f,
                    processedCount = archiveEntries.size,
                )
                taskOrchestrator.scheduleAutoCleanup(taskId)
            } catch (failure: CancellationException) {
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(MessageKey.MSG_CANCELLED),
                )
                throw failure
            } catch (e: Exception) {
                OnyxLogger.error("ArchiveActionDelegate", "解压失败", e)
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = e.toI18nMessage(MessageKey.MSG_EXTRACT_FAILED),
                )
            } finally {
                cancelPasswordRequestsForTask(taskId)
                remoteStagingDir?.let { staging -> cleanupTempDirectoryQuietly(staging) }
                if (taskOrchestrator.tasks.value.firstOrNull { it.id == taskId }?.status != BackgroundTaskStatus.SUCCEEDED) {
                    onRefreshDirectories(setOf(currentLocation))
                }
            }
        }
    }

    /**
     * 为读取压缩包内部文件准备访问密码。
     *
     * 进入压缩包只读取目录清单，不主动询问密码；只有双击读取内部文件或图片时，
     * 才和拖拽解压复用同一个密码输入与会话缓存。
     *
     * @param entry 待读取的压缩包内部文件。
     * @return 无返回值。
     */
    suspend fun prepareArchiveContentAccess(entry: VFile) {
        if (entry.kind != VFileKind.FILE) return
        val (archivePath, innerPath) = ArchiveService.parseArchiveLocation(entry.location) ?: return
        if (innerPath.isBlank()) return
        if (archiveService.hasRememberedPassword(archivePath)) return
        if (!archiveService.isEncrypted(archivePath)) return
        requestArchivePassword(
            taskId = null,
            archivePath = archivePath,
            archiveName = ArchiveService.archiveLocationTitle(ArchiveService.archiveLocation(archivePath)),
        )
    }

    /**
     * 提交当前展示中的压缩包密码。
     *
     * @param password 用户在密码对话框中输入的密码。
     * @return 无返回值。
     */
    fun submitArchivePassword(password: String) {
        val targetDeferred = synchronized(passwordQueueLock) {
            val head = passwordQueue.firstOrNull() ?: return
            if (head.verifying) return
            head.verifying = true
            syncPasswordDialogLocked()
            head.deferred
        }
        targetDeferred.complete(password)
    }

    /**
     * 取消当前展示中的压缩包密码请求，并自动推进队列中下一个任务的密码弹窗。
     *
     * @return 无返回值。
     */
    fun clearPending() {
        val cancelled = synchronized(passwordQueueLock) {
            val head = passwordQueue.removeFirstOrNull()
            syncPasswordDialogLocked()
            head
        }
        cancelled?.deferred?.cancel()
    }

    /**
     * 当外部其他模态弹窗关闭且当前根对话框空闲时，恢复展示队列中尚在等待的归档密码弹窗。
     */
    fun restorePendingPasswordDialogIfIdle() {
        synchronized(passwordQueueLock) {
            if (dialogState.value == null && passwordQueue.isNotEmpty()) {
                syncPasswordDialogLocked()
            }
        }
    }

    /**
     * 取消指定后台任务关联的全部等待密码请求，若正展示其弹窗则立即关闭或切换至队列下一项。
     *
     * @param taskId 已取消或结束的任务 ID。
     */
    fun cancelPasswordRequestsForTask(taskId: String) {
        val removed = synchronized(passwordQueueLock) {
            val matches = passwordQueue.filter { it.taskId == taskId }
            if (matches.isNotEmpty()) {
                passwordQueue.removeAll { it.taskId == taskId }
                syncPasswordDialogLocked()
            }
            matches
        }
        removed.forEach { request -> request.deferred.cancel() }
    }

    /**
     * 复用已验证密码或请求用户输入新的密码。
     *
     * @param taskId 所属后台任务 ID（非后台任务时为 `null`）。
     * @param archivePath 归档在统一 VFS 中的原始位置。
     * @param archiveName 用于密码对话框展示的归档名称。
     * @return 新输入且已验证的密码；已存在会话密码时返回 `null`。
     */
    private suspend fun resolveArchivePassword(
        taskId: String?,
        archivePath: String,
        archiveName: String,
    ): String? {
        if (archiveService.hasRememberedPassword(archivePath)) return null
        return requestArchivePassword(taskId, archivePath, archiveName)
    }

    /**
     * 通过有序队列弹出密码输入对话框并循环验证，直到密码正确或任务/用户取消。
     *
     * @param taskId 所属后台任务 ID。
     * @param archivePath 归档在统一 VFS 中的原始位置。
     * @param archiveName 用于密码对话框展示的归档名称。
     * @return 已验证并写入当前进程会话的归档密码。
     */
    internal suspend fun requestArchivePassword(
        taskId: String?,
        archivePath: String,
        archiveName: String,
    ): String {
        val requestId = UUID.randomUUID().toString()
        val pending = PendingPasswordRequest(
            id = requestId,
            taskId = taskId,
            archivePath = archivePath,
            archiveName = archiveName,
            deferred = CompletableDeferred(),
        )
        var enqueued = false
        try {
            while (true) {
                kotlin.coroutines.coroutineContext.ensureActive()
                if (archiveService.hasRememberedPassword(archivePath)) {
                    return archiveService.rememberedPasswordOrNull(archivePath).orEmpty()
                }
                val currentDeferred = synchronized(passwordQueueLock) {
                    if (!enqueued) {
                        passwordQueue.addLast(pending)
                        enqueued = true
                    }
                    syncPasswordDialogLocked()
                    pending.deferred
                }
                val candidatePassword = currentDeferred.await()
                val valid = archiveService.verifyAndRememberPassword(archivePath, candidatePassword)
                if (valid) {
                    val stillActive = synchronized(passwordQueueLock) {
                        passwordQueue.any { it.id == requestId }
                    }
                    if (!stillActive) {
                        throw CancellationException("Archive password request cancelled during verification")
                    }
                    return candidatePassword
                }
                synchronized(passwordQueueLock) {
                    // 若在校验期间请求已被外部取消移出队列，直接中止循环。
                    if (passwordQueue.none { it.id == requestId }) {
                        throw CancellationException("Archive password request cancelled")
                    }
                    pending.error = I18nMessage(MessageKey.MSG_ARCHIVE_PASSWORD_INVALID)
                    pending.attempt += 1
                    pending.verifying = false
                    pending.deferred = CompletableDeferred()
                    syncPasswordDialogLocked()
                }
            }
        } finally {
            synchronized(passwordQueueLock) {
                val removed = passwordQueue.removeAll { it.id == requestId }
                if (removed) {
                    syncPasswordDialogLocked()
                }
            }
        }
    }

    /** 在持有 [passwordQueueLock] 时同步队列头部请求到根对话框状态。 */
    private fun syncPasswordDialogLocked() {
        val head = passwordQueue.firstOrNull()
        if (head != null) {
            dialogState.value = RootDialogState.ArchivePassword(
                archiveName = head.archiveName,
                error = head.error,
                attempt = head.attempt,
                verifying = head.verifying,
                requestId = head.id,
            )
        } else if (dialogState.value is RootDialogState.ArchivePassword) {
            dialogState.value = null
        }
    }

    /** 判断目标位置是否为需要经临时目录中转上传的远程 VFS 目录（SMB/S3/WebDAV）。 */
    private fun isRemoteExtractTarget(location: String): Boolean {
        val lower = location.lowercase()
        return lower.startsWith("smb://") ||
            lower.startsWith("s3://") ||
            lower.startsWith("webdav://") ||
            lower.startsWith("webdavs://")
    }

    /** 校验非远程目标确实为可写入的本地路径，拒绝虚拟/归档等非法目标避免在 CWD 造畸形目录。 */
    private fun ensureLocalWritableTarget(location: String) {
        if (isSystemVirtualLocation(location) || "://" in location) {
            throw VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.ARCHIVE,
                    location = location,
                    capability = VfsProviderCapability.WRITE_CONTENT,
                )
            )
        }
    }

    /** 校验远程目标具备统一文件命令与列表依赖。 */
    private fun requireRemoteTransferReady(targetLocation: String) {
        if (fileCommandService == null || fileRepository == null) {
            throw VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = detectRemoteProtocol(targetLocation),
                    location = targetLocation,
                    capability = VfsProviderCapability.WRITE_CONTENT,
                )
            )
        }
    }

    /** 将临时本地目录内的解压产物经统一 VFS 传输命令上传至远程目标目录。 */
    private suspend fun uploadExtractedStagingToRemote(
        stagingDir: Path,
        remoteTargetLocation: String,
    ) {
        val repo = requireNotNull(fileRepository)
        val commandService = requireNotNull(fileCommandService)
        val topLevelEntries = repo.list(stagingDir.toAbsolutePath().toString()).getOrThrow()
        if (topLevelEntries.isEmpty()) return
        commandService.copy(
            entries = topLevelEntries,
            targetDirectoryLocation = remoteTargetLocation,
            conflictStrategy = TransferConflictStrategy.KEEP_BOTH,
        ).getOrThrow()
    }

    /** 安全递归清理临时中转目录（叠加 [NonCancellable] 确保协程取消路径下也不会跳过清理）。 */
    private suspend fun cleanupTempDirectoryQuietly(stagingDir: Path) {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                Files.walk(stagingDir).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { path ->
                        runCatching { Files.deleteIfExists(path) }
                    }
                }
            }
        }
    }

    private fun detectRemoteProtocol(location: String): VfsProtocol {
        val lower = location.lowercase()
        return when {
            lower.startsWith("smb://") -> VfsProtocol.SMB
            lower.startsWith("s3://") -> VfsProtocol.S3
            lower.startsWith("webdav://") || lower.startsWith("webdavs://") -> VfsProtocol.WEBDAV
            else -> VfsProtocol.LOCAL
        }
    }

    /**
     * 将归档用例上报的进度同步到任务中心。
     *
     * @param taskId 任务中心中的任务标识。
     * @param progress 归档用例当前进度。
     * @return 无返回值。
     */
    private fun applyTaskProgress(
        taskId: String,
        progress: TaskProgress,
    ) {
        taskOrchestrator.updateTask(
            taskId = taskId,
            status = progress.status,
            detail = progress.detail,
            progress = progress.progress,
            processedCount = progress.processedCount,
            processedBytes = progress.processedBytes,
            totalBytes = progress.totalBytes,
            bytesPerSecond = progress.bytesPerSecond,
            estimatedRemainingSeconds = progress.estimatedRemainingSeconds,
        )
        progress.currentFileName?.let { fileName ->
            taskOrchestrator.updateTaskFields(taskId) { task ->
                task.copy(currentFileName = fileName)
            }
        }
    }
}
