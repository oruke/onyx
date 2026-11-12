package com.oruke.onyx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import kotlin.math.roundToInt
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_empty_directory
import onyx.composeapp.generated.resources.label_empty_directory_hint
import onyx.composeapp.generated.resources.label_trash_empty_view
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** 空状态图标的弱化透明度。 */
private const val EMPTY_STATE_ICON_ALPHA = 0.4f

/**
 * 渲染空目录或空回收站占位面板，并在整块区域支持单击立即切换焦点、右键空白菜单与双击返回上级。
 *
 * @param paneId 所属面板标识。
 * @param actions 文件内容交互回调。
 * @param doubleClickEmptySpaceToNavigateUp 是否启用双击空白返回上级目录。
 * @param isTrash 当前位置是否为回收站。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun EmptyPaneEntries(
    paneId: PaneId,
    actions: PaneEntriesActions,
    doubleClickEmptySpaceToNavigateUp: Boolean = true,
    isTrash: Boolean = false,
) {
    val density = LocalDensity.current
    val currentActions = rememberUpdatedState(actions)
    val doubleClickEnabledState = rememberUpdatedState(doubleClickEmptySpaceToNavigateUp)
    val dragSlopSquared = with(density) { ENTRY_DRAG_SLOP_DP.dp.toPx().let { px -> px * px } }
    val doubleClickSlopPx = with(density) { ENTRY_DOUBLE_CLICK_SLOP_DP.dp.toPx() }
    val clickController = remember(paneId) { BlankAreaClickController() }
    var containerCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("pane-empty-entries-$paneId")
            .onGloballyPositioned { containerCoordinates = it }
            .onPointerEvent(PointerEventType.Press) { event ->
                handleEmptyPanePress(
                    event = event,
                    actions = currentActions.value,
                    clickController = clickController,
                    containerCoordinates = containerCoordinates,
                )
            }
            .onPointerEvent(PointerEventType.Release) { event ->
                handleEmptyPaneRelease(
                    event = event,
                    actions = currentActions.value,
                    clickController = clickController,
                    doubleClickEnabled = doubleClickEnabledState.value,
                    dragSlopSquared = dragSlopSquared,
                    doubleClickSlopPx = doubleClickSlopPx,
                )
            }
            .semantics {
                onClick {
                    clickController.reset()
                    currentActions.value.onActivate()
                    currentActions.value.onDismissContextMenu()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isTrash) {
                Icon(
                    key = AllIconsKeys.General.Delete,
                    contentDescription = null,
                    modifier = Modifier.alpha(EMPTY_STATE_ICON_ALPHA),
                )
                Text(
                    text = stringResource(Res.string.label_trash_empty_view),
                    modifier = Modifier.testTag("pane-trash-empty-state"),
                    color = LocalOnyxPalette.current.mutedForeground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                Icon(
                    key = AllIconsKeys.Nodes.Folder,
                    contentDescription = null,
                    modifier = Modifier.alpha(EMPTY_STATE_ICON_ALPHA),
                )
                Text(
                    text = stringResource(Res.string.label_empty_directory),
                    color = LocalOnyxPalette.current.mutedForeground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(Res.string.label_empty_directory_hint),
                    color = LocalOnyxPalette.current.disabledForeground,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/**
 * 处理空目录面板按下事件：主键按下立即激活面板并关闭菜单，副键按下激活面板并在窗口坐标弹出空白区菜单。
 *
 * @param event 当前指针事件。
 * @param actions 文件内容交互回调。
 * @param clickController 空白区域点击状态机。
 * @param containerCoordinates 空目录容器布局坐标，用于转换为窗口锚点。
 */
private fun handleEmptyPanePress(
    event: PointerEvent,
    actions: PaneEntriesActions,
    clickController: BlankAreaClickController,
    containerCoordinates: LayoutCoordinates?,
) {
    if (event.changes.any { it.isConsumed }) return
    val primaryChange = event.changes.firstOrNull() ?: return
    val position = primaryChange.position
    when {
        event.buttons.isSecondaryPressed && !event.buttons.isPrimaryPressed -> {
            clickController.reset()
            actions.onActivate()
            val windowPosition = containerCoordinates?.localToWindow(position) ?: position
            actions.onBlankAreaContextMenu(
                IntOffset(windowPosition.x.roundToInt(), windowPosition.y.roundToInt()),
            )
        }

        event.isPrimaryPointerPress() -> {
            actions.onActivate()
            actions.onDismissContextMenu()
            clickController.onPrimaryPress(primaryChange.uptimeMillis, position)
        }

        else -> clickController.reset()
    }
}

/**
 * 处理空目录面板主键松开事件，在满足桌面双击条件时返回上级目录。
 *
 * @param event 当前指针事件。
 * @param actions 文件内容交互回调。
 * @param clickController 空白区域点击状态机。
 * @param doubleClickEnabled 是否允许双击空白返回上级目录。
 * @param dragSlopSquared 单次点击允许的最大按下-松开位移平方（像素²）。
 * @param doubleClickSlopPx 两次点击构成双击的最大位移容差（像素）。
 */
private fun handleEmptyPaneRelease(
    event: PointerEvent,
    actions: PaneEntriesActions,
    clickController: BlankAreaClickController,
    doubleClickEnabled: Boolean,
    dragSlopSquared: Float,
    doubleClickSlopPx: Float,
) {
    val releaseChange = event.changes.firstOrNull() ?: return
    if (releaseChange.isConsumed) {
        clickController.reset()
        return
    }
    val isDoubleClick = clickController.onPrimaryRelease(
        releaseMillis = releaseChange.uptimeMillis,
        position = releaseChange.position,
        dragSlopSquared = dragSlopSquared,
        doubleClickSlopPx = doubleClickSlopPx,
    )
    if (doubleClickEnabled && isDoubleClick) {
        actions.onActivate()
        actions.onNavigateUp()
    }
}
