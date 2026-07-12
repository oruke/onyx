package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.panpf.zoomimage.ZoomImage
import com.github.panpf.zoomimage.compose.ZoomState
import com.github.panpf.zoomimage.compose.rememberZoomState
import com.oruke.onyx.core.model.ImageFitMode
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.ImageViewerState
import com.oruke.onyx.ui.theme.formatFileSize
import kotlinx.coroutines.launch
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_image_actual_size
import onyx.composeapp.generated.resources.action_image_fit_window
import onyx.composeapp.generated.resources.action_image_rotate_ccw
import onyx.composeapp.generated.resources.action_image_rotate_cw
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** 查看器读取原图时允许请求的最大边长。 */
private const val VIEWER_MAX_IMAGE_DIMENSION = 4096

/** 左右翻页热区各自占窗口宽度的比例。 */
private const val VIEWER_NAVIGATION_ZONE_FRACTION = 0.2f

/** 缩放倍率转换为百分比的基数。 */
private const val ZOOM_PERCENT_BASE = 100

/** 单次图片旋转的角度。 */
private const val ROTATION_STEP_DEGREES = 90

/** 一周完整旋转的角度。 */
private const val FULL_ROTATION_DEGREES = 360

/**
 * 图片查看器内容。
 *
 * 供独立窗口使用，使用 zoomimage 库处理缩放/平移/手势。
 *
 * 功能：
 * - 居中显示图片，支持鼠标滚轮缩放 + 拖拽平移（由 ZoomImage 处理）
 * - 顶部工具栏：← 上一张 / → 下一张 / 适应窗口 / 1:1 / 旋转
 * - 底部信息栏：文件名 | 分辨率 | 大小 | 索引/总数
 * - 快捷键：Esc 关闭、← → 翻页
 */
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
) {
    val currentFile = state.currentFile ?: return

    // ── 图片加载 ──────────────────────────────────────────────────
    val (bitmap, isLoading) = rememberAsyncBitmap(
        currentFile.location,
        VIEWER_MAX_IMAGE_DIMENSION,
        loadThumbnail,
    )

    // 原图分辨率（用于信息栏显示）
    var nativeResolution by remember(currentFile.location) { mutableStateOf<IntSize?>(null) }
    LaunchedEffect(currentFile.location) {
        nativeResolution = readImageSize(currentFile)
    }

    // ── ZoomImage 状态 ────────────────────────────────────────────
    val zoomState = rememberZoomState()
    val coroutineScope = rememberCoroutineScope()

    // 切换图片时重置缩放
    LaunchedEffect(currentFile.location) {
        zoomState.zoomable.reset()
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // 当前缩放比例（用于工具栏显示）
    val currentScale = zoomState.zoomable.transform.scaleX

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> { onClose(); true }
                    Key.DirectionRight -> { onNext(); true }
                    Key.DirectionLeft -> { onPrevious(); true }
                    Key.Equals, Key.NumPadAdd -> {
                        coroutineScope.launch {
                            val target = (currentScale * 1.25f).coerceAtMost(
                                zoomState.zoomable.maxScale
                            )
                            zoomState.zoomable.scale(
                                targetScale = target,
                                animated = true,
                            )
                            onSetZoom(target)
                        }
                        true
                    }
                    Key.Minus, Key.NumPadSubtract -> {
                        coroutineScope.launch {
                            val target = (currentScale / 1.25f).coerceAtLeast(
                                zoomState.zoomable.minScale
                            )
                            zoomState.zoomable.scale(
                                targetScale = target,
                                animated = true,
                            )
                            onSetZoom(target)
                        }
                        true
                    }
                    Key.Zero -> {
                        coroutineScope.launch {
                            zoomState.zoomable.scale(
                                targetScale = zoomState.zoomable.minScale,
                                animated = true,
                            )
                            onSetFitMode(ImageFitMode.FIT_WINDOW)
                        }
                        true
                    }
                    else -> false
                }
            },
    ) {
        ImageViewerViewport(currentFile, bitmap, isLoading, zoomState)
        ImageViewerToolbar(
            zoomState = zoomState,
            currentScale = currentScale,
            coroutineScope = coroutineScope,
            onPrevious = onPrevious,
            onNext = onNext,
            onSetZoom = onSetZoom,
            onSetFitMode = onSetFitMode,
            onRotate = onRotate,
        )
        ImageViewerInfoBar(state, currentFile, nativeResolution)
        ImageViewerNavigationZones(onPrevious, onNext)
    }
}

/**
 * 渲染查看器图片画布及加载、失败状态。
 *
 * @param file 当前图片文件。
 * @param bitmap 已加载位图。
 * @param isLoading 是否仍在加载。
 * @param zoomState ZoomImage 交互状态。
 */
@Composable
private fun ImageViewerViewport(
    file: VFile,
    bitmap: ImageBitmap?,
    isLoading: Boolean,
    zoomState: ZoomState,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isLoading -> Text("…", color = Color.White.copy(alpha = 0.5f), fontSize = 24.sp)
            bitmap != null -> ZoomImage(
                painter = remember(bitmap) { BitmapPainter(bitmap) },
                contentDescription = file.name,
                modifier = Modifier.fillMaxSize(),
                zoomState = zoomState,
            )
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(AllIconsKeys.FileTypes.Any_type, null, Modifier.size(64.dp))
                Spacer(Modifier.height(8.dp))
                Text(file.name, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            }
        }
    }
}

/**
 * 渲染查看器导航、缩放和旋转工具栏。
 *
 * @param zoomState ZoomImage 交互状态。
 * @param currentScale 当前缩放倍率。
 * @param coroutineScope 运行缩放动画的协程作用域。
 * @param onPrevious 上一张回调。
 * @param onNext 下一张回调。
 * @param onSetZoom 保存缩放状态回调。
 * @param onSetFitMode 保存适应模式回调。
 * @param onRotate 保存旋转状态回调。
 */
@Composable
private fun BoxScope.ImageViewerToolbar(
    zoomState: ZoomState,
    currentScale: Float,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSetZoom: (Float) -> Unit,
    onSetFitMode: (ImageFitMode) -> Unit,
    onRotate: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.TopCenter)
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewerToolbarButton("←", onPrevious)
        Spacer(Modifier.width(4.dp))
        ViewerToolbarButton("→", onNext)
        Spacer(Modifier.width(16.dp))
        ViewerToolbarButton(
            label = stringResource(Res.string.action_image_fit_window),
            onClick = {
            coroutineScope.launch {
                zoomState.zoomable.scale(zoomState.zoomable.minScale, animated = true)
                onSetFitMode(ImageFitMode.FIT_WINDOW)
            }
            },
        )
        Spacer(Modifier.width(4.dp))
        ViewerToolbarButton(
            label = stringResource(Res.string.action_image_actual_size),
            onClick = {
            coroutineScope.launch {
                zoomState.zoomable.scale(1f, animated = true)
                onSetFitMode(ImageFitMode.ACTUAL_SIZE)
            }
            },
        )
        Spacer(Modifier.width(16.dp))
        Text(
            "${(currentScale * ZOOM_PERCENT_BASE).toInt()}%",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 12.sp,
            modifier = Modifier.widthIn(min = 48.dp),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.width(4.dp))
        ViewerToolbarButton(label = "−", onClick = {
            coroutineScope.launch {
                val target = (currentScale / 1.25f).coerceAtLeast(zoomState.zoomable.minScale)
                zoomState.zoomable.scale(target, animated = true)
                onSetZoom(target)
            }
        })
        Spacer(Modifier.width(4.dp))
        ViewerToolbarButton(label = "+", onClick = {
            coroutineScope.launch {
                val target = (currentScale * 1.25f).coerceAtMost(zoomState.zoomable.maxScale)
                zoomState.zoomable.scale(target, animated = true)
                onSetZoom(target)
            }
        })
        Spacer(Modifier.width(16.dp))
        ViewerToolbarButton(
            label = stringResource(Res.string.action_image_rotate_ccw),
            onClick = {
            coroutineScope.launch {
                val rotation = (
                    zoomState.zoomable.transform.rotation.toInt() -
                        ROTATION_STEP_DEGREES + FULL_ROTATION_DEGREES
                    ) % FULL_ROTATION_DEGREES
                zoomState.zoomable.rotate(rotation)
                onRotate(false)
            }
            },
        )
        Spacer(Modifier.width(4.dp))
        ViewerToolbarButton(
            label = stringResource(Res.string.action_image_rotate_cw),
            onClick = {
            coroutineScope.launch {
                val rotation = (
                    zoomState.zoomable.transform.rotation.toInt() + ROTATION_STEP_DEGREES
                    ) % FULL_ROTATION_DEGREES
                zoomState.zoomable.rotate(rotation)
                onRotate(true)
            }
            },
        )
        Spacer(Modifier.weight(1f))
    }
}

/**
 * 渲染当前图片名称、分辨率、大小和索引。
 *
 * @param state 图片查看器状态。
 * @param file 当前图片文件。
 * @param resolution 原图分辨率。
 */
@Composable
private fun BoxScope.ImageViewerInfoBar(
    state: ImageViewerState,
    file: VFile,
    resolution: IntSize?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            file.name,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).widthIn(max = 400.dp),
        )
        Spacer(Modifier.width(16.dp))
        resolution?.let { value ->
            Text("${value.width} × ${value.height}", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
            Spacer(Modifier.width(16.dp))
        }
        Text(formatFileSize(file.sizeBytes), color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        Spacer(Modifier.width(16.dp))
        if (state.allImages.isNotEmpty()) {
            Text(
                "${state.currentIndex + 1} / ${state.allImages.size}",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * 渲染窗口左右两侧的翻页热区。
 *
 * @param onPrevious 上一张回调。
 * @param onNext 下一张回调。
 */
@Composable
private fun BoxScope.ImageViewerNavigationZones(onPrevious: () -> Unit, onNext: () -> Unit) {
    listOf(Alignment.CenterStart to onPrevious, Alignment.CenterEnd to onNext).forEach { (alignment, action) ->
        Box(
            modifier = Modifier
                .fillMaxWidth(VIEWER_NAVIGATION_ZONE_FRACTION)
                .fillMaxSize()
                .align(alignment)
                .padding(vertical = 48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = action,
                ),
        )
    }
}

/**
 * 工具栏按钮。
 */
@Composable
private fun ViewerToolbarButton(
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    active -> Color.White.copy(alpha = 0.2f)
                    isHovered -> Color.White.copy(alpha = 0.12f)
                    else -> Color.Transparent
                }
            )
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = if (active) 1f else 0.85f),
            fontSize = 12.sp,
        )
    }
}
