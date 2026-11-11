package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.SidebarTreeState
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.SidebarSectionKey
import com.oruke.onyx.core.model.SystemQuickAccessLocation
import com.oruke.onyx.core.model.isTrashLocation
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_edit_connection
import onyx.composeapp.generated.resources.action_new_connection
import onyx.composeapp.generated.resources.label_remote_connection_empty
import onyx.composeapp.generated.resources.label_sidebar_empty_favorites
import onyx.composeapp.generated.resources.label_sidebar_empty_recent
import onyx.composeapp.generated.resources.label_sidebar_section_connections
import onyx.composeapp.generated.resources.label_sidebar_section_favorites
import onyx.composeapp.generated.resources.label_sidebar_section_recent
import onyx.composeapp.generated.resources.label_sidebar_section_tree
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** 侧边栏渲染状态。 */
internal data class PaneSidebarState(
    /** 当前激活位置。 */
    val location: String,
    /** 操作系统文件管理器提供的快速访问位置。 */
    val systemQuickAccessLocations: List<SystemQuickAccessLocation>,
    /** 收藏位置。 */
    val favoriteLocations: List<String>,
    /** 最近位置。 */
    val recentLocations: List<String>,
    /** 已保存网络位置。 */
    val remoteConnections: List<RemoteConnectionProfile>,
    /** 目录树状态。 */
    val treeState: SidebarTreeState,
    /** 是否展示目录树。 */
    val showTree: Boolean,
    /** 已折叠的侧边栏分组。 */
    val collapsedSections: Set<SidebarSectionKey>,
)

/** 侧边栏用户操作集合。 */
internal data class PaneSidebarActions(
    /** 位置显示名称解析器。 */
    val locationLabel: (String) -> String,
    /** 激活侧边栏所属面板。 */
    val onActivate: () -> Unit,
    /** 打开位置。 */
    val onOpenLocation: (String) -> Unit,
    /** 切换位置收藏状态。 */
    val onToggleFavoriteLocation: (String) -> Unit,
    /** 新建网络位置。 */
    val onNewRemoteConnection: () -> Unit,
    /** 编辑网络位置。 */
    val onEditRemoteConnection: (RemoteConnectionProfile) -> Unit,
    /** 展开或折叠目录树节点。 */
    val onToggleTreeNode: (String) -> Unit,
    /** 重试目录树节点。 */
    val onRetryTreeNode: (String) -> Unit,
    /** 切换侧边栏分组折叠状态。 */
    val onToggleSection: (SidebarSectionKey) -> Unit,
)

/**
 * 绘制当前活动面板的系统入口、快速访问、收藏、网络位置、最近位置和目录树侧边栏。
 *
 * @param state 侧边栏渲染状态。
 * @param actions 侧边栏用户操作。
 * @param activePane 当前活动面板标识。
 * @param dragBindings 可选拖放绑定，用于为回收站入口注册文件放置热区。
 */
@Composable
internal fun PaneSidebar(
    state: PaneSidebarState,
    actions: PaneSidebarActions,
    activePane: PaneId = PaneId.PRIMARY,
    dragBindings: PaneDragBindings? = null,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .width(184.dp)
            .fillMaxHeight()
            .background(LocalOnyxPalette.current.surfaceVariant)
            .verticalScroll(scrollState)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SidebarSystemLocations(
            state = state,
            actions = actions,
            activePane = activePane,
            dragBindings = dragBindings,
        )
        SidebarQuickAccess(state, actions)
        SidebarFavorites(state, actions)
        SidebarConnections(state, actions)
        SidebarRecentLocations(state, actions)
        if (state.showTree) SidebarDirectoryTree(state, actions)
    }
}

/** 绘制收藏位置区。 */
@Composable
private fun SidebarFavorites(state: PaneSidebarState, actions: PaneSidebarActions) {
    SidebarSection(
        section = SidebarSectionKey.FAVORITES,
        collapsed = state.isSectionCollapsed(SidebarSectionKey.FAVORITES),
        onToggle = actions.onToggleSection,
        title = stringResource(Res.string.label_sidebar_section_favorites),
    ) {
        if (state.favoriteLocations.isEmpty()) {
            SidebarEmptyState(text = stringResource(Res.string.label_sidebar_empty_favorites))
        } else {
            state.favoriteLocations.forEach { favoriteLocation ->
                SidebarLocationItem(
                    label = desktopLocationLabel(actions.locationLabel(favoriteLocation)),
                    selected = state.location == favoriteLocation ||
                        (isTrashLocation(favoriteLocation) && isTrashLocation(state.location)),
                    favorite = true,
                    iconKey = if (isTrashLocation(favoriteLocation)) AllIconsKeys.General.Delete else null,
                    onOpen = { actions.activateAndOpen(favoriteLocation) },
                    onToggleFavorite = { actions.onToggleFavoriteLocation(favoriteLocation) },
                )
            }
        }
    }
}

/** 绘制网络位置区。 */
@Composable
private fun SidebarConnections(state: PaneSidebarState, actions: PaneSidebarActions) {
    SidebarSection(
        section = SidebarSectionKey.CONNECTIONS,
        collapsed = state.isSectionCollapsed(SidebarSectionKey.CONNECTIONS),
        onToggle = actions.onToggleSection,
        title = stringResource(Res.string.label_sidebar_section_connections),
        actionIcon = AllIconsKeys.General.Add,
        actionContentDescription = stringResource(Res.string.action_new_connection),
        onAction = {
            actions.onActivate()
            actions.onNewRemoteConnection()
        },
    ) {
        if (state.remoteConnections.isEmpty()) {
            SidebarEmptyState(text = stringResource(Res.string.label_remote_connection_empty))
        } else {
            state.remoteConnections.forEach { connection ->
                SidebarLocationItem(
                    label = connection.name,
                    selected = state.location == connection.location,
                    favorite = state.favoriteLocations.contains(connection.location),
                    iconKey = AllIconsKeys.General.OpenDisk,
                    onOpen = { actions.activateAndOpen(connection.location) },
                    onToggleFavorite = { actions.onToggleFavoriteLocation(connection.location) },
                    onEdit = {
                        actions.onActivate()
                        actions.onEditRemoteConnection(connection)
                    },
                )
            }
        }
    }
}

/** 绘制最近位置区。 */
@Composable
private fun SidebarRecentLocations(state: PaneSidebarState, actions: PaneSidebarActions) {
    val visibleLocations = state.recentLocations.filterNot { location -> location == state.location }
    SidebarSection(
        section = SidebarSectionKey.RECENT,
        collapsed = state.isSectionCollapsed(SidebarSectionKey.RECENT),
        onToggle = actions.onToggleSection,
        title = stringResource(Res.string.label_sidebar_section_recent),
    ) {
        if (visibleLocations.isEmpty()) {
            SidebarEmptyState(text = stringResource(Res.string.label_sidebar_empty_recent))
        } else {
            visibleLocations.forEach { recentLocation ->
                SidebarLocationItem(
                    label = desktopLocationLabel(actions.locationLabel(recentLocation)),
                    selected = false,
                    favorite = state.favoriteLocations.contains(recentLocation),
                    iconKey = if (isTrashLocation(recentLocation)) AllIconsKeys.General.Delete else null,
                    onOpen = { actions.activateAndOpen(recentLocation) },
                    onToggleFavorite = { actions.onToggleFavoriteLocation(recentLocation) },
                )
            }
        }
    }
}

/** 绘制可展开目录树区。 */
@Composable
private fun SidebarDirectoryTree(state: PaneSidebarState, actions: PaneSidebarActions) {
    SidebarSection(
        section = SidebarSectionKey.TREE,
        collapsed = state.isSectionCollapsed(SidebarSectionKey.TREE),
        onToggle = actions.onToggleSection,
        title = stringResource(Res.string.label_sidebar_section_tree),
    ) {
        SidebarTree(
            selectedLocation = state.location,
            treeState = state.treeState,
            onOpenLocation = actions::activateAndOpen,
            onToggleNode = actions.onToggleTreeNode,
            onRetryNode = actions.onRetryTreeNode,
        )
    }
}

/**
 * 激活所属面板后打开位置。
 *
 * @param location 目标位置。
 */
internal fun PaneSidebarActions.activateAndOpen(location: String) {
    onActivate()
    onOpenLocation(location)
}

private fun PaneSidebarState.isSectionCollapsed(section: SidebarSectionKey): Boolean =
    section in collapsedSections

/**
 * 绘制带折叠开关和可选尾部动作按钮的侧栏分区。
 */
@Composable
internal fun SidebarSection(
    section: SidebarSectionKey,
    collapsed: Boolean,
    onToggle: (SidebarSectionKey) -> Unit,
    title: String,
    actionIcon: IconKey? = null,
    actionContentDescription: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val headerHoverSrc = remember { MutableInteractionSource() }
    val headerClickSrc = remember { MutableInteractionSource() }
    val headerHovered by headerHoverSrc.collectIsHoveredAsState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .hoverable(headerHoverSrc)
                .background(
                    if (headerHovered) LocalOnyxPalette.current.rowHoverBackground else Color.Transparent,
                    RoundedCornerShape(4.dp),
                )
                .clickable(
                    interactionSource = headerClickSrc,
                    indication = null,
                    onClick = { onToggle(section) },
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                key = if (collapsed) AllIconsKeys.General.ArrowRight else AllIconsKeys.General.ArrowDown,
                contentDescription = null,
                modifier = Modifier.size(10.dp),
            )
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                fontSize = 11.sp,
                color = if (headerHovered) {
                    LocalOnyxPalette.current.foreground
                } else {
                    LocalOnyxPalette.current.mutedForeground
                },
                fontWeight = FontWeight.Medium,
            )
            if (actionIcon != null && onAction != null) {
                IconButton(onClick = onAction) {
                    Icon(
                        key = actionIcon,
                        contentDescription = actionContentDescription,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        if (!collapsed) content()
    }
}

/**
 * 绘制侧栏分区的空状态说明文本。
 */
@Composable
internal fun SidebarEmptyState(
    text: String,
) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = LocalOnyxPalette.current.disabledForeground,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * 绘制可打开、收藏并按需编辑的单个侧栏位置。
 *
 * @param label 位置显示名称。
 * @param selected 是否为当前打开位置。
 * @param favorite 是否已加入应用收藏。
 * @param iconKey 可选位置图标。
 * @param onOpen 打开位置回调。
 * @param onToggleFavorite 切换收藏状态回调。
 * @param onEdit 可选编辑回调。
 * @param modifier 统一控件的外部布局与语义修饰符。
 * @param leadingIcon 可选图标渲染入口，尺寸仍由公共侧栏控件统一提供。
 */
@Composable
internal fun SidebarLocationItem(
    label: String,
    selected: Boolean,
    favorite: Boolean,
    iconKey: IconKey? = null,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEdit: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable (Modifier) -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .background(
                when {
                    selected -> LocalOnyxPalette.current.selectionBackground
                    isHovered -> LocalOnyxPalette.current.rowHoverBackground
                    else -> Color.Transparent
                },
                RoundedCornerShape(4.dp),
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpen)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leadingIcon != null) {
            leadingIcon(Modifier.size(14.dp))
        } else {
            Icon(
                key = iconKey ?: AllIconsKeys.Nodes.Folder,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 11.sp,
            color = LocalOnyxPalette.current.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (favorite) "★" else "☆",
            fontSize = 11.sp,
            color = if (favorite) {
                LocalOnyxPalette.current.favorite
            } else {
                LocalOnyxPalette.current.disabledForeground
            },
            modifier = Modifier.clickable(onClick = onToggleFavorite),
        )
        if (onEdit != null) {
            IconButton(onClick = onEdit) {
                Icon(
                    key = AllIconsKeys.Actions.Edit,
                    contentDescription = stringResource(Res.string.action_edit_connection),
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}
