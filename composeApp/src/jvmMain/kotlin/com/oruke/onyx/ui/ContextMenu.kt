package com.oruke.onyx.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.testTag
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
import com.oruke.onyx.app.component.PaneContextMenuCommand
import com.oruke.onyx.app.component.PaneContextMenuModel
import com.oruke.onyx.app.component.PaneContextMenuNode
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * 右键菜单导航的统一状态快照：同时维护键盘/鼠标焦点路径与唯一子菜单展开路径，消除双轨状态打架。
 *
 * @property activePath 从顶层菜单到当前焦点项的节点 ID 路径。
 * @property openSubmenuPath 当前已展开子菜单的父节点路径；空列表表示无子菜单展开。
 */
internal data class ContextMenuNavigationState(
    val activePath: List<String> = emptyList(),
    val openSubmenuPath: List<String> = emptyList(),
)

/**
 * 面板右键菜单弹层，支持完整键盘导航（`↑`/`↓`/`→`/`←`/`Enter`/`Esc`）并阻止按键穿透到底层文件列表。
 *
 * @param anchorOffset 弹层锚点在窗口内的像素位置。
 * @param model 已合成的统一右键菜单模型。
 * @param onCommand 菜单命令点击回调。
 * @param commandShortcuts 面板命令快捷键映射。
 * @param onClose 关闭菜单回调。
 */
@Composable
internal fun BoxScope.PaneContextMenu(
    anchorOffset: IntOffset,
    model: PaneContextMenuModel,
    onCommand: (PaneContextMenuCommand) -> Unit,
    commandShortcuts: OnyxCommandShortcutMap = OnyxCommandShortcutMap.Default,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var navState by remember(model) { mutableStateOf(ContextMenuNavigationState()) }
    val latestNavState by rememberUpdatedState(navState)

    Popup(
        popupPositionProvider = remember(anchorOffset) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val desiredX = anchorOffset.x
                    val desiredY = anchorOffset.y
                    val x = desiredX.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                    val y = desiredY.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                    return IntOffset(x, y)
                }
            }
        },
        onDismissRequest = onClose,
        properties = PopupProperties(focusable = true),
    ) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
        Box(
            modifier = Modifier
                .testTag("pane-context-menu")
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    handleContextMenuKeyEvent(
                        event = event,
                        rootNodes = model.nodes,
                        navigationState = navState,
                        onUpdateState = { next -> navState = next },
                        onCommand = onCommand,
                        onClose = onClose,
                    )
                },
        ) {
            ContextMenuPanel {
                PaneContextMenuNodes(
                    nodes = model.nodes,
                    depth = 0,
                    pathPrefix = emptyList(),
                    navigationState = navState,
                    commandShortcuts = commandShortcuts,
                    onFocusPlainItem = { itemPath, prefix ->
                        navState = ContextMenuNavigationState(
                            activePath = itemPath,
                            openSubmenuPath = prefix,
                        )
                    },
                    onOpenSubmenu = { itemPath ->
                        navState = ContextMenuNavigationState(
                            activePath = itemPath,
                            openSubmenuPath = itemPath,
                        )
                    },
                    onCloseSubmenuIfCurrent = { itemPath, prefix ->
                        val current = latestNavState
                        if (current.openSubmenuPath.hasPrefix(itemPath)) {
                            val nextActive = if (current.activePath.size > itemPath.size &&
                                current.activePath.hasPrefix(itemPath)
                            ) {
                                itemPath
                            } else {
                                current.activePath
                            }
                            navState = ContextMenuNavigationState(
                                activePath = nextActive,
                                openSubmenuPath = prefix,
                            )
                        }
                    },
                    onCommand = onCommand,
                )
            }
        }
    }
}

/**
 * 处理右键菜单弹层的键盘导航与确认事件；菜单打开期间消费全部 `KeyDown` 以杜绝快捷键穿透误操作。
 *
 * @param event 当前键盘事件。
 * @param rootNodes 顶层菜单节点列表。
 * @param navigationState 当前菜单焦点与子菜单展开状态。
 * @param onUpdateState 更新菜单导航状态的回调。
 * @param onCommand 执行菜单项命令的回调。
 * @param onClose 关闭右键菜单的回调。
 * @return 事件已消费时返回 `true`。
 */
internal fun handleContextMenuKeyEvent(
    event: KeyEvent,
    rootNodes: List<PaneContextMenuNode>,
    navigationState: ContextMenuNavigationState,
    onUpdateState: (ContextMenuNavigationState) -> Unit,
    onCommand: (PaneContextMenuCommand) -> Unit,
    onClose: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val activePath = navigationState.activePath
    when (event.key) {
        Key.Escape -> onClose()

        Key.DirectionDown -> {
            val nextPath = stepContextMenuSelection(rootNodes, activePath, delta = 1)
            onUpdateState(
                ContextMenuNavigationState(
                    activePath = nextPath,
                    openSubmenuPath = nextPath.dropLast(1),
                ),
            )
        }

        Key.DirectionUp -> {
            val nextPath = stepContextMenuSelection(rootNodes, activePath, delta = -1)
            onUpdateState(
                ContextMenuNavigationState(
                    activePath = nextPath,
                    openSubmenuPath = nextPath.dropLast(1),
                ),
            )
        }

        Key.DirectionRight -> {
            val currentItem = resolveContextMenuItem(rootNodes, activePath)
            val firstChild = currentItem?.children?.navigableItems()?.firstOrNull()
            if (firstChild != null) {
                onUpdateState(
                    ContextMenuNavigationState(
                        activePath = activePath + firstChild.id,
                        openSubmenuPath = activePath,
                    ),
                )
            }
        }

        Key.DirectionLeft -> {
            if (activePath.size > 1) {
                val parentPath = activePath.dropLast(1)
                onUpdateState(
                    ContextMenuNavigationState(
                        activePath = parentPath,
                        openSubmenuPath = parentPath.dropLast(1),
                    ),
                )
            } else if (navigationState.openSubmenuPath.isNotEmpty()) {
                onUpdateState(navigationState.copy(openSubmenuPath = emptyList()))
            }
        }

        Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
            val currentItem = resolveContextMenuItem(rootNodes, activePath)
            if (currentItem != null && currentItem.enabled) {
                val firstChild = currentItem.children.navigableItems().firstOrNull()
                if (firstChild != null) {
                    onUpdateState(
                        ContextMenuNavigationState(
                            activePath = activePath + firstChild.id,
                            openSubmenuPath = activePath,
                        ),
                    )
                } else {
                    currentItem.command?.let(onCommand)
                }
            }
        }

        else -> Unit
    }
    // 消费所有按键（包括 Delete / Backspace / 字母等），防止按键穿透到底层文件列表误删或误打开。
    return true
}

/**
 * 在当前活跃菜单层级中按方向移动焦点，自动跳过分隔线与禁用项并在首尾循环。
 *
 * @param rootNodes 顶层菜单节点列表。
 * @param activePath 当前焦点路径。
 * @param delta 移动步长（+1 向下，-1 向上）。
 * @return 更新后的焦点路径。
 */
internal fun stepContextMenuSelection(
    rootNodes: List<PaneContextMenuNode>,
    activePath: List<String>,
    delta: Int,
): List<String> {
    val parentPrefix = if (activePath.size > 1) activePath.dropLast(1) else emptyList()
    val levelNodes = resolveContextMenuLevel(rootNodes, parentPrefix)
    val navigable = levelNodes.navigableItems()
    if (navigable.isEmpty()) return parentPrefix
    val currentId = activePath.lastOrNull()
    val currentIndex = navigable.indexOfFirst { item -> item.id == currentId }
    val nextIndex = if (currentIndex < 0) {
        if (delta >= 0) 0 else navigable.lastIndex
    } else {
        (currentIndex + delta).mod(navigable.size)
    }
    return parentPrefix + navigable[nextIndex].id
}

/** 判断路径是否以指定前缀开头。 */
private fun List<String>.hasPrefix(prefix: List<String>): Boolean {
    return size >= prefix.size && subList(0, prefix.size) == prefix
}

/**
 * 解析路径前缀对应的子菜单节点列表；空前缀返回根菜单节点。
 *
 * @param rootNodes 顶层菜单节点列表。
 * @param parentPrefix 父级路径前缀。
 * @return 目标层级的节点列表。
 */
private fun resolveContextMenuLevel(
    rootNodes: List<PaneContextMenuNode>,
    parentPrefix: List<String>,
): List<PaneContextMenuNode> {
    var nodes = rootNodes
    for (id in parentPrefix) {
        val match = nodes.filterIsInstance<PaneContextMenuNode.Item>().firstOrNull { it.id == id }
            ?: return rootNodes
        nodes = match.children
    }
    return nodes
}

/**
 * 根据完整高亮路径解析对应的菜单项节点。
 *
 * @param rootNodes 顶层菜单节点列表。
 * @param activePath 完整节点路径。
 * @return 命中的菜单项节点，未找到时返回 `null`。
 */
private fun resolveContextMenuItem(
    rootNodes: List<PaneContextMenuNode>,
    activePath: List<String>,
): PaneContextMenuNode.Item? {
    if (activePath.isEmpty()) return null
    val levelNodes = resolveContextMenuLevel(rootNodes, activePath.dropLast(1))
    val targetId = activePath.last()
    return levelNodes.filterIsInstance<PaneContextMenuNode.Item>().firstOrNull { it.id == targetId }
}

/** 提取当前层级中允许键盘停驻和激活的可用菜单项。 */
private fun List<PaneContextMenuNode>.navigableItems(): List<PaneContextMenuNode.Item> {
    return filterIsInstance<PaneContextMenuNode.Item>().filter { item ->
        item.enabled && (item.command != null || item.children.isNotEmpty())
    }
}

/**
 * 渲染统一菜单节点列表，并同步鼠标与键盘的级联展开状态。
 *
 * @param nodes 当前层级的菜单节点列表。
 * @param depth 当前菜单嵌套层级（顶层为 0）。
 * @param pathPrefix 到达当前层级的父节点路径前缀。
 * @param navigationState 顶层维护的统一导航与子菜单展开状态。
 * @param commandShortcuts 面板命令快捷键映射。
 * @param onFocusPlainItem 鼠标移入普通启用项时更新焦点并收起同级其他子菜单的回调。
 * @param onOpenSubmenu 展开指定级联菜单项的回调。
 * @param onCloseSubmenuIfCurrent 仅当该子菜单仍为当前展开路径时执行收起的回调。
 * @param onCommand 执行菜单项命令的回调。
 */
@Composable
private fun PaneContextMenuNodes(
    nodes: List<PaneContextMenuNode>,
    depth: Int,
    pathPrefix: List<String>,
    navigationState: ContextMenuNavigationState,
    commandShortcuts: OnyxCommandShortcutMap,
    onFocusPlainItem: (itemPath: List<String>, prefix: List<String>) -> Unit,
    onOpenSubmenu: (itemPath: List<String>) -> Unit,
    onCloseSubmenuIfCurrent: (itemPath: List<String>, prefix: List<String>) -> Unit,
    onCommand: (PaneContextMenuCommand) -> Unit,
) {
    nodes.forEach { node ->
        when (node) {
            PaneContextMenuNode.Divider -> {
                Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
            }

            is PaneContextMenuNode.Item -> {
                val itemPath = pathPrefix + node.id
                val keyboardFocused = navigationState.activePath.getOrNull(depth) == node.id &&
                    navigationState.activePath.hasPrefix(pathPrefix)
                val expanded = navigationState.openSubmenuPath.hasPrefix(itemPath)
                PaneContextMenuItemNode(
                    item = node,
                    depth = depth,
                    itemPath = itemPath,
                    pathPrefix = pathPrefix,
                    navigationState = navigationState,
                    keyboardFocused = keyboardFocused,
                    expanded = expanded,
                    commandShortcuts = commandShortcuts,
                    onFocusPlainItem = onFocusPlainItem,
                    onOpenSubmenu = onOpenSubmenu,
                    onCloseSubmenuIfCurrent = onCloseSubmenuIfCurrent,
                    onCommand = onCommand,
                )
            }
        }
    }
}

/**
 * 渲染单个菜单项，自动选择普通菜单项或级联菜单项。
 *
 * @param item 菜单项模型。
 * @param depth 当前菜单嵌套层级。
 * @param itemPath 当前菜单项完整路径。
 * @param pathPrefix 父级菜单路径前缀。
 * @param navigationState 统一导航与子菜单展开状态。
 * @param keyboardFocused 当前项是否处于键盘或联动高亮状态。
 * @param expanded 当前级联菜单是否展开。
 * @param commandShortcuts 面板命令快捷键映射。
 * @param onFocusPlainItem 鼠标移入普通启用项的回调。
 * @param onOpenSubmenu 展开当前级联子菜单的回调。
 * @param onCloseSubmenuIfCurrent 安全收起当前级联子菜单的回调。
 * @param onCommand 菜单命令点击回调。
 */
@Composable
private fun PaneContextMenuItemNode(
    item: PaneContextMenuNode.Item,
    depth: Int,
    itemPath: List<String>,
    pathPrefix: List<String>,
    navigationState: ContextMenuNavigationState,
    keyboardFocused: Boolean,
    expanded: Boolean,
    commandShortcuts: OnyxCommandShortcutMap,
    onFocusPlainItem: (itemPath: List<String>, prefix: List<String>) -> Unit,
    onOpenSubmenu: (itemPath: List<String>) -> Unit,
    onCloseSubmenuIfCurrent: (itemPath: List<String>, prefix: List<String>) -> Unit,
    onCommand: (PaneContextMenuCommand) -> Unit,
) {
    val text = item.contextMenuText()
    val iconKey = item.icon.toIconKey(item.iconPath)
    if (item.children.isNotEmpty()) {
        ContextMenuSubmenuItem(
            text = text,
            enabled = item.enabled,
            iconKey = iconKey,
            iconPath = item.iconPath,
            keyboardFocused = keyboardFocused,
            expanded = expanded,
            onOpen = { onOpenSubmenu(itemPath) },
            onRequestClose = { onCloseSubmenuIfCurrent(itemPath, pathPrefix) },
        ) {
            PaneContextMenuNodes(
                nodes = item.children,
                depth = depth + 1,
                pathPrefix = itemPath,
                navigationState = navigationState,
                commandShortcuts = commandShortcuts,
                onFocusPlainItem = onFocusPlainItem,
                onOpenSubmenu = onOpenSubmenu,
                onCloseSubmenuIfCurrent = onCloseSubmenuIfCurrent,
                onCommand = onCommand,
            )
        }
    } else {
        val command = item.command
        ContextMenuItem(
            text = text,
            enabled = item.enabled && command != null,
            iconKey = iconKey,
            iconPath = item.iconPath,
            keyboardFocused = keyboardFocused,
            command = command?.shortcutCommandOrNull(),
            commandShortcuts = commandShortcuts,
            onPointerEnter = { onFocusPlainItem(itemPath, pathPrefix) },
            onClick = {
                if (command != null) onCommand(command)
            },
        )
    }
}

/**
 * 渲染级联菜单项，并在鼠标离开时延迟关闭；子菜单保持非聚焦弹层以便顶层菜单统一处理键盘流。
 *
 * @param text 菜单项文本。
 * @param enabled 是否允许展开。
 * @param iconKey 菜单项内置图标键。
 * @param iconPath 平台动态图标路径。
 * @param keyboardFocused 是否处于高亮焦点态。
 * @param expanded 当前级联子菜单是否展开。
 * @param onOpen 展开子菜单并将焦点切到父项的回调。
 * @param onRequestClose 仅在当前仍属于本子菜单展开时执行收起的回调。
 * @param content 子菜单内容插槽。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ContextMenuSubmenuItem(
    text: String,
    enabled: Boolean,
    iconKey: IconKey,
    iconPath: String? = null,
    keyboardFocused: Boolean = false,
    expanded: Boolean,
    onOpen: () -> Unit,
    onRequestClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var closeJob by remember { mutableStateOf<Job?>(null) }
    val latestOnOpen by rememberUpdatedState(onOpen)
    val latestOnRequestClose by rememberUpdatedState(onRequestClose)

    /** 取消待执行的延迟关闭任务（鼠标移入子菜单容器时不覆写父项焦点）。 */
    fun cancelPendingClose() {
        closeJob?.cancel()
        closeJob = null
    }

    /** 延迟关闭当前级联菜单，给鼠标斜向移动到子菜单留出容错时间。 */
    fun closeSoon() {
        closeJob?.cancel()
        closeJob = coroutineScope.launch {
            delay(SUBMENU_CLOSE_DELAY_MS)
            latestOnRequestClose()
        }
    }

    LaunchedEffect(expanded) {
        if (!expanded) cancelPendingClose()
    }
    DisposableEffect(Unit) {
        onDispose { cancelPendingClose() }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onPointerEvent(PointerEventType.Enter) {
                cancelPendingClose()
                if (enabled) latestOnOpen()
            }
            .onPointerEvent(PointerEventType.Exit) {
                if (enabled) closeSoon()
            },
    ) {
        ContextMenuItem(
            text = text,
            enabled = enabled,
            iconKey = iconKey,
            iconPath = iconPath,
            keyboardFocused = keyboardFocused,
            trailingIconKey = AllIconsKeys.General.ArrowRight,
            onClick = {
                cancelPendingClose()
                if (expanded) latestOnRequestClose() else latestOnOpen()
            },
        )
        if (enabled && expanded) {
            Popup(
                popupPositionProvider = CascadingMenuPositionProvider,
                onDismissRequest = {
                    cancelPendingClose()
                    latestOnRequestClose()
                },
                properties = PopupProperties(focusable = false),
            ) {
                Box(
                    modifier = Modifier
                        .testTag("pane-context-submenu")
                        .onPointerEvent(PointerEventType.Enter) {
                            cancelPendingClose()
                        }
                        .onPointerEvent(PointerEventType.Exit) {
                            closeSoon()
                        },
                ) {
                    ContextMenuPanel(content = content)
                }
            }
        }
    }
}

/**
 * 渲染普通菜单项，支持鼠标悬停与键盘焦点同步高亮；禁用项不写入键盘焦点。
 *
 * @param text 菜单项文本。
 * @param enabled 是否允许点击与聚焦。
 * @param iconKey 菜单项内置图标键。
 * @param iconPath 平台动态图标路径。
 * @param keyboardFocused 是否由键盘或菜单状态高亮。
 * @param onClick 点击回调。
 * @param command 用于展示快捷键的 UI 命令。
 * @param commandShortcuts 面板命令快捷键映射。
 * @param trailingIconKey 尾部展开箭头等辅助图标。
 * @param onPointerEnter 指针进入当前启用项时的回调。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ContextMenuItem(
    text: String,
    enabled: Boolean,
    iconKey: IconKey,
    iconPath: String? = null,
    keyboardFocused: Boolean = false,
    onClick: () -> Unit,
    command: OnyxCommand? = null,
    commandShortcuts: OnyxCommandShortcutMap = OnyxCommandShortcutMap.Default,
    trailingIconKey: IconKey? = null,
    onPointerEnter: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val highlighted = enabled && (isHovered || keyboardFocused)
    val background = if (highlighted) LocalOnyxPalette.current.rowHoverBackground else Color.Transparent
    val contentColor = if (enabled) LocalOnyxPalette.current.foreground else LocalOnyxPalette.current.disabledForeground
    val shortcutHint = if (command == null) null else onyxCommandShortcutHint(command, commandShortcuts)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .onPointerEvent(PointerEventType.Enter) {
                if (enabled) onPointerEnter()
            }
            .background(background, RoundedCornerShape(4.dp))
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else DISABLED_CONTEXT_MENU_ALPHA)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ContextMenuLeadingIcon(iconKey = iconKey, iconPath = iconPath)
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp,
            color = contentColor,
        )
        if (shortcutHint != null) {
            Text(
                text = shortcutHint,
                fontSize = 11.sp,
                color = LocalOnyxPalette.current.mutedForeground,
            )
        }
        if (trailingIconKey != null) {
            Icon(key = trailingIconKey, contentDescription = null)
        }
    }
}

/**
 * 渲染菜单项左侧图标，优先使用平台动态图标，失败时回退到 Jewel 图标。
 *
 * @param iconKey 内置 Jewel 图标键。
 * @param iconPath 平台图标路径或标识。
 */
@Composable
private fun ContextMenuLeadingIcon(
    iconKey: IconKey,
    iconPath: String?,
) {
    var bitmap by remember(iconPath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(iconPath) {
        bitmap = null
        bitmap = iconPath
            ?.takeIf { value -> value.isNotBlank() }
            ?.let { value -> SystemMenuIconBitmapLoader.load(value) }
    }
    val resolvedBitmap = bitmap
    if (resolvedBitmap != null) {
        Image(
            bitmap = resolvedBitmap,
            contentDescription = null,
            modifier = Modifier.size(CONTEXT_MENU_ICON_SIZE_DP.dp),
        )
    } else {
        Icon(key = iconKey, contentDescription = null)
    }
}

/**
 * 级联菜单弹层定位器。
 */
private object CascadingMenuPositionProvider : PopupPositionProvider {
    /**
     * 计算级联子菜单在窗口内的安全展示位置。
     *
     * @param anchorBounds 父菜单项边界。
     * @param windowSize 窗口大小。
     * @param layoutDirection 布局方向。
     * @param popupContentSize 子菜单内容大小。
     * @return 子菜单左上角位置。
     */
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val rightX = anchorBounds.right - SUBMENU_OVERLAP_PX
        val leftX = anchorBounds.left - popupContentSize.width + SUBMENU_OVERLAP_PX
        val opensRight = layoutDirection == LayoutDirection.Ltr
        val preferredX = if (opensRight) rightX else leftX
        val fallbackX = if (opensRight) leftX else rightX
        val resolvedX = if (preferredX in 0..maxX) preferredX else fallbackX
        val y = (anchorBounds.top - SUBMENU_VERTICAL_OFFSET_PX).coerceIn(0, maxY)
        return IntOffset(resolvedX.coerceIn(0, maxX), y)
    }
}

private const val SUBMENU_OVERLAP_PX = 4
private const val SUBMENU_VERTICAL_OFFSET_PX = 4
private const val SUBMENU_CLOSE_DELAY_MS = 180L
private const val CONTEXT_MENU_ICON_SIZE_DP = 16

/** 禁用菜单项的显示透明度。 */
private const val DISABLED_CONTEXT_MENU_ALPHA = 0.55f
