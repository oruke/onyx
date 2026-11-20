package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.TabDropZone
import com.oruke.onyx.ui.theme.toIntOffset
import com.oruke.onyx.ui.theme.windowBounds
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_close_other_tabs
import onyx.composeapp.generated.resources.action_close_tab
import onyx.composeapp.generated.resources.action_close_tabs_to_right
import onyx.composeapp.generated.resources.action_copy_path
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
    val location: String = "",
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
    /** 请求展示当前标签的右键菜单（参数为窗口坐标锚点）。 */
    val onContextMenu: (IntOffset) -> Unit,
    /** 在指定窗口坐标完成标签放置。 */
    val onDropTab: (IntOffset) -> Unit,
    /** 更新标签拖拽窗口坐标。 */
    val onDragPositionChange: (IntOffset) -> Unit,
    /** 结束标签拖拽。 */
    val onDragEnd: () -> Unit,
    /** 上报标签窗口边界。 */
    val onBoundsChanged: (IntRect) -> Unit,
    /** 芯片卸载时清理边界缓存。 */
    val onDisposed: () -> Unit,
)

/** 标签右键菜单锚点与目标标签状态。 */
private data class TabContextMenuAnchor(
    val tabId: String,
    val index: Int,
    val windowPosition: IntOffset,
)

/**
 * 面板标签栏：支持中键关闭、右键菜单（关闭/关闭其他/关闭右侧/复制路径）、切换标签自动滚入可见、
 * 零布局抖动的叠加式拖拽插入线，以及标签卸载后的 `tabBounds` 边界自动清理。
 */
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
    onCloseOtherTabs: (String) -> Unit = {},
    onCloseTabsToRight: (String) -> Unit = {},
    onCopyTabPath: (String) -> Unit = {},
) {
    val palette = LocalOnyxPalette.current
    val scrollState = rememberScrollState()
    var barBounds by remember { mutableStateOf<IntRect?>(null) }
    val tabBounds = remember { mutableStateMapOf<String, IntRect>() }
    var contextMenuAnchor by remember { mutableStateOf<TabContextMenuAnchor?>(null) }

    val currentTabs by rememberUpdatedState(state.tabs)
    val currentOnDropZoneChange by rememberUpdatedState(onDropZoneChange)

    fun reportDropZone() {
        val currentBarBounds = barBounds ?: return
        val validIds = currentTabs.map { it.id }
        val validIdSet = validIds.toSet()
        tabBounds.keys.retainAll(validIdSet)
        currentOnDropZoneChange(
            TabDropZone(
                bounds = currentBarBounds,
                tabIds = validIds,
                tabBounds = tabBounds.toMap(),
            ),
        )
    }

    // 标签集合变动时同步清理陈旧边界并上报最新热区
    LaunchedEffect(state.tabs) {
        val validIds = state.tabs.mapTo(mutableSetOf()) { it.id }
        if (tabBounds.keys.retainAll(validIds)) {
            reportDropZone()
        }
        if (contextMenuAnchor != null && contextMenuAnchor?.tabId !in validIds) {
            contextMenuAnchor = null
        }
    }

    // 活动标签溢出可视区（含溢出态刚新建并激活的尾部标签）时，等待布局测量完毕后自动平滑滚动到可见范围
    LaunchedEffect(state.activeTabId, state.tabs.size, barBounds) {
        val bar = barBounds ?: return@LaunchedEffect
        var activeBounds = snapshotFlow { tabBounds[state.activeTabId] }
            .filterNotNull()
            .first()
        var delta = when {
            activeBounds.left < bar.left -> activeBounds.left - bar.left - 8
            activeBounds.right > bar.right -> activeBounds.right - bar.right + 8
            else -> 0
        }
        if (delta > 0 && scrollState.maxValue < scrollState.value + delta) {
            // 新建标签刚加入 Row 时等一帧确保 horizontalScroll 同步更新 maxValue 与最新边界
            withFrameNanos {}
            activeBounds = tabBounds[state.activeTabId] ?: activeBounds
            delta = when {
                activeBounds.left < bar.left -> activeBounds.left - bar.left - 8
                activeBounds.right > bar.right -> activeBounds.right - bar.right + 8
                else -> 0
            }
        }
        if (delta != 0) {
            val target = (scrollState.value + delta).coerceIn(0, scrollState.maxValue)
            scrollState.animateScrollTo(target)
        }
    }

    val indicatorColor = palette.accent
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(LocalOnyxAppearance.current.tabBarHeight)
            .background(palette.headerBackground)
            .onGloballyPositioned { coordinates ->
                barBounds = coordinates.windowBounds()
                reportDropZone()
            }
            .drawWithContent {
                drawContent()
                val indicatorIdx = dropIndicatorIndex
                val bar = barBounds
                if (indicatorIdx != null && bar != null && state.tabs.isNotEmpty()) {
                    val lineWindowX = resolveTabDropIndicatorWindowX(
                        tabs = state.tabs,
                        tabBounds = tabBounds,
                        dropIndicatorIndex = indicatorIdx,
                        barBounds = bar,
                    )
                    if (lineWindowX != null) {
                        val localX = (lineWindowX - bar.left).toFloat()
                            .coerceIn(1.dp.toPx(), (size.width - 2.dp.toPx()).coerceAtLeast(1.dp.toPx()))
                        val verticalPad = 3.dp.toPx()
                        val lineWidth = 2.dp.toPx()
                        drawRoundRect(
                            color = indicatorColor,
                            topLeft = Offset(localX - lineWidth / 2f, verticalPad),
                            size = Size(lineWidth, (size.height - verticalPad * 2f).coerceAtLeast(2f)),
                            cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
                        )
                    }
                }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .horizontalScroll(scrollState)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            state.tabs.forEachIndexed { index, tab ->
                key(tab.id) {
                    PaneTabChip(
                        state = PaneTabChipState(
                            id = tab.id,
                            title = desktopLocationLabel(tab.title),
                            selected = tab.id == state.activeTabId,
                            closeEnabled = state.tabs.size > 1,
                            activePane = active,
                        ),
                        actions = PaneTabChipActions(
                            onActivate = onActivate,
                            onSelect = { onSelectTab(tab.id) },
                            onClose = { onCloseTab(tab.id) },
                            onContextMenu = { anchor ->
                                onActivate()
                                contextMenuAnchor = TabContextMenuAnchor(
                                    tabId = tab.id,
                                    index = index,
                                    windowPosition = anchor,
                                )
                            },
                            onDropTab = { position -> onDropTab(tab.id, position) },
                            onDragPositionChange = onDragPositionChange,
                            onDragEnd = onDragEnd,
                            onBoundsChanged = { bounds ->
                                tabBounds[tab.id] = bounds
                                reportDropZone()
                            },
                            onDisposed = {
                                if (tabBounds.remove(tab.id) != null) {
                                    reportDropZone()
                                }
                            },
                        ),
                    )
                }
            }

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

        contextMenuAnchor?.let { anchor ->
            val currentIndex = state.tabs.indexOfFirst { it.id == anchor.tabId }
            if (currentIndex >= 0) {
                TabContextMenuPopup(
                    anchor = anchor.windowPosition,
                    canCloseCurrent = state.tabs.size > 1,
                    canCloseOthers = state.tabs.size > 1,
                    canCloseToRight = currentIndex < state.tabs.lastIndex,
                    onCloseCurrent = {
                        contextMenuAnchor = null
                        onCloseTab(anchor.tabId)
                    },
                    onCloseOthers = {
                        contextMenuAnchor = null
                        onCloseOtherTabs(anchor.tabId)
                    },
                    onCloseToRight = {
                        contextMenuAnchor = null
                        onCloseTabsToRight(anchor.tabId)
                    },
                    onCopyPath = {
                        contextMenuAnchor = null
                        onCopyTabPath(anchor.tabId)
                    },
                    onDismiss = { contextMenuAnchor = null },
                )
            }
        }
    }
}

/**
 * 计算叠加绘制的标签拖拽插入线在窗口坐标系中的 X 坐标，不占据水平布局流从而彻底消除拖拽落点抖动。
 */
internal fun resolveTabDropIndicatorWindowX(
    tabs: List<PaneTabItemState>,
    tabBounds: Map<String, IntRect>,
    dropIndicatorIndex: Int,
    barBounds: IntRect,
): Int? {
    if (tabs.isEmpty()) return null
    val clamped = dropIndicatorIndex.coerceIn(0, tabs.size)
    return when {
        clamped == 0 -> tabBounds[tabs.first().id]?.left ?: (barBounds.left + 4)
        clamped >= tabs.size -> tabBounds[tabs.last().id]?.right ?: (barBounds.right - 4)
        else -> {
            val leftBounds = tabBounds[tabs[clamped - 1].id]
            val rightBounds = tabBounds[tabs[clamped].id]
            when {
                leftBounds != null && rightBounds != null -> (leftBounds.right + rightBounds.left) / 2
                rightBounds != null -> rightBounds.left
                leftBounds != null -> leftBounds.right
                else -> null
            }
        }
    }
}

/**
 * 渲染标签右键菜单弹窗（关闭标签 / 关闭其他标签 / 关闭右侧标签 / 复制路径）。
 */
@Composable
private fun TabContextMenuPopup(
    anchor: IntOffset,
    canCloseCurrent: Boolean,
    canCloseOthers: Boolean,
    canCloseToRight: Boolean,
    onCloseCurrent: () -> Unit,
    onCloseOthers: () -> Unit,
    onCloseToRight: () -> Unit,
    onCopyPath: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val positionProvider = remember(anchor) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val maxX = (windowSize.width - popupContentSize.width - 8).coerceAtLeast(0)
                val maxY = (windowSize.height - popupContentSize.height - 8).coerceAtLeast(0)
                return IntOffset(
                    x = anchor.x.coerceIn(0, maxX),
                    y = anchor.y.coerceIn(0, maxY),
                )
            }
        }
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .testTag("tab-context-menu")
                .widthIn(min = 168.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(palette.floatingSurface)
                .border(1.dp, palette.outline, RoundedCornerShape(6.dp))
                .padding(vertical = 4.dp),
        ) {
            TabContextMenuItem(
                label = stringResource(Res.string.action_close_tab),
                enabled = canCloseCurrent,
                onClick = onCloseCurrent,
            )
            TabContextMenuItem(
                label = stringResource(Res.string.action_close_other_tabs),
                enabled = canCloseOthers,
                onClick = onCloseOthers,
            )
            TabContextMenuItem(
                label = stringResource(Res.string.action_close_tabs_to_right),
                enabled = canCloseToRight,
                onClick = onCloseToRight,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .height(1.dp)
                    .background(palette.outlineVariant),
            )
            TabContextMenuItem(
                label = stringResource(Res.string.action_copy_path),
                enabled = true,
                onClick = onCopyPath,
            )
        }
    }
}

@Composable
private fun TabContextMenuItem(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (enabled && hovered) palette.rowHoverBackground else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (enabled) palette.foreground else palette.disabledForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PaneTabChip(
    state: PaneTabChipState,
    actions: PaneTabChipActions,
) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var dragPosition by remember { mutableStateOf<IntOffset?>(null) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val currentActions by rememberUpdatedState(actions)
    val currentCloseEnabled by rememberUpdatedState(state.closeEnabled)
    val background = when {
        state.selected && state.activePane -> LocalOnyxPalette.current.surface
        state.selected -> LocalOnyxPalette.current.surfaceVariant
        isHovered -> LocalOnyxPalette.current.titleBarHoverBackground
        else -> Color.Transparent
    }

    DisposableEffect(state.id) {
        onDispose {
            currentActions.onDisposed()
        }
    }

    OnyxTooltip(text = state.title) {
        Row(
            modifier = Modifier
                .testTag("pane-tab-chip-${state.id}")
                .fillMaxHeight()
                .widthIn(max = 148.dp)
                .onGloballyPositioned { layoutCoordinates ->
                    coordinates = layoutCoordinates
                    currentActions.onBoundsChanged(layoutCoordinates.windowBounds())
                }
                .background(background, RoundedCornerShape(4.dp))
                .border(
                    width = 1.dp,
                    color = if (state.selected) LocalOnyxPalette.current.outline else Color.Transparent,
                    shape = RoundedCornerShape(4.dp),
                )
                .onPointerEvent(PointerEventType.Press) { event ->
                    when {
                        event.buttons.isTertiaryPressed -> {
                            if (currentCloseEnabled) {
                                currentActions.onActivate()
                                currentActions.onClose()
                            }
                        }

                        event.buttons.isSecondaryPressed -> {
                            val pointerPos = event.changes.firstOrNull()?.position ?: Offset.Zero
                            val windowPos = coordinates?.localToWindow(pointerPos)?.toIntOffset() ?: IntOffset.Zero
                            currentActions.onContextMenu(windowPos)
                        }
                    }
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        currentActions.onActivate()
                        currentActions.onSelect()
                    },
                )
                .pointerInput(state.id) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragPosition = coordinates?.localToWindow(offset)?.toIntOffset()
                            dragPosition?.let(currentActions.onDragPositionChange)
                        },
                        onDragCancel = {
                            dragPosition = null
                            currentActions.onDragEnd()
                        },
                        onDragEnd = {
                            dragPosition?.let(currentActions.onDropTab)
                            dragPosition = null
                            currentActions.onDragEnd()
                        },
                        onDrag = { change, _ ->
                            dragPosition = coordinates?.localToWindow(change.position)?.toIntOffset()
                            dragPosition?.let(currentActions.onDragPositionChange)
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
                                    currentActions.onActivate()
                                    currentActions.onClose()
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
