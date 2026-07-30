package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.archive.ZipArchiveCreationProgressSink
import com.oruke.onyx.vfs.archive.ZipArchiveCreationRequest
import com.oruke.onyx.vfs.archive.ZipArchiveCreationService
import com.oruke.onyx.vfs.api.VfsProviderException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * ZIP 压缩任务委托，负责后台任务状态、进度同步与完成后的面板刷新。
 *
 * @param zipArchiveCreationService 统一 VFS ZIP 创建服务。
 * @param taskOrchestrator 后台任务状态编排器。
 * @param onRefreshAllPanes 归档创建成功后刷新全部面板的回调。
 */
internal class ArchiveCompressionDelegate(
    private val zipArchiveCreationService: ZipArchiveCreationService,
    private val taskOrchestrator: TaskOrchestrator,
    private val onRefreshAllPanes: () -> Unit,
) {
    /**
     * 启动 ZIP 压缩后台任务。
     *
     * @param selectedEntries 待打包的文件或目录。
     * @param targetDirectoryLocation 新归档写入的父目录位置。
     * @param archiveName 已校验并补齐扩展名的归档名称。
     * @return 无返回值。
     */
    @Suppress("TooGenericExceptionCaught") // 后台任务边界负责将任意 provider 异常转换为任务失败状态。
    fun launchZipArchiveCreation(
        selectedEntries: List<VFile>,
        targetDirectoryLocation: String,
        archiveName: String,
    ) {
        if (selectedEntries.isEmpty()) return
        val taskId = UUID.randomUUID().toString()
        taskOrchestrator.appendTask(
            BackgroundTask(
                id = taskId,
                kind = BackgroundTaskKind.COMPRESS,
                title = I18nMessage(MessageKey.ACTION_CREATE_ZIP),
                status = BackgroundTaskStatus.QUEUED,
                detail = I18nMessage(MessageKey.MSG_COMPRESS_ITEMS, selectedEntries.size),
                progress = null,
                totalCount = 0,
                startTimeMillis = System.currentTimeMillis(),
            )
        )
        taskOrchestrator.launchQueuedTask(taskId) {
            try {
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = I18nMessage(MessageKey.MSG_COMPRESS_ITEMS, selectedEntries.size),
                    progress = null,
                )
                val result = zipArchiveCreationService.create(
                    request = ZipArchiveCreationRequest(
                        entries = selectedEntries,
                        targetDirectoryLocation = targetDirectoryLocation,
                        archiveName = archiveName,
                    ),
                    progressSink = ZipArchiveCreationProgressSink { progress ->
                        // ZIP 流在 IO 线程写入，任务中心状态必须回到主线程更新。
                        withContext(Dispatchers.Main.immediate) {
                            taskOrchestrator.updateTaskFields(taskId) { task ->
                                task.copy(
                                    currentFileName = progress.currentFileName,
                                    processedCount = progress.writtenFileCount,
                                    processedBytes = progress.writtenBytes,
                                )
                            }
                        }
                    },
                ).getOrThrow()
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = I18nMessage(
                        MessageKey.MSG_COMPRESSED_ITEMS,
                        selectedEntries.size,
                        result.archiveName,
                    ),
                    progress = 1f,
                    processedCount = result.entryCount,
                )
                onRefreshAllPanes()
                taskOrchestrator.scheduleAutoCleanup(taskId)
            } catch (failure: CancellationException) {
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = I18nMessage(MessageKey.MSG_CANCELLED),
                    progress = null,
                )
                throw failure
            } catch (failure: Exception) {
                OnyxLogger.error("ArchiveCompressionDelegate", "创建 ZIP 压缩包失败", failure)
                taskOrchestrator.unregisterJob(taskId)
                taskOrchestrator.updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = if (failure is VfsProviderException) {
                        failure.error.toI18nMessage()
                    } else {
                        I18nMessage(MessageKey.MSG_COMPRESS_FAILED)
                    },
                    progress = null,
                )
            }
        }
    }
}
