package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.LocalTooltipController
import com.oruke.onyx.ui.theme.TooltipController
import com.oruke.onyx.ui.theme.TooltipRequest
import com.oruke.onyx.ui.theme.centerBottom
import com.oruke.onyx.ui.theme.containsPoint
import com.oruke.onyx.ui.theme.toIntOffset
import com.oruke.onyx.ui.theme.tooltipOffset
import com.oruke.onyx.ui.theme.windowBounds
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.jetbrains.jewel.ui.component.Text

/** 指针判定仍在提示锚点范围内的像素容差。 */
private const val TOOLTIP_ANCHOR_SLOP_PX = 4

/** 指针稳定悬停后展示提示浮层的延迟毫秒数。 */
private const val TOOLTIP_HOVER_DELAY_MILLIS = 350L

/**
 * 为独立窗口提供跟随指针的 Onyx 提示浮层，并在点击、滚动、移出窗口或锚点越界时立即清理残留浮层。
 *
 * @param content 需要提示能力的窗口内容。
 */
@Composable
internal fun OnyxTooltipHost(content: @Composable () -> Unit) {
    var request by remember { mutableStateOf<TooltipRequest?>(null) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    var windowOrigin by remember { mutableStateOf(IntOffset.Zero) }
    val controller = remember {
        TooltipController(
            show = { request = it },
            hide = { owner -> if (request?.owner === owner) request = null },
            clear = { request = null },
        )
    }
    CompositionLocalProvider(LocalTooltipController provides controller) {
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    contentSize = coordinates.size
                    windowOrigin = coordinates.localToWindow(Offset.Zero).toIntOffset()
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            when (event.type) {
                                PointerEventType.Exit,
                                PointerEventType.Press,
                                PointerEventType.Scroll -> request = null
                                PointerEventType.Move -> {
                                    val active = request
                                    val localPos = event.changes.firstOrNull()?.position
                                    if (active != null && localPos != null) {
                                        val windowPos = IntOffset(
                                            x = windowOrigin.x + localPos.x.roundToInt(),
                                            y = windowOrigin.y + localPos.y.roundToInt(),
                                        )
                                        val paddedBounds = active.anchorBounds.inflate(TOOLTIP_ANCHOR_SLOP_PX)
                                        if (!paddedBounds.containsPoint(windowPos)) {
                                            request = null
                                        }
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                },
        ) {
            content()
            request?.let { OnyxTooltipOverlay(it, contentSize, windowOrigin) }
        }
    }
}

/**
 * 为包裹的控件提供带悬停防抖、按下即收与组件卸载自动销毁的悬浮提示。
 *
 * @param text 提示文本，空白时不显示。
 * @param enabled 是否启用提示。
 * @param content 目标子控件。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun OnyxTooltip(
    text: String,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val tooltipController = LocalTooltipController.current
    val tooltipOwner = remember { Any() }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var anchorBounds by remember { mutableStateOf<IntRect?>(null) }
    var pointerPosition by remember { mutableStateOf<IntOffset?>(null) }
    var dismissedUntilExit by remember { mutableStateOf(false) }
    var readyToShow by remember { mutableStateOf(false) }

    DisposableEffect(tooltipOwner, tooltipController) {
        onDispose { tooltipController.hide(tooltipOwner) }
    }

    fun updatePointerPosition(eventPosition: Offset) {
        pointerPosition = anchorBounds?.let { bounds ->
            IntOffset(
                x = bounds.left + eventPosition.x.roundToInt(),
                y = bounds.top + eventPosition.y.roundToInt(),
            )
        }
    }

    val canHoverShow = enabled && isHovered && !dismissedUntilExit
    LaunchedEffect(canHoverShow, text) {
        if (canHoverShow && text.isNotBlank()) {
            delay(TOOLTIP_HOVER_DELAY_MILLIS)
            readyToShow = true
        } else {
            readyToShow = false
            tooltipController.hide(tooltipOwner)
        }
    }

    LaunchedEffect(readyToShow, text, anchorBounds, pointerPosition) {
        val bounds = anchorBounds
        if (readyToShow && text.isNotBlank() && bounds != null) {
            tooltipController.show(
                TooltipRequest(
                    owner = tooltipOwner,
                    text = text,
                    anchorBounds = bounds,
                    pointerPosition = pointerPosition ?: bounds.centerBottom(),
                )
            )
        } else if (!readyToShow) {
            tooltipController.hide(tooltipOwner)
        }
    }

    Box(
        modifier = Modifier
            .onGloballyPositioned { coordinates -> anchorBounds = coordinates.windowBounds() }
            .onPointerEvent(PointerEventType.Enter, PointerEventPass.Initial) { event ->
                dismissedUntilExit = false
                event.changes.firstOrNull()?.position?.let(::updatePointerPosition)
            }
            .onPointerEvent(PointerEventType.Move, PointerEventPass.Initial) { event ->
                event.changes.firstOrNull()?.position?.let(::updatePointerPosition)
            }
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) {
                dismissedUntilExit = true
                readyToShow = false
                tooltipController.hide(tooltipOwner)
            }
            .onPointerEvent(PointerEventType.Exit, PointerEventPass.Initial) {
                dismissedUntilExit = false
                readyToShow = false
                tooltipController.hide(tooltipOwner)
            }
            .hoverable(enabled = enabled, interactionSource = interactionSource),
    ) {
        content()
    }
}

/**
 * 渲染支持多行自适应换行的提示浮层。
 */
@Composable
internal fun OnyxTooltipOverlay(
    request: TooltipRequest,
    appSize: IntSize,
    appWindowOrigin: IntOffset,
) {
    var tooltipSize by remember(request.text) { mutableStateOf(IntSize.Zero) }
    Text(
        text = request.text,
        modifier = Modifier
            .offset { tooltipOffset(request.pointerPosition, appWindowOrigin, appSize, tooltipSize) }
            .onSizeChanged { tooltipSize = it }
            .widthIn(max = 460.dp)
            .border(1.dp, LocalOnyxPalette.current.outlineVariant, RoundedCornerShape(4.dp))
            .background(LocalOnyxPalette.current.floatingSurface, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        color = LocalOnyxPalette.current.foreground,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
    )
}
