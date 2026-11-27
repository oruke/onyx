package com.oruke.onyx.app.filesystem

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 原生 Skia 位图引用计数回收注册表。
 *
 * Compose Desktop 的 `ImageBitmap` 底层持有 C++ 原生 `org.jetbrains.skia.Bitmap` 像素缓冲区；
 * 若只依赖 JVM GC `Cleaner` 回收，对象晋升老年代后会长期滞留数百 MB 堆外内存。
 * 本注册表将缓存池与 UI 组件的生命周期配对（`register` / `retain` / `release`），
 * 引用归零并在短暂安全帧宽限期结束后显式 `close()` 释放原生像素，同时完全避免渲染中提前关闭崩溃。
 */
internal object ManagedBitmapRegistry {
    /** 单个受管位图的引用计数与待执行的延迟关闭句柄。 */
    private class ManagedEntry(
        /** 当前存活引用数（缓存池 1 份 + 每个正在展示它的 UI 组件 1 份）。 */
        var refCount: Int,
        /** 引用归零后排队的延迟关闭任务；若期间被重新 `retain` 则取消。 */
        var pendingClose: ScheduledFuture<*>? = null,
    )

    /** 默认延迟关闭宽限时间（毫秒），覆盖异步返回至 `DisposableEffect` 挂载及末帧渲染提交窗口。 */
    private const val DEFAULT_CLOSE_GRACE_MS = 500L

    /** 保护受管位图表的一致性锁。 */
    private val lock = Any()

    /** 按对象身份索引的受管位图表，避免不同实例因 equals 误碰撞。 */
    private val entries = IdentityHashMap<ImageBitmap, ManagedEntry>()

    /** 执行延迟原生释放的单线程守护调度器。 */
    private val closer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "onyx-skia-bitmap-closer").apply { isDaemon = true }
    }

    /** 允许测试调整延迟宽限时间；生产默认 500 ms。 */
    @Volatile
    internal var closeGraceMillis: Long = DEFAULT_CLOSE_GRACE_MS

    /**
     * 将新解码并准备入缓存的位图纳入托管，初始引用计数为 1（归属缓存池）。
     *
     * @param bitmap 待托管的 Compose 位图。
     */
    fun register(bitmap: ImageBitmap) {
        synchronized(lock) {
            val existing = entries[bitmap]
            if (existing == null) {
                entries[bitmap] = ManagedEntry(refCount = 1)
            } else {
                existing.pendingClose?.cancel(false)
                existing.pendingClose = null
                existing.refCount += 1
            }
        }
    }

    /**
     * 为正在展示该位图的 UI 组件增加一次引用计数；非托管位图自动忽略。
     *
     * @param bitmap UI 正在渲染的位图。
     * @return 仍处于存活托管状态时返回 `true`。
     */
    fun retain(bitmap: ImageBitmap): Boolean {
        synchronized(lock) {
            val entry = entries[bitmap] ?: return false
            entry.pendingClose?.cancel(false)
            entry.pendingClose = null
            entry.refCount += 1
            return true
        }
    }

    /**
     * 释放缓存池或 UI 组件持有的一次引用；引用归零后按宽限策略显式关闭原生 Skia 位图。
     *
     * @param bitmap 待释放引用的位图。
     * @param immediate 为 `true` 时在引用归零后立即同步关闭原生位图（用于后台清场或单元测试）。
     */
    fun release(bitmap: ImageBitmap, immediate: Boolean = false) {
        var shouldCloseNow = false
        synchronized(lock) {
            val entry = entries[bitmap] ?: return
            entry.refCount = (entry.refCount - 1).coerceAtLeast(0)
            if (entry.refCount == 0) {
                entry.pendingClose?.cancel(false)
                entry.pendingClose = null
                val delayMs = if (immediate) 0L else closeGraceMillis
                if (delayMs <= 0L) {
                    entries.remove(bitmap)
                    shouldCloseNow = true
                } else {
                    entry.pendingClose = closer.schedule(
                        { finalizeIfStillUnreferenced(bitmap) },
                        delayMs,
                        TimeUnit.MILLISECONDS,
                    )
                }
            }
        }
        if (shouldCloseNow) {
            closeNativeBitmapQuietly(bitmap)
        }
    }

    /**
     * 立即关闭所有已归零但尚在宽限期排队等待的原生位图，供窗口卸载入托盘时即时释放堆外内存。
     */
    fun flushPendingCloses() {
        val toClose = mutableListOf<ImageBitmap>()
        synchronized(lock) {
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                val (bitmap, entry) = iterator.next()
                if (entry.refCount <= 0) {
                    entry.pendingClose?.cancel(false)
                    entry.pendingClose = null
                    iterator.remove()
                    toClose.add(bitmap)
                }
            }
        }
        toClose.forEach(::closeNativeBitmapQuietly)
    }

    /**
     * 返回当前仍在托管中的位图数量，仅供诊断与单元测试核对。
     *
     * @return 受管位图条目数。
     */
    internal fun managedCount(): Int = synchronized(lock) { entries.size }

    /**
     * 宽限期到期后再次确认引用计数仍为 0，再执行原生关闭，避免并发重新 `retain` 引发竞争。
     *
     * @param bitmap 待终结的位图。
     */
    private fun finalizeIfStillUnreferenced(bitmap: ImageBitmap) {
        val shouldClose = synchronized(lock) {
            val entry = entries[bitmap]
            if (entry != null && entry.refCount <= 0) {
                entries.remove(bitmap)
                true
            } else {
                false
            }
        }
        if (shouldClose) {
            closeNativeBitmapQuietly(bitmap)
        }
    }

    /**
     * 安全关闭 Compose `ImageBitmap` 背后的原生 Skia `Bitmap`，释放 C++ 像素内存。
     *
     * @param bitmap 待释放底层像素的位图。
     */
    private fun closeNativeBitmapQuietly(bitmap: ImageBitmap) {
        runCatching {
            val skiaBitmap = bitmap.asSkiaBitmap()
            if (!skiaBitmap.isClosed) {
                skiaBitmap.close()
            }
        }
    }
}
