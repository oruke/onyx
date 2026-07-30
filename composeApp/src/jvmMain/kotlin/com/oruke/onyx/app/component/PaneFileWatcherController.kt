package com.oruke.onyx.app.component

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.vfs.local.FileWatcher
import com.oruke.onyx.vfs.local.LocalDirectoryAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * 面板文件监听控制器，负责本地目录监听的启动、取消与降级上报。
 *
 * @param scope 面板生命周期绑定的协程作用域。
 * @param fileWatcher 底层文件监听器。
 * @param onChanged 监听到变更后的回调。
 * @param onWatchDegraded 监听失败或降级后的回调。
 */
internal class PaneFileWatcherController(
    private val scope: CoroutineScope,
    private val fileWatcher: FileWatcher = FileWatcher(),
    private val onChanged: (String) -> Unit,
    private val onWatchDegraded: (String, Throwable) -> Unit,
) {
    /** 当前监听任务，切换目录时先取消旧任务。 */
    private var job: Job? = null

    /**
     * 开始监听指定位置。
     *
     * @param location 待监听位置，仅本地真实目录会启动监听。
     */
    fun start(location: String) {
        job?.cancel()
        job = scope.launch {
            val path = withContext(Dispatchers.IO) {
                location
                    .takeUnless(ArchiveService::isArchiveLocation)
                    ?.takeUnless { it.contains("://") }
                    ?.toLocalPathOrNull()
                    ?.let { candidate -> LocalDirectoryAccess.resolveForListing(candidate).getOrNull() }
                    ?.takeIf(Files::isDirectory)
                    ?.takeIf(Files::isReadable)
            } ?: return@launch
            fileWatcher.watch(path)
                .flowOn(Dispatchers.IO)
                .catch { failure ->
                    OnyxLogger.warn("PaneFileWatcherController", "文件监听已降级：$location", failure)
                    onWatchDegraded(location, failure)
                }
                .collect {
                    onChanged(location)
                }
        }
    }

    /**
     * 将位置文本转换为本地路径，非法路径返回空值。
     *
     * @return 可由 `java.nio` 处理的路径，格式非法时返回 `null`。
     */
    private fun String.toLocalPathOrNull(): Path? {
        return try {
            Path.of(this)
        } catch (_: InvalidPathException) {
            null
        }
    }
}
