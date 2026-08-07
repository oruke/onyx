package com.oruke.onyx.ui

import androidx.compose.runtime.Composable
import com.oruke.onyx.core.model.SystemQuickAccessLocation
import com.oruke.onyx.core.model.SidebarSectionKey
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_home
import onyx.composeapp.generated.resources.label_sidebar_section_quick_access
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * 绘制操作系统文件管理器提供的快速访问位置。
 *
 * @param state 侧栏渲染状态。
 * @param actions 侧栏用户操作集合。
 */
@Composable
internal fun SidebarQuickAccess(
    state: PaneSidebarState,
    actions: PaneSidebarActions,
) {
    SidebarSection(
        section = SidebarSectionKey.QUICK_ACCESS,
        collapsed = SidebarSectionKey.QUICK_ACCESS in state.collapsedSections,
        onToggle = actions.onToggleSection,
        title = stringResource(Res.string.label_sidebar_section_quick_access),
    ) {
        state.systemQuickAccessLocations.forEach { quickAccessLocation ->
            QuickAccessLocationItem(
                quickAccessLocation = quickAccessLocation,
                state = state,
                actions = actions,
            )
        }
    }
}

/**
 * 绘制单个系统快速访问位置，并复用统一收藏操作。
 *
 * @param quickAccessLocation 系统快速访问位置。
 * @param state 侧栏渲染状态。
 * @param actions 侧栏用户操作集合。
 */
@Composable
private fun QuickAccessLocationItem(
    quickAccessLocation: SystemQuickAccessLocation,
    state: PaneSidebarState,
    actions: PaneSidebarActions,
) {
    val location = quickAccessLocation.location
    val label = if (quickAccessLocation.isHome) {
        stringResource(Res.string.label_home)
    } else {
        quickAccessLocation.displayName?.takeIf(String::isNotBlank) ?: actions.locationLabel(location)
    }
    SidebarLocationItem(
        label = label,
        selected = state.location == location,
        favorite = state.favoriteLocations.contains(location),
        iconKey = if (quickAccessLocation.isHome) {
            AllIconsKeys.Nodes.HomeFolder
        } else {
            AllIconsKeys.Nodes.Folder
        },
        onOpen = { actions.activateAndOpen(location) },
        onToggleFavorite = { actions.onToggleFavoriteLocation(location) },
    )
}
