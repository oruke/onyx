package com.oruke.onyx.vfs.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService

/**
 * 基于 `java.nio.file.WatchService` 的文件系统变更监听器。
 */
class FileWatcher {
    /**
     * 文件系统变更类型。
     */
    enum class EventKind {
        CREATED,
        MODIFIED,
        DELETED,
    }

    /**
     * 单次文件系统变更事件。
     */
    data class FileWatchEvent(
        /** 变更类型。 */
        val kind: EventKind,

        /** 发生变更的完整路径。 */
        val path: Path,
    )

    /**
     * 监听指定目录下的文件创建、修改和删除事件。
     *
     * 返回 cold Flow；取消收集时会关闭 WatchService 并解除阻塞等待。
     *
     * @param directory 待监听目录。
     * @return 已去抖的文件变更事件流。
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun watch(directory: Path): Flow<FileWatchEvent> {
        return watch(directory, onRegistered = {})
    }

    /**
     * 监听目录，并在底层 WatchService 完成注册后发出就绪通知。
     *
     * 该入口用于需要与注册时机同步的模块内部场景，目录事件语义与公开入口一致。
     *
     * @param directory 待监听目录。
     * @param onRegistered WatchService 注册完成回调。
     * @return 已去抖的文件变更事件流。
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    internal fun watch(
        directory: Path,
        onRegistered: () -> Unit,
    ): Flow<FileWatchEvent> {
        return callbackFlow {
            val watchService = FileSystems.getDefault().newWatchService()
            directory.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE,
            )
            onRegistered()
            val watchJob = launch(Dispatchers.IO) {
                var watchKeyValid = true
                while (isActive && watchKeyValid) {
                    val key = watchService.takeOrNull()
                    if (key == null) {
                        watchKeyValid = false
                    } else {
                        key.pollEvents()
                            .mapNotNull { event -> event.toFileWatchEvent(directory) }
                            .forEach { event -> trySend(event) }
                        watchKeyValid = key.reset()
                    }
                }
            }
            awaitClose {
                watchService.close()
                watchJob.cancel()
            }
        }.debounce(WATCH_DEBOUNCE_MILLIS)
    }

    /** 文件变更事件去抖毫秒数。 */
    private companion object {
        const val WATCH_DEBOUNCE_MILLIS = 300L
    }
}

/**
 * 阻塞等待下一批文件事件，并把关闭或中断转换为监听结束。
 *
 * @return 下一批事件键；监听已结束时返回 `null`。
 */
private fun WatchService.takeOrNull(): WatchKey? {
    return try {
        take()
    } catch (_: ClosedWatchServiceException) {
        null
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }
}

/**
 * 将 NIO 监听事件转换为领域事件。
 *
 * @param directory 被监听目录。
 * @return 可识别事件；溢出或未知事件返回 `null`。
 */
private fun WatchEvent<*>.toFileWatchEvent(directory: Path): FileWatcher.FileWatchEvent? {
    val eventKind = when (kind()) {
        StandardWatchEventKinds.ENTRY_CREATE -> FileWatcher.EventKind.CREATED
        StandardWatchEventKinds.ENTRY_DELETE -> FileWatcher.EventKind.DELETED
        StandardWatchEventKinds.ENTRY_MODIFY -> FileWatcher.EventKind.MODIFIED
        else -> null
    }
    val relativePath = context() as? Path
    return if (eventKind != null && relativePath != null) {
        FileWatcher.FileWatchEvent(eventKind, directory.resolve(relativePath))
    } else {
        null
    }
}
