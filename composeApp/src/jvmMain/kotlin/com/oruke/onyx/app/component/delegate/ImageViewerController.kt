package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.core.model.ImageFitMode
import com.oruke.onyx.core.model.ImageViewerState
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 图片查看器允许的最小缩放倍率。 */
private const val MIN_VIEWER_ZOOM = 0.1f

/** 图片查看器允许的最大缩放倍率。 */
private const val MAX_VIEWER_ZOOM = 10f

/**
 * 图片查看器控制器 — 管理查看器的打开/关闭、导航、缩放、旋转。
 */
class ImageViewerController {

    private val _state = MutableStateFlow(ImageViewerState())
    val state: StateFlow<ImageViewerState> = _state.asStateFlow()

    /**
     * 打开图片查看器并定位到指定图片。
     *
     * @param file 当前图片。
     * @param allImages 可导航的同目录图片列表。
     */
    fun open(file: VFile, allImages: List<VFile>) {
        val index = allImages.indexOfFirst { it.id == file.id }.coerceAtLeast(0)
        _state.value = ImageViewerState(
            visible = true,
            currentFile = file,
            allImages = allImages,
            currentIndex = index,
            zoomFactor = 1f,
            fitMode = ImageFitMode.FIT_WINDOW,
            rotation = 0,
        )
    }

    /**
     * 关闭图片查看器并重置状态。
     */
    fun close() {
        _state.value = ImageViewerState()
    }

    /**
     * 切换到下一张图片，并重置缩放和旋转。
     */
    fun next() {
        val current = _state.value
        if (!current.visible || current.allImages.isEmpty()) return
        val nextIndex = (current.currentIndex + 1) % current.allImages.size
        val nextFile = current.allImages[nextIndex]
        _state.value = current.copy(
            currentIndex = nextIndex,
            currentFile = nextFile,
            zoomFactor = 1f,
            fitMode = ImageFitMode.FIT_WINDOW,
            rotation = 0,
        )
    }

    /**
     * 切换到上一张图片，并重置缩放和旋转。
     */
    fun previous() {
        val current = _state.value
        if (!current.visible || current.allImages.isEmpty()) return
        val prevIndex = if (current.currentIndex <= 0) current.allImages.lastIndex else current.currentIndex - 1
        val prevFile = current.allImages[prevIndex]
        _state.value = current.copy(
            currentIndex = prevIndex,
            currentFile = prevFile,
            zoomFactor = 1f,
            fitMode = ImageFitMode.FIT_WINDOW,
            rotation = 0,
        )
    }

    /**
     * 设置查看器缩放比例。
     *
     * @param factor 目标缩放比例，超出范围时自动收敛。
     */
    fun setZoom(factor: Float) {
        val current = _state.value
        if (!current.visible) return
        _state.value = current.copy(
            zoomFactor = factor.coerceIn(MIN_VIEWER_ZOOM, MAX_VIEWER_ZOOM),
        )
    }

    /**
     * 设置图片适配模式并重置手动缩放。
     *
     * @param mode 目标适配模式。
     */
    fun setFitMode(mode: ImageFitMode) {
        val current = _state.value
        if (!current.visible) return
        _state.value = current.copy(
            fitMode = mode,
            zoomFactor = 1f,
        )
    }

    /**
     * 按 90 度步进旋转当前图片。
     *
     * @param clockwise 是否顺时针旋转。
     */
    fun rotate(clockwise: Boolean) {
        val current = _state.value
        if (!current.visible) return
        val delta = if (clockwise) 90 else -90
        _state.value = current.copy(
            rotation = (current.rotation + delta + 360) % 360,
        )
    }
}
