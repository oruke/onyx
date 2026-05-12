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
import com.oruke.onyx.app.filesystem.FileContextMenuCommand
import com.oruke.onyx.app.filesystem.FileContextMenuItem
import com.oruke.onyx.app.filesystem.FileContextMenuLabel
import com.oruke.onyx.app.filesystem.FileContextMenuSection
import com.oruke.onyx.app.filesystem.FileContextMenuSectionKind
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_batch_rename
import onyx.composeapp.generated.resources.action_close_menu
import onyx.composeapp.generated.resources.action_copy
import onyx.composeapp.generated.resources.action_copy_path
import onyx.composeapp.generated.resources.action_cut
import onyx.composeapp.generated.resources.action_delete_selected
import onyx.composeapp.generated.resources.action_extract_here
import onyx.composeapp.generated.resources.action_extract_to_directory
import onyx.composeapp.generated.resources.action_extract_smart
import onyx.composeapp.generated.resources.action_new_directory
import onyx.composeapp.generated.resources.action_new_file
import onyx.composeapp.generated.resources.action_open
import onyx.composeapp.generated.resources.action_open_in_new_tab
import onyx.composeapp.generated.resources.action_open_terminal
import onyx.composeapp.generated.resources.action_open_with
import onyx.composeapp.generated.resources.action_open_with_other
import onyx.composeapp.generated.resources.action_paste
import onyx.composeapp.generated.resources.action_refresh_active
import onyx.composeapp.generated.resources.action_rename
import onyx.composeapp.generated.resources.action_system_menu
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
internal fun BoxScope.PaneContextMenu(
    anchorOffset: IntOffset,
    canOperateOnSelection: Boolean,
    canOpenSelection: Boolean,
    canOpenSelectionInNewTab: Boolean,
    canRenameSelection: Boolean,
    canCopyPath: Boolean,
    canPaste: Boolean,
    canExtractSelection: Boolean,
    canBatchRename: Boolean,
    onOpenSelection: () -> Unit,
    onOpenSelectionInNewTab: () -> Unit,
    onRenameSelection: () -> Unit,
    onBatchRename: () -> Unit,
    onCreateFile: () -> Unit,
    onCreateDirectory: () -> Unit,
    onDeleteSelection: () -> Unit,
    onExtractSelection: () -> Unit,
    onExtractToDirectory: () -> Unit,
    onExtractSmart: () -> Unit,
    onCopyPath: () -> Unit,
    onCopySelection: () -> Unit,
    onCutSelection: () -> Unit,
    onPaste: () -> Unit,
    contextMenuSections: List<FileContextMenuSection>,
    onFileContextMenuCommand: (FileContextMenuCommand) -> Unit,
    onRefresh: () -> Unit,
    onOpenTerminal: () -> Unit,
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
        val openWithSection = contextMenuSections.firstOrNull { section ->
            section.kind == FileContextMenuSectionKind.OPEN_WITH && section.items.isNotEmpty()
        }
        val systemSection = contextMenuSections.firstOrNull { section ->
            section.kind == FileContextMenuSectionKind.SYSTEM && section.items.isNotEmpty()
        }
        ContextMenuPanel {
            ContextMenuItem(
                text = stringResource(Res.string.action_open),
                enabled = canOpenSelection,
                iconKey = AllIconsKeys.Actions.MenuOpen,
                command = OnyxCommand.OpenSelection,
                commandShortcuts = commandShortcuts,
                onClick = onOpenSelection,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_open_in_new_tab),
                enabled = canOpenSelectionInNewTab,
                iconKey = AllIconsKeys.Actions.OpenNewTab,
                onClick = onOpenSelectionInNewTab,
            )
            if (openWithSection != null) {
                var openWithExpanded by remember { mutableStateOf(false) }
                ContextMenuSubmenuItem(
                    text = stringResource(Res.string.action_open_with),
                    enabled = true,
                    iconKey = AllIconsKeys.Actions.MenuOpen,
                    expanded = openWithExpanded,
                    onExpandedChange = { expanded -> openWithExpanded = expanded },
                ) {
                    FileContextMenuItems(
                        items = openWithSection.items,
                        onFileContextMenuCommand = onFileContextMenuCommand,
                    )
                }
            }
            if (canOperateOnSelection && systemSection != null) {
                Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
                FileContextMenuItems(
                    items = systemSection.items,
                    onFileContextMenuCommand = onFileContextMenuCommand,
                )
            }
            ContextMenuItem(
                text = stringResource(Res.string.action_rename),
                enabled = canRenameSelection,
                iconKey = AllIconsKeys.Actions.Edit,
                command = OnyxCommand.RenameSelection,
                commandShortcuts = commandShortcuts,
                onClick = onRenameSelection,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_new_file),
                enabled = true,
                iconKey = AllIconsKeys.FileTypes.Any_type,
                command = OnyxCommand.NewFile,
                commandShortcuts = commandShortcuts,
                onClick = onCreateFile,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_new_directory),
                enabled = true,
                iconKey = AllIconsKeys.Nodes.Folder,
                command = OnyxCommand.NewDirectory,
                commandShortcuts = commandShortcuts,
                onClick = onCreateDirectory,
            )
            Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
            ContextMenuItem(
                text = stringResource(Res.string.action_delete_selected),
                enabled = canOperateOnSelection,
                iconKey = AllIconsKeys.General.Delete,
                command = OnyxCommand.DeleteSelection,
                commandShortcuts = commandShortcuts,
                onClick = onDeleteSelection,
            )
            if (canExtractSelection) {
                ContextMenuItem(
                    text = stringResource(Res.string.action_extract_here),
                    enabled = true,
                    iconKey = AllIconsKeys.Nodes.ExtractedFolder,
                    onClick = onExtractSelection,
                )
                ContextMenuItem(
                    text = stringResource(Res.string.action_extract_to_directory),
                    enabled = true,
                    iconKey = AllIconsKeys.Nodes.ExtractedFolder,
                    onClick = onExtractToDirectory,
                )
                ContextMenuItem(
                    text = stringResource(Res.string.action_extract_smart),
                    enabled = true,
                    iconKey = AllIconsKeys.Nodes.ExtractedFolder,
                    onClick = onExtractSmart,
                )
            }
            if (canBatchRename) {
                ContextMenuItem(
                    text = stringResource(Res.string.action_batch_rename),
                    enabled = true,
                    iconKey = AllIconsKeys.Actions.Edit,
                    onClick = onBatchRename,
                )
            }
            ContextMenuItem(
                text = stringResource(Res.string.action_copy_path),
                enabled = canCopyPath,
                iconKey = AllIconsKeys.Actions.Copy,
                onClick = onCopyPath,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_copy),
                enabled = canOperateOnSelection,
                iconKey = AllIconsKeys.Actions.Copy,
                command = OnyxCommand.CopySelection,
                commandShortcuts = commandShortcuts,
                onClick = onCopySelection,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_cut),
                enabled = canOperateOnSelection,
                iconKey = AllIconsKeys.Actions.MenuCut,
                command = OnyxCommand.CutSelection,
                commandShortcuts = commandShortcuts,
                onClick = onCutSelection,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_paste),
                enabled = canPaste,
                iconKey = AllIconsKeys.Actions.MenuPaste,
                command = OnyxCommand.Paste,
                commandShortcuts = commandShortcuts,
                onClick = onPaste,
            )
            Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
            ContextMenuItem(
                text = stringResource(Res.string.action_refresh_active),
                enabled = true,
                iconKey = AllIconsKeys.Actions.Refresh,
                command = OnyxCommand.Refresh,
                commandShortcuts = commandShortcuts,
                onClick = onRefresh,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_open_terminal),
                enabled = true,
                iconKey = AllIconsKeys.Debugger.Console,
                onClick = onOpenTerminal,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_close_menu),
                enabled = true,
                iconKey = AllIconsKeys.Actions.Close,
                command = OnyxCommand.CloseMenu,
                commandShortcuts = commandShortcuts,
                onClick = onClose,
            )
        }
    }
}

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

@Composable
private fun FileContextMenuItems(
    items: List<FileContextMenuItem>,
    onFileContextMenuCommand: (FileContextMenuCommand) -> Unit,
) {
    var expandedActionId by remember { mutableStateOf<String?>(null) }
    items.forEach { item ->
        if (item.children.isNotEmpty()) {
            ContextMenuSubmenuItem(
                text = item.contextMenuText(),
                enabled = true,
                iconKey = item.contextMenuIconKey(),
                expanded = expandedActionId == item.id,
                onExpandedChange = { expanded ->
                    expandedActionId = if (expanded) item.id else null
                },
            ) {
                FileContextMenuItems(
                    items = item.children,
                    onFileContextMenuCommand = onFileContextMenuCommand,
                )
            }
        } else {
            val command = item.command ?: return@forEach
            ContextMenuItem(
                text = item.contextMenuText(),
                enabled = true,
                iconKey = item.contextMenuIconKey(),
                onPointerEnter = { expandedActionId = null },
                onClick = { onFileContextMenuCommand(command) },
            )
        }
    }
}

@Composable
private fun FileContextMenuItem.contextMenuText(): String {
    return when (label) {
        FileContextMenuLabel.OPEN_WITH_OTHER -> stringResource(Res.string.action_open_with_other)
        null -> displayName
    }
}

private fun FileContextMenuItem.contextMenuIconKey(): IconKey {
    return when (command) {
        FileContextMenuCommand.OpenWithChooser -> AllIconsKeys.General.OpenDisk
        else -> AllIconsKeys.Actions.Execute
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ContextMenuSubmenuItem(
    text: String,
    enabled: Boolean,
    iconKey: IconKey,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var closeJob by remember { mutableStateOf<Job?>(null) }
    fun keepOpen() {
        closeJob?.cancel()
        closeJob = null
        if (enabled) onExpandedChange(true)
    }
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ContextMenuItem(
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

private object CascadingMenuPositionProvider : PopupPositionProvider {
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
