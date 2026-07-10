package com.oruke.onyx.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.core.model.RemoteConnectionSavePolicy
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_delete_connection
import onyx.composeapp.generated.resources.action_open
import onyx.composeapp.generated.resources.action_save_connection
import onyx.composeapp.generated.resources.action_test_connection
import onyx.composeapp.generated.resources.label_remote_connection_credentials_section
import onyx.composeapp.generated.resources.label_remote_connection_details_section
import onyx.composeapp.generated.resources.label_remote_connection_domain
import onyx.composeapp.generated.resources.label_remote_connection_edit_title
import onyx.composeapp.generated.resources.label_remote_connection_location
import onyx.composeapp.generated.resources.label_remote_connection_name
import onyx.composeapp.generated.resources.label_remote_connection_new_title
import onyx.composeapp.generated.resources.label_remote_connection_not_configured
import onyx.composeapp.generated.resources.label_remote_connection_protocol
import onyx.composeapp.generated.resources.label_remote_connection_secret
import onyx.composeapp.generated.resources.label_remote_connection_username
import onyx.composeapp.generated.resources.label_remote_credentials_save_do_not_save
import onyx.composeapp.generated.resources.label_remote_credentials_save_policy
import onyx.composeapp.generated.resources.label_remote_credentials_save_session
import onyx.composeapp.generated.resources.label_remote_credentials_save_system_keyring
import onyx.composeapp.generated.resources.label_remote_protocol_s3
import onyx.composeapp.generated.resources.label_remote_protocol_smb
import onyx.composeapp.generated.resources.label_remote_protocol_webdav
import onyx.composeapp.generated.resources.label_remote_protocol_webdavs
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/** 连接名称字段在基础信息行中的布局权重。 */
private const val REMOTE_NAME_FIELD_WEIGHT = 2f

/** 连接地址字段在基础信息行中的布局权重。 */
private const val REMOTE_LOCATION_FIELD_WEIGHT = 3f

/**
 * 展示网络位置详情编辑器，并集中承载测试、打开、删除和保存操作。
 *
 * @param connectionDraft 当前网络位置编辑草稿。
 * @param editingConnectionId 当前编辑的连接 ID，空值表示新建模式。
 * @param testState 当前连接测试状态。
 * @param error 当前表单校验错误。
 * @param onDraftChange 编辑草稿变化回调。
 * @param onSave 保存当前草稿回调。
 * @param onTest 测试当前草稿连接回调。
 * @param onDelete 删除网络位置回调。
 * @param onOpen 打开网络位置回调。
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
    onDraftChange: (RemoteConnectionDraft) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onDelete: (String) -> Unit,
    onOpen: (String) -> Unit,
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
                    onDraftChange = onDraftChange,
                    fontSize = fontSize,
                    labelFontSize = labelFontSize,
                )
                RemoteConnectionCredentialsSection(
                    connectionDraft = connectionDraft,
                    onDraftChange = onDraftChange,
                    fontSize = fontSize,
                    labelFontSize = labelFontSize,
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
            connectionDraft = connectionDraft,
            testState = testState,
            error = error,
            onSave = onSave,
            onTest = onTest,
            onDelete = onDelete,
            onOpen = onOpen,
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
            fontSize = 10.sp,
            color = palette.mutedForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 展示协议、名称和地址等连接基础信息。
 *
 * @param connectionDraft 当前网络位置编辑草稿。
 * @param onDraftChange 编辑草稿变化回调。
 * @param fontSize 正文字号。
 * @param labelFontSize 标签字号。
 */
@Composable
private fun RemoteConnectionDetailsSection(
    connectionDraft: RemoteConnectionDraft,
    onDraftChange: (RemoteConnectionDraft) -> Unit,
    fontSize: TextUnit,
    labelFontSize: TextUnit,
) {
    RemoteConnectionFormSection(
        title = stringResource(Res.string.label_remote_connection_details_section),
        labelFontSize = labelFontSize,
    ) {
        RemoteConnectionFieldLabel(stringResource(Res.string.label_remote_connection_protocol))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RemoteConnectionProtocol.entries.forEach { protocol ->
                SettingsOption(
                    selected = connectionDraft.protocol == protocol,
                    text = remoteProtocolLabel(protocol),
                    fontSize = labelFontSize,
                    onClick = { onDraftChange(connectionDraft.copy(protocol = protocol)) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RemoteConnectionField(
                label = stringResource(Res.string.label_remote_connection_name),
                value = connectionDraft.name,
                onValueChange = { value -> onDraftChange(connectionDraft.copy(name = value)) },
                fontSize = fontSize,
                modifier = Modifier.weight(REMOTE_NAME_FIELD_WEIGHT),
            )
            RemoteConnectionField(
                label = stringResource(Res.string.label_remote_connection_location),
                value = connectionDraft.location,
                onValueChange = { value -> onDraftChange(connectionDraft.copy(location = value)) },
                fontSize = fontSize,
                modifier = Modifier.weight(REMOTE_LOCATION_FIELD_WEIGHT),
            )
        }
    }
}

/**
 * 展示凭据输入和保存策略设置。
 *
 * @param connectionDraft 当前网络位置编辑草稿。
 * @param onDraftChange 编辑草稿变化回调。
 * @param fontSize 正文字号。
 * @param labelFontSize 标签字号。
 */
@Composable
private fun RemoteConnectionCredentialsSection(
    connectionDraft: RemoteConnectionDraft,
    onDraftChange: (RemoteConnectionDraft) -> Unit,
    fontSize: TextUnit,
    labelFontSize: TextUnit,
) {
    RemoteConnectionFormSection(
        title = stringResource(Res.string.label_remote_connection_credentials_section),
        labelFontSize = labelFontSize,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RemoteConnectionField(
                label = stringResource(Res.string.label_remote_connection_username),
                value = connectionDraft.username,
                onValueChange = { value -> onDraftChange(connectionDraft.copy(username = value)) },
                fontSize = fontSize,
                modifier = Modifier.weight(1f),
            )
            RemoteConnectionField(
                label = stringResource(Res.string.label_remote_connection_secret),
                value = connectionDraft.secret,
                onValueChange = { value -> onDraftChange(connectionDraft.copy(secret = value)) },
                fontSize = fontSize,
                password = true,
                modifier = Modifier.weight(1f),
            )
        }
        RemoteConnectionField(
            label = stringResource(Res.string.label_remote_connection_domain),
            value = connectionDraft.domain,
            onValueChange = { value -> onDraftChange(connectionDraft.copy(domain = value)) },
            fontSize = fontSize,
        )
        RemoteConnectionFieldLabel(stringResource(Res.string.label_remote_credentials_save_policy))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RemoteConnectionSavePolicy.entries.forEach { policy ->
                SettingsOption(
                    selected = connectionDraft.savePolicy == policy,
                    text = remoteSavePolicyLabel(policy),
                    fontSize = labelFontSize,
                    onClick = { onDraftChange(connectionDraft.copy(savePolicy = policy)) },
                )
            }
        }
    }
}

/**
 * 展示带分隔标题的编辑器表单分组。
 *
 * @param title 分组标题。
 * @param labelFontSize 分组标题字号。
 * @param content 分组表单内容。
 */
@Composable
private fun RemoteConnectionFormSection(
    title: String,
    labelFontSize: TextUnit,
    content: @Composable () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                fontSize = labelFontSize,
                fontWeight = FontWeight.SemiBold,
                color = palette.foreground,
            )
            Box(modifier = Modifier.weight(1f).height(1.dp).background(palette.outlineVariant))
        }
        content()
    }
}

/**
 * 展示网络位置字段标签。
 *
 * @param text 标签文本。
 */
@Composable
private fun RemoteConnectionFieldLabel(text: String) {
    Text(text = text, fontSize = 11.sp, color = LocalOnyxPalette.current.mutedForeground)
}

/**
 * 展示单个网络位置文本输入字段。
 *
 * @param label 字段标签。
 * @param value 当前字段值。
 * @param onValueChange 字段值变化回调。
 * @param fontSize 输入文字字号。
 * @param modifier 布局修饰符。
 * @param password 是否以密码模式显示。
 */
@Composable
private fun RemoteConnectionField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RemoteConnectionFieldLabel(label)
        OnyxTextInput(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            fontSize = fontSize,
            password = password,
        )
    }
}

/**
 * 展示连接反馈及当前编辑器可用操作。
 *
 * @param editingConnectionId 当前编辑的连接 ID。
 * @param connectionDraft 当前网络位置编辑草稿。
 * @param testState 当前连接测试状态。
 * @param error 当前表单校验错误。
 * @param onSave 保存当前草稿回调。
 * @param onTest 测试当前草稿连接回调。
 * @param onDelete 删除网络位置回调。
 * @param onOpen 打开网络位置回调。
 * @param fontSize 正文字号。
 */
@Composable
private fun RemoteConnectionEditorFooter(
    editingConnectionId: String?,
    connectionDraft: RemoteConnectionDraft,
    testState: RemoteConnectionTestState,
    error: RemoteConnectionDialogError?,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onDelete: (String) -> Unit,
    onOpen: (String) -> Unit,
    fontSize: TextUnit,
) {
    val palette = LocalOnyxPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surfaceVariant.copy(alpha = 0.48f))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RemoteConnectionFeedback(testState = testState, error = error)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            editingConnectionId?.let { connectionId ->
                DialogTextButton(
                    text = stringResource(Res.string.action_delete_connection),
                    destructive = true,
                    fontSize = fontSize,
                    onClick = { onDelete(connectionId) },
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (editingConnectionId != null) {
                DialogTextButton(
                    text = stringResource(Res.string.action_open),
                    fontSize = fontSize,
                    onClick = { onOpen(connectionDraft.location) },
                )
            }
            DialogTextButton(
                text = stringResource(Res.string.action_test_connection),
                fontSize = fontSize,
                onClick = onTest,
            )
            DialogTextButton(
                text = stringResource(Res.string.action_save_connection),
                emphasized = true,
                fontSize = fontSize,
                onClick = onSave,
            )
        }
    }
}

/**
 * 返回网络位置协议的本地化显示名称。
 *
 * @param protocol 网络位置协议。
 * @return 本地化协议名称。
 */
@Composable
private fun remoteProtocolLabel(protocol: RemoteConnectionProtocol): String {
    return when (protocol) {
        RemoteConnectionProtocol.SMB -> stringResource(Res.string.label_remote_protocol_smb)
        RemoteConnectionProtocol.WEBDAV -> stringResource(Res.string.label_remote_protocol_webdav)
        RemoteConnectionProtocol.WEBDAVS -> stringResource(Res.string.label_remote_protocol_webdavs)
        RemoteConnectionProtocol.S3 -> stringResource(Res.string.label_remote_protocol_s3)
    }
}

/**
 * 返回凭据保存策略的本地化显示名称。
 *
 * @param policy 凭据保存策略。
 * @return 本地化保存策略名称。
 */
@Composable
private fun remoteSavePolicyLabel(policy: RemoteConnectionSavePolicy): String {
    return when (policy) {
        RemoteConnectionSavePolicy.DO_NOT_SAVE ->
            stringResource(Res.string.label_remote_credentials_save_do_not_save)
        RemoteConnectionSavePolicy.SESSION ->
            stringResource(Res.string.label_remote_credentials_save_session)
        RemoteConnectionSavePolicy.SYSTEM_KEYRING ->
            stringResource(Res.string.label_remote_credentials_save_system_keyring)
    }
}
