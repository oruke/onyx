package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.filesystem.ArchiveService
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.msg_cancelled
import onyx.composeapp.generated.resources.msg_extract_failed
import onyx.composeapp.generated.resources.msg_extract_items
import onyx.composeapp.generated.resources.msg_extracted_items
import onyx.composeapp.generated.resources.msg_string_literal
import java.util.*

/**
 * 压缩包操作委托 — 负责解压任务、密码验证等压缩包相关业务。
 *
 * 从 DefaultRootComponent 剥离的纯业务逻辑。
 */
class ArchiveActionDelegate(
    private val scope: CoroutineScope,
    private val archiveService: ArchiveService,
    private val taskOrchestrator: TaskOrchestrator,
    private val dialogState: MutableStateFlow<RootDialogState?>,
    private val onRefreshAllPanes: () -> Unit,
) {
    var pendingArchiveExtraction: PendingArchiveExtraction? = null
        private set

    /**
     * 将压缩包内的选中条目解压到目标本地目录。
     * 用于从已打开的压缩包面板拖拽文件到本地目录面板。
     */
    fun launchArchiveExtractToDirectory(
        entries: List<VFile>,
        targetDirectoryLocation: String,
    ) {
        val taskId = UUID.randomUUID().toString()
        taskOrchestrator.appendTask(
            BackgroundTask(
                id = taskId,
                kind = BackgroundTaskKind.EXTRACT,
                title = I18nMessage(Res.string.msg_extract_items, entries.size),
                status = BackgroundTaskStatus.QUEUED,
                detail = I18nMessage(Res.string.msg_string_literal, targetDirectoryLocation),
                progress = 0f,
                totalCount = entries.size,
                startTimeMillis = System.currentTimeMillis(),
            )
        )

        val job = scope.launch {
            try {
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(Res.string.msg_string_literal, targetDirectoryLocation),
                    progress = 0f,
                )
                // 按 archivePath 分组，减少压缩包打开次数
                val grouped = entries.mapNotNull { entry ->
                    ArchiveService.parseArchiveLocation(entry.location)?.let { (archivePath, innerPath) ->
                        Triple(archivePath, innerPath, entry)
                    }
                }.groupBy { it.first }

                var processedCount = 0
                for ((archivePath, group) in grouped) {
                    ensureActive()
                    val innerPaths = group.map { it.second }.filter { it.isNotBlank() }
                    if (innerPaths.isEmpty()) continue

                    taskOrchestrator.updateTask(
                        taskId = taskId,
                        status = BackgroundTaskStatus.RUNNING,
                        detail = I18nMessage(Res.string.msg_string_literal, group.first().third.name),
                        progress = processedCount.toFloat() / entries.size,
                    )

                    // 检测是否加密，如需密码则弹出对话框并验证
                    var password: String? = null
                    val encrypted = archiveService.isEncrypted(archivePath)
                    if (encrypted) {
                        password = requestArchivePassword(
                            archivePath = archivePath,
                            archiveName = java.io.File(archivePath).name,
                            entries = entries,
                            targetLocation = targetDirectoryLocation,
                            taskId = taskId,
                            taskTitle = I18nMessage(Res.string.msg_extract_items, entries.size),
                        )
                    }

                    archiveService.extractEntriesToTemp(
                        archivePath = archivePath,
                        entryPaths = innerPaths,
                        targetDir = targetDirectoryLocation,
                        password = password,
                    ).getOrThrow()

                    processedCount += group.size
                }

                taskOrchestrator.dismissTask(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = I18nMessage(Res.string.msg_extracted_items, entries.size, targetDirectoryLocation),
                    progress = 1f,
                )
                // 刷新目标目录所在面板
                onRefreshAllPanes()
            } catch (e: CancellationException) {
                taskOrchestrator.dismissTask(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(Res.string.msg_extract_failed),
                    progress = 0f,
                )
            } catch (e: Exception) {
                OnyxLogger.error("ArchiveActionDelegate", "解压失败", e)
                taskOrchestrator.dismissTask(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = if (e.message != null) {
                        I18nMessage(Res.string.msg_string_literal, e.message!!)
                    } else {
                        I18nMessage(Res.string.msg_extract_failed)
                    },
                    progress = 0f,
                )
            }
        }
        taskOrchestrator.registerJob(taskId, job)
    }

    /**
     * 通用压缩包解压任务启动器 — 支持加密压缩包密码输入。
     */
    fun launchArchiveExtraction(
        selectedEntries: List<VFile>,
        currentLocation: String,
        taskTitle: I18nMessage,
        extractAction: suspend (VFile, String, String?) -> Result<Unit>,
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
                detail = I18nMessage(Res.string.msg_string_literal, buildTaskDetail(archiveEntries)),
                progress = 0f,
                totalCount = archiveEntries.size,
                startTimeMillis = System.currentTimeMillis(),
            )
        )

        val job = scope.launch {
            try {
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(Res.string.msg_string_literal, buildTaskDetail(archiveEntries)),
                    progress = 0f,
                )
                archiveEntries.forEachIndexed { index, entry ->
                    ensureActive()
                    taskOrchestrator.updateTask(
                        taskId = taskId,
                        status = BackgroundTaskStatus.RUNNING,
                        detail = I18nMessage(Res.string.msg_string_literal, entry.name),
                        progress = index.toFloat() / archiveEntries.size,
                    )

                    // 检测是否加密，如需密码则弹出对话框并验证
                    var password: String? = null
                    val encrypted = archiveService.isEncrypted(entry.location)
                    if (encrypted) {
                        password = requestArchivePassword(
                            archivePath = entry.location,
                            archiveName = entry.name,
                            entries = archiveEntries,
                            targetLocation = currentLocation,
                            taskId = taskId,
                            taskTitle = taskTitle,
                            extractAction = extractAction,
                        )
                    }

                    extractAction(entry, currentLocation, password).getOrThrow()
                }

                taskOrchestrator.dismissTask(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = I18nMessage(Res.string.msg_string_literal, buildTaskDetail(archiveEntries)),
                    progress = 1f,
                    processedCount = archiveEntries.size,
                )
                onRefreshAllPanes()
                taskOrchestrator.scheduleAutoCleanup(taskId)
            } catch (_: CancellationException) {
                pendingArchiveExtraction = null
                taskOrchestrator.dismissTask(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(Res.string.msg_cancelled),
                )
            } catch (e: Throwable) {
                OnyxLogger.error("ArchiveActionDelegate", "拖拽解压失败", e)
                pendingArchiveExtraction = null
                taskOrchestrator.dismissTask(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = I18nMessage(Res.string.msg_string_literal, e.message ?: "Unknown error"),
                )
            }
        }
        taskOrchestrator.registerJob(taskId, job)
    }

    /**
     * 提交压缩包密码（从对话框回调）。
     */
    fun submitArchivePassword(password: String) {
        val pending = pendingArchiveExtraction ?: return
        pending.passwordDeferred.complete(password)
    }

    /**
     * 清除待处理状态（用于对话框取消时）。
     */
    fun clearPending() {
        pendingArchiveExtraction?.passwordDeferred?.cancel()
        pendingArchiveExtraction = null
    }

    /**
     * 通用密码请求循环 — 弹出对话框、验证密码，直到正确或取消。
     */
    private suspend fun requestArchivePassword(
        archivePath: String,
        archiveName: String,
        entries: List<VFile>,
        targetLocation: String,
        taskId: String,
        taskTitle: I18nMessage,
        extractAction: suspend (VFile, String, String?) -> Result<Unit> = { _, _, _ -> Result.success(Unit) },
    ): String {
        var errorMsg: String? = null
        while (true) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val deferred = CompletableDeferred<String>()
            pendingArchiveExtraction = PendingArchiveExtraction(
                entries = entries,
                currentLocation = targetLocation,
                taskId = taskId,
                taskTitle = taskTitle,
                extractAction = extractAction,
                passwordDeferred = deferred,
            )
            dialogState.value = RootDialogState.ArchivePassword(
                archiveName = archiveName,
                error = errorMsg,
            )
            val candidatePassword = deferred.await()
            val valid = archiveService.verifyPassword(archivePath, candidatePassword)
            if (valid) {
                dialogState.value = null
                pendingArchiveExtraction = null
                return candidatePassword
            } else {
                errorMsg = "密码错误，请重新输入"
            }
        }
    }

    class PendingArchiveExtraction(
        val entries: List<VFile>,
        val currentLocation: String,
        val taskId: String,
        val taskTitle: I18nMessage,
        val extractAction: suspend (VFile, String, String?) -> Result<Unit>,
        val passwordDeferred: CompletableDeferred<String>,
    )
}
