package com.oruke.onyx.vfs.archive

import com.oruke.onyx.vfs.api.VfsRandomAccessHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.sf.sevenzipjbinding.IInStream
import net.sf.sevenzipjbinding.SevenZipException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * 将挂起式 VFS 随机读取句柄适配为 7-Zip-JBinding 的同步 `IInStream`。
 *
 * 7-Zip 原生回调可能来自不同线程，因此游标操作必须串行化；实际网络 I/O 始终调度到 I/O 协程。
 *
 * @param source VFS 随机访问源。
 * @param fileLength 打开时确认的文件长度。
 */
internal class SevenZipVfsInStream(
    private val source: VfsRandomAccessHandle,
    private val fileLength: Long,
) : IInStream {
    /** 在同步原生回调与挂起 VFS API 之间传递结果。 */
    private val bridge = SuspendIoBridge()

    /** 当前 7-Zip 流游标。 */
    private var position = 0L

    /**
     * 从当前游标读取数据并推进游标。
     *
     * @param data 7-Zip 提供的接收缓冲区。
     * @return 实际读取字节数；到达末尾时返回 `0`。
     */
    @Synchronized
    override fun read(data: ByteArray): Int {
        if (data.isEmpty()) return 0
        return runCatching {
            val read = bridge.execute {
                source.readAt(
                    position = position,
                    buffer = data,
                    bufferOffset = 0,
                    byteCount = data.size,
                )
            }
            if (read <= 0) {
                0
            } else {
                position += read
                read
            }
        }.getOrElse { failure ->
            failure.throwIfCoroutineCancellation()
            throw SevenZipException("读取 VFS 随机访问源失败", failure)
        }
    }

    /**
     * 按 7-Zip 指定基准移动当前游标。
     *
     * @param offset 相对或绝对偏移。
     * @param seekOrigin `SEEK_SET`、`SEEK_CUR` 或 `SEEK_END`。
     * @return 移动后的绝对位置。
     */
    @Synchronized
    override fun seek(offset: Long, seekOrigin: Int): Long {
        return runCatching {
            val base = when (seekOrigin) {
                IInStream.SEEK_SET -> 0L
                IInStream.SEEK_CUR -> position
                IInStream.SEEK_END -> fileLength
                else -> throw IllegalArgumentException("Unknown seek origin: $seekOrigin")
            }
            val target = Math.addExact(base, offset)
            require(target >= 0L) {
                "Seek target must not be negative"
            }
            position = target
            target
        }.getOrElse { failure ->
            failure.throwIfCoroutineCancellation()
            throw SevenZipException("移动 VFS 随机访问游标失败", failure)
        }
    }

    /**
     * 关闭协程桥和底层 VFS 随机访问源。
     */
    @Synchronized
    override fun close() {
        bridge.close()
        source.close()
    }
}

/**
 * 在 7-Zip 错误转换前保留协程取消语义。
 */
private fun Throwable.throwIfCoroutineCancellation() {
    if (this is CancellationException) throw this
}

/**
 * 在同步第三方回调中等待挂起 I/O，并保持协程生命周期可控。
 */
private class SuspendIoBridge {
    /** 随句柄关闭而取消的父任务。 */
    private val job = SupervisorJob()

    /** 网络和文件 I/O 使用的结构化协程作用域。 */
    private val scope = CoroutineScope(job + Dispatchers.IO)

    /**
     * 调度挂起动作并同步返回结果。
     *
     * 同步等待只发生在 7-Zip 原生回调线程；真正的 I/O 不占用该线程执行。
     *
     * @param block 待执行挂起动作。
     * @return 动作结果。
     */
    fun <T> execute(block: suspend () -> T): T {
        check(scope.isActive) {
            "SevenZip VFS stream is closed"
        }
        val future = CompletableFuture<Result<T>>()
        val requestJob = scope.launch {
            future.complete(runCatching { block() })
        }
        requestJob.invokeOnCompletion { failure ->
            if (failure != null) {
                future.complete(Result.failure(failure))
            }
        }
        return try {
            future.get().getOrThrow()
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
    }

    /**
     * 取消尚未完成的挂起 I/O。
     */
    fun close() {
        scope.cancel()
    }
}
