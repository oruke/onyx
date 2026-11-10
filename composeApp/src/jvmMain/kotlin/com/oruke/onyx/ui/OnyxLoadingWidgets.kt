package com.oruke.onyx.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.detailsColumnWidth
import com.oruke.onyx.ui.theme.resolve
import com.oruke.onyx.ui.theme.visibleDetailsColumns
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_error_prefix
import onyx.composeapp.generated.resources.label_loading_entries
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

private const val SPINNER_ROTATION_DURATION_MS = 850
private const val SPINNER_SWEEP_DURATION_MS = 650
private const val SPINNER_MIN_SWEEP_ANGLE = 105f
private const val SPINNER_MAX_SWEEP_ANGLE = 255f
private const val FULL_CIRCLE_DEGREES = 360f
private const val TRACK_ALPHA = 0.38f
private const val HALF_DIVISOR = 2f

private const val PROGRESS_DURATION_MS = 1000
private const val PROGRESS_SEGMENT_FRACTION = 0.32f
private const val PROGRESS_TRACK_ALPHA = 0.28f
private const val PROGRESS_BAR_ALPHA = 0.92f

private const val SHIMMER_DURATION_MS = 850
private const val SHIMMER_MIN_ALPHA = 0.16f
private const val SHIMMER_MAX_ALPHA = 0.30f
private const val SKELETON_ZEBRA_ALPHA = 0.65f
private const val SKELETON_ROW_COUNT = 8
private const val SKELETON_GALLERY_COUNT = 8
private const val SKELETON_GALLERY_ICON_FRACTION = 0.46f
private const val FAILURE_ICON_ALPHA = 0.72f

private val SKELETON_NAME_WIDTH_FRACTIONS = listOf(0.52f, 0.38f, 0.64f, 0.45f, 0.58f, 0.34f, 0.49f, 0.41f)
private val SKELETON_META_WIDTH_FRACTIONS = listOf(0.58f, 0.46f, 0.62f, 0.50f, 0.55f, 0.44f, 0.60f, 0.48f)

/**
 * 渲染紧凑平滑的旋转圆弧加载指示器，适用于主视图中心徽章、状态栏与目录树节点。
 *
 * @param modifier 外部布局修饰符。
 * @param size 指示器尺寸。
 * @param strokeWidth 圆弧线宽。
 * @param color 主圆弧颜色。
 * @param trackColor 底部轨道环颜色。
 */
@Composable
internal fun OnyxLoadingSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    strokeWidth: Dp = 2.dp,
    color: Color = LocalOnyxPalette.current.accent,
    trackColor: Color = LocalOnyxPalette.current.outlineVariant,
) {
    val transition = rememberInfiniteTransition()
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = FULL_CIRCLE_DEGREES,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SPINNER_ROTATION_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )
    val sweep by transition.animateFloat(
        initialValue = SPINNER_MIN_SWEEP_ANGLE,
        targetValue = SPINNER_MAX_SWEEP_ANGLE,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SPINNER_SWEEP_DURATION_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    Canvas(modifier = modifier.size(size)) {
        val strokePx = strokeWidth.toPx()
        val inset = strokePx / HALF_DIVISOR
        val arcSize = androidx.compose.ui.geometry.Size(
            width = (this.size.width - strokePx).coerceAtLeast(1f),
            height = (this.size.height - strokePx).coerceAtLeast(1f),
        )
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(
            color = trackColor.copy(alpha = TRACK_ALPHA),
            startAngle = 0f,
            sweepAngle = FULL_CIRCLE_DEGREES,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx),
        )
        drawArc(
            color = color,
            startAngle = rotation,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
    }
}

/**
 * 为工具栏刷新图标提供按需启用的连续旋转角度。
 *
 * @param active 当前是否处于加载或刷新状态。
 * @return 当前旋转角度（0 到 360 度）。
 */
@Composable
internal fun rememberLoadingIconRotation(active: Boolean): Float {
    if (!active) return 0f
    val transition = rememberInfiniteTransition()
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = FULL_CIRCLE_DEGREES,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SPINNER_ROTATION_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )
    return rotation
}

/**
 * 渲染面板顶部紧凑型不确定进度条，提供无布局抖动的连续加载反馈。
 *
 * @param modifier 外部布局修饰符。
 * @param height 进度条高度。
 * @param color 进度高亮颜色。
 */
@Composable
internal fun OnyxIndeterminateProgressBar(
    modifier: Modifier = Modifier,
    height: Dp = 2.dp,
    color: Color = LocalOnyxPalette.current.accent,
) {
    val palette = LocalOnyxPalette.current
    val transition = rememberInfiniteTransition()
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = PROGRESS_DURATION_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(palette.outlineVariant.copy(alpha = PROGRESS_TRACK_ALPHA)),
    ) {
        val travelWidth = maxWidth * (1f - PROGRESS_SEGMENT_FRACTION)
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(PROGRESS_SEGMENT_FRACTION)
                .offset(x = travelWidth * phase)
                .background(color.copy(alpha = PROGRESS_BAR_ALPHA)),
        )
    }
}

/**
 * 渲染文件面板加载态：在详情视图下保留固定表头，并展示顶部进度条、骨架占位与居中加载胶囊。
 *
 * @param model 文件内容视图状态。
 * @param actions 文件内容交互回调。
 */
@Composable
internal fun PaneEntriesLoading(
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
) {
    val horizontalScrollState = rememberPaneHorizontalScroll(model)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("pane-entries-loading-${model.paneId}"),
    ) {
        if (model.viewMode == ViewMode.DETAILS) {
            DetailsHeader(
                columns = model.columns,
                columnWidths = model.columnWeights,
                hiddenColumns = model.hiddenColumns,
                sort = model.sort,
                onToggleSort = actions.onToggleSort,
                onResizeColumn = actions.onResizeColumn,
                onToggleColumnVisibility = actions.onToggleColumnVisibility,
                scrollState = horizontalScrollState,
            )
        }
        Box(modifier = Modifier.fillMaxSize()) {
            if (model.viewMode == ViewMode.DETAILS) {
                DetailsLoadingSkeleton(model, horizontalScrollState)
            } else {
                GalleryLoadingSkeleton(model.galleryItemSizeDp)
            }
            OnyxIndeterminateProgressBar(modifier = Modifier.align(Alignment.TopStart))
            OnyxLoadingStatusBadge(
                text = stringResource(Res.string.label_loading_entries),
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * 渲染居中加载状态面板，适用于“此电脑”设备枚举或搜索加载等全区域加载场景。
 *
 * @param text 加载状态说明文本。
 * @param modifier 外部布局修饰符。
 */
@Composable
internal fun OnyxCenteredLoadingArea(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        OnyxIndeterminateProgressBar(modifier = Modifier.align(Alignment.TopStart))
        OnyxLoadingStatusBadge(
            text = text,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * 渲染扁平紧凑风格的居中加载状态胶囊。
 *
 * @param text 状态文案。
 * @param modifier 外部布局修饰符。
 */
@Composable
internal fun OnyxLoadingStatusBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalOnyxPalette.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(palette.surfaceVariant)
            .border(1.dp, palette.outline, shape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OnyxLoadingSpinner(size = 16.dp, strokeWidth = 2.dp)
        Text(
            text = text,
            color = palette.foreground,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 渲染文件面板加载失败状态，在详情视图下保留表头并居中展示错误详情。
 *
 * @param model 文件内容视图状态。
 * @param actions 文件内容交互回调。
 * @param state 加载失败状态。
 */
@Composable
internal fun PaneEntriesFailure(
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    state: PaneEntriesState.Failure,
) {
    val horizontalScrollState = rememberPaneHorizontalScroll(model)
    val palette = LocalOnyxPalette.current
    val message = buildString {
        append(stringResource(Res.string.label_error_prefix))
        append(' ')
        append(state.error.detail?.resolve().orEmpty())
    }.trim()
    Column(modifier = Modifier.fillMaxSize()) {
        if (model.viewMode == ViewMode.DETAILS) {
            DetailsHeader(
                columns = model.columns,
                columnWidths = model.columnWeights,
                hiddenColumns = model.hiddenColumns,
                sort = model.sort,
                onToggleSort = actions.onToggleSort,
                onResizeColumn = actions.onResizeColumn,
                onToggleColumnVisibility = actions.onToggleColumnVisibility,
                scrollState = horizontalScrollState,
            )
        }
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    key = AllIconsKeys.General.Warning,
                    contentDescription = null,
                    modifier = Modifier.alpha(FAILURE_ICON_ALPHA),
                )
                Text(
                    text = message,
                    color = palette.foreground,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 渲染详情视图下的呼吸骨架占位行，与当前列宽及行高保持严格对齐。
 *
 * @param model 文件内容视图状态。
 * @param scrollState 表头同步横向滚动状态。
 */
@Composable
private fun DetailsLoadingSkeleton(
    model: PaneEntriesViewModel,
    scrollState: ScrollState,
) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    val shimmerAlpha = rememberShimmerAlpha()
    val visibleColumns = visibleDetailsColumns(model.columns)
    val placeholderColor = palette.mutedForeground.copy(alpha = shimmerAlpha)
    Column(modifier = Modifier.fillMaxSize()) {
        repeat(SKELETON_ROW_COUNT) { rowIndex ->
            val rowBackground = if (rowIndex % 2 == 1) {
                palette.surfaceVariant.copy(alpha = SKELETON_ZEBRA_ALPHA)
            } else {
                Color.Transparent
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(appearance.listRowHeight)
                    .horizontalScroll(scrollState)
                    .background(rowBackground)
                    .padding(horizontal = appearance.entryHorizontalPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                visibleColumns.forEach { column ->
                    val colWidth = detailsColumnWidth(model.columnWeights, column).dp
                    DetailsSkeletonCell(column, colWidth, rowIndex, placeholderColor)
                }
            }
        }
    }
}

/**
 * 渲染单个详情骨架单元格。
 *
 * @param column 当前详情列枚举。
 * @param width 列宽。
 * @param rowIndex 行序号。
 * @param color 呼吸占位颜色。
 */
@Composable
private fun DetailsSkeletonCell(
    column: DetailsColumn,
    width: Dp,
    rowIndex: Int,
    color: Color,
) {
    val barShape = RoundedCornerShape(3.dp)
    if (column == DetailsColumn.NAME) {
        val fraction = SKELETON_NAME_WIDTH_FRACTIONS[rowIndex % SKELETON_NAME_WIDTH_FRACTIONS.size]
        Row(
            modifier = Modifier.width(width).padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Spacer(modifier = Modifier.width(16.dp))
            Box(
                modifier = Modifier
                    .size(13.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
            Box(
                modifier = Modifier
                    .width((width - 42.dp).coerceAtLeast(32.dp) * fraction)
                    .height(8.dp)
                    .clip(barShape)
                    .background(color),
            )
        }
    } else {
        val fraction = SKELETON_META_WIDTH_FRACTIONS[
            (rowIndex + column.ordinal) % SKELETON_META_WIDTH_FRACTIONS.size
        ]
        val contentAlignment = if (column == DetailsColumn.SIZE) {
            Alignment.CenterEnd
        } else {
            Alignment.CenterStart
        }
        Box(
            modifier = Modifier.width(width).padding(horizontal = 6.dp),
            contentAlignment = contentAlignment,
        ) {
            Box(
                modifier = Modifier
                    .width((width - 12.dp).coerceAtLeast(24.dp) * fraction)
                    .height(7.dp)
                    .clip(barShape)
                    .background(color),
            )
        }
    }
}

/**
 * 渲染画廊视图下的呼吸骨架占位卡片。
 *
 * @param itemSizeDp 画廊卡片逻辑尺寸。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GalleryLoadingSkeleton(itemSizeDp: Int) {
    val palette = LocalOnyxPalette.current
    val shimmerAlpha = rememberShimmerAlpha()
    val placeholderColor = palette.mutedForeground.copy(alpha = shimmerAlpha)
    val cardSize = itemSizeDp.coerceIn(96, 240).dp
    val cardShape = RoundedCornerShape(6.dp)
    FlowRow(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(SKELETON_GALLERY_COUNT) { index ->
            val titleFraction = SKELETON_NAME_WIDTH_FRACTIONS[index % SKELETON_NAME_WIDTH_FRACTIONS.size]
            Column(
                modifier = Modifier
                    .width(cardSize)
                    .clip(cardShape)
                    .background(palette.surfaceVariant.copy(alpha = SKELETON_ZEBRA_ALPHA))
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size((cardSize * SKELETON_GALLERY_ICON_FRACTION).coerceIn(36.dp, 88.dp))
                        .clip(RoundedCornerShape(6.dp))
                        .background(placeholderColor),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(titleFraction)
                        .height(8.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(placeholderColor),
                )
            }
        }
    }
}

/**
 * 生成骨架屏呼吸透明度动画值。
 *
 * @return 当前帧透明度。
 */
@Composable
private fun rememberShimmerAlpha(): Float {
    val transition = rememberInfiniteTransition()
    val alpha by transition.animateFloat(
        initialValue = SHIMMER_MIN_ALPHA,
        targetValue = SHIMMER_MAX_ALPHA,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SHIMMER_DURATION_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    return alpha
}
