package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.shared.usecase.ArchiveExtractionUseCase
import com.oruke.onyx.shared.usecase.TaskProgress
import com.oruke.onyx.shared.usecase.buildTaskDetail
import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.vfs.archive.ArchiveProgressSink
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 压缩包操作委托 — 负责解压任务、密码验证等压缩包相关业务。
 *
 * 从 DefaultRootComponent 剥离的纯业务逻辑。
 */
internal class ArchiveActionDelegate(
    /** 压缩包读写与密码验证服务。 */
    private val archiveService: ArchiveService,
    /** 后台任务状态编排器。 */
    private val taskOrchestrator: TaskOrchestrator,
    /** 根级对话框状态来源。 */
    private val dialogState: MutableStateFlow<RootDialogState?>,
    /** 解压成功后刷新全部面板的回调。 */
    private val onRefreshAllPanes: () -> Unit,
) {
    /** 当前等待用户输入的归档密码。 */
    private var pendingArchivePassword: CompletableDeferred<String>? = null

    /** 压缩包批量提取业务用例。 */
    private val archiveExtractionUseCase = ArchiveExtractionUseCase(archiveService)

    /**
     * 将压缩包内的选中条目解压到目标本地目录。
     * 用于从已打开的压缩包面板拖拽文件到本地目录面板。
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
            try {
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, targetDirectoryLocation),
                    progress = 0f,
                )
                archiveExtractionUseCase.extractEntriesToDirectory(
                    request = ArchiveExtractionUseCase.ArchiveEntryExtractionRequest(
                        entries = entries,
                        targetDirectoryLocation = targetDirectoryLocation,
                    ),
                    resolvePassword = { request ->
                        resolveArchivePassword(
                            archivePath = request.archivePath,
                            archiveName = request.archiveName,
                        )
                    },
                ).collect { progress ->
                    applyTaskProgress(taskId, progress)
                }

                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = I18nMessage(MessageKey.MSG_EXTRACTED_ITEMS, entries.size, targetDirectoryLocation),
                    progress = 1f,
                )
                // 刷新目标目录所在面板
                onRefreshAllPanes()
                taskOrchestrator.scheduleAutoCleanup(taskId)
            } catch (e: CancellationException) {
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(MessageKey.MSG_EXTRACT_FAILED),
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
            }
        }
    }

    /**
     * 通用压缩包解压任务启动器 — 支持加密压缩包密码输入。
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
            try {
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, buildTaskDetail(archiveEntries)),
                    progress = 0f,
                )
                archiveExtractionUseCase.extractArchiveFiles(
                    request = ArchiveExtractionUseCase.ArchiveFileExtractionRequest(
                        archiveEntries = archiveEntries,
                        currentLocation = currentLocation,
                        extractAction = extractAction,
                    ),
                    resolvePassword = { request ->
                        resolveArchivePassword(
                            archivePath = request.archivePath,
                            archiveName = request.archiveName,
                        )
                    },
                ).collect { progress ->
                    applyTaskProgress(taskId, progress)
                }

                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, buildTaskDetail(archiveEntries)),
                    progress = 1f,
                    processedCount = archiveEntries.size,
                )
                onRefreshAllPanes()
                taskOrchestrator.scheduleAutoCleanup(taskId)
            } catch (failure: CancellationException) {
                pendingArchivePassword = null
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(MessageKey.MSG_CANCELLED),
                )
                throw failure
            } catch (e: Exception) {
                OnyxLogger.error("ArchiveActionDelegate", "拖拽解压失败", e)
                pendingArchivePassword = null
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = e.toI18nMessage(MessageKey.MSG_EXTRACT_FAILED),
                )
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
            archivePath = archivePath,
            archiveName = ArchiveService.archiveLocationTitle(ArchiveService.archiveLocation(archivePath)),
        )
    }

    /**
     * 提交压缩包密码。
     *
     * @param password 用户在密码对话框中输入的密码。
     * @return 无返回值。
     */
    fun submitArchivePassword(password: String) {
        pendingArchivePassword?.complete(password)
    }

    /**
     * 取消当前等待的压缩包密码请求。
     *
     * @return 无返回值。
     */
    fun clearPending() {
        pendingArchivePassword?.cancel()
        pendingArchivePassword = null
    }

    /**
     * 复用已验证密码或请求用户输入新的密码。
     *
     * @param archivePath 归档在统一 VFS 中的原始位置。
     * @param archiveName 用于密码对话框展示的归档名称。
     * @return 新输入且已验证的密码；已存在会话密码时返回 `null`。
     */
    private suspend fun resolveArchivePassword(
        archivePath: String,
        archiveName: String,
    ): String? {
        if (archiveService.hasRememberedPassword(archivePath)) return null
        return requestArchivePassword(archivePath, archiveName)
    }

    /**
     * 弹出密码输入对话框并循环验证，直到密码正确或用户取消。
     *
     * @param archivePath 归档在统一 VFS 中的原始位置。
     * @param archiveName 用于密码对话框展示的归档名称。
     * @return 已验证并写入当前进程会话的归档密码。
     */
    private suspend fun requestArchivePassword(
        archivePath: String,
        archiveName: String,
    ): String {
        var errorMsg: I18nMessage? = null
        while (true) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val deferred = CompletableDeferred<String>()
            pendingArchivePassword = deferred
            dialogState.value = RootDialogState.ArchivePassword(
                archiveName = archiveName,
                error = errorMsg,
            )
            val candidatePassword = deferred.await()
            val valid = archiveService.verifyAndRememberPassword(archivePath, candidatePassword)
            if (valid) {
                dialogState.value = null
                pendingArchivePassword = null
                return candidatePassword
            } else {
                errorMsg = I18nMessage(MessageKey.MSG_ARCHIVE_PASSWORD_INVALID)
            }
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
