package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.component.PaneFolderTreeNode
import com.oruke.onyx.app.component.PaneFolderTreeNodeLoadState
import com.oruke.onyx.app.component.PaneFolderTreeState
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * 面板内嵌目录树 Composable。
 *
 * 嵌入在 PaneSurface 左侧，提供当前面板独立的文件系统树状导航。
 * 风格保持高密度、IntelliJ IDEA 风格。
 */
@Composable
internal fun PaneFolderTree(
    treeState: PaneFolderTreeState,
    currentLocation: String,
    onNodeClick: (location: String) -> Unit,
    onNodeToggle: (location: String) -> Unit,
    onRetryNode: (location: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current

    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .background(palette.surface),
    ) {
        treeState.roots.forEach { root ->
            renderTreeNode(
                node = root,
                depth = 0,
                currentLocation = currentLocation,
                onNodeClick = onNodeClick,
                onNodeToggle = onNodeToggle,
                onRetryNode = onRetryNode,
            )
        }
    }
}

/**
 * 递归渲染树节点到 LazyColumn。
 */
private fun androidx.compose.foundation.lazy.LazyListScope.renderTreeNode(
    node: PaneFolderTreeNode,
    depth: Int,
    currentLocation: String,
    onNodeClick: (String) -> Unit,
    onNodeToggle: (String) -> Unit,
    onRetryNode: (String) -> Unit,
) {
    item(key = node.location) {
        FolderTreeNodeRow(
            node = node,
            depth = depth,
            isActive = node.location == currentLocation,
            onNodeClick = onNodeClick,
            onNodeToggle = onNodeToggle,
            onRetryNode = onRetryNode,
        )
    }

    if (node.expanded) {
        when (node.loadState) {
            PaneFolderTreeNodeLoadState.LOADING -> {
                item(key = "${node.location}/__loading") {
                    LoadingIndicatorRow(depth = depth + 1)
                }
            }
            PaneFolderTreeNodeLoadState.FAILURE -> {
                item(key = "${node.location}/__failure") {
                    FailureIndicatorRow(
                        depth = depth + 1,
                        onRetry = { onRetryNode(node.location) },
                    )
                }
            }
            PaneFolderTreeNodeLoadState.READY, PaneFolderTreeNodeLoadState.IDLE -> {
                node.children.forEach { child ->
                    renderTreeNode(
                        node = child,
                        depth = depth + 1,
                        currentLocation = currentLocation,
                        onNodeClick = onNodeClick,
                        onNodeToggle = onNodeToggle,
                        onRetryNode = onRetryNode,
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderTreeNodeRow(
    node: PaneFolderTreeNode,
    depth: Int,
    isActive: Boolean,
    onNodeClick: (String) -> Unit,
    onNodeToggle: (String) -> Unit,
    onRetryNode: (String) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    val interactionSource = remember(node.location) { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val indent = (depth * 16 + 4).dp

    val bgColor = when {
        isActive -> palette.selectionBackground
        isHovered -> palette.rowHoverBackground
        else -> palette.surface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(appearance.listRowHeight)
            .hoverable(interactionSource)
            .background(bgColor)
            .clickable { onNodeClick(node.location) }
            .padding(start = indent, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 展开/折叠箭头
        Box(
            modifier = Modifier
                .size(16.dp)
                .clickable { onNodeToggle(node.location) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                key = if (node.expanded) {
                    AllIconsKeys.General.ArrowDown
                } else {
                    AllIconsKeys.General.ArrowRight
                },
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
        }

        Spacer(Modifier.width(2.dp))

        // 文件夹图标
        Icon(
            key = AllIconsKeys.Nodes.Folder,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))

        // 标签
        Text(
            text = node.label,
            fontSize = appearance.listFontSize,
            color = if (isActive) palette.selectionForeground else palette.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LoadingIndicatorRow(depth: Int) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    val indent = (depth * 16 + 4).dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(appearance.listRowHeight)
            .padding(start = indent + 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "…",
            fontSize = appearance.listFontSize,
            color = palette.foreground.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun FailureIndicatorRow(depth: Int, onRetry: () -> Unit) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    val indent = (depth * 16 + 4).dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(appearance.listRowHeight)
            .padding(start = indent + 18.dp)
            .clickable(onClick = onRetry),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            key = AllIconsKeys.General.Warning,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "⟳",
            fontSize = appearance.listFontSize,
            color = palette.foreground.copy(alpha = 0.6f),
        )
    }
}
