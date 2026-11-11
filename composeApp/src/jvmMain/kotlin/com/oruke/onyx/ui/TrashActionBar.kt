package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.TrashContextMenuAction
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_trash_delete_permanently
import onyx.composeapp.generated.resources.action_trash_empty
import onyx.composeapp.generated.resources.action_trash_restore_all
import onyx.composeapp.generated.resources.action_trash_restore_selected
import onyx.composeapp.generated.resources.label_trash_empty_view
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** 回收站操作栏禁用按钮透明度。 */
private const val DISABLED_TRASH_ACTION_ALPHA = 0.45f

/**
 * 渲染回收站视图专属的高密度顶部操作栏，提供还原所选、全部还原、永久删除和清空回收站动作。
 *
 * @param runtime 当前面板视图运行时控制器。
 */
@Composable
internal fun TrashActionBar(runtime: PaneSurfaceRuntime) {
    val entriesReady = runtime.state.entriesState as? PaneEntriesState.Ready
    val readyEntries = entriesReady?.entries.orEmpty()
    val selectedIds = runtime.state.selectedEntryIds
    val selectedEntries = readyEntries.filter { entry -> entry.id in selectedIds }
    val selectedTargetIds = selectedEntries.mapTo(linkedSetOf()) { entry -> entry.id }

    val canRestoreSelected = selectedEntries.any { entry -> entry.parentLocation != null }
    val canRestoreAll = readyEntries.any { entry -> entry.parentLocation != null }
    val canDeletePermanently = selectedEntries.isNotEmpty()
    val canEmptyTrash = readyEntries.isNotEmpty()

    fun executeTrashAction(action: TrashContextMenuAction) {
        runtime.model.onActivate()
        runtime.model.actions.onTrashAction(action)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("trash-action-bar")
            .background(LocalOnyxPalette.current.headerBackground)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TrashActionButton(
            text = stringResource(Res.string.action_trash_restore_selected),
            iconKey = AllIconsKeys.Actions.Back,
            enabled = canRestoreSelected,
            modifier = Modifier.testTag("trash-action-restore-selected"),
            onClick = { executeTrashAction(TrashContextMenuAction.Restore(selectedTargetIds)) },
        )
        TrashActionButton(
            text = stringResource(Res.string.action_trash_restore_all),
            iconKey = AllIconsKeys.Actions.Back,
            enabled = canRestoreAll,
            modifier = Modifier.testTag("trash-action-restore-all"),
            onClick = { executeTrashAction(TrashContextMenuAction.RestoreAll) },
        )
        TrashActionButton(
            text = stringResource(Res.string.action_trash_delete_permanently),
            iconKey = AllIconsKeys.General.Delete,
            enabled = canDeletePermanently,
            destructive = true,
            modifier = Modifier.testTag("trash-action-delete-permanently"),
            onClick = { executeTrashAction(TrashContextMenuAction.DeletePermanently(selectedTargetIds)) },
        )
        TrashActionButton(
            text = stringResource(Res.string.action_trash_empty),
            iconKey = AllIconsKeys.General.Delete,
            enabled = canEmptyTrash,
            destructive = true,
            modifier = Modifier.testTag("trash-action-empty"),
            onClick = { executeTrashAction(TrashContextMenuAction.EmptyTrash) },
        )
        if (entriesReady != null && readyEntries.isEmpty()) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(Res.string.label_trash_empty_view),
                fontSize = 11.sp,
                color = LocalOnyxPalette.current.mutedForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 渲染回收站顶部操作栏中的紧凑图文按钮。
 *
 * @param text 按钮显示文案。
 * @param iconKey 按钮前置图标键。
 * @param enabled 是否允许点击。
 * @param onClick 点击动作回调。
 * @param destructive 是否为不可逆删除动作。
 * @param modifier 外部布局与测试标记修饰符。
 */
@Composable
private fun TrashActionButton(
    text: String,
    iconKey: IconKey,
    enabled: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val palette = LocalOnyxPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val background = when {
        !enabled -> Color.Transparent
        isPressed -> palette.titleBarPressedBackground
        isHovered -> palette.rowHoverBackground
        else -> palette.surface
    }
    val borderColor = when {
        !enabled -> palette.outlineVariant.copy(alpha = 0.5f)
        isHovered -> palette.outline
        else -> palette.outlineVariant
    }
    val textColor = when {
        !enabled -> palette.disabledForeground
        destructive && isHovered -> palette.error
        else -> palette.foreground
    }

    Row(
        modifier = modifier
            .hoverable(enabled = enabled, interactionSource = interactionSource)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .background(background, RoundedCornerShape(4.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else DISABLED_TRASH_ACTION_ALPHA)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            key = iconKey,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
