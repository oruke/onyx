package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.filesystem.isComputerLocation
import com.oruke.onyx.core.model.COMPUTER_LOCATION
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.TRASH_LOCATION
import com.oruke.onyx.core.model.isTrashLocation
import com.oruke.onyx.ui.theme.FileDropZone
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.windowBounds
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** 侧边栏回收站放置热区的固定唯一注册键。 */
private const val SIDEBAR_TRASH_DROP_ZONE_KEY = "__sidebar_trash"

/**
 * 绘制侧边栏顶部的系统级虚拟入口（此电脑与回收站），并为回收站入口维护独立拖放热区。
 *
 * @param state 侧边栏渲染状态。
 * @param actions 侧边栏用户动作。
 * @param activePane 当前活动面板标识。
 * @param dragBindings 可选拖放绑定；侧边栏隐藏卸载时自动清理回收站放置热区。
 */
@Composable
internal fun SidebarSystemLocations(
    state: PaneSidebarState,
    actions: PaneSidebarActions,
    activePane: PaneId,
    dragBindings: PaneDragBindings?,
) {
    DisposableEffect(dragBindings) {
        onDispose {
            dragBindings?.fileDropZones?.remove(SIDEBAR_TRASH_DROP_ZONE_KEY)
        }
    }

    val isTrashDropTarget = dragBindings?.fileDropTarget?.let { target ->
        target.isSidebarTarget && isTrashLocation(target.targetDirectoryLocation)
    } == true

    val trashDropModifier = Modifier
        .testTag("sidebar-trash")
        .onGloballyPositioned { coordinates ->
            if (dragBindings != null) {
                dragBindings.fileDropZones[SIDEBAR_TRASH_DROP_ZONE_KEY] = FileDropZone(
                    paneId = activePane,
                    targetDirectoryLocation = TRASH_LOCATION,
                    bounds = coordinates.windowBounds(),
                    zoneKeyOverride = SIDEBAR_TRASH_DROP_ZONE_KEY,
                    isSidebarTarget = true,
                )
            }
        }
        .let { base ->
            if (isTrashDropTarget) {
                base
                    .border(1.dp, LocalOnyxPalette.current.accent, RoundedCornerShape(4.dp))
                    .background(LocalOnyxPalette.current.accent.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
            } else {
                base
            }
        }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SidebarLocationItem(
            label = desktopComputerLabel(),
            selected = isComputerLocation(state.location),
            favorite = COMPUTER_LOCATION in state.favoriteLocations,
            iconKey = AllIconsKeys.General.OpenDisk,
            modifier = Modifier.testTag("sidebar-computer"),
            leadingIcon = { StorageDeviceIcon(modifier = it) },
            onOpen = { actions.activateAndOpen(COMPUTER_LOCATION) },
            onToggleFavorite = { actions.onToggleFavoriteLocation(COMPUTER_LOCATION) },
        )
        SidebarLocationItem(
            label = desktopTrashLabel(),
            selected = isTrashLocation(state.location),
            favorite = TRASH_LOCATION in state.favoriteLocations,
            iconKey = AllIconsKeys.General.Delete,
            modifier = trashDropModifier,
            onOpen = { actions.activateAndOpen(TRASH_LOCATION) },
            onToggleFavorite = { actions.onToggleFavoriteLocation(TRASH_LOCATION) },
        )
    }
}
