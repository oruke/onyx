package com.oruke.onyx.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.oruke.onyx.app.filesystem.ManagedBitmapRegistry

/**
 * 记住并加载异步位图；若提供同步内存缓存探测函数 [peekCached]，命中时首帧即直接返回位图而不闪烁占位图标。
 *
 * @param location 图片或压缩包位置。
 * @param maxDimension 请求的最大边长像素。
 * @param loader 异步加载回调。
 * @param versionKey 可选文件版本指纹（如 `modifiedAtEpochMillis to sizeBytes`），同名覆写后立即刷新缓存。
 * @param peekCached 可选同步内存缓存查询函数（严格只读内存，不得触发磁盘或网络 I/O）。
 * @return `(当前位图, 是否仍在加载)` 二元组。
 */
@Composable
internal fun rememberAsyncBitmap(
    location: String,
    maxDimension: Int,
    loader: suspend (String, Int) -> ImageBitmap?,
    versionKey: Any? = null,
    peekCached: ((String, Int) -> ImageBitmap?)? = null,
): Pair<ImageBitmap?, Boolean> {
    val initialCached = remember(location, maxDimension, versionKey) {
        peekCached?.invoke(location, maxDimension)
    }
    var bitmap by remember(location, maxDimension, versionKey) { mutableStateOf(initialCached) }
    var loading by remember(location, maxDimension, versionKey) { mutableStateOf(initialCached == null) }
    LaunchedEffect(location, maxDimension, versionKey, loader) {
        if (bitmap != null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        bitmap = loader(location, maxDimension)
        loading = false
    }
    DisposableEffect(bitmap) {
        val current = bitmap
        if (current != null) {
            ManagedBitmapRegistry.retain(current)
        }
        onDispose {
            if (current != null) {
                ManagedBitmapRegistry.release(current)
            }
        }
    }
    return bitmap to loading
}
