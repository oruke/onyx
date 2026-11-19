package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_clear_filter
import onyx.composeapp.generated.resources.label_filter_placeholder
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * 面板右上角悬浮快速筛选框，支持实时匹配计数、上下键切换条目、回车直接进入以及一键清除。
 *
 * @param query 当前筛选关键词。
 * @param focusRequester 输入框焦点请求器。
 * @param focusRequestId 递增触发自动聚焦的请求编号。
 * @param matchCount 当前筛选可见条目数；未就绪时为空。
 * @param totalCount 当前目录总条目数。
 * @param onQueryChange 筛选文本变更回调。
 * @param onFocusChanged 焦点状态变化回调。
 * @param onMoveSelection 在结果列表中上下移动选中的回调（传入偏移与 Shift 状态）。
 * @param onConfirmSelection 按下回车打开当前选中或首个匹配项的回调。
 * @param onClose 清空筛选并收起浮层的回调。
 * @param modifier 外部位置与尺寸修饰符。
 */
@Composable
internal fun FloatingFilterInput(
    query: String,
    focusRequester: FocusRequester,
    focusRequestId: Int,
    onQueryChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onClose: () -> Unit,
    matchCount: Int? = null,
    totalCount: Int = 0,
    onMoveSelection: (offset: Int, extendSelection: Boolean) -> Unit = { _, _ -> },
    onConfirmSelection: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = LocalOnyxPalette.current
    DisposableEffect(Unit) {
        onDispose { onFocusChanged(false) }
    }
    LaunchedEffect(focusRequestId) {
        if (focusRequestId > 0) {
            focusRequester.requestFocus()
        }
    }

    Row(
        modifier = modifier
            .testTag("pane-filter-overlay")
            .height(28.dp)
            .border(1.dp, palette.accent.copy(alpha = 0.55f), RoundedCornerShape(5.dp))
            .background(palette.inputBackground, RoundedCornerShape(5.dp))
            .padding(start = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            key = AllIconsKeys.Actions.Find,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = palette.accent,
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .testTag("pane-filter-input")
                .focusRequester(focusRequester)
                .onFocusChanged { focusState -> onFocusChanged(focusState.isFocused) }
                .onPreviewKeyEvent { event ->
                    handleFilterKeyEvent(event, onMoveSelection, onConfirmSelection, onClose)
                },
            textStyle = TextStyle(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = palette.foreground,
            ),
            singleLine = true,
            cursorBrush = SolidColor(palette.accent),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.label_filter_placeholder),
                            fontSize = 11.sp,
                            color = palette.disabledForeground,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (query.isNotEmpty() && matchCount != null) {
            Text(
                text = "$matchCount/$totalCount",
                fontSize = 11.sp,
                color = if (matchCount == 0) palette.error else palette.mutedForeground,
                maxLines = 1,
                modifier = Modifier.testTag("pane-filter-count"),
            )
        }
        FilterCloseButton(onClose = onClose)
    }
}

/**
 * 处理面板悬浮筛选框内的键盘导航与打开快捷键。
 */
private fun handleFilterKeyEvent(
    event: KeyEvent,
    onMoveSelection: (offset: Int, extendSelection: Boolean) -> Unit,
    onConfirmSelection: () -> Unit,
    onClose: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.Escape -> {
            onClose()
            true
        }
        Key.DirectionDown -> {
            onMoveSelection(1, event.isShiftPressed)
            true
        }
        Key.DirectionUp -> {
            onMoveSelection(-1, event.isShiftPressed)
            true
        }
        Key.Enter -> {
            onConfirmSelection()
            true
        }
        else -> false
    }
}

/**
 * 渲染悬浮筛选框右侧的一键清除关闭按钮。
 */
@Composable
private fun FilterCloseButton(onClose: () -> Unit) {
    val palette = LocalOnyxPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val clearTooltip = stringResource(Res.string.action_clear_filter)
    OnyxTooltip(clearTooltip) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .hoverable(interactionSource)
                .background(if (hovered) palette.rowHoverBackground else Color.Transparent)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClose,
                )
                .testTag("pane-filter-close"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                key = AllIconsKeys.Actions.Close,
                contentDescription = clearTooltip,
                modifier = Modifier.size(11.dp),
                tint = if (hovered) palette.foreground else palette.mutedForeground,
            )
        }
    }
}
