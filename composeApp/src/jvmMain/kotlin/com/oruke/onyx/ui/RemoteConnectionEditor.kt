package com.oruke.onyx.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.RemoteConnectionDialogError
import com.oruke.onyx.app.component.RemoteConnectionDraft
import com.oruke.onyx.app.component.RemoteConnectionTestState
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_cancel_test
import onyx.composeapp.generated.resources.action_delete_connection
import onyx.composeapp.generated.resources.action_open
import onyx.composeapp.generated.resources.action_save_connection
import onyx.composeapp.generated.resources.action_test_connection
import onyx.composeapp.generated.resources.label_remote_connection_edit_title
import onyx.composeapp.generated.resources.label_remote_connection_new_title
import onyx.composeapp.generated.resources.label_remote_connection_not_configured
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/**
 * 展示网络位置详情编辑器，并集中承载测试/取消测试、规范化打开、删除和保存操作。
 *
 * @param connectionDraft 当前网络位置编辑草稿。
 * @param editingConnectionId 当前编辑连接 ID；空值表示新建模式。
 * @param testState 当前连接测试状态。
 * @param error 当前表单校验错误。
 * @param saving 是否正在保存连接与凭据。
 * @param actions 网络位置用户操作集合。
 * @param fontSize 正文字号。
 * @param labelFontSize 标签字号。
 * @param modifier 布局修饰符。
 */
@Composable
internal fun RemoteConnectionEditorPane(
    connectionDraft: RemoteConnectionDraft,
    editingConnectionId: String?,
    testState: RemoteConnectionTestState,
    error: RemoteConnectionDialogError?,
    saving: Boolean,
    actions: RemoteConnectionUiActions,
    fontSize: TextUnit,
    labelFontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalOnyxPalette.current
    val contentScrollState = rememberScrollState()
    val isEditing = editingConnectionId != null
    Column(modifier = modifier.background(palette.surface)) {
        RemoteConnectionEditorHeader(
            connectionDraft = connectionDraft,
            isEditing = isEditing,
            fontSize = fontSize,
        )
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.outlineVariant))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(contentScrollState)
                    .padding(start = 16.dp, top = 14.dp, end = 26.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                RemoteConnectionDetailsSection(
                    connectionDraft = connectionDraft,
                    onDraftChange = actions.onDraftChange,
                    fontSize = fontSize,
                    labelFontSize = labelFontSize,
                    enabled = !saving,
                )
                RemoteConnectionCredentialsSection(
                    connectionDraft = connectionDraft,
                    isEditing = isEditing,
                    onDraftChange = actions.onDraftChange,
                    fontSize = fontSize,
                    labelFontSize = labelFontSize,
                    enabled = !saving,
                )
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(contentScrollState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.outlineVariant))
        RemoteConnectionEditorFooter(
            editingConnectionId = editingConnectionId,
            testState = testState,
            error = error,
            saving = saving,
            onSave = actions.onSave,
            onTest = actions.onTest,
            onCancelTest = actions.onCancelTest,
            onDelete = actions.onDelete,
            onOpenDraft = actions.onOpenDraft,
            fontSize = fontSize,
        )
    }
}

/**
 * 展示编辑器标题及当前协议和地址摘要。
 *
 * @param connectionDraft 当前网络位置编辑草稿。
 * @param isEditing 是否处于已有连接编辑模式。
 * @param fontSize 正文字号。
 */
@Composable
private fun RemoteConnectionEditorHeader(
    connectionDraft: RemoteConnectionDraft,
    isEditing: Boolean,
    fontSize: TextUnit,
) {
    val palette = LocalOnyxPalette.current
    val appearance = LocalOnyxAppearance.current
    val fallbackTitle = stringResource(
        if (isEditing) Res.string.label_remote_connection_edit_title else Res.string.label_remote_connection_new_title,
    )
    val location = connectionDraft.location.ifBlank {
        stringResource(Res.string.label_remote_connection_not_configured)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.headerBackground)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = connectionDraft.name.ifBlank { fallbackTitle },
            fontSize = (fontSize.value + 1).sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${remoteProtocolLabel(connectionDraft.protocol)} · $location",
            fontSize = appearance.feedbackFontSize,
            color = palette.mutedForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 展示连接反馈及当前编辑器可用操作，采用 [FlowRow] 弹性排版确保 150% DPI 下按钮绝不被挤压成单字。
 *
 * @param editingConnectionId 当前编辑连接 ID。
 * @param testState 当前连接测试状态。
 * @param error 当前表单校验错误。
 * @param saving 是否正在保存连接与凭据。
 * @param onSave 保存当前草稿回调。
 * @param onTest 测试当前草稿连接回调。
 * @param onCancelTest 取消正在进行的连接测试回调。
 * @param onDelete 删除网络位置回调。
 * @param onOpenDraft 规范化校验并打开当前草稿回调。
 * @param fontSize 正文字号。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoteConnectionEditorFooter(
    editingConnectionId: String?,
    testState: RemoteConnectionTestState,
    error: RemoteConnectionDialogError?,
    saving: Boolean,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onCancelTest: () -> Unit,
    onDelete: (String) -> Unit,
    onOpenDraft: () -> Unit,
    fontSize: TextUnit,
) {
    val palette = LocalOnyxPalette.current
    val isTesting = testState is RemoteConnectionTestState.Testing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surfaceVariant.copy(alpha = 0.48f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RemoteConnectionFeedback(testState = testState, error = error, saving = saving)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            editingConnectionId?.let { connectionId ->
                DialogTextButton(
                    text = stringResource(Res.string.action_delete_connection),
                    destructive = true,
                    enabled = !saving,
                    fontSize = fontSize,
                    onClick = { onDelete(connectionId) },
                )
            }
            if (editingConnectionId != null) {
                DialogTextButton(
                    text = stringResource(Res.string.action_open),
                    enabled = !saving,
                    fontSize = fontSize,
                    onClick = onOpenDraft,
                )
            }
            if (isTesting) {
                DialogTextButton(
                    text = stringResource(Res.string.action_cancel_test),
                    destructive = true,
                    enabled = !saving,
                    fontSize = fontSize,
                    onClick = onCancelTest,
                )
            } else {
                DialogTextButton(
                    text = stringResource(Res.string.action_test_connection),
                    enabled = !saving,
                    fontSize = fontSize,
                    onClick = onTest,
                )
            }
            DialogTextButton(
                text = stringResource(Res.string.action_save_connection),
                emphasized = true,
                enabled = !saving,
                fontSize = fontSize,
                onClick = onSave,
            )
        }
    }
}
