package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.oruke.onyx.app.component.RemoteCredentialsDialogError
import com.oruke.onyx.app.component.RemoteCredentialsDraft
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.vfs.api.RemoteCredentialSavePolicy
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Text
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_close_menu
import onyx.composeapp.generated.resources.action_confirm
import onyx.composeapp.generated.resources.label_remote_credentials_do_not_save_hint
import onyx.composeapp.generated.resources.label_remote_credentials_domain
import onyx.composeapp.generated.resources.label_remote_credentials_error_username_required
import onyx.composeapp.generated.resources.label_remote_credentials_hint
import onyx.composeapp.generated.resources.label_remote_credentials_password
import onyx.composeapp.generated.resources.label_remote_credentials_rejected
import onyx.composeapp.generated.resources.label_remote_credentials_save_do_not_save
import onyx.composeapp.generated.resources.label_remote_credentials_save_policy
import onyx.composeapp.generated.resources.label_remote_credentials_save_session
import onyx.composeapp.generated.resources.label_remote_credentials_save_system_keyring
import onyx.composeapp.generated.resources.label_remote_credentials_session_only
import onyx.composeapp.generated.resources.label_remote_credentials_system_keyring_unavailable
import onyx.composeapp.generated.resources.label_remote_credentials_title
import onyx.composeapp.generated.resources.label_remote_credentials_username

@Composable
internal fun RemoteCredentialsDialog(
    state: RootDialogState.RemoteCredentials,
    onDraftChange: (RemoteCredentialsDraft) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    val title = stringResource(Res.string.label_remote_credentials_title)

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    DialogWindow(
        onCloseRequest = onDismiss,
        title = title,
        state = rememberDialogState(width = 540.dp, height = 460.dp),
        resizable = true,
    ) {
        window.minimumSize = java.awt.Dimension(480, 360)
        IntUiTheme(isDark = isSystemInDarkTheme()) {
            DialogFrame(
                title = title,
                body = {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(end = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RemoteCredentialHeader(
                            location = state.location,
                            hint = stringResource(Res.string.label_remote_credentials_hint, state.protocol.name),
                        )
                        CredentialInputField(
                            label = stringResource(Res.string.label_remote_credentials_username),
                            value = state.draft.username,
                            onValueChange = { value -> onDraftChange(state.draft.copy(username = value)) },
                            focusRequester = focusRequester,
                            onConfirm = onConfirm,
                            onDismiss = onDismiss,
                        )
                        CredentialInputField(
                            label = stringResource(Res.string.label_remote_credentials_password),
                            value = state.draft.password,
                            onValueChange = { value -> onDraftChange(state.draft.copy(password = value)) },
                            password = true,
                            onConfirm = onConfirm,
                            onDismiss = onDismiss,
                        )
                        CredentialInputField(
                            label = stringResource(Res.string.label_remote_credentials_domain),
                            value = state.draft.domain,
                            onValueChange = { value -> onDraftChange(state.draft.copy(domain = value)) },
                            onConfirm = onConfirm,
                            onDismiss = onDismiss,
                        )
                        CredentialSavePolicySelector(
                            selected = state.draft.savePolicy,
                            onSelected = { savePolicy ->
                                onDraftChange(state.draft.copy(savePolicy = savePolicy))
                            },
                        )
                        Text(
                            text = when (state.draft.savePolicy) {
                                RemoteCredentialSavePolicy.DO_NOT_SAVE ->
                                    stringResource(Res.string.label_remote_credentials_do_not_save_hint)

                                RemoteCredentialSavePolicy.SESSION ->
                                    stringResource(Res.string.label_remote_credentials_session_only)

                                RemoteCredentialSavePolicy.SYSTEM_KEYRING ->
                                    stringResource(Res.string.label_remote_credentials_save_system_keyring)
                            },
                            fontSize = 11.sp,
                            color = LocalOnyxPalette.current.mutedForeground,
                        )
                        if (state.rejected) {
                            Text(
                                text = stringResource(Res.string.label_remote_credentials_rejected),
                                fontSize = 11.sp,
                                color = Color(0xFFD74E4E),
                            )
                        }
                        if (state.error == RemoteCredentialsDialogError.USERNAME_EMPTY) {
                            Text(
                                text = stringResource(Res.string.label_remote_credentials_error_username_required),
                                fontSize = 11.sp,
                                color = Color(0xFFD74E4E),
                            )
                        }
                        if (state.error == RemoteCredentialsDialogError.SYSTEM_KEYRING_UNAVAILABLE) {
                            Text(
                                text = stringResource(Res.string.label_remote_credentials_system_keyring_unavailable),
                                fontSize = 11.sp,
                                color = Color(0xFFD74E4E),
                            )
                        }
                    }
                },
                actions = {
                    DialogTextButton(
                        text = stringResource(Res.string.action_close_menu),
                        onClick = onDismiss,
                    )
                    DialogTextButton(
                        text = stringResource(Res.string.action_confirm),
                        emphasized = true,
                        onClick = onConfirm,
                    )
                },
            )
        }
    }
}

@Composable
private fun RemoteCredentialHeader(
    location: String,
    hint: String,
) {
    val palette = LocalOnyxPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surfaceVariant, RoundedCornerShape(6.dp))
            .border(1.dp, palette.outlineVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = location,
            fontSize = 11.sp,
            color = palette.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = hint,
            fontSize = 12.sp,
            color = palette.foreground,
        )
    }
}

@Composable
private fun CredentialSavePolicySelector(
    selected: RemoteCredentialSavePolicy,
    onSelected: (RemoteCredentialSavePolicy) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = stringResource(Res.string.label_remote_credentials_save_policy),
            fontSize = 11.sp,
            color = LocalOnyxPalette.current.mutedForeground,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RemoteCredentialSavePolicy.entries.forEach { policy ->
                val active = policy == selected
                val text = when (policy) {
                    RemoteCredentialSavePolicy.DO_NOT_SAVE ->
                        stringResource(Res.string.label_remote_credentials_save_do_not_save)

                    RemoteCredentialSavePolicy.SESSION ->
                        stringResource(Res.string.label_remote_credentials_save_session)

                    RemoteCredentialSavePolicy.SYSTEM_KEYRING ->
                        stringResource(Res.string.label_remote_credentials_save_system_keyring)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .background(
                            if (active) {
                                LocalOnyxPalette.current.accent.copy(alpha = 0.14f)
                            } else {
                                LocalOnyxPalette.current.surfaceVariant
                            },
                            RoundedCornerShape(6.dp),
                        )
                        .border(
                            1.dp,
                            if (active) LocalOnyxPalette.current.accent else LocalOnyxPalette.current.outlineVariant,
                            RoundedCornerShape(6.dp),
                        )
                        .clickable { onSelected(policy) }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = text,
                        fontSize = 11.sp,
                        color = if (active) LocalOnyxPalette.current.accent else LocalOnyxPalette.current.foreground,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun CredentialInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    password: Boolean = false,
    focusRequester: FocusRequester? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focusModifier = if (focusRequester != null) {
        Modifier.focusRequester(focusRequester)
    } else {
        Modifier
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = LocalOnyxPalette.current.mutedForeground,
        )
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            OnyxTextInput(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(focusModifier)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        when (event.key) {
                            Key.Enter -> {
                                onConfirm()
                                true
                            }

                            Key.Escape -> {
                                onDismiss()
                                true
                            }

                            else -> false
                        }
                },
                fontSize = 12.sp,
                height = 30.dp,
                password = password,
            )
        }
    }
}
