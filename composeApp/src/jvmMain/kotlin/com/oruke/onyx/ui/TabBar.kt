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
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.ui.theme.OnyxPalette
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

@Composable
internal fun PaneTabBar(
    state: PaneState,
    active: Boolean,
    palette: OnyxPalette,
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
            .height(26.dp)
            .background(palette.headerBackground)
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
                palette = palette,
            )
            PaneTabChip(
                tabId = tab.id,
                title = tab.title,
                selected = tab.id == state.activeTabId,
                closeEnabled = state.tabs.size > 1,
                activePane = active,
                palette = palette,
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
            )
        }
        TabDropIndicator(
            visible = dropIndicatorIndex == state.tabs.size,
            palette = palette,
        )

        val newTabTooltip = stringResource(Res.string.action_new_tab)
        OnyxTooltip(text = newTabTooltip, palette = palette) {
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
    palette: OnyxPalette,
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
                    .background(palette.accent, RoundedCornerShape(1.dp)),
            )
        }
    }
}

@Composable
internal fun PaneTabChip(
    tabId: String,
    title: String,
    selected: Boolean,
    closeEnabled: Boolean,
    activePane: Boolean,
    palette: OnyxPalette,
    onActivate: () -> Unit,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onDropTab: (IntOffset) -> Unit,
    onDragPositionChange: (IntOffset) -> Unit,
    onDragEnd: () -> Unit,
    onBoundsChanged: (IntRect) -> Unit,
) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var dragPosition by remember { mutableStateOf<IntOffset?>(null) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val background = when {
        selected && activePane -> palette.surface
        selected -> palette.surfaceVariant
        isHovered -> palette.titleBarHoverBackground
        else -> Color.Transparent
    }

    OnyxTooltip(text = title, palette = palette) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 148.dp)
                .onGloballyPositioned { layoutCoordinates ->
                    coordinates = layoutCoordinates
                    onBoundsChanged(layoutCoordinates.windowBounds())
                }
                .background(background, RoundedCornerShape(4.dp))
                .border(
                    width = 1.dp,
                    color = if (selected) palette.outline else Color.Transparent,
                    shape = RoundedCornerShape(4.dp),
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        onActivate()
                        onSelect()
                    },
                )
                .pointerInput(tabId) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragPosition = coordinates?.localToWindow(offset)?.toIntOffset()
                            dragPosition?.let(onDragPositionChange)
                        },
                        onDragCancel = {
                            dragPosition = null
                            onDragEnd()
                        },
                        onDragEnd = {
                            dragPosition?.let(onDropTab)
                            dragPosition = null
                            onDragEnd()
                        },
                        onDrag = { change, _ ->
                            dragPosition = coordinates?.localToWindow(change.position)?.toIntOffset()
                            dragPosition?.let(onDragPositionChange)
                        },
                    )
                }
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.widthIn(max = if (closeEnabled) 112.dp else 132.dp),
                fontSize = 12.sp,
                color = if (selected) palette.foreground else palette.mutedForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (closeEnabled) {
                val closeTabTooltip = stringResource(Res.string.action_close_tab)
                OnyxTooltip(text = closeTabTooltip, palette = palette) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    onActivate()
                                    onClose()
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

