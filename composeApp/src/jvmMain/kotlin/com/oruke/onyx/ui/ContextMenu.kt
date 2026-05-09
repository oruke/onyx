package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.oruke.onyx.app.filesystem.OpenWithApp
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
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// ── Context menu ────────────────────────────────────────────────────────────

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
    openWithApps: List<OpenWithApp>,
    onOpenWith: (OpenWithApp) -> Unit,
    onOpenWithChooser: () -> Unit,
    onRefresh: () -> Unit,
    onOpenTerminal: () -> Unit,
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
        Column(
            modifier = Modifier
                .width(220.dp)
                .border(1.dp, LocalOnyxPalette.current.outlineVariant, RoundedCornerShape(6.dp))
                .background(LocalOnyxPalette.current.floatingSurface, RoundedCornerShape(6.dp))
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            ContextMenuItem(
                text = stringResource(Res.string.action_open),
                enabled = canOpenSelection,
                iconKey = AllIconsKeys.Actions.MenuOpen,
                command = OnyxCommand.OpenSelection,
                onClick = onOpenSelection,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_open_in_new_tab),
                enabled = canOpenSelectionInNewTab,
                iconKey = AllIconsKeys.Actions.OpenNewTab,
                onClick = onOpenSelectionInNewTab,
            )
            // "打开方式" 子菜单
            if (canOpenSelection && openWithApps.isNotEmpty()) {
                var openWithExpanded by remember { mutableStateOf(false) }
                ContextMenuItem(
                    text = stringResource(Res.string.action_open_with) + if (openWithExpanded) " ▾" else " ▸",
                    enabled = true,
                    iconKey = AllIconsKeys.Actions.MenuOpen,
                    onClick = { openWithExpanded = !openWithExpanded },
                )
                if (openWithExpanded) {
                    openWithApps.forEach { app ->
                        ContextMenuItem(
                            text = "  ${app.displayName}",
                            enabled = true,
                            iconKey = AllIconsKeys.Actions.Execute,
                            onClick = { onOpenWith(app) },
                        )
                    }
                    ContextMenuItem(
                        text = "  ${stringResource(Res.string.action_open_with_other)}",
                        enabled = true,
                        iconKey = AllIconsKeys.General.OpenDisk,
                        onClick = onOpenWithChooser,
                    )
                }
            } else if (canOpenSelection) {
                // 无关联应用时只显示「其他应用…」
                ContextMenuItem(
                    text = stringResource(Res.string.action_open_with),
                    enabled = true,
                    iconKey = AllIconsKeys.Actions.MenuOpen,
                    onClick = onOpenWithChooser,
                )
            }
            ContextMenuItem(
                text = stringResource(Res.string.action_rename),
                enabled = canRenameSelection,
                iconKey = AllIconsKeys.Actions.Edit,
                command = OnyxCommand.RenameSelection,
                onClick = onRenameSelection,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_new_file),
                enabled = true,
                iconKey = AllIconsKeys.FileTypes.Any_type,
                command = OnyxCommand.NewFile,
                onClick = onCreateFile,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_new_directory),
                enabled = true,
                iconKey = AllIconsKeys.Nodes.Folder,
                command = OnyxCommand.NewDirectory,
                onClick = onCreateDirectory,
            )
            Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
            ContextMenuItem(
                text = stringResource(Res.string.action_delete_selected),
                enabled = canOperateOnSelection,
                iconKey = AllIconsKeys.General.Delete,
                command = OnyxCommand.DeleteSelection,
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
                onClick = onCopySelection,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_cut),
                enabled = canOperateOnSelection,
                iconKey = AllIconsKeys.Actions.MenuCut,
                command = OnyxCommand.CutSelection,
                onClick = onCutSelection,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_paste),
                enabled = canPaste,
                iconKey = AllIconsKeys.Actions.MenuPaste,
                command = OnyxCommand.Paste,
                onClick = onPaste,
            )
            Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
            ContextMenuItem(
                text = stringResource(Res.string.action_refresh_active),
                enabled = true,
                iconKey = AllIconsKeys.Actions.Refresh,
                command = OnyxCommand.Refresh,
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
                onClick = onClose,
            )
        }
    }
}

@Composable
internal fun ContextMenuItem(
    text: String,
    enabled: Boolean,
    iconKey: org.jetbrains.jewel.ui.icon.IconKey,
    onClick: () -> Unit,
    command: OnyxCommand? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val background = if (enabled && isHovered) LocalOnyxPalette.current.rowHoverBackground else Color.Transparent
    val contentColor = if (enabled) LocalOnyxPalette.current.foreground else LocalOnyxPalette.current.disabledForeground
    val shortcutHint = if (command == null) null else onyxCommandShortcutHint(command)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .background(background, RoundedCornerShape(4.dp))
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.55f)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(key = iconKey, contentDescription = null)
        Text(text = text, fontSize = 12.sp, color = contentColor)
        if (shortcutHint != null) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = shortcutHint,
                fontSize = 11.sp,
                color = LocalOnyxPalette.current.mutedForeground,
            )
        }
    }
}
