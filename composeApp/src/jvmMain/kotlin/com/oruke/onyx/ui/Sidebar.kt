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
import com.oruke.onyx.ui.theme.OnyxPalette
import com.oruke.onyx.ui.theme.orEmpty
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_refresh_active
import onyx.composeapp.generated.resources.label_home
import onyx.composeapp.generated.resources.label_sidebar_empty_favorites
import onyx.composeapp.generated.resources.label_sidebar_empty_recent
import onyx.composeapp.generated.resources.label_sidebar_section_favorites
import onyx.composeapp.generated.resources.label_sidebar_section_quick_access
import onyx.composeapp.generated.resources.label_sidebar_section_recent
import onyx.composeapp.generated.resources.label_sidebar_section_tree
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.nio.file.Path

@Composable
internal fun PaneSidebar(
    location: String,
    favoriteLocations: List<String>,
    recentLocations: List<String>,
    treeState: SidebarTreeState,
    palette: OnyxPalette,
    onActivate: () -> Unit,
    onOpenLocation: (String) -> Unit,
    onToggleFavoriteLocation: (String) -> Unit,
    onToggleTreeNode: (String) -> Unit,
    onRetryTreeNode: (String) -> Unit,
) {
    val homeLocation = System.getProperty("user.home")
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .width(184.dp)
            .fillMaxHeight()
            .background(palette.surfaceVariant)
            .verticalScroll(scrollState)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SidebarSection(
            title = stringResource(Res.string.label_sidebar_section_quick_access),
            palette = palette,
        ) {
            SidebarLocationItem(
                label = stringResource(Res.string.label_home),
                location = homeLocation,
                selected = location == homeLocation,
                favorite = favoriteLocations.contains(homeLocation),
                palette = palette,
                onOpen = {
                    onActivate()
                    onOpenLocation(homeLocation)
                },
                onToggleFavorite = { onToggleFavoriteLocation(homeLocation) },
            )
        }

        SidebarSection(
            title = stringResource(Res.string.label_sidebar_section_favorites),
            palette = palette,
        ) {
            if (favoriteLocations.isEmpty()) {
                SidebarEmptyState(
                    text = stringResource(Res.string.label_sidebar_empty_favorites),
                    palette = palette,
                )
            } else {
                favoriteLocations.forEach { favoriteLocation ->
                    SidebarLocationItem(
                        label = Path.of(favoriteLocation).fileName?.toString().orEmpty().ifBlank { favoriteLocation },
                        location = favoriteLocation,
                        selected = location == favoriteLocation,
                        favorite = true,
                        palette = palette,
                        onOpen = {
                            onActivate()
                            onOpenLocation(favoriteLocation)
                        },
                        onToggleFavorite = { onToggleFavoriteLocation(favoriteLocation) },
                    )
                }
            }
        }

        SidebarSection(
            title = stringResource(Res.string.label_sidebar_section_recent),
            palette = palette,
        ) {
            val displayRecentLocations = recentLocations.filterNot { recentLocation ->
                recentLocation == location
            }
            if (displayRecentLocations.isEmpty()) {
                SidebarEmptyState(
                    text = stringResource(Res.string.label_sidebar_empty_recent),
                    palette = palette,
                )
            } else {
                displayRecentLocations.forEach { recentLocation ->
                    SidebarLocationItem(
                        label = Path.of(recentLocation).fileName?.toString().orEmpty().ifBlank { recentLocation },
                        location = recentLocation,
                        selected = false,
                        favorite = favoriteLocations.contains(recentLocation),
                        palette = palette,
                        onOpen = {
                            onActivate()
                            onOpenLocation(recentLocation)
                        },
                        onToggleFavorite = { onToggleFavoriteLocation(recentLocation) },
                    )
                }
            }
        }

        SidebarSection(
            title = stringResource(Res.string.label_sidebar_section_tree),
            palette = palette,
        ) {
            SidebarTree(
                selectedLocation = location,
                treeState = treeState,
                palette = palette,
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

@Composable
internal fun SidebarSection(
    title: String,
    palette: OnyxPalette,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            fontSize = 11.sp,
            color = palette.mutedForeground,
            fontWeight = FontWeight.Medium,
        )
        content()
    }
}

@Composable
internal fun SidebarEmptyState(
    text: String,
    palette: OnyxPalette,
) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = palette.disabledForeground,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
internal fun SidebarLocationItem(
    label: String,
    location: String,
    selected: Boolean,
    favorite: Boolean,
    palette: OnyxPalette,
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
                    selected -> palette.selectionBackground
                    isHovered -> palette.rowHoverBackground.copy(alpha = 0.28f)
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
            color = palette.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (favorite) "★" else "☆",
            fontSize = 11.sp,
            color = if (favorite) Color(0xFFFFC94D) else palette.disabledForeground,
            modifier = Modifier.clickable(onClick = onToggleFavorite),
        )
    }
}

@Composable
internal fun SidebarTree(
    selectedLocation: String,
    treeState: SidebarTreeState,
    palette: OnyxPalette,
    onOpenLocation: (String) -> Unit,
    onToggleNode: (String) -> Unit,
    onRetryNode: (String) -> Unit,
) {
    val visibleNodes = remember(treeState.roots) { flattenSidebarNodes(treeState.roots) }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 260.dp),
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
                palette = palette,
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
    palette: OnyxPalette,
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
                    selected -> palette.selectionBackground
                    isHovered -> palette.rowHoverBackground.copy(alpha = 0.24f)
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
                    Text(text = "…", fontSize = 10.sp, color = palette.disabledForeground)
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
            color = palette.foreground,
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

