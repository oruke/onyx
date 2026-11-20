package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberDialogState
import com.oruke.onyx.app.component.RemoteConnectionDialogError
import com.oruke.onyx.app.component.RemoteConnectionDraft
import com.oruke.onyx.app.component.RemoteConnectionTestState
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.component.toRemoteConnectionDraft
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_close_window
import onyx.composeapp.generated.resources.action_discard_changes
import onyx.composeapp.generated.resources.action_keep_editing
import onyx.composeapp.generated.resources.label_remote_connections_title
import onyx.composeapp.generated.resources.label_unsaved_changes_message
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/** 网络位置窗口默认宽度。 */
private const val REMOTE_CONNECTION_DIALOG_WIDTH = 920

/** 网络位置窗口默认高度。 */
private const val REMOTE_CONNECTION_DIALOG_HEIGHT = 620

/** 网络位置窗口最小宽度。 */
private const val REMOTE_CONNECTION_DIALOG_MIN_WIDTH = 720

/** 网络位置窗口最小高度。 */
private const val REMOTE_CONNECTION_DIALOG_MIN_HEIGHT = 480

/**
 * 汇总网络位置编辑器向组件层发送的用户操作。
 */
internal data class RemoteConnectionUiActions(
    /** 更新编辑草稿。 */
    val onDraftChange: (RemoteConnectionDraft) -> Unit,
    /** 新建网络位置。 */
    val onNew: () -> Unit,
    /** 编辑指定网络位置。 */
    val onEdit: (RemoteConnectionProfile) -> Unit,
    /** 保存当前草稿。 */
    val onSave: () -> Unit,
    /** 测试当前草稿连接。 */
    val onTest: () -> Unit,
    /** 取消正在进行的连接测试。 */
    val onCancelTest: () -> Unit = {},
    /** 删除指定网络位置。 */
    val onDelete: (String) -> Unit,
    /** 直接打开已保存列表中的网络位置。 */
    val onOpen: (String) -> Unit,
    /** 经表单校验与规范化后打开当前编辑草稿。 */
    val onOpenDraft: () -> Unit = {},
)

/** 切项或关闭时暂存的待确认目标动作。 */
private sealed interface PendingRemoteSwitchAction {
    /** 新建连接。 */
    data object NewConnection : PendingRemoteSwitchAction

    /** 切换编辑已有连接。 */
    data class EditConnection(val profile: RemoteConnectionProfile) : PendingRemoteSwitchAction

    /** 直接打开列表中的其他网络位置。 */
    data class OpenLocation(val location: String) : PendingRemoteSwitchAction

    /** 关闭管理窗口。 */
    data object CloseDialog : PendingRemoteSwitchAction
}

/**
 * 展示独立的网络位置管理窗口，内置未保存草稿保护与 150% DPI 自适应布局。
 *
 * @param state 网络位置编辑窗口状态。
 * @param connections 当前已保存的网络位置列表。
 * @param onDraftChange 编辑草稿变化回调。
 * @param onNew 新建网络位置回调。
 * @param onEdit 编辑指定网络位置回调。
 * @param onSave 保存当前草稿回调。
 * @param onTest 测试当前草稿连接回调。
 * @param onCancelTest 取消进行中测试的回调。
 * @param onDelete 删除网络位置回调。
 * @param onOpen 打开已保存网络位置回调。
 * @param onOpenDraft 规范化校验并打开当前编辑草稿的回调。
 * @param onDismiss 关闭窗口回调。
 */
@Composable
internal fun RemoteConnectionsDialog(
    state: RootDialogState.RemoteConnections,
    connections: List<RemoteConnectionProfile>,
    onDraftChange: (RemoteConnectionDraft) -> Unit,
    onNew: () -> Unit,
    onEdit: (RemoteConnectionProfile) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onCancelTest: () -> Unit = {},
    onDelete: (String) -> Unit,
    onOpen: (String) -> Unit,
    onOpenDraft: () -> Unit = { onOpen(state.remoteConnectionDraft.location) },
    onDismiss: () -> Unit,
) {
    val title = stringResource(Res.string.label_remote_connections_title)
    val dialogState = rememberDialogState(
        width = REMOTE_CONNECTION_DIALOG_WIDTH.dp,
        height = REMOTE_CONNECTION_DIALOG_HEIGHT.dp,
    )
    val baselineDraft = remember(state.editingRemoteConnectionId, connections) {
        val editingProfile = state.editingRemoteConnectionId?.let { id ->
            connections.firstOrNull { it.id == id }
        }
        editingProfile?.toRemoteConnectionDraft() ?: RemoteConnectionDraft()
    }
    val hasUnsavedChanges = state.remoteConnectionDraft != baselineDraft
    var pendingSwitch by remember { mutableStateOf<PendingRemoteSwitchAction?>(null) }

    fun guardAction(action: PendingRemoteSwitchAction, execute: () -> Unit) {
        if (hasUnsavedChanges && !state.saving) {
            pendingSwitch = action
        } else {
            pendingSwitch = null
            execute()
        }
    }

    OnyxDialogWindow(
        onCloseRequest = { guardAction(PendingRemoteSwitchAction.CloseDialog, onDismiss) },
        title = title,
        state = dialogState,
        resizable = true,
        minimumWidthPx = REMOTE_CONNECTION_DIALOG_MIN_WIDTH,
        minimumHeightPx = REMOTE_CONNECTION_DIALOG_MIN_HEIGHT,
        scaleMinimumSizeWithUi = true,
        windowName = "onyx-remote-connections",
    ) {
        DialogFrame(
            title = title,
            body = {
                val appearance = LocalOnyxAppearance.current
                val actions = RemoteConnectionUiActions(
                    onDraftChange = { next ->
                        pendingSwitch = null
                        onDraftChange(next)
                    },
                    onNew = { guardAction(PendingRemoteSwitchAction.NewConnection, onNew) },
                    onEdit = { profile ->
                        if (profile.id != state.editingRemoteConnectionId) {
                            guardAction(PendingRemoteSwitchAction.EditConnection(profile)) { onEdit(profile) }
                        }
                    },
                    onSave = {
                        pendingSwitch = null
                        onSave()
                    },
                    onTest = onTest,
                    onCancelTest = onCancelTest,
                    onDelete = onDelete,
                    onOpen = { location ->
                        guardAction(PendingRemoteSwitchAction.OpenLocation(location)) { onOpen(location) }
                    },
                    onOpenDraft = onOpenDraft,
                )
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pendingSwitch?.let { pending ->
                        UnsavedChangesBanner(
                            onDiscard = {
                                pendingSwitch = null
                                when (pending) {
                                    PendingRemoteSwitchAction.NewConnection -> onNew()
                                    is PendingRemoteSwitchAction.EditConnection -> onEdit(pending.profile)
                                    is PendingRemoteSwitchAction.OpenLocation -> onOpen(pending.location)
                                    PendingRemoteSwitchAction.CloseDialog -> onDismiss()
                                }
                            },
                            onKeepEditing = { pendingSwitch = null },
                        )
                    }
                    RemoteConnectionsSettings(
                        connections = connections,
                        connectionDraft = state.remoteConnectionDraft,
                        editingConnectionId = state.editingRemoteConnectionId,
                        testState = state.remoteConnectionTestState,
                        error = state.remoteConnectionError,
                        saving = state.saving,
                        actions = actions,
                        fontSize = appearance.listFontSize,
                        labelFontSize = appearance.headerFontSize,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            },
            actions = {
                DialogTextButton(
                    text = stringResource(Res.string.action_close_window),
                    onClick = { guardAction(PendingRemoteSwitchAction.CloseDialog, onDismiss) },
                )
            },
        )
    }
}

/**
 * 轻量内联未保存更改确认条（设置窗口与远程连接管理器共用）。
 *
 * @param onDiscard 确认放弃更改回调。
 * @param onKeepEditing 取消切换/关闭并继续编辑回调。
 */
@Composable
internal fun UnsavedChangesBanner(
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.error.copy(alpha = 0.12f), shape)
            .border(1.dp, palette.error.copy(alpha = 0.45f), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.label_unsaved_changes_message),
            fontSize = appearance.listFontSize,
            color = palette.foreground,
            modifier = Modifier.weight(1f),
        )
        DialogTextButton(
            text = stringResource(Res.string.action_keep_editing),
            fontSize = appearance.headerFontSize,
            onClick = onKeepEditing,
        )
        DialogTextButton(
            text = stringResource(Res.string.action_discard_changes),
            destructive = true,
            fontSize = appearance.headerFontSize,
            onClick = onDiscard,
        )
    }
}

/**
 * 以主从布局组织网络位置列表与连接编辑器，左侧列表宽度按窗口逻辑宽度弹性收敛以适配 150% DPI。
 *
 * @param connections 当前已保存的网络位置列表。
 * @param connectionDraft 当前正在编辑的网络位置草稿。
 * @param editingConnectionId 当前编辑的连接 ID，空值表示新建模式。
 * @param testState 当前连接测试状态。
 * @param error 当前表单校验错误。
 * @param saving 是否正在保存连接与凭据。
 * @param actions 网络位置用户操作集合。
 * @param fontSize 正文字号。
 * @param labelFontSize 标签字号。
 * @param modifier 外层布局修饰符。
 */
@Composable
internal fun RemoteConnectionsSettings(
    connections: List<RemoteConnectionProfile>,
    connectionDraft: RemoteConnectionDraft,
    editingConnectionId: String?,
    testState: RemoteConnectionTestState,
    error: RemoteConnectionDialogError?,
    saving: Boolean,
    actions: RemoteConnectionUiActions,
    fontSize: androidx.compose.ui.unit.TextUnit,
    labelFontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalOnyxPalette.current
    val shape = RoundedCornerShape(6.dp)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(palette.surface, shape)
            .border(1.dp, palette.outlineVariant, shape),
    ) {
        val listWidth = (maxWidth * 0.34f).coerceIn(190.dp, 260.dp)
        Row(modifier = Modifier.fillMaxSize()) {
            RemoteConnectionListPane(
                connections = connections,
                editingConnectionId = editingConnectionId,
                onNew = actions.onNew,
                onEdit = actions.onEdit,
                onOpen = actions.onOpen,
                fontSize = fontSize,
                modifier = Modifier.width(listWidth).fillMaxHeight(),
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(palette.outlineVariant),
            )
            RemoteConnectionEditorPane(
                connectionDraft = connectionDraft,
                editingConnectionId = editingConnectionId,
                testState = testState,
                error = error,
                saving = saving,
                actions = actions,
                fontSize = fontSize,
                labelFontSize = labelFontSize,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}
