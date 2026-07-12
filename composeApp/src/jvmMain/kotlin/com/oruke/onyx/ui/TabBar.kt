package com.oruke.onyx.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.TabDropZone
import com.oruke.onyx.ui.theme.toIntOffset
import com.oruke.onyx.ui.theme.windowBounds
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_close_tab
import onyx.composeapp.generated.resources.action_new_tab
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

internal data class PaneTabBarState(
    val activeTabId: String,
    val tabs: List<PaneTabItemState>,
)

internal data class PaneTabItemState(
    val id: String,
    val title: String,
)

/**
 * 单个标签项的显示状态。
 */
private data class PaneTabChipState(
    /** 标签唯一标识。 */
    val id: String,
    /** 标签显示标题。 */
    val title: String,
    /** 标签是否被选中。 */
    val selected: Boolean,
    /** 是否允许关闭标签。 */
    val closeEnabled: Boolean,
    /** 标签所属面板是否为活动面板。 */
    val activePane: Boolean,
)

/**
 * 单个标签项的交互回调。
 */
private data class PaneTabChipActions(
    /** 激活标签所属面板。 */
    val onActivate: () -> Unit,
    /** 选中当前标签。 */
    val onSelect: () -> Unit,
    /** 关闭当前标签。 */
    val onClose: () -> Unit,
    /** 在指定窗口坐标完成标签放置。 */
    val onDropTab: (IntOffset) -> Unit,
    /** 更新标签拖拽窗口坐标。 */
    val onDragPositionChange: (IntOffset) -> Unit,
    /** 结束标签拖拽。 */
    val onDragEnd: () -> Unit,
    /** 上报标签窗口边界。 */
    val onBoundsChanged: (IntRect) -> Unit,
)

@Composable
internal fun PaneTabBar(
    state: PaneTabBarState,
    active: Boolean,
    onActivate: () -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCreateTab: () -> Unit,
    onDropTab: (String, IntOffset) -> Unit,
    onDragPositionChange: (IntOffset) -> Unit,
    onDragEnd: () -> Unit,
    onDropZoneChange: (TabDropZone) -> Unit,
    dropIndicatorIndex: Int?,
) {
    val scrollState = rememberScrollState()
    var barBounds by remember { mutableStateOf<IntRect?>(null) }
    val tabBounds = remember { mutableStateMapOf<String, IntRect>() }

    fun reportDropZone() {
        val currentBarBounds = barBounds ?: return
        onDropZoneChange(
            TabDropZone(
                bounds = currentBarBounds,
                tabIds = state.tabs.map { it.id },
                tabBounds = tabBounds.toMap(),
            )
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LocalOnyxAppearance.current.tabBarHeight)
            .background(LocalOnyxPalette.current.headerBackground)
            .onGloballyPositioned { coordinates ->
                barBounds = coordinates.windowBounds()
                reportDropZone()
            }
            .horizontalScroll(scrollState)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        state.tabs.forEachIndexed { index, tab ->
            TabDropIndicator(
                visible = dropIndicatorIndex == index,
            )
            PaneTabChip(
                state = PaneTabChipState(
                    id = tab.id,
                    title = tab.title,
                    selected = tab.id == state.activeTabId,
                    closeEnabled = state.tabs.size > 1,
                    activePane = active,
                ),
                actions = PaneTabChipActions(
                    onActivate = onActivate,
                    onSelect = { onSelectTab(tab.id) },
                    onClose = { onCloseTab(tab.id) },
                    onDropTab = { position -> onDropTab(tab.id, position) },
                    onDragPositionChange = onDragPositionChange,
                    onDragEnd = onDragEnd,
                    onBoundsChanged = { bounds ->
                        tabBounds[tab.id] = bounds
                        reportDropZone()
                    },
                ),
            )
        }
        TabDropIndicator(
            visible = dropIndicatorIndex == state.tabs.size,
        )

        val newTabTooltip = stringResource(Res.string.action_new_tab)
        OnyxTooltip(text = newTabTooltip) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(24.dp)
                    .background(Color.Transparent, RoundedCornerShape(4.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            onActivate()
                            onCreateTab()
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    key = AllIconsKeys.General.Add,
                    contentDescription = newTabTooltip,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

@Composable
internal fun TabDropIndicator(
    visible: Boolean,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 120)) +
                expandHorizontally(animationSpec = tween(durationMillis = 120)),
        exit = fadeOut(animationSpec = tween(durationMillis = 90)) +
                shrinkHorizontally(animationSpec = tween(durationMillis = 90)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(8.dp)
                .padding(horizontal = 3.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(2.dp)
                    .background(LocalOnyxPalette.current.accent, RoundedCornerShape(1.dp)),
            )
        }
    }
}

@Composable
private fun PaneTabChip(
    state: PaneTabChipState,
    actions: PaneTabChipActions,
) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var dragPosition by remember { mutableStateOf<IntOffset?>(null) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val background = when {
        state.selected && state.activePane -> LocalOnyxPalette.current.surface
        state.selected -> LocalOnyxPalette.current.surfaceVariant
        isHovered -> LocalOnyxPalette.current.titleBarHoverBackground
        else -> Color.Transparent
    }

    OnyxTooltip(text = state.title) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 148.dp)
                .onGloballyPositioned { layoutCoordinates ->
                    coordinates = layoutCoordinates
                    actions.onBoundsChanged(layoutCoordinates.windowBounds())
                }
                .background(background, RoundedCornerShape(4.dp))
                .border(
                    width = 1.dp,
                    color = if (state.selected) LocalOnyxPalette.current.outline else Color.Transparent,
                    shape = RoundedCornerShape(4.dp),
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        actions.onActivate()
                        actions.onSelect()
                    },
                )
                .pointerInput(state.id) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragPosition = coordinates?.localToWindow(offset)?.toIntOffset()
                            dragPosition?.let(actions.onDragPositionChange)
                        },
                        onDragCancel = {
                            dragPosition = null
                            actions.onDragEnd()
                        },
                        onDragEnd = {
                            dragPosition?.let(actions.onDropTab)
                            dragPosition = null
                            actions.onDragEnd()
                        },
                        onDrag = { change, _ ->
                            dragPosition = coordinates?.localToWindow(change.position)?.toIntOffset()
                            dragPosition?.let(actions.onDragPositionChange)
                        },
                    )
                }
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = state.title,
                modifier = Modifier.widthIn(max = if (state.closeEnabled) 112.dp else 132.dp),
                fontSize = 12.sp,
                color = if (state.selected) {
                    LocalOnyxPalette.current.foreground
                } else {
                    LocalOnyxPalette.current.mutedForeground
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.closeEnabled) {
                val closeTabTooltip = stringResource(Res.string.action_close_tab)
                OnyxTooltip(text = closeTabTooltip) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    actions.onActivate()
                                    actions.onClose()
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            key = AllIconsKeys.Actions.Close,
                            contentDescription = closeTabTooltip,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
    }
}
