package com.oruke.onyx.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.oruke.onyx.app.component.CreateDirectoriesDialogError
import com.oruke.onyx.app.component.FileTransferOperation
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.filesystem.TransferConflictStrategy
import com.oruke.onyx.core.model.AppLocale
import com.oruke.onyx.core.model.DeleteMode
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_apply
import onyx.composeapp.generated.resources.action_close_menu
import onyx.composeapp.generated.resources.action_confirm
import onyx.composeapp.generated.resources.action_delete_selected
import onyx.composeapp.generated.resources.action_keep_both
import onyx.composeapp.generated.resources.action_layout_dual_horizontal
import onyx.composeapp.generated.resources.action_layout_dual_vertical
import onyx.composeapp.generated.resources.action_layout_single
import onyx.composeapp.generated.resources.action_move
import onyx.composeapp.generated.resources.action_new_directory
import onyx.composeapp.generated.resources.action_overwrite
import onyx.composeapp.generated.resources.action_skip
import onyx.composeapp.generated.resources.label_conflict_resolution_message
import onyx.composeapp.generated.resources.label_conflict_resolution_title
import onyx.composeapp.generated.resources.label_create_directories_description
import onyx.composeapp.generated.resources.label_create_directories_error_empty
import onyx.composeapp.generated.resources.label_create_directories_placeholder
import onyx.composeapp.generated.resources.label_create_directories_shortcuts
import onyx.composeapp.generated.resources.label_create_directories_title
import onyx.composeapp.generated.resources.label_archive_password
import onyx.composeapp.generated.resources.label_archive_password_hint
import onyx.composeapp.generated.resources.label_default_layout_mode
import onyx.composeapp.generated.resources.label_default_view_mode
import onyx.composeapp.generated.resources.label_delete_confirmation_move_to_trash
import onyx.composeapp.generated.resources.label_delete_confirmation_permanent
import onyx.composeapp.generated.resources.label_delete_confirmation_trash_unavailable
import onyx.composeapp.generated.resources.label_delete_mode
import onyx.composeapp.generated.resources.label_delete_mode_move_to_trash
import onyx.composeapp.generated.resources.label_delete_mode_permanent
import onyx.composeapp.generated.resources.label_mode_details
import onyx.composeapp.generated.resources.label_mode_gallery
import onyx.composeapp.generated.resources.label_operation_copy
import onyx.composeapp.generated.resources.label_operation_move
import onyx.composeapp.generated.resources.label_setting_hide
import onyx.composeapp.generated.resources.label_setting_show
import onyx.composeapp.generated.resources.label_settings_title
import onyx.composeapp.generated.resources.label_sidebar_visibility
import onyx.composeapp.generated.resources.label_sidebar_tree_visibility
import onyx.composeapp.generated.resources.label_status_bar_visibility
import onyx.composeapp.generated.resources.label_ui_scale
import onyx.composeapp.generated.resources.label_settings_general
import onyx.composeapp.generated.resources.label_settings_layout
import onyx.composeapp.generated.resources.label_settings_appearance
import onyx.composeapp.generated.resources.label_settings_columns
import onyx.composeapp.generated.resources.label_language
import onyx.composeapp.generated.resources.label_locale_system
import onyx.composeapp.generated.resources.label_locale_english
import onyx.composeapp.generated.resources.label_locale_chinese
import onyx.composeapp.generated.resources.label_locale_japanese
import onyx.composeapp.generated.resources.label_list_row_height
import onyx.composeapp.generated.resources.label_list_font_size
import onyx.composeapp.generated.resources.label_zebra_stripe
import onyx.composeapp.generated.resources.label_column_name
import onyx.composeapp.generated.resources.label_column_type
import onyx.composeapp.generated.resources.label_column_size
import onyx.composeapp.generated.resources.label_column_modified
import onyx.composeapp.generated.resources.label_verbose_logging
import onyx.composeapp.generated.resources.label_verbose_logging_hint
import onyx.composeapp.generated.resources.label_setting_enabled
import onyx.composeapp.generated.resources.label_setting_disabled
import onyx.composeapp.generated.resources.message_apply_to_remaining_conflicts
import com.oruke.onyx.app.OnyxLogger
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text


@Composable
internal fun ConfirmationDialog(
    state: RootDialogState.DeleteSelectionConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = if (state.moveToTrash) {
        stringResource(Res.string.action_move)
    } else {
        stringResource(Res.string.action_delete_selected)
    }
    val message = buildString {
        append(
            if (state.moveToTrash) {
                stringResource(Res.string.label_delete_confirmation_move_to_trash, state.itemCount)
            } else {
                stringResource(Res.string.label_delete_confirmation_permanent, state.itemCount)
            }
        )
        if (state.trashUnavailable) {
            append("\n")
            append(stringResource(Res.string.label_delete_confirmation_trash_unavailable))
        }
    }
    val confirmLabel = if (state.moveToTrash) {
        stringResource(Res.string.action_move)
    } else {
        stringResource(Res.string.action_delete_selected)
    }
    // 0 = 取消（默认安全焦点），1 = 确认
    var focusedButton by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    DialogWindow(
        onCloseRequest = onDismiss,
        title = title,
        state = rememberDialogState(width = 420.dp, height = 220.dp),
        resizable = false,
    ) {
        IntUiTheme(isDark = isSystemInDarkTheme()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LocalOnyxPalette.current.appBackground)
                    .padding(14.dp)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Escape -> { onDismiss(); true }
                            Key.Enter -> {
                                if (focusedButton == 1) onConfirm() else onDismiss()
                                true
                            }
                            Key.DirectionLeft, Key.Tab -> {
                                focusedButton = (focusedButton - 1).coerceAtLeast(0)
                                true
                            }
                            Key.DirectionRight -> {
                                focusedButton = (focusedButton + 1).coerceAtMost(1)
                                true
                            }
                            else -> false
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LocalOnyxPalette.current.foreground,
                )
                Box(modifier = Modifier.weight(1f, fill = true)) {
                    Text(
                        text = message,
                        fontSize = 12.sp,
                        color = LocalOnyxPalette.current.foreground,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DialogTextButton(
                        text = stringResource(Res.string.action_close_menu),
                        onClick = onDismiss,
                        focused = focusedButton == 0,
                    )
                    DialogTextButton(
                        text = confirmLabel,
                        emphasized = true,
                        destructive = true,
                        onClick = onConfirm,
                        focused = focusedButton == 1,
                    )
                }
            }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        }
    }
}

@Composable
internal fun ConflictResolutionDialog(
    state: RootDialogState.ConflictResolution,
    onResolve: (TransferConflictStrategy, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var applyToAll by remember(state.sourceName, state.currentIndex, state.total) { mutableStateOf(false) }
    // 0=取消, 1=跳过, 2=保留两者, 3=覆盖
    var focusedButton by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val operationLabel = stringResource(
        if (state.operation == FileTransferOperation.COPY) {
            Res.string.label_operation_copy
        } else {
            Res.string.label_operation_move
        }
    )
    val dialogTitle = stringResource(Res.string.label_conflict_resolution_title, state.currentIndex, state.total)
    DialogWindow(
        onCloseRequest = onDismiss,
        title = dialogTitle,
        state = rememberDialogState(width = 460.dp, height = 260.dp),
        resizable = false,
    ) {
        IntUiTheme(isDark = isSystemInDarkTheme()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LocalOnyxPalette.current.appBackground)
                    .padding(14.dp)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Escape -> { onDismiss(); true }
                            Key.Enter -> {
                                when (focusedButton) {
                                    0 -> onDismiss()
                                    1 -> onResolve(TransferConflictStrategy.SKIP, applyToAll)
                                    2 -> onResolve(TransferConflictStrategy.KEEP_BOTH, applyToAll)
                                    3 -> onResolve(TransferConflictStrategy.OVERWRITE, applyToAll)
                                }
                                true
                            }
                            Key.DirectionLeft -> {
                                focusedButton = (focusedButton - 1).coerceAtLeast(0)
                                true
                            }
                            Key.DirectionRight, Key.Tab -> {
                                focusedButton = (focusedButton + 1).coerceAtMost(3)
                                true
                            }
                            else -> false
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = dialogTitle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LocalOnyxPalette.current.foreground,
                )
                Box(modifier = Modifier.weight(1f, fill = true)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = operationLabel,
                            fontSize = 11.sp,
                            color = LocalOnyxPalette.current.mutedForeground,
                        )
                        Text(
                            text = stringResource(
                                Res.string.label_conflict_resolution_message,
                                state.sourceName,
                                state.targetLocation,
                            ),
                            fontSize = 12.sp,
                            color = LocalOnyxPalette.current.foreground,
                        )
                        ApplyToAllToggle(
                            checked = applyToAll,
                            text = stringResource(Res.string.message_apply_to_remaining_conflicts),
                            onToggle = { applyToAll = !applyToAll },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DialogTextButton(
                        text = stringResource(Res.string.action_close_menu),
                        onClick = onDismiss,
                        focused = focusedButton == 0,
                    )
                    DialogTextButton(
                        text = stringResource(Res.string.action_skip),
                        onClick = { onResolve(TransferConflictStrategy.SKIP, applyToAll) },
                        focused = focusedButton == 1,
                    )
                    DialogTextButton(
                        text = stringResource(Res.string.action_keep_both),
                        onClick = { onResolve(TransferConflictStrategy.KEEP_BOTH, applyToAll) },
                        focused = focusedButton == 2,
                    )
                    DialogTextButton(
                        text = stringResource(Res.string.action_overwrite),
                        emphasized = true,
                        onClick = { onResolve(TransferConflictStrategy.OVERWRITE, applyToAll) },
                        focused = focusedButton == 3,
                    )
                }
            }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        }
    }
}

@Composable
internal fun CreateDirectoriesDialog(
    state: RootDialogState.CreateDirectories,
    onDraftChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val inputScrollState = rememberScrollState()
    var draftFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = state.draft,
                selection = TextRange(state.draft.length),
            )
        )
    }
    val inputTextStyle = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = LocalOnyxPalette.current.foreground,
        textAlign = TextAlign.Start,
        textDirection = TextDirection.Ltr,
        fontFamily = FontFamily.Monospace,
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(state.draft) {
        if (state.draft != draftFieldValue.text) {
            draftFieldValue = draftFieldValue.copy(
                text = state.draft,
                selection = TextRange(state.draft.length),
            )
        }
    }

    DialogWindow(
        onCloseRequest = onDismiss,
        title = stringResource(Res.string.label_create_directories_title),
        state = rememberDialogState(width = 520.dp, height = 360.dp),
    ) {
        IntUiTheme(isDark = isSystemInDarkTheme()) {
            DialogFrame(
                title = stringResource(Res.string.label_create_directories_title),
                body = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = state.location,
                            fontSize = 11.sp,
                            color = LocalOnyxPalette.current.mutedForeground,
                        )
                        Text(
                            text = stringResource(Res.string.label_create_directories_description),
                            fontSize = 12.sp,
                            color = LocalOnyxPalette.current.foreground,
                        )
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            BasicTextField(
                                value = draftFieldValue,
                                onValueChange = { nextValue ->
                                    draftFieldValue = nextValue
                                    onDraftChange(nextValue.text)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester)
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) {
                                            return@onPreviewKeyEvent false
                                        }
                                        when {
                                            (event.key == Key.Enter) && (event.isCtrlPressed || event.isMetaPressed) -> {
                                                onConfirm()
                                                true
                                            }

                                            event.key == Key.Escape -> {
                                                onDismiss()
                                                true
                                            }

                                            else -> false
                                        }
                                    },
                                textStyle = inputTextStyle,
                                cursorBrush = SolidColor(LocalOnyxPalette.current.accent),
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 170.dp, max = 220.dp)
                                            .background(
                                                LocalOnyxPalette.current.inputBackground,
                                                RoundedCornerShape(6.dp)
                                            )
                                            .border(
                                                1.dp,
                                                LocalOnyxPalette.current.outlineVariant,
                                                RoundedCornerShape(6.dp)
                                            )
                                            .padding(horizontal = 10.dp, vertical = 9.dp)
                                            .verticalScroll(inputScrollState),
                                    ) {
                                        if (draftFieldValue.text.isBlank()) {
                                            Text(
                                                text = stringResource(Res.string.label_create_directories_placeholder),
                                                fontSize = 12.sp,
                                                lineHeight = 17.sp,
                                                color = LocalOnyxPalette.current.disabledForeground,
                                                fontFamily = FontFamily.Monospace,
                                            )
                                        }
                                        innerTextField()
                                    }
                                },
                            )
                        }
                        Text(
                            text = stringResource(Res.string.label_create_directories_shortcuts),
                            fontSize = 11.sp,
                            color = LocalOnyxPalette.current.mutedForeground,
                        )
                        state.error?.let { error ->
                            Text(
                                text = when (error) {
                                    CreateDirectoriesDialogError.EMPTY_INPUT ->
                                        stringResource(Res.string.label_create_directories_error_empty)
                                },
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
                        text = stringResource(Res.string.action_new_directory),
                        emphasized = true,
                        onClick = onConfirm,
                    )
                },
            )
        }
    }
}

@Composable
internal fun DialogFrame(
    title: String,
    body: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalOnyxPalette.current.appBackground)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = LocalOnyxPalette.current.foreground,
        )
        Box(modifier = Modifier.weight(1f, fill = true)) {
            body()
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions()
        }
    }
}

@Composable
internal fun DialogTextButton(
    text: String,
    emphasized: Boolean = false,
    destructive: Boolean = false,
    focused: Boolean = false,
    fontSize: TextUnit = 12.sp,
    onClick: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val baseBg = when {
        destructive && emphasized -> Color(0xFFD74E4E)
        emphasized -> palette.accent
        else -> palette.surfaceVariant
    }
    val bg by animateColorAsState(
        if (hovered || focused) baseBg.copy(alpha = baseBg.alpha * 0.85f) else baseBg, tween(120),
    )
    val contentColor = if (emphasized) Color.White else palette.foreground
    val focusBorder = if (focused) Modifier.border(1.5.dp, palette.accent, RoundedCornerShape(6.dp)) else Modifier
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .hoverable(src)
            .background(bg, RoundedCornerShape(6.dp))
            .then(focusBorder)
            .clickable(src, null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, fontSize = fontSize, color = contentColor)
    }
}

@Composable
internal fun ApplyToAllToggle(
    checked: Boolean,
    text: String,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .border(1.dp, LocalOnyxPalette.current.outline, RoundedCornerShape(4.dp))
                .background(
                    if (checked) LocalOnyxPalette.current.accent else Color.Transparent,
                    RoundedCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text(text = "✓", fontSize = 10.sp, color = Color.White)
            }
        }
        Text(
            text = text,
            fontSize = 12.sp,
            color = LocalOnyxPalette.current.foreground,
        )
    }
}

/**
 * 压缩包密码输入对话框。
 */
@Composable
internal fun ArchivePasswordDialog(
    archiveName: String,
    error: String?,
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
                                text = errMsg,
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
