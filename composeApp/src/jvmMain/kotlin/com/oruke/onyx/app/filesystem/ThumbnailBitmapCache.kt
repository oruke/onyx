package com.oruke.onyx.app.filesystem

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 按条目数与估算像素字节数双重封顶的线程安全位图 LRU 缓存。
 *
 * 用于避免高分辨率大图（例如 4096px 查看器位图）仅按条数缓存导致堆与原生内存失控膨胀。
 *
 * @param maxEntries 允许缓存的最大条目数。
 * @param maxBytes 允许缓存的最大估算字节数（按 RGBA 4 字节/像素估算）。
 */
internal class ByteBoundedBitmapLruCache(
    private val maxEntries: Int,
    private val maxBytes: Long,
) {
    /** 内部缓存条目，记录位图与其估算像素字节大小。 */
    private data class CacheEntry(
        val bitmap: ImageBitmap,
        val estimatedBytes: Long,
    )

    /** 按访问顺序维护的底层 LRU 映射。 */
    private val entries = LinkedHashMap<String, CacheEntry>(INITIAL_CAPACITY, LOAD_FACTOR, true)

    /** 当前已占用估算总字节数。 */
    private var totalBytes: Long = 0L

    /** 当前已缓存条目数量。 */
    @Synchronized
    fun size(): Int = entries.size

    /** 当前已缓存位图的估算总字节数。 */
    @Synchronized
    fun currentBytes(): Long = totalBytes

    /**
     * 按键查询缓存中的位图并刷新 LRU 访问顺序。
     *
     * @param key 缓存键。
     * @return 命中的位图；未命中时返回 `null`。
     */
    @Synchronized
    fun get(key: String): ImageBitmap? = entries[key]?.bitmap

    /**
     * 写入位图并按条目数与总字节预算淘汰最久未使用条目。
     *
     * 若单张位图本身已超过预算上限则不进入缓存，并清除同名旧条目。
     *
     * @param key 缓存键。
     * @param bitmap 待缓存位图。
     */
    @Synchronized
    fun put(key: String, bitmap: ImageBitmap) {
        val estimatedBytes = estimateBitmapBytes(bitmap)
        entries.remove(key)?.let { previous ->
            totalBytes = (totalBytes - previous.estimatedBytes).coerceAtLeast(0L)
        }
        if (maxEntries <= 0 || maxBytes <= 0L || estimatedBytes > maxBytes) {
            return
        }
        entries[key] = CacheEntry(bitmap = bitmap, estimatedBytes = estimatedBytes)
        totalBytes += estimatedBytes
        evictIfNeeded()
    }

    /** 清空全部缓存并重置占用统计。 */
    @Synchronized
    fun clear() {
        entries.clear()
        totalBytes = 0L
    }

    /** 淘汰最久未使用的条目，直到同时满足条数与总字节上限。 */
    private fun evictIfNeeded() {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext() && (entries.size > maxEntries || totalBytes > maxBytes)) {
            val eldest = iterator.next()
            iterator.remove()
            totalBytes = (totalBytes - eldest.value.estimatedBytes).coerceAtLeast(0L)
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 64
        const val LOAD_FACTOR = 0.75f
        const val BYTES_PER_PIXEL = 4L

        /** 按每像素 4 字节（N32 Premul）估算位图内存占用。 */
        fun estimateBitmapBytes(bitmap: ImageBitmap): Long {
            val pixels = bitmap.width.toLong().coerceAtLeast(1L) * bitmap.height.toLong().coerceAtLeast(1L)
            return pixels * BYTES_PER_PIXEL
        }
    }
}
