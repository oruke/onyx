package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Text
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_close_menu
import onyx.composeapp.generated.resources.action_delete_selected
import onyx.composeapp.generated.resources.action_keep_both
import onyx.composeapp.generated.resources.action_move
import onyx.composeapp.generated.resources.action_overwrite
import onyx.composeapp.generated.resources.action_skip
import onyx.composeapp.generated.resources.label_conflict_resolution_message
import onyx.composeapp.generated.resources.label_conflict_resolution_title
import onyx.composeapp.generated.resources.label_delete_confirmation_move_to_trash
import onyx.composeapp.generated.resources.label_delete_confirmation_permanent
import onyx.composeapp.generated.resources.label_delete_confirmation_trash_unavailable
import onyx.composeapp.generated.resources.label_operation_copy
import onyx.composeapp.generated.resources.label_operation_move
import onyx.composeapp.generated.resources.message_apply_to_remaining_conflicts

@Composable
internal fun ConfirmationDialog(
    state: RootDialogState.DeleteSelectionConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = if (state.moveToTrash) {
        stringResource(Res.string.action_move)
    } else {
        stringResource(Res.string.action_delete_selected)
    }
    val message = buildString {
        append(
            if (state.moveToTrash) {
                stringResource(Res.string.label_delete_confirmation_move_to_trash, state.itemCount)
            } else {
                stringResource(Res.string.label_delete_confirmation_permanent, state.itemCount)
            }
        )
        if (state.trashUnavailable) {
            append("\n")
            append(stringResource(Res.string.label_delete_confirmation_trash_unavailable))
        }
    }
    val confirmLabel = if (state.moveToTrash) {
        stringResource(Res.string.action_move)
    } else {
        stringResource(Res.string.action_delete_selected)
    }
    // 0 = 取消（默认安全焦点），1 = 确认
    var focusedButton by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    DialogWindow(
        onCloseRequest = onDismiss,
        title = title,
        state = rememberDialogState(width = 420.dp, height = 220.dp),
        resizable = false,
    ) {
        IntUiTheme(isDark = isSystemInDarkTheme()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LocalOnyxPalette.current.appBackground)
                    .padding(14.dp)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Escape -> { onDismiss(); true }
                            Key.Enter -> {
                                if (focusedButton == 1) onConfirm() else onDismiss()
                                true
                            }
                            Key.DirectionLeft, Key.Tab -> {
                                focusedButton = (focusedButton - 1).coerceAtLeast(0)
                                true
                            }
                            Key.DirectionRight -> {
                                focusedButton = (focusedButton + 1).coerceAtMost(1)
                                true
                            }
                            else -> false
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LocalOnyxPalette.current.foreground,
                )
                Box(modifier = Modifier.weight(1f, fill = true)) {
                    Text(
                        text = message,
                        fontSize = 12.sp,
                        color = LocalOnyxPalette.current.foreground,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DialogTextButton(
                        text = stringResource(Res.string.action_close_menu),
                        onClick = onDismiss,
                        focused = focusedButton == 0,
                    )
                    DialogTextButton(
                        text = confirmLabel,
                        emphasized = true,
                        destructive = true,
                        onClick = onConfirm,
                        focused = focusedButton == 1,
                    )
                }
            }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        }
    }
}

@Composable
internal fun ConflictResolutionDialog(
    state: RootDialogState.ConflictResolution,
    onResolve: (TransferConflictStrategy, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var applyToAll by remember(state.sourceName, state.currentIndex, state.total) { mutableStateOf(false) }
    // 0=取消, 1=跳过, 2=保留两者, 3=覆盖
    var focusedButton by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val operationLabel = stringResource(
        if (state.operation == FileTransferOperation.COPY) {
            Res.string.label_operation_copy
        } else {
            Res.string.label_operation_move
        }
    )
    val dialogTitle = stringResource(Res.string.label_conflict_resolution_title, state.currentIndex, state.total)
    DialogWindow(
        onCloseRequest = onDismiss,
        title = dialogTitle,
        state = rememberDialogState(width = 460.dp, height = 260.dp),
        resizable = false,
    ) {
        IntUiTheme(isDark = isSystemInDarkTheme()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LocalOnyxPalette.current.appBackground)
                    .padding(14.dp)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Escape -> { onDismiss(); true }
                            Key.Enter -> {
                                when (focusedButton) {
                                    0 -> onDismiss()
                                    1 -> onResolve(TransferConflictStrategy.SKIP, applyToAll)
                                    2 -> onResolve(TransferConflictStrategy.KEEP_BOTH, applyToAll)
                                    LAST_CONFLICT_ACTION_INDEX -> {
                                        onResolve(TransferConflictStrategy.OVERWRITE, applyToAll)
                                    }
                                }
                                true
                            }
                            Key.DirectionLeft -> {
                                focusedButton = (focusedButton - 1).coerceAtLeast(0)
                                true
                            }
                            Key.DirectionRight, Key.Tab -> {
                                focusedButton = (focusedButton + 1).coerceAtMost(LAST_CONFLICT_ACTION_INDEX)
                                true
                            }
                            else -> false
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = dialogTitle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LocalOnyxPalette.current.foreground,
                )
                Box(modifier = Modifier.weight(1f, fill = true)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = operationLabel,
                            fontSize = 11.sp,
                            color = LocalOnyxPalette.current.mutedForeground,
                        )
                        Text(
                            text = stringResource(
                                Res.string.label_conflict_resolution_message,
                                state.sourceName,
                                state.targetLocation,
                            ),
                            fontSize = 12.sp,
                            color = LocalOnyxPalette.current.foreground,
                        )
                        ApplyToAllToggle(
                            checked = applyToAll,
                            text = stringResource(Res.string.message_apply_to_remaining_conflicts),
                            onToggle = { applyToAll = !applyToAll },
                        )
                    }
                }
                ConflictResolutionActions(
                    focusedButton = focusedButton,
                    applyToAll = applyToAll,
                    onResolve = onResolve,
                    onDismiss = onDismiss,
                )
            }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        }
    }
}

/**
 * 绘制冲突解决窗口的四个动作按钮。
 *
 * @param focusedButton 当前键盘焦点按钮索引。
 * @param applyToAll 是否应用到剩余冲突。
 * @param onResolve 冲突策略回调。
 * @param onDismiss 关闭回调。
 */
@Composable
private fun ConflictResolutionActions(
    focusedButton: Int,
    applyToAll: Boolean,
    onResolve: (TransferConflictStrategy, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DialogTextButton(
            text = stringResource(Res.string.action_close_menu),
            onClick = onDismiss,
            focused = focusedButton == 0,
        )
        DialogTextButton(
            text = stringResource(Res.string.action_skip),
            onClick = { onResolve(TransferConflictStrategy.SKIP, applyToAll) },
            focused = focusedButton == 1,
        )
        DialogTextButton(
            text = stringResource(Res.string.action_keep_both),
            onClick = { onResolve(TransferConflictStrategy.KEEP_BOTH, applyToAll) },
            focused = focusedButton == 2,
        )
        DialogTextButton(
            text = stringResource(Res.string.action_overwrite),
            emphasized = true,
            onClick = { onResolve(TransferConflictStrategy.OVERWRITE, applyToAll) },
            focused = focusedButton == LAST_CONFLICT_ACTION_INDEX,
        )
    }
}

private const val LAST_CONFLICT_ACTION_INDEX = 3
