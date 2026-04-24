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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.oruke.onyx.ui.theme.OnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_close_menu
import onyx.composeapp.generated.resources.action_copy
import onyx.composeapp.generated.resources.action_copy_path
import onyx.composeapp.generated.resources.action_cut
import onyx.composeapp.generated.resources.action_delete_selected
import onyx.composeapp.generated.resources.action_new_directory
import onyx.composeapp.generated.resources.action_new_file
import onyx.composeapp.generated.resources.action_open
import onyx.composeapp.generated.resources.action_open_in_new_tab
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
    onOpenSelection: () -> Unit,
    onOpenSelectionInNewTab: () -> Unit,
    onRenameSelection: () -> Unit,
    onCreateFile: () -> Unit,
    onCreateDirectory: () -> Unit,
    onDeleteSelection: () -> Unit,
    onCopyPath: () -> Unit,
    onCopySelection: () -> Unit,
    onCutSelection: () -> Unit,
    onPaste: () -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    palette: OnyxPalette,
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
                .border(1.dp, palette.outlineVariant, RoundedCornerShape(6.dp))
                .background(palette.floatingSurface, RoundedCornerShape(6.dp))
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            ContextMenuItem(
                text = stringResource(Res.string.action_open),
                enabled = canOpenSelection,
                iconKey = AllIconsKeys.Actions.ListFiles,
                palette = palette,
                onClick = onOpenSelection,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_open_in_new_tab),
                enabled = canOpenSelectionInNewTab,
                iconKey = AllIconsKeys.General.OpenDisk,
                palette = palette,
                onClick = onOpenSelectionInNewTab,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_rename),
                enabled = canRenameSelection,
                iconKey = AllIconsKeys.Actions.ListFiles,
                palette = palette,
                onClick = onRenameSelection,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_new_file),
                enabled = true,
                iconKey = AllIconsKeys.FileTypes.Any_type,
                palette = palette,
                onClick = onCreateFile,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_new_directory),
                enabled = true,
                iconKey = AllIconsKeys.Nodes.Folder,
                palette = palette,
                onClick = onCreateDirectory,
            )
            Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
            ContextMenuItem(
                text = stringResource(Res.string.action_delete_selected),
                enabled = canOperateOnSelection,
                iconKey = AllIconsKeys.Actions.DeleteTag,
                palette = palette,
                onClick = onDeleteSelection,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_copy_path),
                enabled = canCopyPath,
                iconKey = AllIconsKeys.Actions.Copy,
                palette = palette,
                onClick = onCopyPath,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_copy),
                enabled = canOperateOnSelection,
                iconKey = AllIconsKeys.Actions.Copy,
                palette = palette,
                onClick = onCopySelection,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_cut),
                enabled = canOperateOnSelection,
                iconKey = AllIconsKeys.Actions.MenuCut,
                palette = palette,
                onClick = onCutSelection,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_paste),
                enabled = canPaste,
                iconKey = AllIconsKeys.Actions.MenuPaste,
                palette = palette,
                onClick = onPaste,
            )
            Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth().height(1.dp))
            ContextMenuItem(
                text = stringResource(Res.string.action_refresh_active),
                enabled = true,
                iconKey = AllIconsKeys.Actions.Refresh,
                palette = palette,
                onClick = onRefresh,
            )
            ContextMenuItem(
                text = stringResource(Res.string.action_close_menu),
                enabled = true,
                iconKey = AllIconsKeys.Actions.Close,
                palette = palette,
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
    palette: OnyxPalette,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val background = if (enabled && isHovered) palette.rowHoverBackground else Color.Transparent
    val contentColor = if (enabled) palette.foreground else palette.disabledForeground

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
    }
}

