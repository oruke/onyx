package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.runtime.Composable
import com.oruke.onyx.app.component.RemoteConnectionDialogError
import com.oruke.onyx.app.component.RemoteConnectionDraft
import com.oruke.onyx.app.component.RemoteConnectionTestState
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_close_window
import onyx.composeapp.generated.resources.label_remote_connections_title
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme

/** 网络位置窗口默认宽度。 */
private const val REMOTE_CONNECTION_DIALOG_WIDTH = 900

/** 网络位置窗口默认高度。 */
private const val REMOTE_CONNECTION_DIALOG_HEIGHT = 600

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
    /** 删除指定网络位置。 */
    val onDelete: (String) -> Unit,
    /** 打开指定网络位置。 */
    val onOpen: (String) -> Unit,
)

/**
 * 展示独立的网络位置管理窗口。
 *
 * @param state 网络位置编辑窗口状态。
 * @param connections 当前已保存的网络位置列表。
 * @param onDraftChange 编辑草稿变化回调。
 * @param onNew 新建网络位置回调。
 * @param onEdit 编辑指定网络位置回调。
 * @param onSave 保存当前草稿回调。
 * @param onTest 测试当前草稿连接回调。
 * @param onDelete 删除网络位置回调。
 * @param onOpen 打开网络位置回调。
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
    onDelete: (String) -> Unit,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = stringResource(Res.string.label_remote_connections_title)
    val dialogState = rememberDialogState(
        width = REMOTE_CONNECTION_DIALOG_WIDTH.dp,
        height = REMOTE_CONNECTION_DIALOG_HEIGHT.dp,
    )
    DialogWindow(
        onCloseRequest = onDismiss,
        title = title,
        state = dialogState,
        resizable = true,
    ) {
        window.minimumSize = java.awt.Dimension(
            REMOTE_CONNECTION_DIALOG_MIN_WIDTH,
            REMOTE_CONNECTION_DIALOG_MIN_HEIGHT,
        )
        IntUiTheme(isDark = isSystemInDarkTheme()) {
            DialogFrame(
                title = title,
                body = {
                    val appearance = LocalOnyxAppearance.current
                    val actions = RemoteConnectionUiActions(
                        onDraftChange = onDraftChange,
                        onNew = onNew,
                        onEdit = onEdit,
                        onSave = onSave,
                        onTest = onTest,
                        onDelete = onDelete,
                        onOpen = onOpen,
                    )
                    RemoteConnectionsSettings(
                        connections = connections,
                        connectionDraft = state.remoteConnectionDraft,
                        editingConnectionId = state.editingRemoteConnectionId,
                        testState = state.remoteConnectionTestState,
                        error = state.remoteConnectionError,
                        actions = actions,
                        fontSize = appearance.listFontSize,
                        labelFontSize = appearance.headerFontSize,
                    )
                },
                actions = {
                    DialogTextButton(
                        text = stringResource(Res.string.action_close_window),
                        onClick = onDismiss,
                    )
                },
            )
        }
    }
}

/**
 * 以主从布局组织网络位置列表与连接编辑器。
 *
 * @param connections 当前已保存的网络位置列表。
 * @param connectionDraft 当前正在编辑的网络位置草稿。
 * @param editingConnectionId 当前编辑的连接 ID，空值表示新建模式。
 * @param testState 当前连接测试状态。
 * @param error 当前表单校验错误。
 * @param actions 网络位置用户操作集合。
 * @param fontSize 正文字号。
 * @param labelFontSize 标签字号。
 */
@Composable
internal fun RemoteConnectionsSettings(
    connections: List<RemoteConnectionProfile>,
    connectionDraft: RemoteConnectionDraft,
    editingConnectionId: String?,
    testState: RemoteConnectionTestState,
    error: RemoteConnectionDialogError?,
    actions: RemoteConnectionUiActions,
    fontSize: androidx.compose.ui.unit.TextUnit,
    labelFontSize: androidx.compose.ui.unit.TextUnit,
) {
    val palette = LocalOnyxPalette.current
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(palette.surface, shape)
            .border(1.dp, palette.outlineVariant, shape),
    ) {
        RemoteConnectionListPane(
            connections = connections,
            editingConnectionId = editingConnectionId,
            onNew = actions.onNew,
            onEdit = actions.onEdit,
            onOpen = actions.onOpen,
            fontSize = fontSize,
            modifier = Modifier.width(276.dp).fillMaxHeight(),
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
            onDraftChange = actions.onDraftChange,
            onSave = actions.onSave,
            onTest = actions.onTest,
            onDelete = actions.onDelete,
            onOpen = actions.onOpen,
            fontSize = fontSize,
            labelFontSize = labelFontSize,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}
