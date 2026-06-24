package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.resolve
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Text
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_close_menu
import onyx.composeapp.generated.resources.action_confirm
import onyx.composeapp.generated.resources.label_archive_password
import onyx.composeapp.generated.resources.label_archive_password_hint

/**
 * 压缩包密码输入对话框。
 */
@Composable
internal fun ArchivePasswordDialog(
    archiveName: String,
    error: I18nMessage?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    DialogWindow(
        onCloseRequest = onDismiss,
        title = stringResource(Res.string.label_archive_password),
        state = rememberDialogState(width = 420.dp, height = 250.dp),
    ) {
        IntUiTheme(isDark = isSystemInDarkTheme()) {
            DialogFrame(
                title = stringResource(Res.string.label_archive_password),
                body = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = archiveName,
                            fontSize = 11.sp,
                            color = LocalOnyxPalette.current.mutedForeground,
                        )
                        Text(
                            text = stringResource(Res.string.label_archive_password_hint),
                            fontSize = 12.sp,
                            color = LocalOnyxPalette.current.foreground,
                        )
                        BasicTextField(
                            value = password,
                            onValueChange = { password = it },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) {
                                        return@onPreviewKeyEvent false
                                    }
                                    when (event.key) {
                                        Key.Enter -> {
                                            if (password.isNotEmpty()) {
                                                onConfirm(password)
                                            }
                                            true
                                        }
                                        Key.Escape -> {
                                            onDismiss()
                                            true
                                        }
                                        else -> false
                                    }
                                },
                            textStyle = TextStyle(
                                fontSize = 13.sp,
                                color = LocalOnyxPalette.current.foreground,
                            ),
                            cursorBrush = SolidColor(LocalOnyxPalette.current.accent),
                            visualTransformation = if (showPassword) {
                                androidx.compose.ui.text.input.VisualTransformation.None
                            } else {
                                androidx.compose.ui.text.input.PasswordVisualTransformation()
                            },
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(34.dp)
                                        .background(
                                            LocalOnyxPalette.current.inputBackground,
                                            RoundedCornerShape(6.dp),
                                        )
                                        .border(
                                            1.dp,
                                            LocalOnyxPalette.current.outlineVariant,
                                            RoundedCornerShape(6.dp),
                                        )
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                ) {
                                    if (password.isEmpty()) {
                                        Text(
                                            text = "••••••••",
                                            fontSize = 13.sp,
                                            color = LocalOnyxPalette.current.disabledForeground,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                        error?.let { errMsg ->
                            Text(
                                text = errMsg.resolve(),
                                fontSize = 11.sp,
                                color = androidx.compose.ui.graphics.Color(0xFFD74E4E),
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
                        onClick = {
                            if (password.isNotEmpty()) {
                                onConfirm(password)
                            }
                        },
                    )
                },
            )
        }
    }
}
