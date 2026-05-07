package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.core.model.ImageFitMode
import com.oruke.onyx.core.model.ImageViewerState
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 图片查看器控制器 — 管理查看器的打开/关闭、导航、缩放、旋转。
 */
class ImageViewerController {

    private val _state = MutableStateFlow(ImageViewerState())
    val state: StateFlow<ImageViewerState> = _state.asStateFlow()

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

    fun close() {
        _state.value = ImageViewerState()
    }

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

    fun setZoom(factor: Float) {
        val current = _state.value
        if (!current.visible) return
        _state.value = current.copy(
            zoomFactor = factor.coerceIn(0.1f, 10f),
        )
    }

    fun setFitMode(mode: ImageFitMode) {
        val current = _state.value
        if (!current.visible) return
        _state.value = current.copy(
            fitMode = mode,
            zoomFactor = 1f,
        )
    }

    fun rotate(clockwise: Boolean) {
        val current = _state.value
        if (!current.visible) return
        val delta = if (clockwise) 90 else -90
        _state.value = current.copy(
            rotation = (current.rotation + delta + 360) % 360,
        )
    }
}
