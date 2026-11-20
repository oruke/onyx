package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * 向统一侧边栏 [LazyListScope] 写入系统级虚拟入口（此电脑与回收站），
 * 并确保回收站行滚出视口或侧边栏卸载时自动清理其拖放热区。
 */
internal fun LazyListScope.sidebarSystemLocationItems(
    state: PaneSidebarState,
    actions: PaneSidebarActions,
    activePane: PaneId,
    dragBindings: PaneDragBindings?,
    onContextMenu: (SidebarContextMenuRequest) -> Unit,
) {
    item(key = "system:computer") {
        val computerFav = COMPUTER_LOCATION in state.favoriteLocations
        SidebarLocationItem(
            label = desktopComputerLabel(),
            selected = isComputerLocation(state.location),
            favorite = computerFav,
            iconKey = AllIconsKeys.General.OpenDisk,
            modifier = Modifier.testTag("sidebar-computer"),
            leadingIcon = { StorageDeviceIcon(modifier = it) },
            onOpen = { actions.activateAndOpen(COMPUTER_LOCATION) },
            onToggleFavorite = { actions.onToggleFavoriteLocation(COMPUTER_LOCATION) },
            onContextMenu = { anchor ->
                onContextMenu(
                    SidebarContextMenuRequest(
                        location = COMPUTER_LOCATION,
                        favorite = computerFav,
                        anchorWindowPosition = anchor,
                    ),
                )
            },
        )
    }
    item(key = "system:trash") {
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
        val trashFav = TRASH_LOCATION in state.favoriteLocations
        SidebarLocationItem(
            label = desktopTrashLabel(),
            selected = isTrashLocation(state.location),
            favorite = trashFav,
            iconKey = AllIconsKeys.General.Delete,
            modifier = trashDropModifier,
            onOpen = { actions.activateAndOpen(TRASH_LOCATION) },
            onToggleFavorite = { actions.onToggleFavoriteLocation(TRASH_LOCATION) },
            onContextMenu = { anchor ->
                onContextMenu(
                    SidebarContextMenuRequest(
                        location = TRASH_LOCATION,
                        favorite = trashFav,
                        anchorWindowPosition = anchor,
                    ),
                )
            },
        )
    }
}
