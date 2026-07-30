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
import com.oruke.onyx.app.component.CreateZipArchiveDialogError
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Text
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_close_menu
import onyx.composeapp.generated.resources.action_create_zip
import onyx.composeapp.generated.resources.label_create_archive_description
import onyx.composeapp.generated.resources.label_create_archive_error_empty
import onyx.composeapp.generated.resources.label_create_archive_error_invalid
import onyx.composeapp.generated.resources.label_create_archive_name
import onyx.composeapp.generated.resources.label_create_archive_placeholder
import onyx.composeapp.generated.resources.label_create_archive_title

/**
 * ZIP 压缩包名称输入对话框。
 *
 * @param state 当前 ZIP 压缩包创建对话框状态。
 * @param onDraftChange 归档名称草稿变化回调。
 * @param onConfirm 用户确认创建 ZIP 的回调。
 * @param onDismiss 用户关闭对话框的回调。
 */
@Composable
internal fun CreateZipArchiveDialog(
    state: RootDialogState.CreateZipArchive,
    onDraftChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var draft by remember { mutableStateOf(state.draft) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(state.draft) {
        if (state.draft != draft) {
            draft = state.draft
        }
    }

    DialogWindow(
        onCloseRequest = onDismiss,
        title = stringResource(Res.string.label_create_archive_title),
        state = rememberDialogState(width = 460.dp, height = 280.dp),
    ) {
        IntUiTheme(isDark = isSystemInDarkTheme()) {
            DialogFrame(
                title = stringResource(Res.string.label_create_archive_title),
                body = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = state.location,
                            fontSize = 11.sp,
                            color = LocalOnyxPalette.current.mutedForeground,
                        )
                        Text(
                            text = stringResource(Res.string.label_create_archive_description),
                            fontSize = 12.sp,
                            color = LocalOnyxPalette.current.foreground,
                        )
                        Text(
                            text = stringResource(Res.string.label_create_archive_name),
                            fontSize = 12.sp,
                            color = LocalOnyxPalette.current.foreground,
                        )
                        ZipArchiveNameInput(
                            value = draft,
                            focusRequester = focusRequester,
                            onValueChange = { value ->
                                draft = value
                                onDraftChange(value)
                            },
                            onConfirm = onConfirm,
                            onDismiss = onDismiss,
                        )
                        state.error?.let { error ->
                            Text(
                                text = when (error) {
                                    CreateZipArchiveDialogError.EMPTY_INPUT -> {
                                        stringResource(Res.string.label_create_archive_error_empty)
                                    }

                                    CreateZipArchiveDialogError.INVALID_NAME -> {
                                        stringResource(Res.string.label_create_archive_error_invalid)
                                    }
                                },
                                fontSize = 11.sp,
                                color = LocalOnyxPalette.current.error,
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
                        text = stringResource(Res.string.action_create_zip),
                        emphasized = true,
                        onClick = onConfirm,
                    )
                },
            )
        }
    }
}

/**
 * 绘制 ZIP 归档名称输入框并处理确认与取消快捷键。
 *
 * @param value 当前输入值。
 * @param focusRequester 对话框打开后请求键盘焦点的对象。
 * @param onValueChange 输入值变化回调。
 * @param onConfirm 用户确认创建 ZIP 的回调。
 * @param onDismiss 用户关闭对话框的回调。
 */
@Composable
private fun ZipArchiveNameInput(
    value: String,
    focusRequester: FocusRequester,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
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
        textStyle = TextStyle(
            fontSize = 13.sp,
            color = LocalOnyxPalette.current.foreground,
        ),
        cursorBrush = SolidColor(LocalOnyxPalette.current.accent),
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
                if (value.isBlank()) {
                    Text(
                        text = stringResource(Res.string.label_create_archive_placeholder),
                        fontSize = 13.sp,
                        color = LocalOnyxPalette.current.disabledForeground,
                    )
                }
                innerTextField()
            }
        },
    )
}
