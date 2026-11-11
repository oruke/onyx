package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.SidebarTreeNode
import com.oruke.onyx.app.component.SidebarTreeNodeLoadState
import com.oruke.onyx.app.component.SidebarTreeState
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_refresh_active
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * 渲染侧边栏可展开文件系统目录树。
 *
 * @param selectedLocation 当前活动面板位置。
 * @param treeState 目录树展开与加载状态。
 * @param onOpenLocation 打开指定目录节点。
 * @param onToggleNode 展开或折叠指定目录节点。
 * @param onRetryNode 重试加载失败的目录节点。
 */
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

/**
 * 渲染侧边栏目录树中的单个节点行。
 *
 * @param item 扁平化后的节点与缩进层级。
 * @param selected 是否为当前选中位置。
 * @param onOpenLocation 打开节点目录回调。
 * @param onToggleNode 切换节点展开状态回调。
 * @param onRetryNode 重试加载回调。
 */
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
                    isHovered -> LocalOnyxPalette.current.rowHoverBackground
                    else -> Color.Transparent
                },
                RoundedCornerShape(4.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onOpenLocation(node.location) },
            )
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
                    OnyxLoadingSpinner(size = 10.dp, strokeWidth = 1.4.dp)
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

/**
 * 扁平化后的侧边栏目录树行模型。
 *
 * @property node 目录树节点。
 * @property depth 缩进深度。
 */
internal data class SidebarTreeItemState(
    val node: SidebarTreeNode,
    val depth: Int,
)

/**
 * 将层级目录树按当前展开状态递归扁平化为可交由 [LazyColumn] 渲染的列表。
 *
 * @param nodes 当前层目录节点列表。
 * @param depth 当前层缩进深度。
 * @return 扁平化后的可见目录节点列表。
 */
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
