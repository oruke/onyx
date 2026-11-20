package com.oruke.onyx.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.oruke.onyx.app.component.SidebarTreeState
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.SidebarSectionKey
import com.oruke.onyx.core.model.SystemQuickAccessLocation
import com.oruke.onyx.core.model.isTrashLocation
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.toIntOffset
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_add_to_favorites
import onyx.composeapp.generated.resources.action_copy_path
import onyx.composeapp.generated.resources.action_edit_connection
import onyx.composeapp.generated.resources.action_new_connection
import onyx.composeapp.generated.resources.action_open
import onyx.composeapp.generated.resources.action_open_in_new_tab
import onyx.composeapp.generated.resources.action_remove_from_favorites
import onyx.composeapp.generated.resources.label_remote_connection_empty
import onyx.composeapp.generated.resources.label_sidebar_empty_favorites
import onyx.composeapp.generated.resources.label_sidebar_empty_recent
import onyx.composeapp.generated.resources.label_sidebar_section_connections
import onyx.composeapp.generated.resources.label_sidebar_section_favorites
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
    /** 在新标签页中打开指定侧栏位置。 */
    val onOpenInNewTab: (String) -> Unit = {},
    /** 复制指定侧栏位置路径。 */
    val onCopyPath: (String) -> Unit = {},
)

/** 侧边栏位置右键菜单请求上下文。 */
internal data class SidebarContextMenuRequest(
    val location: String,
    val favorite: Boolean,
    val anchorWindowPosition: IntOffset,
    val onEdit: (() -> Unit)? = null,
)

/**
 * 绘制单一 `LazyColumn` + `VerticalScrollbar` 驱动的侧边栏（消除内外嵌套滚轮争抢），
 * 并为全部侧栏位置与目录树节点提供统一右键菜单（打开、在新标签打开、收藏/取消收藏、复制路径、编辑连接）。
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
    val listState = rememberLazyListState()
    var contextMenu by remember { mutableStateOf<SidebarContextMenuRequest?>(null) }
    val visibleRecent = remember(state.recentLocations, state.location) {
        state.recentLocations.filterNot { location -> location == state.location }
    }
    val visibleTreeNodes = remember(state.treeState.roots, state.showTree) {
        if (state.showTree) flattenSidebarNodes(state.treeState.roots) else emptyList()
    }

    val openContextMenu: (SidebarContextMenuRequest) -> Unit = { request ->
        actions.onActivate()
        contextMenu = request
    }

    Box(
        modifier = Modifier
            .width(184.dp)
            .fillMaxHeight()
            .background(LocalOnyxPalette.current.surfaceVariant),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("sidebar-list"),
            contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // 1. 系统入口（此电脑 / 回收站，回收站热区在离屏或卸载时自动注销）
            sidebarSystemLocationItems(
                state = state,
                actions = actions,
                activePane = activePane,
                dragBindings = dragBindings,
                onContextMenu = openContextMenu,
            )

            // 2. 快速访问
            item(key = "section:quick_access") {
                Spacer(modifier = Modifier.height(6.dp))
                SidebarSectionHeader(
                    section = SidebarSectionKey.QUICK_ACCESS,
                    collapsed = state.isSectionCollapsed(SidebarSectionKey.QUICK_ACCESS),
                    onToggle = actions.onToggleSection,
                    title = stringResource(Res.string.label_sidebar_section_quick_access),
                )
            }
            if (!state.isSectionCollapsed(SidebarSectionKey.QUICK_ACCESS)) {
                sidebarQuickAccessItems(
                    state = state,
                    actions = actions,
                    onContextMenu = openContextMenu,
                )
            }

            // 3. 收藏位置
            item(key = "section:favorites") {
                Spacer(modifier = Modifier.height(6.dp))
                SidebarSectionHeader(
                    section = SidebarSectionKey.FAVORITES,
                    collapsed = state.isSectionCollapsed(SidebarSectionKey.FAVORITES),
                    onToggle = actions.onToggleSection,
                    title = stringResource(Res.string.label_sidebar_section_favorites),
                )
            }
            if (!state.isSectionCollapsed(SidebarSectionKey.FAVORITES)) {
                if (state.favoriteLocations.isEmpty()) {
                    item(key = "empty:favorites") {
                        SidebarEmptyState(text = stringResource(Res.string.label_sidebar_empty_favorites))
                    }
                } else {
                    items(
                        items = state.favoriteLocations,
                        key = { location -> "favorite:$location" },
                    ) { favoriteLocation ->
                        SidebarLocationItem(
                            label = desktopLocationLabel(actions.locationLabel(favoriteLocation)),
                            selected = state.location == favoriteLocation ||
                                (isTrashLocation(favoriteLocation) && isTrashLocation(state.location)),
                            favorite = true,
                            iconKey = if (isTrashLocation(favoriteLocation)) AllIconsKeys.General.Delete else null,
                            onOpen = { actions.activateAndOpen(favoriteLocation) },
                            onToggleFavorite = { actions.onToggleFavoriteLocation(favoriteLocation) },
                            onContextMenu = { anchor ->
                                openContextMenu(
                                    SidebarContextMenuRequest(
                                        location = favoriteLocation,
                                        favorite = true,
                                        anchorWindowPosition = anchor,
                                    ),
                                )
                            },
                        )
                    }
                }
            }

            // 4. 网络位置
            item(key = "section:connections") {
                Spacer(modifier = Modifier.height(6.dp))
                SidebarSectionHeader(
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
                )
            }
            if (!state.isSectionCollapsed(SidebarSectionKey.CONNECTIONS)) {
                if (state.remoteConnections.isEmpty()) {
                    item(key = "empty:connections") {
                        SidebarEmptyState(text = stringResource(Res.string.label_remote_connection_empty))
                    }
                } else {
                    items(
                        items = state.remoteConnections,
                        key = { conn -> "connection:${conn.id}" },
                    ) { connection ->
                        val isFav = state.favoriteLocations.contains(connection.location)
                        val editAction: () -> Unit = {
                            actions.onActivate()
                            actions.onEditRemoteConnection(connection)
                        }
                        SidebarLocationItem(
                            label = connection.name,
                            selected = state.location == connection.location,
                            favorite = isFav,
                            iconKey = AllIconsKeys.General.OpenDisk,
                            onOpen = { actions.activateAndOpen(connection.location) },
                            onToggleFavorite = { actions.onToggleFavoriteLocation(connection.location) },
                            onEdit = editAction,
                            onContextMenu = { anchor ->
                                openContextMenu(
                                    SidebarContextMenuRequest(
                                        location = connection.location,
                                        favorite = isFav,
                                        anchorWindowPosition = anchor,
                                        onEdit = editAction,
                                    ),
                                )
                            },
                        )
                    }
                }
            }

            // 5. 最近位置
            item(key = "section:recent") {
                Spacer(modifier = Modifier.height(6.dp))
                SidebarSectionHeader(
                    section = SidebarSectionKey.RECENT,
                    collapsed = state.isSectionCollapsed(SidebarSectionKey.RECENT),
                    onToggle = actions.onToggleSection,
                    title = stringResource(Res.string.label_sidebar_section_recent),
                )
            }
            if (!state.isSectionCollapsed(SidebarSectionKey.RECENT)) {
                if (visibleRecent.isEmpty()) {
                    item(key = "empty:recent") {
                        SidebarEmptyState(text = stringResource(Res.string.label_sidebar_empty_recent))
                    }
                } else {
                    items(
                        items = visibleRecent,
                        key = { location -> "recent:$location" },
                    ) { recentLocation ->
                        val isFav = state.favoriteLocations.contains(recentLocation)
                        SidebarLocationItem(
                            label = desktopLocationLabel(actions.locationLabel(recentLocation)),
                            selected = false,
                            favorite = isFav,
                            iconKey = if (isTrashLocation(recentLocation)) AllIconsKeys.General.Delete else null,
                            onOpen = { actions.activateAndOpen(recentLocation) },
                            onToggleFavorite = { actions.onToggleFavoriteLocation(recentLocation) },
                            onContextMenu = { anchor ->
                                openContextMenu(
                                    SidebarContextMenuRequest(
                                        location = recentLocation,
                                        favorite = isFav,
                                        anchorWindowPosition = anchor,
                                    ),
                                )
                            },
                        )
                    }
                }
            }

            // 6. 目录树（扁平化直接纳入同一 LazyColumn，彻底消除嵌套滚动冲突）
            if (state.showTree) {
                item(key = "section:tree") {
                    Spacer(modifier = Modifier.height(6.dp))
                    SidebarSectionHeader(
                        section = SidebarSectionKey.TREE,
                        collapsed = state.isSectionCollapsed(SidebarSectionKey.TREE),
                        onToggle = actions.onToggleSection,
                        title = stringResource(Res.string.label_sidebar_section_tree),
                    )
                }
                if (!state.isSectionCollapsed(SidebarSectionKey.TREE)) {
                    items(
                        items = visibleTreeNodes,
                        key = { item -> "tree:${item.node.location}" },
                    ) { treeItem ->
                        val nodeLocation = treeItem.node.location
                        val isFav = state.favoriteLocations.contains(nodeLocation)
                        SidebarTreeItem(
                            item = treeItem,
                            selected = state.location == nodeLocation,
                            onOpenLocation = actions::activateAndOpen,
                            onToggleNode = actions.onToggleTreeNode,
                            onRetryNode = actions.onRetryTreeNode,
                            onContextMenu = { anchor ->
                                openContextMenu(
                                    SidebarContextMenuRequest(
                                        location = nodeLocation,
                                        favorite = isFav,
                                        anchorWindowPosition = anchor,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .testTag("sidebar-scrollbar"),
        )

        contextMenu?.let { menu ->
            SidebarContextMenuPopup(
                request = menu,
                onOpen = {
                    contextMenu = null
                    actions.activateAndOpen(menu.location)
                },
                onOpenInNewTab = {
                    contextMenu = null
                    actions.onActivate()
                    actions.onOpenInNewTab(menu.location)
                },
                onToggleFavorite = {
                    contextMenu = null
                    actions.onToggleFavoriteLocation(menu.location)
                },
                onCopyPath = {
                    contextMenu = null
                    actions.onCopyPath(menu.location)
                },
                onEdit = menu.onEdit?.let { edit ->
                    {
                        contextMenu = null
                        edit()
                    }
                },
                onDismiss = { contextMenu = null },
            )
        }
    }
}

/** 渲染侧边栏位置右键菜单。 */
@Composable
private fun SidebarContextMenuPopup(
    request: SidebarContextMenuRequest,
    onOpen: () -> Unit,
    onOpenInNewTab: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCopyPath: () -> Unit,
    onEdit: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val positionProvider = remember(request.anchorWindowPosition) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val maxX = (windowSize.width - popupContentSize.width - 8).coerceAtLeast(0)
                val maxY = (windowSize.height - popupContentSize.height - 8).coerceAtLeast(0)
                return IntOffset(
                    x = request.anchorWindowPosition.x.coerceIn(0, maxX),
                    y = request.anchorWindowPosition.y.coerceIn(0, maxY),
                )
            }
        }
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .testTag("sidebar-context-menu")
                .widthIn(min = 168.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(palette.floatingSurface)
                .border(1.dp, palette.outline, RoundedCornerShape(6.dp))
                .padding(vertical = 4.dp),
        ) {
            SidebarContextMenuRow(stringResource(Res.string.action_open), onOpen)
            SidebarContextMenuRow(stringResource(Res.string.action_open_in_new_tab), onOpenInNewTab)
            SidebarContextMenuRow(
                stringResource(
                    if (request.favorite) Res.string.action_remove_from_favorites else Res.string.action_add_to_favorites,
                ),
                onToggleFavorite,
            )
            if (onEdit != null) {
                SidebarContextMenuRow(stringResource(Res.string.action_edit_connection), onEdit)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .height(1.dp)
                    .background(palette.outlineVariant),
            )
            SidebarContextMenuRow(stringResource(Res.string.action_copy_path), onCopyPath)
        }
    }
}

@Composable
private fun SidebarContextMenuRow(
    label: String,
    onClick: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (hovered) palette.rowHoverBackground else Color.Transparent)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = palette.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
 * 绘制带折叠开关和可选尾部动作按钮的侧栏分区标题行。
 */
@Composable
internal fun SidebarSectionHeader(
    section: SidebarSectionKey,
    collapsed: Boolean,
    onToggle: (SidebarSectionKey) -> Unit,
    title: String,
    actionIcon: IconKey? = null,
    actionContentDescription: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val headerHoverSrc = remember { MutableInteractionSource() }
    val headerClickSrc = remember { MutableInteractionSource() }
    val headerHovered by headerHoverSrc.collectIsHoveredAsState()
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
            fontSize = LocalOnyxAppearance.current.headerFontSize,
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
        fontSize = LocalOnyxAppearance.current.headerFontSize,
        color = LocalOnyxPalette.current.disabledForeground,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * 绘制可打开、收藏、右键菜单并按需编辑的单个侧栏位置。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SidebarLocationItem(
    label: String,
    selected: Boolean,
    favorite: Boolean,
    iconKey: IconKey? = null,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onContextMenu: ((IntOffset) -> Unit)? = null,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable (Modifier) -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var itemCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { itemCoordinates = it }
            .hoverable(interactionSource)
            .background(
                when {
                    selected -> LocalOnyxPalette.current.selectionBackground
                    isHovered -> LocalOnyxPalette.current.rowHoverBackground
                    else -> Color.Transparent
                },
                RoundedCornerShape(4.dp),
            )
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.buttons.isSecondaryPressed && onContextMenu != null) {
                    val pointerPos = event.changes.firstOrNull()?.position ?: Offset.Zero
                    val windowPos = itemCoordinates?.localToWindow(pointerPos)?.toIntOffset() ?: IntOffset.Zero
                    onContextMenu(windowPos)
                }
            }
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
        val itemFontSize = LocalOnyxAppearance.current.headerFontSize
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = itemFontSize,
            color = LocalOnyxPalette.current.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (favorite) "★" else "☆",
            fontSize = itemFontSize,
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
