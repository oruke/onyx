package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.panpf.zoomimage.ZoomImage
import com.github.panpf.zoomimage.compose.ZoomState
import com.github.panpf.zoomimage.compose.rememberZoomState
import com.oruke.onyx.app.filesystem.ImageLoadOutcome
import com.oruke.onyx.core.model.ImageFitMode
import com.oruke.onyx.core.model.ImageViewerState
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.decodeSfmDisplayText
import com.oruke.onyx.ui.theme.formatFileSize
import kotlinx.coroutines.launch
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_open_externally
import onyx.composeapp.generated.resources.action_retry_task
import onyx.composeapp.generated.resources.label_image_viewer_error_decode_failed
import onyx.composeapp.generated.resources.label_image_viewer_error_not_found
import onyx.composeapp.generated.resources.label_image_viewer_error_too_large
import onyx.composeapp.generated.resources.label_image_viewer_error_unsupported
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlin.math.abs
import kotlin.math.min

/** 查看器读取原图时允许请求的最大边长。 */
private const val VIEWER_MAX_IMAGE_DIMENSION = 4096

/** 缩放倍率转换为百分比的基数。 */
private const val ZOOM_PERCENT_BASE = 100

/** 单次快捷缩放步进倍率。 */
private const val ZOOM_STEP_FACTOR = 1.25f

/** 单次图片旋转的角度。 */
private const val ROTATION_STEP_DEGREES = 90

/** 一周完整旋转的角度。 */
private const val FULL_ROTATION_DEGREES = 360

/** 判定缩放模式按钮激活态的浮点容差。 */
private const val SCALE_MODE_EPSILON = 0.04f

/**
 * 计算真实 1:1（原图 100% 物理像素）对应的 ZoomImage 目标缩放倍率。
 *
 * 兼容 ZoomImage 两种坐标系：
 * - 当 `minScale` 等于容器拟合比例 `fitScale` 时，原图 1:1 倍率为 `nativeWidth / bitmapWidth`；
 * - 当 `minScale ≈ 1f`（ZoomImage 将适配窗口视作 1.0x 基准）时，按 `minScale * (nativeWidth / fittedDisplayWidth)` 计算真实 1:1 倍率，
 *   确保「1:1」按钮与快捷键 `1` 真正切换到 100% 原图像素而非停留在 Fit 模式。
 */
internal fun resolveActualSizeTargetScale(
    nativeSize: IntSize?,
    bitmapSize: IntSize?,
    containerSize: IntSize,
    minScale: Float,
    maxScale: Float,
    rotationDegrees: Int = 0,
): Float {
    val safeMin = minScale.coerceAtLeast(0.01f)
    val safeMax = maxScale.coerceAtLeast(safeMin)
    val source = nativeSize?.takeIf { it.width > 0 && it.height > 0 }
        ?: bitmapSize?.takeIf { it.width > 0 && it.height > 0 }
        ?: return 1f.coerceIn(safeMin, safeMax)
    val rotated = (abs(rotationDegrees) % 180) == 90
    val orientedW = (if (rotated) source.height else source.width).toFloat()
    val orientedH = (if (rotated) source.width else source.height).toFloat()
    val downsampleRatio = if (nativeSize != null && bitmapSize != null && bitmapSize.width > 0) {
        (nativeSize.width.toFloat() / bitmapSize.width.toFloat()).coerceAtLeast(1f)
    } else {
        1f
    }

    if (containerSize.width <= 0 || containerSize.height <= 0) {
        return downsampleRatio.coerceIn(safeMin, safeMax)
    }
    val fitScale = min(containerSize.width / orientedW, containerSize.height / orientedH)
        .coerceAtLeast(0.01f)

    // 如果 minScale 非常接近 1f 且真实 fitScale 明显偏离 1f，说明 ZoomImage 将 Fit 状态视作 scale=1f。
    val target = if (abs(safeMin - 1f) < 0.05f && abs(fitScale - 1f) > 0.05f) {
        (safeMin / fitScale)
    } else {
        downsampleRatio
    }
    return target.coerceIn(safeMin, safeMax)
}

/**
 * 基于当前缩放倍率、原图尺寸与容器尺寸计算真实像素显示百分比。
 */
internal fun resolveDisplayZoomPercent(
    currentScale: Float,
    nativeSize: IntSize?,
    bitmapSize: IntSize?,
    containerSize: IntSize,
    minScale: Float,
    rotationDegrees: Int = 0,
): Int {
    val actualScale = resolveActualSizeTargetScale(
        nativeSize = nativeSize,
        bitmapSize = bitmapSize,
        containerSize = containerSize,
        minScale = minScale,
        maxScale = Float.MAX_VALUE,
        rotationDegrees = rotationDegrees,
    ).coerceAtLeast(0.01f)
    return ((currentScale / actualScale) * ZOOM_PERCENT_BASE).toInt().coerceAtLeast(1)
}

/**
 * 防漂移缩放步进计算：优先基于上一次尚未播完动画的目标倍率累乘，避免连按 `=`/`-` 读取陈旧动画帧。
 */
internal fun resolveNextStepZoomScale(
    currentScale: Float,
    pendingTargetScale: Float?,
    factor: Float,
    minScale: Float,
    maxScale: Float,
): Float {
    val base = (pendingTargetScale ?: currentScale).coerceAtLeast(0.01f)
    return (base * factor).coerceIn(minScale.coerceAtLeast(0.01f), maxScale.coerceAtLeast(minScale))
}

/**
 * 独立图片查看器主界面。
 *
 * - 支持 ZoomImage 滚轮缩放、拖拽平移与双击缩放（移除了覆盖左右 40% 画布的不可见翻页遮罩，改为仅悬停显示的居中边缘小箭头）；
 * - 顶部工具栏提供翻页、适应窗口（带激活态）、真实 1:1（带激活态）、防漂移缩放、旋转与显式关闭按钮；
 * - 解码失败或图片过大时展示结构化错误卡片，提供「重试」与「用系统默认应用打开」按钮；
 * - 快捷键：`Esc` 关闭、`←/→` 翻页、`=`/`-` 缩放、`0` 适应窗口、`1` 真实 1:1、`R`/`Shift+R` 顺/逆时针旋转、`F11` 切换全屏。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ImageViewerContent(
    state: ImageViewerState,
    onClose: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSetZoom: (Float) -> Unit,
    onSetFitMode: (ImageFitMode) -> Unit,
    onRotate: (Boolean) -> Unit,
    loadThumbnail: suspend (String, Int) -> ImageBitmap?,
    readImageSize: suspend (VFile) -> IntSize?,
    loadViewerImage: (suspend (VFile, Int) -> ImageLoadOutcome)? = null,
    onOpenExternally: (VFile) -> Unit = {},
    onToggleFullscreen: () -> Unit = {},
) {
    val currentFile = state.currentFile ?: return

    // ── 结构化图片加载（支持重试与明确失败原因） ────────────────────
    var retryToken by remember(currentFile.location) { mutableIntStateOf(0) }
    var isLoading by remember(currentFile.location, retryToken) { mutableStateOf(true) }
    var loadOutcome by remember(currentFile.location, retryToken) { mutableStateOf<ImageLoadOutcome?>(null) }
    LaunchedEffect(currentFile.location, currentFile.modifiedAtEpochMillis, currentFile.sizeBytes, retryToken) {
        isLoading = true
        loadOutcome = if (loadViewerImage != null) {
            loadViewerImage(currentFile, VIEWER_MAX_IMAGE_DIMENSION)
        } else {
            val loaded = loadThumbnail(currentFile.location, VIEWER_MAX_IMAGE_DIMENSION)
            if (loaded != null) ImageLoadOutcome.Loaded(loaded) else ImageLoadOutcome.DecodeFailed()
        }
        isLoading = false
    }
    val bitmap = (loadOutcome as? ImageLoadOutcome.Loaded)?.bitmap

    // 原图分辨率（含 ImageIO + Skia 现代格式回退）
    var nativeResolution by remember(currentFile.location, retryToken) { mutableStateOf<IntSize?>(null) }
    LaunchedEffect(currentFile.location, currentFile.modifiedAtEpochMillis, currentFile.sizeBytes, retryToken) {
        nativeResolution = readImageSize(currentFile)
    }

    // ── ZoomImage 状态与防漂移目标倍率 ─────────────────────────────
    val zoomState = rememberZoomState()
    val coroutineScope = rememberCoroutineScope()
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var pendingTargetScale by remember(currentFile.location) { mutableStateOf<Float?>(null) }
    var pointerHovered by remember { mutableStateOf(false) }

    LaunchedEffect(currentFile.location) {
        pendingTargetScale = null
        zoomState.zoomable.reset()
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val currentScale = zoomState.zoomable.transform.scaleX
    val rotationDegrees = zoomState.zoomable.transform.rotation.toInt()
    val bitmapSize = remember(bitmap) { bitmap?.let { IntSize(it.width, it.height) } }
    val effectiveContainerSize = zoomState.zoomable.containerSize
        .takeIf { it.width > 0 && it.height > 0 }
        ?: viewportSize
    val actualSizeScale = resolveActualSizeTargetScale(
        nativeSize = nativeResolution,
        bitmapSize = bitmapSize,
        containerSize = effectiveContainerSize,
        minScale = zoomState.zoomable.minScale,
        maxScale = zoomState.zoomable.maxScale,
        rotationDegrees = rotationDegrees,
    )
    val displayZoomPercent = resolveDisplayZoomPercent(
        currentScale = currentScale,
        nativeSize = nativeResolution,
        bitmapSize = bitmapSize,
        containerSize = effectiveContainerSize,
        minScale = zoomState.zoomable.minScale,
        rotationDegrees = rotationDegrees,
    )

    fun applyZoomStep(factor: Float) {
        val target = resolveNextStepZoomScale(
            currentScale = zoomState.zoomable.transform.scaleX,
            pendingTargetScale = pendingTargetScale,
            factor = factor,
            minScale = zoomState.zoomable.minScale,
            maxScale = zoomState.zoomable.maxScale,
        )
        pendingTargetScale = target
        coroutineScope.launch {
            zoomState.zoomable.scale(targetScale = target, animated = true)
            if (pendingTargetScale == target) pendingTargetScale = null
            onSetZoom(target)
        }
    }

    fun applyFitWindow() {
        val target = zoomState.zoomable.minScale
        pendingTargetScale = target
        coroutineScope.launch {
            zoomState.zoomable.scale(targetScale = target, animated = true)
            if (pendingTargetScale == target) pendingTargetScale = null
            onSetFitMode(ImageFitMode.FIT_WINDOW)
        }
    }

    fun applyActualSize() {
        val target = actualSizeScale
        pendingTargetScale = target
        coroutineScope.launch {
            zoomState.zoomable.scale(targetScale = target, animated = true)
            if (pendingTargetScale == target) pendingTargetScale = null
            onSetFitMode(ImageFitMode.ACTUAL_SIZE)
        }
    }

    fun applyRotate(clockwise: Boolean) {
        pendingTargetScale = null
        coroutineScope.launch {
            val delta = if (clockwise) ROTATION_STEP_DEGREES else -ROTATION_STEP_DEGREES
            val rotation = (
                zoomState.zoomable.transform.rotation.toInt() + delta + FULL_ROTATION_DEGREES
                ) % FULL_ROTATION_DEGREES
            zoomState.zoomable.rotate(rotation)
            onRotate(clockwise)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPointerEvent(PointerEventType.Enter) { pointerHovered = true }
            .onPointerEvent(PointerEventType.Move) { pointerHovered = true }
            .onPointerEvent(PointerEventType.Exit) { pointerHovered = false }
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> { onClose(); true }
                    Key.DirectionRight -> { onNext(); true }
                    Key.DirectionLeft -> { onPrevious(); true }
                    Key.Equals, Key.NumPadAdd -> { applyZoomStep(ZOOM_STEP_FACTOR); true }
                    Key.Minus, Key.NumPadSubtract -> { applyZoomStep(1f / ZOOM_STEP_FACTOR); true }
                    Key.Zero, Key.NumPad0 -> { applyFitWindow(); true }
                    Key.One, Key.NumPad1 -> { applyActualSize(); true }
                    Key.R -> { applyRotate(clockwise = !event.isShiftPressed); true }
                    Key.F11 -> { onToggleFullscreen(); true }
                    else -> false
                }
            },
    ) {
        ImageViewerViewport(
            file = currentFile,
            outcome = loadOutcome,
            isLoading = isLoading,
            zoomState = zoomState,
            onViewportSizeChanged = { viewportSize = it },
            onRetry = { retryToken += 1 },
            onOpenExternally = { onOpenExternally(currentFile) },
        )
        val fitActive = state.fitMode == ImageFitMode.FIT_WINDOW &&
            abs(currentScale - zoomState.zoomable.minScale) <= SCALE_MODE_EPSILON
        val actualActive = state.fitMode == ImageFitMode.ACTUAL_SIZE &&
            abs(currentScale - actualSizeScale) <= SCALE_MODE_EPSILON
        ImageViewerToolbar(
            displayZoomPercent = displayZoomPercent,
            fitActive = fitActive,
            actualActive = actualActive,
            actions = ImageViewerToolbarActions(
                onPrevious = onPrevious,
                onNext = onNext,
                onFitWindow = ::applyFitWindow,
                onActualSize = ::applyActualSize,
                onZoomOut = { applyZoomStep(1f / ZOOM_STEP_FACTOR) },
                onZoomIn = { applyZoomStep(ZOOM_STEP_FACTOR) },
                onRotateCcw = { applyRotate(clockwise = false) },
                onRotateCw = { applyRotate(clockwise = true) },
                onClose = onClose,
            ),
        )
        ImageViewerInfoBar(state, currentFile, nativeResolution ?: bitmapSize)
        if (state.allImages.size > 1 && pointerHovered) {
            ImageViewerEdgeArrows(onPrevious = onPrevious, onNext = onNext)
        }
    }
}

/**
 * 渲染查看器图片画布、加载状态与结构化错误恢复卡片。
 */
@Composable
private fun ImageViewerViewport(
    file: VFile,
    outcome: ImageLoadOutcome?,
    isLoading: Boolean,
    zoomState: ZoomState,
    onViewportSizeChanged: (IntSize) -> Unit,
    onRetry: () -> Unit,
    onOpenExternally: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 48.dp)
            .onSizeChanged(onViewportSizeChanged),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isLoading -> Text("…", color = Color.White.copy(alpha = 0.6f), fontSize = 24.sp)
            outcome is ImageLoadOutcome.Loaded -> ZoomImage(
                painter = remember(outcome.bitmap) { BitmapPainter(outcome.bitmap) },
                contentDescription = file.name,
                modifier = Modifier.fillMaxSize(),
                zoomState = zoomState,
            )

            else -> ImageViewerErrorCard(
                file = file,
                outcome = outcome,
                onRetry = onRetry,
                onOpenExternally = onOpenExternally,
            )
        }
    }
}

/**
 * 渲染结构化图片加载失败卡片，给出明确失败原因及「重试」「外部打开」恢复按钮。
 */
@Composable
private fun ImageViewerErrorCard(
    file: VFile,
    outcome: ImageLoadOutcome?,
    onRetry: () -> Unit,
    onOpenExternally: () -> Unit,
) {
    val reasonText = when (outcome) {
        is ImageLoadOutcome.TooLarge -> stringResource(
            Res.string.label_image_viewer_error_too_large,
            formatFileSize(outcome.limitBytes),
        )

        ImageLoadOutcome.NotFound -> stringResource(Res.string.label_image_viewer_error_not_found)
        ImageLoadOutcome.UnsupportedLocation -> stringResource(Res.string.label_image_viewer_error_unsupported)
        is ImageLoadOutcome.DecodeFailed, null -> stringResource(Res.string.label_image_viewer_error_decode_failed)
        is ImageLoadOutcome.Loaded -> ""
    }
    Column(
        modifier = Modifier
            .testTag("image-viewer-error-card")
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(AllIconsKeys.FileTypes.Any_type, null, Modifier.size(48.dp))
        Text(
            text = file.name.decodeSfmDisplayText(),
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = reasonText,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewerToolbarButton(
                label = stringResource(Res.string.action_retry_task),
                onClick = onRetry,
                active = true,
                modifier = Modifier.testTag("image-viewer-retry-button"),
            )
            ViewerToolbarButton(
                label = stringResource(Res.string.action_open_externally),
                onClick = onOpenExternally,
                modifier = Modifier.testTag("image-viewer-open-external-button"),
            )
        }
    }
}
