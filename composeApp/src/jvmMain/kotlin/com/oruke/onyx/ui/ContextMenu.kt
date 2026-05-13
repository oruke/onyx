package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
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
import com.oruke.onyx.app.component.PaneContextMenuIcon
import com.oruke.onyx.app.component.PaneContextMenuModel
import com.oruke.onyx.app.component.PaneContextMenuNode
import com.oruke.onyx.app.component.PaneContextMenuText
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_batch_rename
import onyx.composeapp.generated.resources.action_close_menu
import onyx.composeapp.generated.resources.action_copy
import onyx.composeapp.generated.resources.action_copy_path
import onyx.composeapp.generated.resources.action_cut
import onyx.composeapp.generated.resources.action_delete_selected
import onyx.composeapp.generated.resources.action_extract_here
import onyx.composeapp.generated.resources.action_extract_smart
import onyx.composeapp.generated.resources.action_extract_to_directory
import onyx.composeapp.generated.resources.action_new_directory
import onyx.composeapp.generated.resources.action_new_file
import onyx.composeapp.generated.resources.action_open
import onyx.composeapp.generated.resources.action_open_in_new_tab
import onyx.composeapp.generated.resources.action_open_terminal
import onyx.composeapp.generated.resources.action_open_with
import onyx.composeapp.generated.resources.action_open_with_other
import onyx.composeapp.generated.resources.action_paste
import onyx.composeapp.generated.resources.action_redo
import onyx.composeapp.generated.resources.action_refresh_active
import onyx.composeapp.generated.resources.action_rename
import onyx.composeapp.generated.resources.action_undo
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * 面板右键菜单弹层。
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
        properties = PopupProperties(focusable = false),
    ) {
        ContextMenuPanel {
            PaneContextMenuNodes(
                nodes = model.nodes,
                commandShortcuts = commandShortcuts,
                onCommand = onCommand,
            )
        }
    }
}

/**
 * 右键菜单面板容器。
 *
 * @param content 菜单内容。
 */
@Composable
private fun ContextMenuPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .width(220.dp)
            .border(1.dp, LocalOnyxPalette.current.outlineVariant, RoundedCornerShape(6.dp))
            .background(LocalOnyxPalette.current.floatingSurface, RoundedCornerShape(6.dp))
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
        content = content,
    )
}

/**
 * 渲染统一菜单节点列表，并维护同级级联菜单的展开状态。
 *
 * @param nodes 菜单节点列表。
 * @param commandShortcuts 面板命令快捷键映射。
 * @param onCommand 菜单命令点击回调。
 * @param onItemPointerEnter 指针进入任意子项时通知父级菜单的回调。
 */
@Composable
private fun PaneContextMenuNodes(
    nodes: List<PaneContextMenuNode>,
    commandShortcuts: OnyxCommandShortcutMap,
    onCommand: (PaneContextMenuCommand) -> Unit,
    onItemPointerEnter: () -> Unit = {},
) {
    var expandedItemId by remember { mutableStateOf<String?>(null) }
    nodes.forEach { node ->
        when (node) {
            PaneContextMenuNode.Divider -> {
                Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
            }

            is PaneContextMenuNode.Item -> {
                PaneContextMenuItemNode(
                    item = node,
                    commandShortcuts = commandShortcuts,
                    expanded = expandedItemId == node.id,
                    onExpandedChange = { expanded ->
                        expandedItemId = if (expanded) node.id else expandedItemId.takeUnless { it == node.id }
                    },
                    onPointerEnter = {
                        onItemPointerEnter()
                        if (node.children.isEmpty()) expandedItemId = null
                    },
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
 * @param commandShortcuts 面板命令快捷键映射。
 * @param expanded 当前级联菜单是否展开。
 * @param onExpandedChange 级联菜单展开状态变更回调。
 * @param onPointerEnter 指针进入当前项时的回调。
 * @param onCommand 菜单命令点击回调。
 */
@Composable
private fun PaneContextMenuItemNode(
    item: PaneContextMenuNode.Item,
    commandShortcuts: OnyxCommandShortcutMap,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPointerEnter: () -> Unit,
    onCommand: (PaneContextMenuCommand) -> Unit,
) {
    val text = item.contextMenuText()
    val iconKey = item.icon.toIconKey(item.iconPath)
    if (item.children.isNotEmpty()) {
        ContextMenuSubmenuItem(
            text = text,
            enabled = item.enabled,
            iconKey = iconKey,
            expanded = expanded,
            onPointerEnter = onPointerEnter,
            onExpandedChange = onExpandedChange,
        ) {
            PaneContextMenuNodes(
                nodes = item.children,
                commandShortcuts = commandShortcuts,
                onCommand = onCommand,
            )
        }
    } else {
        val command = item.command
        ContextMenuItem(
            text = text,
            enabled = item.enabled && command != null,
            iconKey = iconKey,
            command = command?.shortcutCommandOrNull(),
            commandShortcuts = commandShortcuts,
            onPointerEnter = onPointerEnter,
            onClick = {
                if (command != null) onCommand(command)
            },
        )
    }
}

/**
 * 渲染级联菜单项，并在鼠标离开时延迟关闭。
 *
 * @param text 菜单项文本。
 * @param enabled 是否允许展开。
 * @param iconKey 菜单项图标。
 * @param expanded 当前是否展开。
 * @param onPointerEnter 指针进入当前项时的回调。
 * @param onExpandedChange 展开状态变更回调。
 * @param content 子菜单内容。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ContextMenuSubmenuItem(
    text: String,
    enabled: Boolean,
    iconKey: IconKey,
    expanded: Boolean,
    onPointerEnter: () -> Unit = {},
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var closeJob by remember { mutableStateOf<Job?>(null) }

    /**
     * 保持当前级联菜单展开。
     */
    fun keepOpen() {
        closeJob?.cancel()
        closeJob = null
        onPointerEnter()
        if (enabled) onExpandedChange(true)
    }

    /**
     * 延迟关闭当前级联菜单，给鼠标移动到子菜单留出容错时间。
     */
    fun closeSoon() {
        closeJob?.cancel()
        closeJob = coroutineScope.launch {
            delay(SUBMENU_CLOSE_DELAY_MS)
            onExpandedChange(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onPointerEvent(PointerEventType.Enter) {
                keepOpen()
            }
            .onPointerEvent(PointerEventType.Exit) {
                closeSoon()
            },
    ) {
        ContextMenuItem(
            text = text,
            enabled = enabled,
            iconKey = iconKey,
            trailingIconKey = AllIconsKeys.General.ArrowRight,
            onClick = { onExpandedChange(!expanded) },
        )
        if (enabled && expanded) {
            Popup(
                popupPositionProvider = CascadingMenuPositionProvider,
                onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(focusable = false),
            ) {
                Box(
                    modifier = Modifier
                        .onPointerEvent(PointerEventType.Enter) {
                            keepOpen()
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
 * 渲染普通菜单项。
 *
 * @param text 菜单项文本。
 * @param enabled 是否允许点击。
 * @param iconKey 菜单项图标。
 * @param onClick 点击回调。
 * @param command 用于展示快捷键的 UI 命令。
 * @param commandShortcuts 面板命令快捷键映射。
 * @param trailingIconKey 尾部图标。
 * @param onPointerEnter 指针进入当前项时的回调。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ContextMenuItem(
    text: String,
    enabled: Boolean,
    iconKey: IconKey,
    onClick: () -> Unit,
    command: OnyxCommand? = null,
    commandShortcuts: OnyxCommandShortcutMap = OnyxCommandShortcutMap.Default,
    trailingIconKey: IconKey? = null,
    onPointerEnter: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val background = if (enabled && isHovered) LocalOnyxPalette.current.rowHoverBackground else Color.Transparent
    val contentColor = if (enabled) LocalOnyxPalette.current.foreground else LocalOnyxPalette.current.disabledForeground
    val shortcutHint = if (command == null) null else onyxCommandShortcutHint(command, commandShortcuts)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .onPointerEvent(PointerEventType.Enter) { onPointerEnter() }
            .background(background, RoundedCornerShape(4.dp))
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.55f)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(key = iconKey, contentDescription = null)
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
 * 将菜单项文案语义解析为可显示文本。
 *
 * @return 当前语言下的菜单文本。
 */
@Composable
private fun PaneContextMenuNode.Item.contextMenuText(): String {
    return text?.stringResourceValue() ?: displayName.orEmpty()
}

/**
 * 将内置菜单文案语义映射到资源。
 *
 * @return 字符串资源。
 */
private fun PaneContextMenuText.stringResourceKey(): StringResource {
    return when (this) {
        PaneContextMenuText.OPEN -> Res.string.action_open
        PaneContextMenuText.OPEN_IN_NEW_TAB -> Res.string.action_open_in_new_tab
        PaneContextMenuText.OPEN_WITH -> Res.string.action_open_with
        PaneContextMenuText.OPEN_WITH_OTHER -> Res.string.action_open_with_other
        PaneContextMenuText.RENAME -> Res.string.action_rename
        PaneContextMenuText.NEW_FILE -> Res.string.action_new_file
        PaneContextMenuText.NEW_DIRECTORY -> Res.string.action_new_directory
        PaneContextMenuText.DELETE_SELECTED -> Res.string.action_delete_selected
        PaneContextMenuText.EXTRACT_HERE -> Res.string.action_extract_here
        PaneContextMenuText.EXTRACT_TO_DIRECTORY -> Res.string.action_extract_to_directory
        PaneContextMenuText.EXTRACT_SMART -> Res.string.action_extract_smart
        PaneContextMenuText.BATCH_RENAME -> Res.string.action_batch_rename
        PaneContextMenuText.COPY_PATH -> Res.string.action_copy_path
        PaneContextMenuText.COPY -> Res.string.action_copy
        PaneContextMenuText.CUT -> Res.string.action_cut
        PaneContextMenuText.PASTE -> Res.string.action_paste
        PaneContextMenuText.UNDO -> Res.string.action_undo
        PaneContextMenuText.REDO -> Res.string.action_redo
        PaneContextMenuText.REFRESH -> Res.string.action_refresh_active
        PaneContextMenuText.OPEN_TERMINAL -> Res.string.action_open_terminal
        PaneContextMenuText.CLOSE_MENU -> Res.string.action_close_menu
    }
}

/**
 * 读取内置菜单文案的当前语言字符串。
 *
 * @return 当前语言字符串。
 */
@Composable
private fun PaneContextMenuText.stringResourceValue(): String {
    return stringResource(stringResourceKey())
}

/**
 * 将统一菜单图标语义映射到 Jewel 图标。
 *
 * @param iconPath 平台动态图标路径或标识，当前用于保留菜单来源信息。
 * @return Jewel 图标键。
 */
private fun PaneContextMenuIcon.toIconKey(iconPath: String?): IconKey {
    return when (this) {
        PaneContextMenuIcon.OPEN -> AllIconsKeys.Actions.MenuOpen
        PaneContextMenuIcon.OPEN_IN_NEW_TAB -> AllIconsKeys.Actions.OpenNewTab
        PaneContextMenuIcon.EDIT -> AllIconsKeys.Actions.Edit
        PaneContextMenuIcon.FILE -> AllIconsKeys.FileTypes.Any_type
        PaneContextMenuIcon.FOLDER -> AllIconsKeys.Nodes.Folder
        PaneContextMenuIcon.DELETE -> AllIconsKeys.General.Delete
        PaneContextMenuIcon.EXTRACT -> AllIconsKeys.Nodes.ExtractedFolder
        PaneContextMenuIcon.COPY -> AllIconsKeys.Actions.Copy
        PaneContextMenuIcon.CUT -> AllIconsKeys.Actions.MenuCut
        PaneContextMenuIcon.PASTE -> AllIconsKeys.Actions.MenuPaste
        PaneContextMenuIcon.UNDO -> AllIconsKeys.Actions.Back
        PaneContextMenuIcon.REDO -> AllIconsKeys.Actions.Forward
        PaneContextMenuIcon.REFRESH -> AllIconsKeys.Actions.Refresh
        PaneContextMenuIcon.TERMINAL -> AllIconsKeys.Debugger.Console
        PaneContextMenuIcon.CLOSE -> AllIconsKeys.Actions.Close
        PaneContextMenuIcon.OPEN_WITH -> AllIconsKeys.General.OpenDisk
        PaneContextMenuIcon.EXECUTE -> {
            if (iconPath.isNullOrBlank()) AllIconsKeys.Actions.Execute else AllIconsKeys.FileTypes.Any_type
        }
    }
}

/**
 * 解析菜单命令对应的 UI 快捷键命令。
 *
 * @return 可展示快捷键的 UI 命令。
 */
private fun PaneContextMenuCommand.shortcutCommandOrNull(): OnyxCommand? {
    return when (this) {
        is PaneContextMenuCommand.Pane -> command.toOnyxCommand()
        PaneContextMenuCommand.BatchRename,
        PaneContextMenuCommand.ExtractSelection,
        PaneContextMenuCommand.ExtractToDirectory,
        PaneContextMenuCommand.ExtractSmart,
        PaneContextMenuCommand.CopyPath,
        PaneContextMenuCommand.OpenTerminal,
        is PaneContextMenuCommand.FileContext -> null
    }
}

/**
 * 级联菜单弹层定位器。
 */
private object CascadingMenuPositionProvider : PopupPositionProvider {
    /**
     * 计算级联菜单位置。
     *
     * @param anchorBounds 父菜单项边界。
     * @param windowSize 窗口大小。
     * @param layoutDirection 布局方向。
     * @param popupContentSize 子菜单大小。
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
