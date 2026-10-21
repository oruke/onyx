package com.oruke.onyx.app.platform.storage

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/**
 * 将系统设备工具的持续事件输出转换为校验信号，不解析本地化文案。
 *
 * @param arguments 操作系统提供的监听程序及独立参数。
 * @return 原生设备变化的触发流，取消订阅会关闭本次进程和输出管道。
 */
internal fun storageCommandChanges(arguments: List<String>): Flow<Unit> = callbackFlow {
    val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
    process.outputStream.close()
    val closed = AtomicBoolean()
    val worker =
        thread(name = "onyx-native-storage-events", isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { trySend(Unit) }
                }
            }
                .onFailure { if (!closed.get()) close(it) }
        }
    awaitClose {
        closed.set(true)
        process.destroyForcibly()
        process.inputStream.close()
        worker.join(STORAGE_MONITOR_CLOSE_WAIT_MILLIS)
    }
}
    .flowOn(Dispatchers.IO)

/** 系统监听工具退出时等待输出读取线程结束的最长时间。 */
private const val STORAGE_MONITOR_CLOSE_WAIT_MILLIS = 1_000L
