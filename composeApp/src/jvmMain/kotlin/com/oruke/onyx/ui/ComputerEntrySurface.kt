package com.oruke.onyx.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.toIntOffset
import com.oruke.onyx.ui.theme.windowBounds

/**
 * 文件夹和设备共用的紧凑条目交互，沿用面板选择、焦点、右键与双击含义。
 *
 * @param id 当前条目的稳定身份。
 * @param model 当前面板视图状态。
 * @param actions 组件事件入口。
 * @param onOpen 双击打开动作。
 * @param modifier 由详情或画廊提供的布局约束。
 * @param content 条目内容。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun ComputerEntrySurface(
    id: String,
    model: PaneEntriesViewModel,
    actions: PaneEntriesActions,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val selected = id in model.selectedEntryIds
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    var additive by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf(false) }
    val background =
        when {
            selected && model.paneActive ->
                appearance.selectionBackground ?: palette.selectionBackground
            selected ->
                appearance.inactiveSelectionBackground ?: palette.inactiveSelectionBackground
            hovered -> palette.rowHoverBackground
            else -> Color.Transparent
        }
    Box(
        modifier
            .background(background)
            .hoverable(interactions)
            .onGloballyPositioned { origin = it.windowBounds().topLeft }
            .onPointerEvent(PointerEventType.Press) { event ->
                additive =
                    event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                range = event.keyboardModifiers.isShiftPressed
                if (event.buttons.isSecondaryPressed) {
                    actions.onActivate()
                    actions.onShowContextMenu(
                        id,
                        selected,
                        origin + event.changes.first().position.toIntOffset(),
                    )
                }
            }
            .combinedClickable(
                onClick = {
                    actions.onActivate()
                    actions.onSelectEntry(id, additive, range)
                },
                onDoubleClick = {
                    actions.onActivate()
                    onOpen()
                },
            )
            .semantics { this.selected = selected }
            .testTag("computer-entry-${model.paneId}-$id"),
        content = content,
    )
}
