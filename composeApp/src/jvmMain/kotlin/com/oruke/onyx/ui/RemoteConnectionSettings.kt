package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.component.RemoteConnectionDialogError
import com.oruke.onyx.app.component.RemoteConnectionDraft
import com.oruke.onyx.app.component.RemoteConnectionTestState
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.core.model.RemoteConnectionSavePolicy
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.resolve
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_delete_connection
import onyx.composeapp.generated.resources.action_edit_connection
import onyx.composeapp.generated.resources.action_new_connection
import onyx.composeapp.generated.resources.action_open
import onyx.composeapp.generated.resources.action_save_connection
import onyx.composeapp.generated.resources.action_test_connection
import onyx.composeapp.generated.resources.label_remote_connection_domain
import onyx.composeapp.generated.resources.label_remote_connection_empty
import onyx.composeapp.generated.resources.label_remote_connection_error_location_required
import onyx.composeapp.generated.resources.label_remote_connection_error_name_required
import onyx.composeapp.generated.resources.label_remote_connection_error_username_required
import onyx.composeapp.generated.resources.label_remote_connection_location
import onyx.composeapp.generated.resources.label_remote_connection_name
import onyx.composeapp.generated.resources.label_remote_connection_protocol
import onyx.composeapp.generated.resources.label_remote_connection_secret
import onyx.composeapp.generated.resources.label_remote_connection_test_failed
import onyx.composeapp.generated.resources.label_remote_connection_test_ready
import onyx.composeapp.generated.resources.label_remote_connection_test_success
import onyx.composeapp.generated.resources.label_remote_connection_testing
import onyx.composeapp.generated.resources.label_remote_connection_username
import onyx.composeapp.generated.resources.label_remote_credentials_save_do_not_save
import onyx.composeapp.generated.resources.label_remote_credentials_save_session
import onyx.composeapp.generated.resources.label_remote_credentials_save_system_keyring
import onyx.composeapp.generated.resources.label_remote_credentials_system_keyring_unavailable
import onyx.composeapp.generated.resources.label_remote_protocol_s3
import onyx.composeapp.generated.resources.label_remote_protocol_smb
import onyx.composeapp.generated.resources.label_remote_protocol_webdav
import onyx.composeapp.generated.resources.label_remote_protocol_webdavs
import onyx.composeapp.generated.resources.label_remote_credentials_save_policy
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun RemoteConnectionsSettings(
    connections: List<RemoteConnectionProfile>,
    connectionDraft: RemoteConnectionDraft,
    editingConnectionId: String?,
    testState: RemoteConnectionTestState,
    error: RemoteConnectionDialogError?,
    onDraftChange: (RemoteConnectionDraft) -> Unit,
    onNew: () -> Unit,
    onEdit: (RemoteConnectionProfile) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onDelete: (String) -> Unit,
    onOpen: (String) -> Unit,
    fontSize: TextUnit,
    labelFontSize: TextUnit,
) {
    val palette = LocalOnyxPalette.current
    val listScrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 360.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier
                .width(260.dp)
                .background(palette.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                .border(1.dp, palette.outlineVariant, RoundedCornerShape(6.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp, max = 280.dp)
                    .verticalScroll(listScrollState),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (connections.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.label_remote_connection_empty),
                            fontSize = fontSize,
                            color = palette.disabledForeground,
                        )
                    } else {
                        connections.forEach { connection ->
                            RemoteConnectionRow(
                                connection = connection,
                                selected = connection.id == editingConnectionId,
                                onOpen = { onOpen(connection.location) },
                                onEdit = { onEdit(connection) },
                                onDelete = { onDelete(connection.id) },
                                fontSize = fontSize,
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                DialogTextButton(
                    text = stringResource(Res.string.action_new_connection),
                    fontSize = fontSize,
                    onClick = onNew,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .background(palette.appBackground, RoundedCornerShape(6.dp))
                .border(1.dp, palette.outlineVariant, RoundedCornerShape(6.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingsSection(stringResource(Res.string.label_remote_connection_protocol), labelFontSize) {
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
            }
            SettingsTextField(
                label = stringResource(Res.string.label_remote_connection_name),
                value = connectionDraft.name,
                onValueChange = { value -> onDraftChange(connectionDraft.copy(name = value)) },
                fontSize = fontSize,
            )
            SettingsTextField(
                label = stringResource(Res.string.label_remote_connection_location),
                value = connectionDraft.location,
                onValueChange = { value -> onDraftChange(connectionDraft.copy(location = value)) },
                fontSize = fontSize,
            )
            SettingsTextField(
                label = stringResource(Res.string.label_remote_connection_username),
                value = connectionDraft.username,
                onValueChange = { value -> onDraftChange(connectionDraft.copy(username = value)) },
                fontSize = fontSize,
            )
            SettingsTextField(
                label = stringResource(Res.string.label_remote_connection_secret),
                value = connectionDraft.secret,
                onValueChange = { value -> onDraftChange(connectionDraft.copy(secret = value)) },
                fontSize = fontSize,
                password = true,
            )
            SettingsTextField(
                label = stringResource(Res.string.label_remote_connection_domain),
                value = connectionDraft.domain,
                onValueChange = { value -> onDraftChange(connectionDraft.copy(domain = value)) },
                fontSize = fontSize,
            )
            SettingsSection(stringResource(Res.string.label_remote_credentials_save_policy), labelFontSize) {
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
            error?.let { err ->
                Text(
                    text = remoteConnectionErrorText(err),
                    fontSize = 11.sp,
                    color = Color(0xFFD74E4E),
                )
            }
            RemoteConnectionTestStatus(testState)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
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
}

@Composable
private fun RemoteConnectionRow(
    connection: RemoteConnectionProfile,
    selected: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    fontSize: TextUnit,
) {
    val palette = LocalOnyxPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) palette.selectionBackground else palette.surface,
                RoundedCornerShape(4.dp),
            )
            .border(1.dp, if (selected) palette.accent else palette.outlineVariant, RoundedCornerShape(4.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = connection.name,
            fontSize = fontSize,
            color = palette.foreground,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(
            text = connection.location,
            fontSize = 10.sp,
            color = palette.mutedForeground,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            DialogTextButton(text = stringResource(Res.string.action_open), fontSize = 10.sp, onClick = onOpen)
            DialogTextButton(text = stringResource(Res.string.action_edit_connection), fontSize = 10.sp, onClick = onEdit)
            DialogTextButton(text = stringResource(Res.string.action_delete_connection), fontSize = 10.sp, onClick = onDelete)
        }
    }
}

@Composable
internal fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    fontSize: TextUnit,
    password: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 11.sp, color = LocalOnyxPalette.current.mutedForeground)
        OnyxTextInput(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            fontSize = fontSize,
            password = password,
        )
    }
}

@Composable
private fun RemoteConnectionTestStatus(testState: RemoteConnectionTestState) {
    val text = when (testState) {
        RemoteConnectionTestState.Idle -> stringResource(Res.string.label_remote_connection_test_ready)
        RemoteConnectionTestState.Testing -> stringResource(Res.string.label_remote_connection_testing)
        is RemoteConnectionTestState.Reachable -> {
            val capabilities = testState.capabilities.joinToString(", ")
            stringResource(Res.string.label_remote_connection_test_success, capabilities)
        }
        is RemoteConnectionTestState.Failed ->
            stringResource(Res.string.label_remote_connection_test_failed, testState.reason.resolve())
    }
    val color = when (testState) {
        is RemoteConnectionTestState.Reachable -> Color(0xFF2E8B57)
        is RemoteConnectionTestState.Failed -> Color(0xFFD74E4E)
        else -> LocalOnyxPalette.current.mutedForeground
    }
    Text(text = text, fontSize = 11.sp, color = color)
}

@Composable
private fun remoteProtocolLabel(protocol: RemoteConnectionProtocol): String {
    return when (protocol) {
        RemoteConnectionProtocol.SMB -> stringResource(Res.string.label_remote_protocol_smb)
        RemoteConnectionProtocol.WEBDAV -> stringResource(Res.string.label_remote_protocol_webdav)
        RemoteConnectionProtocol.WEBDAVS -> stringResource(Res.string.label_remote_protocol_webdavs)
        RemoteConnectionProtocol.S3 -> stringResource(Res.string.label_remote_protocol_s3)
    }
}

@Composable
private fun remoteSavePolicyLabel(policy: RemoteConnectionSavePolicy): String {
    return when (policy) {
        RemoteConnectionSavePolicy.DO_NOT_SAVE -> stringResource(Res.string.label_remote_credentials_save_do_not_save)
        RemoteConnectionSavePolicy.SESSION -> stringResource(Res.string.label_remote_credentials_save_session)
        RemoteConnectionSavePolicy.SYSTEM_KEYRING -> stringResource(Res.string.label_remote_credentials_save_system_keyring)
    }
}

@Composable
private fun remoteConnectionErrorText(error: RemoteConnectionDialogError): String {
    return when (error) {
        RemoteConnectionDialogError.NAME_EMPTY -> stringResource(Res.string.label_remote_connection_error_name_required)
        RemoteConnectionDialogError.LOCATION_EMPTY -> stringResource(Res.string.label_remote_connection_error_location_required)
        RemoteConnectionDialogError.USERNAME_EMPTY -> stringResource(Res.string.label_remote_connection_error_username_required)
        RemoteConnectionDialogError.SYSTEM_KEYRING_UNAVAILABLE ->
            stringResource(Res.string.label_remote_credentials_system_keyring_unavailable)
    }
}
