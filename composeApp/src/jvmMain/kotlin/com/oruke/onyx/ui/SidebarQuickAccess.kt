package com.oruke.onyx.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * 向统一侧边栏 [LazyListScope] 写入操作系统快速访问位置列表。
 */
internal fun LazyListScope.sidebarQuickAccessItems(
    state: PaneSidebarState,
    actions: PaneSidebarActions,
    onContextMenu: (SidebarContextMenuRequest) -> Unit,
) {
    items(
        items = state.systemQuickAccessLocations,
        key = { item -> "quick:${item.location}" },
    ) { quickAccessLocation ->
        val location = quickAccessLocation.location
        val label = systemPlaceLabel(quickAccessLocation, actions.locationLabel(location))
        val isFav = state.favoriteLocations.contains(location)
        SidebarLocationItem(
            label = label,
            selected = state.location == location,
            favorite = isFav,
            iconKey = if (quickAccessLocation.isHome) {
                AllIconsKeys.Nodes.HomeFolder
            } else {
                AllIconsKeys.Nodes.Folder
            },
            onOpen = { actions.activateAndOpen(location) },
            onToggleFavorite = { actions.onToggleFavoriteLocation(location) },
            onContextMenu = { anchor ->
                onContextMenu(
                    SidebarContextMenuRequest(
                        location = location,
                        favorite = isFav,
                        anchorWindowPosition = anchor,
                    ),
                )
            },
        )
    }
}
