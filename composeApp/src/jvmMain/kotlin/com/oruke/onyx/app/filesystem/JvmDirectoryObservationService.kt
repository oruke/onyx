package com.oruke.onyx.app.filesystem

import com.oruke.onyx.vfs.api.DirectoryObservationService
import com.oruke.onyx.vfs.api.DirectoryWatchEvent
import com.oruke.onyx.vfs.local.FileWatcher
import com.oruke.onyx.vfs.local.LocalDirectoryAccess
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * JVM 目录观察适配：本地使用 NIO，其他位置及监听失败使用有预算的轮询。
 * @param pathService 统一路径解析服务，保留逻辑入口与实际挂载路径之间的映射。
 */
internal class JvmDirectoryObservationService(
    private val pathService: VfsPathService,
) : DirectoryObservationService {
    /**
     * 在后台观察位置，注册后才触发初始校验，取消时关闭底层 WatchService。
     * @param location 规范化目录位置。
     * @return 统一的目录变化和观察失败事件。
     */
    override fun observe(location: String): Flow<DirectoryWatchEvent> {
        return if (location.contains("://")) {
            flow {
                emit(DirectoryWatchEvent.Ready)
                while (currentCoroutineContext().isActive) {
                    delay(POLL_INTERVAL_MILLIS)
                    emit(DirectoryWatchEvent.Invalidated)
                }
            }
        } else {
            observeLocal(location)
        }
    }

    /**
     * 监听本地实际目录，事件回映为用户浏览的逻辑路径。
     * @param location 逻辑目录位置。
     * @return 本地变化、失效与失败事件流。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun observeLocal(location: String): Flow<DirectoryWatchEvent> = channelFlow {
        while (isActive) {
            try {
                val directory = Path.of(location)
                val watched = LocalDirectoryAccess.resolveForListing(directory).getOrThrow()
                // 系统挂载盘可能不发送事件；活动目录保留低频校验，不能把本地路径等同于可靠观察。
                val verification = launch {
                    while (isActive) { delay(POLL_INTERVAL_MILLIS); send(DirectoryWatchEvent.Invalidated) }
                }
                try {
                    FileWatcher().watch(watched) { trySend(DirectoryWatchEvent.Ready) }.collect { event ->
                        if (event.kind == FileWatcher.EventKind.INVALIDATED) {
                            send(DirectoryWatchEvent.Invalidated)
                        } else {
                            val logical = directory.resolve(watched.relativize(event.path))
                            send(DirectoryWatchEvent.Changed(pathService.normalizeLocation(logical.toString())))
                        }
                    }
                } finally {
                    verification.cancel()
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                send(DirectoryWatchEvent.Unavailable(failure))
            }
            delay(POLL_INTERVAL_MILLIS)
            send(DirectoryWatchEvent.Invalidated)
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        /** 无可靠通知或观察失效时的有界重试间隔。 */
        const val POLL_INTERVAL_MILLIS = 30_000L
    }
}
