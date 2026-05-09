package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.SidebarTreeNode
import com.oruke.onyx.app.component.SidebarTreeNodeLoadState
import com.oruke.onyx.app.component.SidebarTreeState
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.orEmpty
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_refresh_active
import onyx.composeapp.generated.resources.label_home
import onyx.composeapp.generated.resources.label_sidebar_empty_filter
import onyx.composeapp.generated.resources.label_sidebar_empty_favorites
import onyx.composeapp.generated.resources.label_sidebar_empty_recent
import onyx.composeapp.generated.resources.label_sidebar_filter_placeholder
import onyx.composeapp.generated.resources.label_sidebar_section_favorites
import onyx.composeapp.generated.resources.label_sidebar_section_connections
import onyx.composeapp.generated.resources.label_sidebar_section_quick_access
import onyx.composeapp.generated.resources.label_sidebar_section_recent
import onyx.composeapp.generated.resources.label_sidebar_section_tree
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
internal fun PaneSidebar(
    location: String,
    favoriteLocations: List<String>,
    recentLocations: List<String>,
    remoteConnections: List<RemoteConnectionProfile>,
    locationLabel: (String) -> String,
    treeState: SidebarTreeState,
    showTree: Boolean,
    onActivate: () -> Unit,
    onOpenLocation: (String) -> Unit,
    onToggleFavoriteLocation: (String) -> Unit,
    onToggleTreeNode: (String) -> Unit,
    onRetryTreeNode: (String) -> Unit,
) {
    val homeLocation = System.getProperty("user.home")
    val scrollState = rememberScrollState()
    var locationFilterQuery by remember { mutableStateOf("") }
    val normalizedLocationFilterQuery = locationFilterQuery.trim().lowercase()

    fun matchesLocationFilter(
        label: String,
        itemLocation: String,
    ): Boolean {
        if (normalizedLocationFilterQuery.isEmpty()) return true
        return label.lowercase().contains(normalizedLocationFilterQuery) ||
            itemLocation.lowercase().contains(normalizedLocationFilterQuery)
    }

    Column(
        modifier = Modifier
            .width(184.dp)
            .fillMaxHeight()
            .background(LocalOnyxPalette.current.surfaceVariant)
            .verticalScroll(scrollState)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SidebarLocationFilter(
            query = locationFilterQuery,
            onQueryChange = { locationFilterQuery = it },
        )

        SidebarSection(
            title = stringResource(Res.string.label_sidebar_section_quick_access),
        ) {
            SidebarLocationItem(
                label = stringResource(Res.string.label_home),
                location = homeLocation,
                selected = location == homeLocation,
                favorite = favoriteLocations.contains(homeLocation),
                onOpen = {
                    onActivate()
                    onOpenLocation(homeLocation)
                },
                onToggleFavorite = { onToggleFavoriteLocation(homeLocation) },
            )
        }

        SidebarSection(
            title = stringResource(Res.string.label_sidebar_section_favorites),
        ) {
            val filteredFavoriteLocations = favoriteLocations.filter { favoriteLocation ->
                matchesLocationFilter(locationLabel(favoriteLocation), favoriteLocation)
            }
            if (favoriteLocations.isEmpty()) {
                SidebarEmptyState(
                    text = stringResource(Res.string.label_sidebar_empty_favorites),
                )
            } else if (filteredFavoriteLocations.isEmpty()) {
                SidebarEmptyState(
                    text = stringResource(Res.string.label_sidebar_empty_filter),
                )
            } else {
                filteredFavoriteLocations.forEach { favoriteLocation ->
                    SidebarLocationItem(
                        label = locationLabel(favoriteLocation),
                        location = favoriteLocation,
                        selected = location == favoriteLocation,
                        favorite = true,
                        onOpen = {
                            onActivate()
                            onOpenLocation(favoriteLocation)
                        },
                        onToggleFavorite = { onToggleFavoriteLocation(favoriteLocation) },
                    )
                }
            }
        }

        if (remoteConnections.isNotEmpty()) {
            SidebarSection(
                title = stringResource(Res.string.label_sidebar_section_connections),
            ) {
                val filteredConnections = remoteConnections.filter { connection ->
                    matchesLocationFilter(connection.name, connection.location)
                }
                if (filteredConnections.isEmpty()) {
                    SidebarEmptyState(
                        text = stringResource(Res.string.label_sidebar_empty_filter),
                    )
                } else {
                    filteredConnections.forEach { connection ->
                        SidebarLocationItem(
                            label = connection.name,
                            location = connection.location,
                            selected = location == connection.location,
                            favorite = favoriteLocations.contains(connection.location),
                            onOpen = {
                                onActivate()
                                onOpenLocation(connection.location)
                            },
                            onToggleFavorite = { onToggleFavoriteLocation(connection.location) },
                        )
                    }
                }
            }
        }

        SidebarSection(
            title = stringResource(Res.string.label_sidebar_section_recent),
        ) {
            val displayRecentLocations = recentLocations.filterNot { recentLocation ->
                recentLocation == location
            }.filter { recentLocation ->
                matchesLocationFilter(locationLabel(recentLocation), recentLocation)
            }
            if (recentLocations.isEmpty() || (displayRecentLocations.isEmpty() && normalizedLocationFilterQuery.isEmpty())) {
                SidebarEmptyState(
                    text = stringResource(Res.string.label_sidebar_empty_recent),
                )
            } else if (displayRecentLocations.isEmpty()) {
                SidebarEmptyState(
                    text = stringResource(Res.string.label_sidebar_empty_filter),
                )
            } else {
                displayRecentLocations.forEach { recentLocation ->
                    SidebarLocationItem(
                        label = locationLabel(recentLocation),
                        location = recentLocation,
                        selected = false,
                        favorite = favoriteLocations.contains(recentLocation),
                        onOpen = {
                            onActivate()
                            onOpenLocation(recentLocation)
                        },
                        onToggleFavorite = { onToggleFavoriteLocation(recentLocation) },
                    )
                }
            }
        }

        if (showTree) {
            SidebarSection(
                title = stringResource(Res.string.label_sidebar_section_tree),
            ) {
                SidebarTree(
                    selectedLocation = location,
                    treeState = treeState,
                    onOpenLocation = { treeLocation ->
                        onActivate()
                        onOpenLocation(treeLocation)
                    },
                    onToggleNode = onToggleTreeNode,
                    onRetryNode = onRetryTreeNode,
                )
            }
        }
    }
}

@Composable
internal fun SidebarLocationFilter(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .background(LocalOnyxPalette.current.inputBackground, RoundedCornerShape(4.dp))
            .border(1.dp, LocalOnyxPalette.current.outlineVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 5.dp),
        textStyle = TextStyle(
            fontSize = 11.sp,
            color = LocalOnyxPalette.current.foreground,
        ),
        singleLine = true,
        cursorBrush = SolidColor(LocalOnyxPalette.current.accent),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.label_sidebar_filter_placeholder),
                        fontSize = 11.sp,
                        color = LocalOnyxPalette.current.disabledForeground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
internal fun SidebarSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            fontSize = 11.sp,
            color = LocalOnyxPalette.current.mutedForeground,
            fontWeight = FontWeight.Medium,
        )
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

@Composable
internal fun SidebarLocationItem(
    label: String,
    location: String,
    selected: Boolean,
    favorite: Boolean,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
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
            key = if (location == System.getProperty("user.home")) AllIconsKeys.Nodes.HomeFolder else AllIconsKeys.Nodes.Folder,
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
            color = if (favorite) Color(0xFFFFC94D) else LocalOnyxPalette.current.disabledForeground,
            modifier = Modifier.clickable(onClick = onToggleFavorite),
        )
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
