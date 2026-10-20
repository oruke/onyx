package com.oruke.onyx.app.filesystem

import com.oruke.onyx.vfs.api.DirectoryObservationService
import com.oruke.onyx.vfs.api.DirectoryWatchEvent
import com.oruke.onyx.vfs.api.DirectoryVerificationResult
import com.oruke.onyx.vfs.local.FileWatcher
import com.oruke.onyx.vfs.local.LocalDirectoryAccess
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
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
 * @param pollingPolicy 无通知校验和失败重试使用的统一频率与预算。
 */
internal class JvmDirectoryObservationService(
    /** 统一路径解析与逻辑入口映射服务。 */
    private val pathService: VfsPathService,
    /** 当前应用使用的后台校验频率与读取预算。 */
    private val pollingPolicy: DirectoryPollingPolicy = DirectoryPollingPolicy(),
) : DirectoryObservationService {
    /** 每个活动目录共享一个校验计划，最后一个观察释放时移除。 */
    private val polling = ConcurrentHashMap<String, DirectoryPollingSchedule>()

    /**
     * 在后台观察位置，注册后才触发初始校验，取消时关闭底层 WatchService。
     * @param location 规范化目录位置。
     * @return 统一的目录变化和观察失败事件。
     */
    override fun observe(location: String): Flow<DirectoryWatchEvent> {
        return flow {
            val schedule = DirectoryPollingSchedule(pollingPolicy)
            polling[location] = schedule
            try {
                if (location.contains("://")) {
                    emit(DirectoryWatchEvent.Ready)
                    while (currentCoroutineContext().isActive) {
                        delay(schedule.delayMillis)
                        emit(DirectoryWatchEvent.Verify)
                    }
                } else {
                    observeLocal(location, schedule).collect { emit(it) }
                }
            } finally {
                polling.remove(location, schedule)
            }
        }.flowOn(Dispatchers.IO)
    }

    /**
     * 用实际校验结果调节下一次检查，持续写入时保持快速响应，慢目录避免密集读取。
     * @param location 已完成读取的目录位置。
     * @param result 内容变化、读取结果与耗时。
     * @return 无返回值，反馈只调整活动观察的后台校验计划。
     */
    override fun onVerified(location: String, result: DirectoryVerificationResult) {
        polling[location]?.verified(result)
    }

    /**
     * 监听本地实际目录，事件回映为用户浏览的逻辑路径。
     * @param location 逻辑目录位置。
     * @param schedule 当前活动目录的后台校验计划。
     * @return 本地变化、失效与失败事件流。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun observeLocal(
        location: String,
        schedule: DirectoryPollingSchedule,
    ): Flow<DirectoryWatchEvent> = channelFlow {
        while (isActive) {
            try {
                val directory = Path.of(location)
                val watched = LocalDirectoryAccess.resolveForListing(directory).getOrThrow()
                // 系统挂载盘可能不发送事件；活动目录保留低频校验，不能把本地路径等同于可靠观察。
                val verification = launch {
                    while (isActive) { delay(schedule.delayMillis); send(DirectoryWatchEvent.Verify) }
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
            delay(schedule.delayMillis)
            send(DirectoryWatchEvent.Verify)
        }
    }.flowOn(Dispatchers.IO)
}
