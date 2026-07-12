package com.oruke.onyx.core.model

/**
 * 图片查看器的完整状态。
 *
 * 由 RootComponent 持有，控制全屏覆盖式图片查看器的显示与交互。
 */
data class ImageViewerState(
    /** 查看器是否可见 */
    val visible: Boolean = false,
    /** 当前显示的文件 */
    val currentFile: VFile? = null,
    /** 同目录下所有图片文件列表（用于前后翻页） */
    val allImages: List<VFile> = emptyList(),
    /** 当前图片在 allImages 中的索引 */
    val currentIndex: Int = 0,
    /** 缩放倍率 (1.0 = 100%) */
    val zoomFactor: Float = 1f,
    /** 适应模式 */
    val fitMode: ImageFitMode = ImageFitMode.FIT_WINDOW,
    /** 旋转角度，只允许 0/90/180/270 */
    val rotation: Int = 0,
)

/** 图片查看器的缩放适配模式。 */
enum class ImageFitMode {
    /** 适应窗口（保持宽高比，完整显示） */
    FIT_WINDOW,
    /** 原始尺寸 (1:1) */
    ACTUAL_SIZE,
    /** 适应宽度 */
    FILL_WIDTH,
}
