package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.RemoteConnectionDraft
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.core.model.RemoteConnectionSavePolicy
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.description_remote_protocol_s3
import onyx.composeapp.generated.resources.description_remote_protocol_smb
import onyx.composeapp.generated.resources.description_remote_protocol_webdav
import onyx.composeapp.generated.resources.description_remote_protocol_webdavs
import onyx.composeapp.generated.resources.hint_remote_connection_anonymous_credentials
import onyx.composeapp.generated.resources.hint_remote_connection_s3_location
import onyx.composeapp.generated.resources.hint_remote_connection_secret_unchanged
import onyx.composeapp.generated.resources.hint_remote_connection_smb_location
import onyx.composeapp.generated.resources.hint_remote_connection_webdav_location
import onyx.composeapp.generated.resources.hint_remote_connection_webdavs_location
import onyx.composeapp.generated.resources.label_remote_connection_credentials_section
import onyx.composeapp.generated.resources.label_remote_connection_details_section
import onyx.composeapp.generated.resources.label_remote_connection_name
import onyx.composeapp.generated.resources.label_remote_connection_protocol
import onyx.composeapp.generated.resources.label_remote_connection_s3_location
import onyx.composeapp.generated.resources.label_remote_connection_smb_domain
import onyx.composeapp.generated.resources.label_remote_connection_smb_location
import onyx.composeapp.generated.resources.label_remote_connection_smb_password
import onyx.composeapp.generated.resources.label_remote_connection_smb_username
import onyx.composeapp.generated.resources.label_remote_connection_webdav_location
import onyx.composeapp.generated.resources.label_remote_connection_webdav_password
import onyx.composeapp.generated.resources.label_remote_connection_webdav_username
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

/** 网络位置字段说明文字字号。 */
private val RemoteFieldHintFontSize = 10.sp

/**
 * 展示协议、名称和协议专属地址字段。
 *
 * @param connectionDraft 当前网络位置编辑草稿。
 * @param onDraftChange 编辑草稿变化回调。
 * @param fontSize 正文字号。
 * @param labelFontSize 标签字号。
 * @param enabled 是否允许编辑。
 */
@Composable
internal fun RemoteConnectionDetailsSection(
    connectionDraft: RemoteConnectionDraft,
    onDraftChange: (RemoteConnectionDraft) -> Unit,
    fontSize: TextUnit,
    labelFontSize: TextUnit,
    enabled: Boolean,
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
                    onClick = {
                        if (enabled) {
                            onDraftChange(connectionDraft.copy(protocol = protocol))
                        }
                    },
                )
            }
        }
        RemoteConnectionProtocolNotice(connectionDraft.protocol)
        RemoteConnectionField(
            label = stringResource(Res.string.label_remote_connection_name),
            value = connectionDraft.name,
            onValueChange = { value -> onDraftChange(connectionDraft.copy(name = value)) },
            fontSize = fontSize,
            enabled = enabled,
        )
        RemoteConnectionField(
            label = remoteLocationLabel(connectionDraft.protocol),
            value = connectionDraft.location,
            onValueChange = { value -> onDraftChange(connectionDraft.copy(location = value)) },
            supportingText = remoteLocationHint(connectionDraft.protocol),
            fontSize = fontSize,
            enabled = enabled,
        )
    }
}

/**
 * 展示协议专属凭据字段和保存策略。
 *
 * @param connectionDraft 当前网络位置编辑草稿。
 * @param isEditing 是否编辑已有连接。
 * @param onDraftChange 编辑草稿变化回调。
 * @param fontSize 正文字号。
 * @param labelFontSize 标签字号。
 * @param enabled 是否允许编辑。
 */
@Composable
internal fun RemoteConnectionCredentialsSection(
    connectionDraft: RemoteConnectionDraft,
    isEditing: Boolean,
    onDraftChange: (RemoteConnectionDraft) -> Unit,
    fontSize: TextUnit,
    labelFontSize: TextUnit,
    enabled: Boolean,
) {
    RemoteConnectionFormSection(
        title = stringResource(Res.string.label_remote_connection_credentials_section),
        labelFontSize = labelFontSize,
    ) {
        when (connectionDraft.protocol) {
            RemoteConnectionProtocol.SMB -> SmbCredentialFields(
                connectionDraft = connectionDraft,
                isEditing = isEditing,
                onDraftChange = onDraftChange,
                fontSize = fontSize,
                enabled = enabled,
            )

            RemoteConnectionProtocol.WEBDAV,
            RemoteConnectionProtocol.WEBDAVS,
            -> WebDavCredentialFields(
                connectionDraft = connectionDraft,
                isEditing = isEditing,
                onDraftChange = onDraftChange,
                fontSize = fontSize,
                enabled = enabled,
            )

            RemoteConnectionProtocol.S3 -> S3CredentialFields(
                connectionDraft = connectionDraft,
                isEditing = isEditing,
                onDraftChange = onDraftChange,
                fontSize = fontSize,
                enabled = enabled,
            )
        }
        RemoteConnectionFieldLabel(stringResource(Res.string.label_remote_credentials_save_policy))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RemoteConnectionSavePolicy.entries.forEach { policy ->
                SettingsOption(
                    selected = connectionDraft.savePolicy == policy,
                    text = remoteSavePolicyLabel(policy),
                    fontSize = labelFontSize,
                    onClick = {
                        if (enabled) onDraftChange(connectionDraft.copy(savePolicy = policy))
                    },
                )
            }
        }
    }
}

/**
 * 展示 SMB 用户名、密码和域字段。
 *
 * @param connectionDraft 当前连接草稿。
 * @param isEditing 是否编辑已有连接。
 * @param onDraftChange 草稿变化回调。
 * @param fontSize 正文字号。
 * @param enabled 是否允许编辑。
 */
@Composable
private fun SmbCredentialFields(
    connectionDraft: RemoteConnectionDraft,
    isEditing: Boolean,
    onDraftChange: (RemoteConnectionDraft) -> Unit,
    fontSize: TextUnit,
    enabled: Boolean,
) {
    CredentialPair(
        connectionDraft = connectionDraft,
        identityLabel = stringResource(Res.string.label_remote_connection_smb_username),
        secretLabel = stringResource(Res.string.label_remote_connection_smb_password),
        isEditing = isEditing,
        onDraftChange = onDraftChange,
        fontSize = fontSize,
        enabled = enabled,
    )
    RemoteConnectionField(
        label = stringResource(Res.string.label_remote_connection_smb_domain),
        value = connectionDraft.domain,
        onValueChange = { value -> onDraftChange(connectionDraft.copy(domain = value)) },
        fontSize = fontSize,
        enabled = enabled,
    )
    RemoteConnectionFieldHint(stringResource(Res.string.hint_remote_connection_anonymous_credentials))
}

/**
 * 展示 WebDAV Basic 用户名和密码字段。
 *
 * @param connectionDraft 当前连接草稿。
 * @param isEditing 是否编辑已有连接。
 * @param onDraftChange 草稿变化回调。
 * @param fontSize 正文字号。
 * @param enabled 是否允许编辑。
 */
@Composable
private fun WebDavCredentialFields(
    connectionDraft: RemoteConnectionDraft,
    isEditing: Boolean,
    onDraftChange: (RemoteConnectionDraft) -> Unit,
    fontSize: TextUnit,
    enabled: Boolean,
) {
    CredentialPair(
        connectionDraft = connectionDraft,
        identityLabel = stringResource(Res.string.label_remote_connection_webdav_username),
        secretLabel = stringResource(Res.string.label_remote_connection_webdav_password),
        isEditing = isEditing,
        onDraftChange = onDraftChange,
        fontSize = fontSize,
        enabled = enabled,
    )
    RemoteConnectionFieldHint(stringResource(Res.string.hint_remote_connection_anonymous_credentials))
}

/**
 * 展示并排的身份标识和密钥字段。
 *
 * @param connectionDraft 当前连接草稿。
 * @param identityLabel 身份字段标签。
 * @param secretLabel 密钥字段标签。
 * @param isEditing 是否编辑已有连接。
 * @param onDraftChange 草稿变化回调。
 * @param fontSize 正文字号。
 * @param enabled 是否允许编辑。
 */
@Composable
internal fun CredentialPair(
    connectionDraft: RemoteConnectionDraft,
    identityLabel: String,
    secretLabel: String,
    isEditing: Boolean,
    onDraftChange: (RemoteConnectionDraft) -> Unit,
    fontSize: TextUnit,
    enabled: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RemoteConnectionField(
            label = identityLabel,
            value = connectionDraft.username,
            onValueChange = { value -> onDraftChange(connectionDraft.copy(username = value)) },
            fontSize = fontSize,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        RemoteConnectionField(
            label = secretLabel,
            value = connectionDraft.secret,
            onValueChange = { value ->
                onDraftChange(connectionDraft.copy(secret = value, secretChanged = true))
            },
            supportingText = if (isEditing && !connectionDraft.secretChanged) {
                stringResource(Res.string.hint_remote_connection_secret_unchanged)
            } else {
                null
            },
            fontSize = fontSize,
            password = true,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 展示当前协议的能力与限制摘要。
 *
 * @param protocol 当前连接协议。
 */
@Composable
private fun RemoteConnectionProtocolNotice(protocol: RemoteConnectionProtocol) {
    val palette = LocalOnyxPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surfaceVariant.copy(alpha = 0.58f), RoundedCornerShape(4.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        Text(
            text = remoteProtocolDescription(protocol),
            fontSize = RemoteFieldHintFontSize,
            color = palette.mutedForeground,
        )
    }
}

/**
 * 展示带分隔线标题的编辑器表单分组。
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
internal fun RemoteConnectionFieldLabel(text: String) {
    Text(text = text, fontSize = 11.sp, color = LocalOnyxPalette.current.mutedForeground)
}

/**
 * 展示网络位置字段辅助说明。
 *
 * @param text 辅助说明文本。
 */
@Composable
internal fun RemoteConnectionFieldHint(text: String) {
    Text(text = text, fontSize = RemoteFieldHintFontSize, color = LocalOnyxPalette.current.disabledForeground)
}

/**
 * 展示单个网络位置文本输入字段。
 *
 * @param label 字段标签。
 * @param value 当前字段值。
 * @param onValueChange 字段变化回调。
 * @param fontSize 输入文字字号。
 * @param modifier 布局修饰符。
 * @param supportingText 可选辅助说明。
 * @param password 是否按密码模式显示。
 * @param enabled 是否允许输入。
 */
@Composable
internal fun RemoteConnectionField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    password: Boolean = false,
    enabled: Boolean = true,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RemoteConnectionFieldLabel(label)
        OnyxTextInput(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            fontSize = fontSize,
            password = password,
            enabled = enabled,
        )
        supportingText?.let { text -> RemoteConnectionFieldHint(text) }
    }
}

/**
 * 返回协议本地化显示名。
 *
 * @param protocol 网络位置协议。
 * @return 本地化协议名称。
 */
@Composable
internal fun remoteProtocolLabel(protocol: RemoteConnectionProtocol): String {
    return when (protocol) {
        RemoteConnectionProtocol.SMB -> stringResource(Res.string.label_remote_protocol_smb)
        RemoteConnectionProtocol.WEBDAV -> stringResource(Res.string.label_remote_protocol_webdav)
        RemoteConnectionProtocol.WEBDAVS -> stringResource(Res.string.label_remote_protocol_webdavs)
        RemoteConnectionProtocol.S3 -> stringResource(Res.string.label_remote_protocol_s3)
    }
}

/**
 * 返回协议能力摘要。
 *
 * @param protocol 网络位置协议。
 * @return 本地化协议说明。
 */
@Composable
private fun remoteProtocolDescription(protocol: RemoteConnectionProtocol): String {
    return when (protocol) {
        RemoteConnectionProtocol.SMB -> stringResource(Res.string.description_remote_protocol_smb)
        RemoteConnectionProtocol.WEBDAV -> stringResource(Res.string.description_remote_protocol_webdav)
        RemoteConnectionProtocol.WEBDAVS -> stringResource(Res.string.description_remote_protocol_webdavs)
        RemoteConnectionProtocol.S3 -> stringResource(Res.string.description_remote_protocol_s3)
    }
}

/**
 * 返回协议专属地址标签。
 *
 * @param protocol 网络位置协议。
 * @return 本地化地址标签。
 */
@Composable
private fun remoteLocationLabel(protocol: RemoteConnectionProtocol): String {
    return when (protocol) {
        RemoteConnectionProtocol.SMB -> stringResource(Res.string.label_remote_connection_smb_location)
        RemoteConnectionProtocol.WEBDAV,
        RemoteConnectionProtocol.WEBDAVS,
        -> stringResource(Res.string.label_remote_connection_webdav_location)
        RemoteConnectionProtocol.S3 -> stringResource(Res.string.label_remote_connection_s3_location)
    }
}

/**
 * 返回协议专属地址示例。
 *
 * @param protocol 网络位置协议。
 * @return 本地化地址示例。
 */
@Composable
private fun remoteLocationHint(protocol: RemoteConnectionProtocol): String {
    return when (protocol) {
        RemoteConnectionProtocol.SMB -> stringResource(Res.string.hint_remote_connection_smb_location)
        RemoteConnectionProtocol.WEBDAV -> stringResource(Res.string.hint_remote_connection_webdav_location)
        RemoteConnectionProtocol.WEBDAVS -> stringResource(Res.string.hint_remote_connection_webdavs_location)
        RemoteConnectionProtocol.S3 -> stringResource(Res.string.hint_remote_connection_s3_location)
    }
}

/**
 * 返回凭据保存策略本地化名称。
 *
 * @param policy 凭据保存策略。
 * @return 本地化策略名称。
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
