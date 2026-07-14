package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import com.oruke.onyx.app.component.SidebarTreeNode
import com.oruke.onyx.app.component.SidebarTreeNodeLoadState
import com.oruke.onyx.app.component.SidebarTreeState
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.SystemQuickAccessLocation
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_edit_connection
import onyx.composeapp.generated.resources.action_new_connection
import onyx.composeapp.generated.resources.action_refresh_active
import onyx.composeapp.generated.resources.label_remote_connection_empty
import onyx.composeapp.generated.resources.label_sidebar_empty_favorites
import onyx.composeapp.generated.resources.label_sidebar_empty_recent
import onyx.composeapp.generated.resources.label_sidebar_section_favorites
import onyx.composeapp.generated.resources.label_sidebar_section_connections
import onyx.composeapp.generated.resources.label_sidebar_section_quick_access
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
)

/**
 * 绘制当前活动面板的收藏、网络位置、最近位置和目录树侧边栏。
 *
 * @param state 侧边栏渲染状态。
 * @param actions 侧边栏用户操作。
 */
@Composable
internal fun PaneSidebar(
    state: PaneSidebarState,
    actions: PaneSidebarActions,
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
    SidebarSection(title = stringResource(Res.string.label_sidebar_section_favorites)) {
        if (state.favoriteLocations.isEmpty()) {
            SidebarEmptyState(text = stringResource(Res.string.label_sidebar_empty_favorites))
        } else {
            state.favoriteLocations.forEach { favoriteLocation ->
                SidebarLocationItem(
                    label = actions.locationLabel(favoriteLocation),
                    selected = state.location == favoriteLocation,
                    favorite = true,
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
    SidebarSection(title = stringResource(Res.string.label_sidebar_section_recent)) {
        if (visibleLocations.isEmpty()) {
            SidebarEmptyState(text = stringResource(Res.string.label_sidebar_empty_recent))
        } else {
            visibleLocations.forEach { recentLocation ->
                SidebarLocationItem(
                    label = actions.locationLabel(recentLocation),
                    selected = false,
                    favorite = state.favoriteLocations.contains(recentLocation),
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
    SidebarSection(title = stringResource(Res.string.label_sidebar_section_tree)) {
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

@Composable
internal fun SidebarSection(
    title: String,
    actionIcon: IconKey? = null,
    actionContentDescription: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                fontSize = 11.sp,
                color = LocalOnyxPalette.current.mutedForeground,
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
        content()
    }
}

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
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .background(
                when {
                    selected -> LocalOnyxPalette.current.selectionBackground
                    isHovered -> LocalOnyxPalette.current.rowHoverBackground.copy(alpha = 0.28f)
                    else -> Color.Transparent
                },
                RoundedCornerShape(4.dp),
            )
            .clickable(onClick = onOpen)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            key = iconKey ?: AllIconsKeys.Nodes.Folder,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
        )
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

@Composable
internal fun SidebarTree(
    selectedLocation: String,
    treeState: SidebarTreeState,
    onOpenLocation: (String) -> Unit,
    onToggleNode: (String) -> Unit,
    onRetryNode: (String) -> Unit,
) {
    val visibleNodes = remember(treeState.roots) { flattenSidebarNodes(treeState.roots) }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 600.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(
            count = visibleNodes.size,
            key = { index -> visibleNodes[index].node.location },
        ) { index ->
            val item = visibleNodes[index]
            SidebarTreeItem(
                item = item,
                selected = selectedLocation == item.node.location,
                onOpenLocation = onOpenLocation,
                onToggleNode = onToggleNode,
                onRetryNode = onRetryNode,
            )
        }
    }
}

@Composable
internal fun SidebarTreeItem(
    item: SidebarTreeItemState,
    selected: Boolean,
    onOpenLocation: (String) -> Unit,
    onToggleNode: (String) -> Unit,
    onRetryNode: (String) -> Unit,
) {
    val node = item.node
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val showExpander = node.loadState != SidebarTreeNodeLoadState.READY || node.children.isNotEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .background(
                when {
                    selected -> LocalOnyxPalette.current.selectionBackground
                    isHovered -> LocalOnyxPalette.current.rowHoverBackground.copy(alpha = 0.24f)
                    else -> Color.Transparent
                },
                RoundedCornerShape(4.dp),
            )
            .clickable(onClick = { onOpenLocation(node.location) })
            .padding(start = (item.depth * 12).dp + 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clickable(enabled = showExpander, onClick = { onToggleNode(node.location) }),
            contentAlignment = Alignment.Center,
        ) {
            when {
                node.loadState == SidebarTreeNodeLoadState.LOADING -> {
                    Text(text = "…", fontSize = 10.sp, color = LocalOnyxPalette.current.disabledForeground)
                }

                showExpander -> {
                    Icon(
                        key = if (node.expanded) AllIconsKeys.General.ArrowDown else AllIconsKeys.General.ArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
        }
        Icon(
            key = AllIconsKeys.Nodes.Folder,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = node.label,
            modifier = Modifier.weight(1f),
            fontSize = 11.sp,
            color = LocalOnyxPalette.current.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (node.loadState == SidebarTreeNodeLoadState.FAILURE) {
            IconButton(onClick = { onRetryNode(node.location) }) {
                Icon(
                    key = AllIconsKeys.Actions.Refresh,
                    contentDescription = stringResource(Res.string.action_refresh_active),
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

internal data class SidebarTreeItemState(
    val node: SidebarTreeNode,
    val depth: Int,
)

internal fun flattenSidebarNodes(
    nodes: List<SidebarTreeNode>,
    depth: Int = 0,
): List<SidebarTreeItemState> {
    return buildList {
        nodes.forEach { node ->
            add(SidebarTreeItemState(node = node, depth = depth))
            if (node.expanded) {
                addAll(flattenSidebarNodes(node.children, depth + 1))
            }
        }
    }
}
