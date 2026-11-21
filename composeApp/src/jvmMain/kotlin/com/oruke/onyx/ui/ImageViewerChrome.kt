package com.oruke.onyx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.core.model.ImageViewerState
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.decodeSfmDisplayText
import com.oruke.onyx.ui.theme.formatFileSize
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_image_viewer_actual_size
import onyx.composeapp.generated.resources.action_image_viewer_fit_window
import onyx.composeapp.generated.resources.action_image_viewer_next
import onyx.composeapp.generated.resources.action_image_viewer_previous
import onyx.composeapp.generated.resources.action_image_viewer_rotate_ccw
import onyx.composeapp.generated.resources.action_image_viewer_rotate_cw
import onyx.composeapp.generated.resources.action_image_viewer_zoom_in
import onyx.composeapp.generated.resources.action_image_viewer_zoom_out
import onyx.composeapp.generated.resources.label_image_viewer_close
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** 45 度角的正/余弦值（`1 / sqrt(2)`），用于计算旋转图标箭头端点坐标。 */
private const val COS_45_DEG = 0.70710677f

/**
 * 图片查看器顶部工具栏回调集合，避免 Composable 参数列表过长并统一交互入口。
 */
internal data class ImageViewerToolbarActions(
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onFitWindow: () -> Unit,
    val onActualSize: () -> Unit,
    val onZoomOut: () -> Unit,
    val onZoomIn: () -> Unit,
    val onRotateCcw: () -> Unit,
    val onRotateCw: () -> Unit,
    val onClose: () -> Unit,
)

/**
 * 渲染图片查看器顶部操作栏。
 *
 * 采用紧凑矢量图标 + 悬停 Tooltip 分组布局：
 * 翻页（上一张 / 下一张）| 视图模式（适应窗口 / 原始大小 1:1）| 缩放步进（缩小 / 百分比 / 放大）| 旋转（左 / 右）... 关闭。
 */
@Composable
internal fun BoxScope.ImageViewerToolbar(
    displayZoomPercent: Int,
    fitActive: Boolean,
    actualActive: Boolean,
    actions: ImageViewerToolbarActions,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.TopCenter)
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewerToolbarIconButton(
            iconKey = AllIconsKeys.Actions.Back,
            tooltip = stringResource(Res.string.action_image_viewer_previous),
            onClick = actions.onPrevious,
            modifier = Modifier.testTag("image-viewer-prev-button"),
        )
        Spacer(Modifier.width(2.dp))
        ViewerToolbarIconButton(
            iconKey = AllIconsKeys.Actions.Forward,
            tooltip = stringResource(Res.string.action_image_viewer_next),
            onClick = actions.onNext,
            modifier = Modifier.testTag("image-viewer-next-button"),
        )
        ViewerToolbarDivider()
        ViewerToolbarIconButton(
            iconKey = AllIconsKeys.General.FitContent,
            tooltip = stringResource(Res.string.action_image_viewer_fit_window),
            onClick = actions.onFitWindow,
            active = fitActive,
            modifier = Modifier.testTag("image-viewer-fit-button"),
        )
        Spacer(Modifier.width(2.dp))
        ViewerToolbarIconButton(
            iconKey = AllIconsKeys.General.ActualZoom,
            tooltip = stringResource(Res.string.action_image_viewer_actual_size),
            onClick = actions.onActualSize,
            active = actualActive,
            modifier = Modifier.testTag("image-viewer-actual-button"),
        )
        ViewerToolbarDivider()
        ViewerToolbarIconButton(
            iconKey = AllIconsKeys.Graph.ZoomOut,
            tooltip = stringResource(Res.string.action_image_viewer_zoom_out),
            onClick = actions.onZoomOut,
            modifier = Modifier.testTag("image-viewer-zoom-out-button"),
        )
        Text(
            text = "$displayZoomPercent%",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier
                .testTag("image-viewer-zoom-percent")
                .widthIn(min = 46.dp)
                .padding(horizontal = 4.dp),
            textAlign = TextAlign.Center,
        )
        ViewerToolbarIconButton(
            iconKey = AllIconsKeys.Graph.ZoomIn,
            tooltip = stringResource(Res.string.action_image_viewer_zoom_in),
            onClick = actions.onZoomIn,
            modifier = Modifier.testTag("image-viewer-zoom-in-button"),
        )
        ViewerToolbarDivider()
        ViewerToolbarCustomIconButton(
            tooltip = stringResource(Res.string.action_image_viewer_rotate_ccw),
            onClick = actions.onRotateCcw,
            modifier = Modifier.testTag("image-viewer-rotate-ccw-button"),
        ) { tint ->
            ImageRotateIcon(clockwise = false, tint = tint, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(2.dp))
        ViewerToolbarCustomIconButton(
            tooltip = stringResource(Res.string.action_image_viewer_rotate_cw),
            onClick = actions.onRotateCw,
            modifier = Modifier.testTag("image-viewer-rotate-cw-button"),
        ) { tint ->
            ImageRotateIcon(clockwise = true, tint = tint, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.weight(1f))
        ViewerToolbarIconButton(
            iconKey = AllIconsKeys.General.Close,
            tooltip = stringResource(Res.string.label_image_viewer_close),
            onClick = actions.onClose,
            modifier = Modifier.testTag("image-viewer-close-button"),
        )
    }
}

/** 渲染查看器顶部工具栏分组细竖线。 */
@Composable
private fun ViewerToolbarDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .width(1.dp)
            .height(14.dp)
            .background(Color.White.copy(alpha = 0.16f)),
    )
}

/**
 * 渲染当前图片名称、分辨率、大小和索引的底部状态条。
 */
@Composable
internal fun BoxScope.ImageViewerInfoBar(
    state: ImageViewerState,
    file: VFile,
    resolution: IntSize?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            file.name.decodeSfmDisplayText(),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).widthIn(max = 400.dp),
        )
        Spacer(Modifier.width(16.dp))
        resolution?.let { value ->
            Text("${value.width} × ${value.height}", color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
            Spacer(Modifier.width(16.dp))
        }
        Text(formatFileSize(file.sizeBytes), color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
        Spacer(Modifier.width(16.dp))
        if (state.allImages.isNotEmpty()) {
            Text(
                "${state.currentIndex + 1} / ${state.allImages.size}",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * 仅在鼠标悬停窗口时显示的紧凑居中边缘翻页箭头按钮，避免大面积遮罩劫持放大后的拖拽平移与双击缩放。
 */
@Composable
internal fun BoxScope.ImageViewerEdgeArrows(
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    listOf(
        Triple(Alignment.CenterStart, "‹", "image-viewer-edge-prev" to onPrevious),
        Triple(Alignment.CenterEnd, "›", "image-viewer-edge-next" to onNext),
    ).forEach { (alignment, glyph, tagAndAction) ->
        val (tag, action) = tagAndAction
        val interactionSource = remember { MutableInteractionSource() }
        val hovered by interactionSource.collectIsHoveredAsState()
        Box(
            modifier = Modifier
                .align(alignment)
                .padding(horizontal = 12.dp)
                .size(width = 36.dp, height = 56.dp)
                .testTag(tag)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = if (hovered) 0.72f else 0.45f))
                .border(1.dp, Color.White.copy(alpha = if (hovered) 0.35f else 0.16f), RoundedCornerShape(8.dp))
                .hoverable(interactionSource)
                .clickable(onClick = action),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = glyph,
                color = Color.White.copy(alpha = if (hovered) 1f else 0.85f),
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * 查看器深色工具栏的紧凑图标按钮（基于单色矢量 [IconKey] 显式白色着色，浅色主题下亦保持清晰对比度）。
 */
@Composable
private fun ViewerToolbarIconButton(
    iconKey: IconKey,
    tooltip: String,
    onClick: () -> Unit,
    active: Boolean = false,
    modifier: Modifier = Modifier,
) {
    ViewerToolbarCustomIconButton(
        tooltip = tooltip,
        onClick = onClick,
        active = active,
        modifier = modifier,
    ) { tint ->
        Icon(
            key = iconKey,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 查看器深色工具栏的紧凑图标按钮容器，带悬停/激活反馈、无障碍标签与 [OnyxTooltip] 提示。
 */
@Composable
private fun ViewerToolbarCustomIconButton(
    tooltip: String,
    onClick: () -> Unit,
    active: Boolean = false,
    modifier: Modifier = Modifier,
    icon: @Composable (tint: Color) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val tint = Color.White.copy(alpha = if (active || isHovered) 1f else 0.85f)
    OnyxTooltip(text = tooltip) {
        Box(
            modifier = modifier
                .size(28.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(
                    when {
                        active -> Color.White.copy(alpha = 0.22f)
                        isHovered -> Color.White.copy(alpha = 0.14f)
                        else -> Color.Transparent
                    },
                )
                .border(
                    width = 1.dp,
                    color = if (active) Color.White.copy(alpha = 0.38f) else Color.Transparent,
                    shape = RoundedCornerShape(5.dp),
                )
                .hoverable(interactionSource)
                .semantics { contentDescription = tooltip }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            icon(tint)
        }
    }
}

/**
 * 绘制与 JetBrains ExpUI 线性风格一致的 90° 顺/逆时针旋转单色矢量图标。
 */
@Composable
private fun ImageRotateIcon(
    clockwise: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = (size.minDimension * 0.072f).coerceAtLeast(1.1f)
        val radius = size.minDimension * 0.365f
        val cx = size.width / 2f
        val cy = size.height * 0.52f
        val arcTopLeft = Offset(cx - radius, cy - radius)
        val arcSize = Size(radius * 2f, radius * 2f)
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val diag = radius * COS_45_DEG
        val barb = size.minDimension * 0.175f

        if (clockwise) {
            drawArc(
                color = tint,
                startAngle = 35f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = stroke,
            )
            val tip = Offset(cx + diag, cy - diag)
            val arrowPath = Path().apply {
                moveTo(tip.x - barb, tip.y)
                lineTo(tip.x, tip.y)
                lineTo(tip.x, tip.y - barb)
            }
            drawPath(path = arrowPath, color = tint, style = stroke)
        } else {
            drawArc(
                color = tint,
                startAngle = 145f,
                sweepAngle = -280f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = stroke,
            )
            val tip = Offset(cx - diag, cy - diag)
            val arrowPath = Path().apply {
                moveTo(tip.x + barb, tip.y)
                lineTo(tip.x, tip.y)
                lineTo(tip.x, tip.y - barb)
            }
            drawPath(path = arrowPath, color = tint, style = stroke)
        }
    }
}

/**
 * 查看器文字操作按钮（用于错误恢复卡片的「重试 / 用系统默认应用打开」操作）。
 */
@Composable
internal fun ViewerToolbarButton(
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    active -> Color.White.copy(alpha = 0.24f)
                    isHovered -> Color.White.copy(alpha = 0.14f)
                    else -> Color.Transparent
                },
            )
            .border(
                width = 1.dp,
                color = if (active) Color.White.copy(alpha = 0.4f) else Color.Transparent,
                shape = RoundedCornerShape(4.dp),
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
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
