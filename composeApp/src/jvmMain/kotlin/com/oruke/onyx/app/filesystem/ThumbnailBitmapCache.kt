@file:Suppress("MatchingDeclarationName")

package com.oruke.onyx.app.filesystem

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 按条目数与估算像素字节数双重封顶的线程安全位图 LRU 缓存。
 *
 * 用于避免高分辨率大图（例如 4096px 查看器位图）仅按条数缓存导致堆与原生内存失控膨胀，
 * 并通过可选的 [onEvict] 回调配合引用计数及时释放淘汰位图的原生像素内存。
 *
 * @param maxEntries 允许缓存的最大条目数。
 * @param maxBytes 允许缓存的最大估算字节数（按 RGBA 4 字节/像素估算）。
 * @param onEvict 条目因覆盖、超容淘汰或清空离开缓存时触发的释放回调。
 */
internal class ByteBoundedBitmapLruCache(
    /** 允许缓存的最大条目数。 */
    private val maxEntries: Int,
    /** 允许缓存的最大估算字节数。 */
    private val maxBytes: Long,
    /** 条目离开缓存池时的释放回调。 */
    private val onEvict: (ImageBitmap) -> Unit = {},
) {
    /** 内部缓存条目，记录位图与其估算像素字节大小。 */
    private data class CacheEntry(
        /** 缓存的位图对象。 */
        val bitmap: ImageBitmap,
        /** 按 RGBA 4 字节/像素估算的内存字节数。 */
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
     * 若单张位图本身已超过预算上限则不进入缓存，并清除同名旧条目且触发淘汰释放回调。
     *
     * @param key 缓存键。
     * @param bitmap 待缓存位图。
     * @return 实际进入缓存池时返回 `true`；因单张超预算被拒收时返回 `false`。
     */
    fun put(key: String, bitmap: ImageBitmap): Boolean {
        val evicted = mutableListOf<ImageBitmap>()
        val accepted: Boolean
        synchronized(this) {
            val estimatedBytes = estimateBitmapBytes(bitmap)
            entries.remove(key)?.let { previous ->
                totalBytes = (totalBytes - previous.estimatedBytes).coerceAtLeast(0L)
                if (previous.bitmap !== bitmap) {
                    evicted.add(previous.bitmap)
                }
            }
            if (maxEntries <= 0 || maxBytes <= 0L || estimatedBytes > maxBytes) {
                accepted = false
            } else {
                entries[key] = CacheEntry(bitmap = bitmap, estimatedBytes = estimatedBytes)
                totalBytes += estimatedBytes
                evictIfNeeded(evicted)
                accepted = true
            }
        }
        evicted.forEach(onEvict)
        if (!accepted) {
            onEvict(bitmap)
        }
        return accepted
    }

    /** 清空全部缓存并通知释放已缓存位图。 */
    fun clear() {
        val evicted: List<ImageBitmap>
        synchronized(this) {
            evicted = entries.values.map { entry -> entry.bitmap }
            entries.clear()
            totalBytes = 0L
        }
        evicted.forEach(onEvict)
    }

    /**
     * 淘汰最久未使用的条目，直到同时满足条数与总字节上限。
     *
     * @param evicted 收集本次被淘汰位图的输出列表，供锁外回调释放。
     */
    private fun evictIfNeeded(evicted: MutableList<ImageBitmap>) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext() && (entries.size > maxEntries || totalBytes > maxBytes)) {
            val eldest = iterator.next()
            iterator.remove()
            totalBytes = (totalBytes - eldest.value.estimatedBytes).coerceAtLeast(0L)
            evicted.add(eldest.value.bitmap)
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
