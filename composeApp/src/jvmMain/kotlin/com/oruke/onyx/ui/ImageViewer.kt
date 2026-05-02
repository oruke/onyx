package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import com.github.panpf.zoomimage.compose.rememberZoomState
import com.oruke.onyx.core.model.ImageViewerState
import com.oruke.onyx.ui.theme.formatFileSize
import com.oruke.onyx.ui.theme.rememberThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

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
    onSetFitMode: (com.oruke.onyx.core.model.ImageFitMode) -> Unit,
    onRotate: (Boolean) -> Unit,
) {
    val currentFile = state.currentFile ?: return

    // ── 图片加载 ──────────────────────────────────────────────────
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
                        }
                        true
                    }
                    Key.Zero -> {
                        coroutineScope.launch {
                            zoomState.zoomable.scale(
                                targetScale = zoomState.zoomable.minScale,
                                animated = true,
                            )
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
                .padding(vertical = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isLoading) {
                Text(
                    text = "…",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 24.sp,
                )
            } else if (bitmap != null) {
                ZoomImage(
                    painter = remember(bitmap) { BitmapPainter(bitmap) },
                    contentDescription = currentFile.name,
                    modifier = Modifier.fillMaxSize(),
                    zoomState = zoomState,
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
                onClick = {
                    coroutineScope.launch {
                        zoomState.zoomable.scale(
                            targetScale = zoomState.zoomable.minScale,
                            animated = true,
                        )
                    }
                },
            )
            Spacer(Modifier.width(4.dp))
            ViewerToolbarButton(
                label = stringResource(Res.string.action_image_actual_size),
                onClick = {
                    coroutineScope.launch {
                        zoomState.zoomable.scale(
                            targetScale = 1f,
                            animated = true,
                        )
                    }
                },
            )
            Spacer(Modifier.width(16.dp))

            // 缩放百分比显示
            Text(
                text = "${(currentScale * 100).toInt()}%",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                modifier = Modifier.widthIn(min = 48.dp),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(4.dp))
            ViewerToolbarButton(label = "−", onClick = {
                coroutineScope.launch {
                    val target = (currentScale / 1.25f).coerceAtLeast(
                        zoomState.zoomable.minScale
                    )
                    zoomState.zoomable.scale(targetScale = target, animated = true)
                }
            })
            Spacer(Modifier.width(4.dp))
            ViewerToolbarButton(label = "+", onClick = {
                coroutineScope.launch {
                    val target = (currentScale * 1.25f).coerceAtMost(
                        zoomState.zoomable.maxScale
                    )
                    zoomState.zoomable.scale(targetScale = target, animated = true)
                }
            })
            Spacer(Modifier.width(16.dp))
            ViewerToolbarButton(
                label = stringResource(Res.string.action_image_rotate_ccw),
                onClick = {
                    coroutineScope.launch {
                        zoomState.zoomable.rotate(
                            targetRotation = (zoomState.zoomable.transform.rotation.toInt() - 90 + 360) % 360,
                        )
                    }
                },
            )
            Spacer(Modifier.width(4.dp))
            ViewerToolbarButton(
                label = stringResource(Res.string.action_image_rotate_cw),
                onClick = {
                    coroutineScope.launch {
                        zoomState.zoomable.rotate(
                            targetRotation = (zoomState.zoomable.transform.rotation.toInt() + 90) % 360,
                        )
                    }
                },
            )
            Spacer(Modifier.weight(1f))
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
        // 左侧 20% 点击 = 上一张
        Box(
            modifier = Modifier
                .fillMaxWidth(0.2f)
                .fillMaxSize()
                .align(Alignment.CenterStart)
                .padding(vertical = 48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onPrevious,
                ),
        )
        // 右侧 20% 点击 = 下一张
        Box(
            modifier = Modifier
                .fillMaxWidth(0.2f)
                .fillMaxSize()
                .align(Alignment.CenterEnd)
                .padding(vertical = 48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onNext,
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
