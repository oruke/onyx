package com.oruke.onyx.app.filesystem

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey

/**
 * 基于 java.nio.file.WatchService 的文件系统变更监听器。
 *
 * 通过 [watch] 方法监听指定目录下的文件创建、修改、删除事件。
 * 自动进行 300ms 去抖，防止文件操作期间的频繁刷新。
 */
class FileWatcher {

    enum class EventKind { CREATED, MODIFIED, DELETED }

    data class FileWatchEvent(
        val kind: EventKind,
        val path: Path,
    )

    /**
     * 开始监听指定目录下的文件系统变更。
     *
     * 返回一个 cold Flow，在收集时启动监听，取消收集时自动关闭 WatchService。
     * 自动去抖 300ms。
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun watch(directory: Path): Flow<FileWatchEvent> {
        return callbackFlow {
            val watchService = FileSystems.getDefault().newWatchService()
            directory.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE,
            )

            var running = true
            while (running) {
                val key: WatchKey = try {
                    watchService.take() // 阻塞直到有事件
                } catch (_: java.nio.file.ClosedWatchServiceException) {
                    break
                } catch (_: InterruptedException) {
                    break
                }

                for (event in key.pollEvents()) {
                    val kind = when (event.kind()) {
                        StandardWatchEventKinds.ENTRY_CREATE -> EventKind.CREATED
                        StandardWatchEventKinds.ENTRY_DELETE -> EventKind.DELETED
                        StandardWatchEventKinds.ENTRY_MODIFY -> EventKind.MODIFIED
                        StandardWatchEventKinds.OVERFLOW -> continue
                        else -> continue
                    }
                    val context = event.context() as? Path ?: continue
                    val resolved = directory.resolve(context)
                    trySend(FileWatchEvent(kind, resolved))
                }

                if (!key.reset()) {
                    running = false
                }
            }

            awaitClose {
                watchService.close()
            }
        }
            .debounce(300)
            .flowOn(Dispatchers.IO)
    }
}
