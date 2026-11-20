package com.oruke.onyx.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.PaneDividerHitSlop
import com.oruke.onyx.ui.theme.horizontalResizePointerIcon
import com.oruke.onyx.ui.theme.verticalResizePointerIcon
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_copy
import onyx.composeapp.generated.resources.action_extract
import onyx.composeapp.generated.resources.action_move
import onyx.composeapp.generated.resources.label_copy_to_destination
import onyx.composeapp.generated.resources.label_extract_to_destination
import onyx.composeapp.generated.resources.label_move_to_destination
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** 禁用工具栏图标的显示透明度。 */
private const val DISABLED_TOOLBAR_ICON_ALPHA = 0.45f

@Composable
internal fun FileDragOverlay(
    operation: FileTransferOperation,
    pointerWindowPosition: IntOffset?,
    targetDirectoryLocation: String?,
    appWindowOrigin: IntOffset,
) {
    val alpha by animateFloatAsState(
        targetValue = if (pointerWindowPosition != null) 1f else 0f,
        animationSpec = tween(durationMillis = 100),
    )
    val label = if (targetDirectoryLocation != null) {
        val displayDestination = desktopLocationLabel(targetDirectoryLocation)
        when (operation) {
            FileTransferOperation.COPY -> stringResource(Res.string.label_copy_to_destination, displayDestination)
            FileTransferOperation.MOVE -> stringResource(Res.string.label_move_to_destination, displayDestination)
            FileTransferOperation.EXTRACT -> stringResource(
                Res.string.label_extract_to_destination,
                displayDestination,
            )
        }
    } else {
        when (operation) {
            FileTransferOperation.COPY -> stringResource(Res.string.action_copy)
            FileTransferOperation.MOVE -> stringResource(Res.string.action_move)
            FileTransferOperation.EXTRACT -> stringResource(Res.string.action_extract)
        }
    }
    val pointerPosition = pointerWindowPosition ?: return

    Row(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = pointerPosition.x - appWindowOrigin.x + 18,
                    y = pointerPosition.y - appWindowOrigin.y + 18,
                )
            }
            .alpha(alpha)
            .border(1.dp, LocalOnyxPalette.current.outline, RoundedCornerShape(4.dp))
            .background(LocalOnyxPalette.current.floatingSurface, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            key = when (operation) {
                FileTransferOperation.COPY -> AllIconsKeys.Actions.Copy
                FileTransferOperation.MOVE -> AllIconsKeys.Actions.MenuCut
                FileTransferOperation.EXTRACT -> AllIconsKeys.Nodes.ExtractedFolder
            },
            contentDescription = null,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = label,
            modifier = Modifier.widthIn(max = 320.dp),
            color = LocalOnyxPalette.current.foreground,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 渲染标题栏中具有选中反馈的紧凑操作入口。
 *
 * @param selected 是否显示为已选中。
 * @param onClick 点击入口时执行的组件动作。
 * @param tooltip 悬停说明。
 * @param modifier 入口语义与布局修饰。
 * @param content 入口图标或文案。
 * @return 无返回值。
 */
@Composable
internal fun LayoutIconButton(
    selected: Boolean,
    onClick: () -> Unit,
    tooltip: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val background = when {
        selected -> LocalOnyxPalette.current.titleBarActiveBackground
        isPressed -> LocalOnyxPalette.current.titleBarPressedBackground
        isHovered -> LocalOnyxPalette.current.titleBarHoverBackground
        else -> Color.Transparent
    }

    OnyxTooltip(text = tooltip) {
        Box(
            modifier = modifier
                .hoverable(interactionSource)
                .background(background, RoundedCornerShape(4.dp))
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = 5.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
internal fun TitleBarIconButton(
    onClick: () -> Unit,
    tooltip: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    OnyxTooltip(text = tooltip) {
        Box(
            modifier = modifier
                .hoverable(interactionSource)
                .background(
                    when {
                        isPressed -> LocalOnyxPalette.current.titleBarPressedBackground
                        isHovered -> LocalOnyxPalette.current.titleBarHoverBackground
                        else -> Color.Transparent
                    },
                    RoundedCornerShape(4.dp),
                )
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = 5.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
internal fun ResizablePaneDivider(
    orientation: Orientation,
    onDragDelta: (Float) -> Unit,
) {
    val latestOnDragDelta by androidx.compose.runtime.rememberUpdatedState(onDragDelta)
    val modifier = when (orientation) {
        Orientation.Vertical -> Modifier
            .fillMaxHeight()
            .width(PaneDividerHitSlop)
            .pointerHoverIcon(horizontalResizePointerIcon())
            .pointerInput(Unit) {
                detectDragGestures { _, dragAmount ->
                    latestOnDragDelta(dragAmount.x)
                }
            }

        Orientation.Horizontal -> Modifier
            .fillMaxWidth()
            .height(PaneDividerHitSlop)
            .pointerHoverIcon(verticalResizePointerIcon())
            .pointerInput(Unit) {
                detectDragGestures { _, dragAmount ->
                    latestOnDragDelta(dragAmount.y)
                }
            }
    }

    Box(
        modifier = modifier.background(LocalOnyxPalette.current.appBackground),
        contentAlignment = Alignment.Center,
    ) {
        when (orientation) {
            Orientation.Vertical -> Divider(
                Orientation.Vertical,
                modifier = Modifier.fillMaxHeight().width(1.dp),
            )

            Orientation.Horizontal -> Divider(
                Orientation.Horizontal,
                modifier = Modifier.fillMaxWidth().height(1.dp),
            )
        }
    }
}

@Composable
internal fun ToolbarIconButton(
    enabled: Boolean,
    onClick: () -> Unit,
    tooltip: String,
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val background = when {
        !enabled -> Color.Transparent
        selected -> LocalOnyxPalette.current.titleBarActiveBackground
        isPressed -> LocalOnyxPalette.current.titleBarPressedBackground
        isHovered -> LocalOnyxPalette.current.titleBarHoverBackground
        else -> Color.Transparent
    }

    OnyxTooltip(text = tooltip, enabled = enabled) {
        Box(
            modifier = Modifier
                .hoverable(enabled = enabled, interactionSource = interactionSource)
                .background(background, RoundedCornerShape(4.dp))
                .clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .alpha(if (enabled) 1f else DISABLED_TOOLBAR_ICON_ALPHA)
                .padding(horizontal = 5.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}
