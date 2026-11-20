package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.rememberDialogState
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.resolve
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_close_window
import onyx.composeapp.generated.resources.action_confirm
import onyx.composeapp.generated.resources.label_archive_password
import onyx.composeapp.generated.resources.label_archive_password_hint
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/**
 * 压缩包密码输入对话框：支持错密重试自动全选重焦与提交期间防连击锁定。
 *
 * @param archiveName 归档名称。
 * @param error 上次校验失败时的本地化错误提示。
 * @param attempt 失败重试递增计数，用于驱动重复错误时的自动全选。
 * @param verifying 是否正在后台校验密码。
 * @param onConfirm 提交密码回调。
 * @param onDismiss 取消或关闭对话框回调。
 */
@Composable
internal fun ArchivePasswordDialog(
    archiveName: String,
    error: I18nMessage?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    attempt: Int = 0,
    verifying: Boolean = false,
    requestId: String = "",
) {
    val title = stringResource(Res.string.label_archive_password)
    OnyxDialogWindow(
        onCloseRequest = onDismiss,
        title = title,
        state = rememberDialogState(width = 420.dp, height = 240.dp),
        windowName = "onyx-archive-password",
    ) {
        ArchivePasswordDialogContent(
            archiveName = archiveName,
            error = error,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            attempt = attempt,
            verifying = verifying,
            requestId = requestId,
        )
    }
}

/**
 * 压缩包密码输入对话框主体内容（无窗口外壳，支持独立挂载与交互验证）。
 */
@Composable
internal fun ArchivePasswordDialogContent(
    archiveName: String,
    error: I18nMessage?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    attempt: Int = 0,
    verifying: Boolean = false,
    requestId: String = "",
) {
    val sessionKey = requestId.ifBlank { archiveName }
    val focusRequester = remember { FocusRequester() }
    var password by remember(sessionKey) { mutableStateOf(TextFieldValue("")) }
    var localSubmitting by remember(sessionKey, attempt, error) { mutableStateOf(false) }
    val busy = verifying || localSubmitting

    fun submitIfAllowed() {
        if (busy || password.text.isEmpty()) return
        localSubmitting = true
        onConfirm(password.text)
    }

    val title = stringResource(Res.string.label_archive_password)
    LaunchedEffect(sessionKey) {
        focusRequester.requestFocus()
    }
    LaunchedEffect(attempt, error) {
        if (error != null || attempt > 0) {
            localSubmitting = false
            password = password.copy(selection = TextRange(0, password.text.length))
            focusRequester.requestFocus()
        }
    }
    DialogFrame(
        title = title,
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
                    onValueChange = { if (!busy) password = it },
                    enabled = !busy,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("archive-password-input")
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) {
                                return@onPreviewKeyEvent false
                            }
                            when (event.key) {
                                Key.Enter, Key.NumPadEnter -> {
                                    submitIfAllowed()
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
                    visualTransformation = PasswordVisualTransformation(),
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
                                    if (error != null) {
                                        LocalOnyxPalette.current.error
                                    } else {
                                        LocalOnyxPalette.current.outlineVariant
                                    },
                                    RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                        ) {
                            innerTextField()
                        }
                    },
                )
                error?.let { errMsg ->
                    Text(
                        text = errMsg.resolve(),
                        fontSize = 11.sp,
                        color = LocalOnyxPalette.current.errorForeground,
                    )
                }
            }
        },
        actions = {
            DialogTextButton(
                text = stringResource(Res.string.action_close_window),
                onClick = onDismiss,
            )
            DialogTextButton(
                text = stringResource(Res.string.action_confirm),
                emphasized = true,
                enabled = !busy && password.text.isNotEmpty(),
                onClick = ::submitIfAllowed,
            )
        },
    )
}
