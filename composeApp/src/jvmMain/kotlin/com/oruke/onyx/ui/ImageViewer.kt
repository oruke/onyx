package com.oruke.onyx.ui



import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.core.model.ImageFitMode
import com.oruke.onyx.core.model.ImageViewerState
import com.oruke.onyx.ui.theme.formatFileSize
import com.oruke.onyx.ui.theme.rememberThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_image_actual_size
import onyx.composeapp.generated.resources.action_image_fit_window
import onyx.composeapp.generated.resources.action_image_rotate_ccw
import onyx.composeapp.generated.resources.action_image_rotate_cw
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * 图片查看器内容。
 *
 * 供独立窗口使用，不含覆盖层逻辑。
 *
 * 功能：
 * - 居中显示图片，支持鼠标滚轮缩放 + 拖拽平移
 * - 顶部工具栏：← 上一张 / → 下一张 / 适应窗口 / 1:1 / 旋转 / 关闭
 * - 底部信息栏：文件名 | 分辨率 | 大小 | 索引/总数
 * - 快捷键：Esc 关闭、← → 翻页、+ - 缩放、0 适应窗口
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
) {
    val currentFile = state.currentFile ?: return

    // ── 图片加载 ──────────────────────────────────────────────────
    // 用大尺寸 rememberThumbnail 加载（适合大多数图片）
    val (bitmap, isLoading) = rememberThumbnail(currentFile.location, 4096)

    // 原图分辨率（用于信息栏显示）
    var nativeResolution by remember(currentFile.location) { mutableStateOf<IntSize?>(null) }
    LaunchedEffect(currentFile.location) {
        nativeResolution = withContext(Dispatchers.IO) {
            try {
                val path = Path.of(currentFile.location)
                if (Files.exists(path)) {
                    val reader = ImageIO.getImageReadersBySuffix(
                        currentFile.name.substringAfterLast('.', "")
                    )
                    if (reader.hasNext()) {
                        val r = reader.next()
                        ImageIO.createImageInputStream(path.toFile()).use { stream ->
                            r.input = stream
                            IntSize(r.getWidth(0), r.getHeight(0))
                        }
                    } else null
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }

    // ── 缩放 & 平移状态 ──────────────────────────────────────────
    var panOffset by remember(currentFile.location) { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // 根据 fitMode 计算实际缩放
    val effectiveZoom = when (state.fitMode) {
        ImageFitMode.FIT_WINDOW -> 1f // graphicsLayer 的 contentScale 处理
        ImageFitMode.ACTUAL_SIZE -> state.zoomFactor
        ImageFitMode.FILL_WIDTH -> state.zoomFactor
    }

    val contentScale = when (state.fitMode) {
        ImageFitMode.FIT_WINDOW -> ContentScale.Fit
        ImageFitMode.ACTUAL_SIZE -> ContentScale.None
        ImageFitMode.FILL_WIDTH -> ContentScale.FillWidth
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onGloballyPositioned { containerSize = it.size }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> { onClose(); true }
                    Key.DirectionRight -> { onNext(); true }
                    Key.DirectionLeft -> { onPrevious(); true }
                    Key.Equals, Key.NumPadAdd -> {
                        onSetZoom(state.zoomFactor * 1.25f)
                        true
                    }
                    Key.Minus, Key.NumPadSubtract -> {
                        onSetZoom(state.zoomFactor / 1.25f)
                        true
                    }
                    Key.Zero -> {
                        if (event.isCtrlPressed || event.isMetaPressed) {
                            onSetFitMode(ImageFitMode.ACTUAL_SIZE)
                        } else {
                            onSetFitMode(ImageFitMode.FIT_WINDOW)
                        }
                        true
                    }
                    else -> false
                }
            },
    ) {
        // ── 图片区域 ──────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 48.dp)
                .pointerInput(currentFile.location) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) {
                            onSetZoom(state.zoomFactor * zoom)
                        }
                        panOffset = Offset(
                            x = panOffset.x + pan.x,
                            y = panOffset.y + pan.y,
                        )
                    }
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val scrollDelta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (scrollDelta != 0f) {
                        val factor = if (scrollDelta > 0) 0.9f else 1.1f
                        onSetZoom(state.zoomFactor * factor)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (isLoading) {
                Text(
                    text = "…",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 24.sp,
                )
            } else if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = currentFile.name,
                    contentScale = contentScale,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier
                        .then(
                            if (state.fitMode == ImageFitMode.FIT_WINDOW) {
                                Modifier.fillMaxSize()
                            } else {
                                Modifier
                            }
                        )
                        .graphicsLayer {
                            scaleX = effectiveZoom
                            scaleY = effectiveZoom
                            translationX = panOffset.x
                            translationY = panOffset.y
                            rotationZ = state.rotation.toFloat()
                        },
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        key = AllIconsKeys.FileTypes.Any_type,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = currentFile.name,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                    )
                }
            }
        }

        // ── 顶部工具栏 ────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewerToolbarButton(label = "←", onClick = onPrevious)
            Spacer(Modifier.width(4.dp))
            ViewerToolbarButton(label = "→", onClick = onNext)
            Spacer(Modifier.width(16.dp))
            ViewerToolbarButton(
                label = stringResource(Res.string.action_image_fit_window),
                onClick = { onSetFitMode(ImageFitMode.FIT_WINDOW) },
                active = state.fitMode == ImageFitMode.FIT_WINDOW,
            )
            Spacer(Modifier.width(4.dp))
            ViewerToolbarButton(
                label = stringResource(Res.string.action_image_actual_size),
                onClick = { onSetFitMode(ImageFitMode.ACTUAL_SIZE) },
                active = state.fitMode == ImageFitMode.ACTUAL_SIZE,
            )
            Spacer(Modifier.width(16.dp))

            // 缩放百分比显示
            Text(
                text = "${(effectiveZoom * 100).toInt()}%",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                modifier = Modifier.widthIn(min = 48.dp),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(4.dp))
            ViewerToolbarButton(label = "−", onClick = { onSetZoom(state.zoomFactor / 1.25f) })
            Spacer(Modifier.width(4.dp))
            ViewerToolbarButton(label = "+", onClick = { onSetZoom(state.zoomFactor * 1.25f) })
            Spacer(Modifier.width(16.dp))
            ViewerToolbarButton(
                label = stringResource(Res.string.action_image_rotate_ccw),
                onClick = { onRotate(false) },
            )
            Spacer(Modifier.width(4.dp))
            ViewerToolbarButton(
                label = stringResource(Res.string.action_image_rotate_cw),
                onClick = { onRotate(true) },
            )
            Spacer(Modifier.weight(1f))
            ViewerToolbarButton(label = "✕", onClick = onClose)
        }

        // ── 底部信息栏 ────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 文件名
            Text(
                text = currentFile.name,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 400.dp),
            )
            Spacer(Modifier.width(16.dp))

            // 分辨率
            nativeResolution?.let { res ->
                Text(
                    text = "${res.width} × ${res.height}",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                )
                Spacer(Modifier.width(16.dp))
            }

            // 文件大小
            Text(
                text = formatFileSize(currentFile.sizeBytes),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
            )
            Spacer(Modifier.width(16.dp))

            // 索引
            if (state.allImages.isNotEmpty()) {
                Text(
                    text = "${state.currentIndex + 1} / ${state.allImages.size}",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                )
            }
        }

        // ── 左右翻页点击区域 ──────────────────────────────────────
        // 左侧 15% 点击 = 上一张
        Box(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.CenterStart)
                .padding(vertical = 48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onPrevious,
                )
                .fillMaxWidth(0.15f),
        )
        // 右侧 15% 点击 = 下一张
        Box(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.CenterEnd)
                .padding(vertical = 48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onNext,
                )
                .fillMaxWidth(0.15f),
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
