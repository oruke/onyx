package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.oruke.onyx.app.component.CreateDirectoriesDialogError
import com.oruke.onyx.app.component.FileTransferOperation
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.filesystem.TransferConflictStrategy
import com.oruke.onyx.core.model.DeleteMode
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.ui.theme.OnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_apply
import onyx.composeapp.generated.resources.action_close_menu
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
import onyx.composeapp.generated.resources.label_status_bar_visibility
import onyx.composeapp.generated.resources.label_ui_scale
import onyx.composeapp.generated.resources.message_apply_to_remaining_conflicts
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun SettingsDialog(
    state: RootDialogState.Settings,
    palette: OnyxPalette,
    onDraftChange: (OnyxSettings) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = state.draft
    val title = stringResource(Res.string.label_settings_title)
    DialogWindow(
        onCloseRequest = onDismiss,
        title = title,
        state = rememberDialogState(width = 560.dp, height = 420.dp),
        resizable = false,
    ) {
        IntUiTheme(isDark = isSystemInDarkTheme()) {
            DialogFrame(
                palette = palette,
                title = title,
                body = {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SettingsSection(
                            title = stringResource(Res.string.label_default_layout_mode),
                            palette = palette,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SettingsOption(
                                    selected = draft.defaultLayoutMode == PaneLayoutMode.SINGLE,
                                    text = stringResource(Res.string.action_layout_single),
                                    palette = palette,
                                    onClick = { onDraftChange(draft.copy(defaultLayoutMode = PaneLayoutMode.SINGLE)) },
                                )
                                SettingsOption(
                                    selected = draft.defaultLayoutMode == PaneLayoutMode.DUAL_VERTICAL,
                                    text = stringResource(Res.string.action_layout_dual_vertical),
                                    palette = palette,
                                    onClick = { onDraftChange(draft.copy(defaultLayoutMode = PaneLayoutMode.DUAL_VERTICAL)) },
                                )
                                SettingsOption(
                                    selected = draft.defaultLayoutMode == PaneLayoutMode.DUAL_HORIZONTAL,
                                    text = stringResource(Res.string.action_layout_dual_horizontal),
                                    palette = palette,
                                    onClick = { onDraftChange(draft.copy(defaultLayoutMode = PaneLayoutMode.DUAL_HORIZONTAL)) },
                                )
                            }
                        }

                        SettingsSection(
                            title = stringResource(Res.string.label_default_view_mode),
                            palette = palette,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SettingsOption(
                                    selected = draft.defaultViewMode == ViewMode.DETAILS,
                                    text = stringResource(Res.string.label_mode_details),
                                    palette = palette,
                                    onClick = { onDraftChange(draft.copy(defaultViewMode = ViewMode.DETAILS)) },
                                )
                                SettingsOption(
                                    selected = draft.defaultViewMode == ViewMode.GALLERY,
                                    text = stringResource(Res.string.label_mode_gallery),
                                    palette = palette,
                                    onClick = { onDraftChange(draft.copy(defaultViewMode = ViewMode.GALLERY)) },
                                )
                            }
                        }

                        SettingsSection(
                            title = stringResource(Res.string.label_delete_mode),
                            palette = palette,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SettingsOption(
                                    selected = draft.deleteMode == DeleteMode.MOVE_TO_TRASH_PREFERRED,
                                    text = stringResource(Res.string.label_delete_mode_move_to_trash),
                                    palette = palette,
                                    onClick = { onDraftChange(draft.copy(deleteMode = DeleteMode.MOVE_TO_TRASH_PREFERRED)) },
                                )
                                SettingsOption(
                                    selected = draft.deleteMode == DeleteMode.PERMANENT,
                                    text = stringResource(Res.string.label_delete_mode_permanent),
                                    palette = palette,
                                    onClick = { onDraftChange(draft.copy(deleteMode = DeleteMode.PERMANENT)) },
                                )
                            }
                        }

                        SettingsSection(
                            title = stringResource(Res.string.label_sidebar_visibility),
                            palette = palette,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SettingsOption(
                                    selected = draft.sidebarVisible,
                                    text = stringResource(Res.string.label_setting_show),
                                    palette = palette,
                                    onClick = { onDraftChange(draft.copy(sidebarVisible = true)) },
                                )
                                SettingsOption(
                                    selected = !draft.sidebarVisible,
                                    text = stringResource(Res.string.label_setting_hide),
                                    palette = palette,
                                    onClick = { onDraftChange(draft.copy(sidebarVisible = false)) },
                                )
                            }
                        }

                        SettingsSection(
                            title = stringResource(Res.string.label_status_bar_visibility),
                            palette = palette,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SettingsOption(
                                    selected = draft.statusBarVisible,
                                    text = stringResource(Res.string.label_setting_show),
                                    palette = palette,
                                    onClick = { onDraftChange(draft.copy(statusBarVisible = true)) },
                                )
                                SettingsOption(
                                    selected = !draft.statusBarVisible,
                                    text = stringResource(Res.string.label_setting_hide),
                                    palette = palette,
                                    onClick = { onDraftChange(draft.copy(statusBarVisible = false)) },
                                )
                            }
                        }

                        SettingsSection(
                            title = stringResource(Res.string.label_ui_scale),
                            palette = palette,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = "${draft.uiScale}%",
                                        fontSize = 11.sp,
                                        color = palette.foreground,
                                        modifier = Modifier.width(52.dp),
                                    )
                                    SettingsScaleSlider(
                                        value = draft.uiScale,
                                        min = 75,
                                        max = 200,
                                        palette = palette,
                                        onValueChange = { value -> onDraftChange(draft.copy(uiScale = value)) },
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    DialogTextButton(
                        text = stringResource(Res.string.action_close_menu),
                        palette = palette,
                        onClick = onDismiss,
                    )
                    DialogTextButton(
                        text = stringResource(Res.string.action_apply),
                        palette = palette,
                        emphasized = true,
                        onClick = onConfirm,
                    )
                },
            )
        }
    }
}

@Composable
internal fun SettingsSection(
    title: String,
    palette: OnyxPalette,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            fontSize = 12.sp,
            color = palette.foreground,
            fontWeight = FontWeight.Medium,
        )
        content()
    }
}

@Composable
internal fun SettingsOption(
    selected: Boolean,
    text: String,
    palette: OnyxPalette,
    onClick: () -> Unit,
) {
    val background = if (selected) palette.titleBarActiveBackground else palette.surface
    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(4.dp))
            .border(1.dp, if (selected) palette.accent else palette.outlineVariant, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else palette.foreground,
            fontSize = 11.sp,
        )
    }
}

@Composable
internal fun SettingsScaleSlider(
    value: Int,
    min: Int,
    max: Int,
    palette: OnyxPalette,
    onValueChange: (Int) -> Unit,
) {
    val clampedValue = value.coerceIn(min, max)
    val fraction = ((clampedValue - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    val sliderWidthDp = 280
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sliderWidthPx = with(density) { sliderWidthDp.dp.toPx() }

    Box(
        modifier = Modifier
            .width(sliderWidthDp.dp)
            .height(16.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val x = change.position.x.coerceIn(0f, sliderWidthPx)
                    val newFraction = x / sliderWidthPx
                    onValueChange((min + (newFraction * (max - min)).toInt()).coerceIn(min, max))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val newFraction = (offset.x / sliderWidthPx).coerceIn(0f, 1f)
                    onValueChange((min + (newFraction * (max - min)).toInt()).coerceIn(min, max))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(palette.outlineVariant, RoundedCornerShape(1.dp)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(3.dp)
                .background(palette.accent, RoundedCornerShape(1.dp)),
        )
        Box(
            modifier = Modifier
                .offset(x = (fraction * (sliderWidthDp - 8)).dp)
                .size(8.dp)
                .background(palette.accent, RoundedCornerShape(4.dp)),
        )
    }
}

@Composable
internal fun ConfirmationDialog(
    state: RootDialogState.DeleteSelectionConfirmation,
    palette: OnyxPalette,
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
    DialogWindow(
        onCloseRequest = onDismiss,
        title = title,
        state = rememberDialogState(width = 420.dp, height = 220.dp),
        resizable = false,
    ) {
        IntUiTheme(isDark = isSystemInDarkTheme()) {
            DialogFrame(
                palette = palette,
                title = title,
                body = {
                    Text(
                        text = message,
                        fontSize = 12.sp,
                        color = palette.foreground,
                    )
                },
                actions = {
                    DialogTextButton(
                        text = stringResource(Res.string.action_close_menu),
                        palette = palette,
                        onClick = onDismiss,
                    )
                    DialogTextButton(
                        text = confirmLabel,
                        palette = palette,
                        emphasized = true,
                        destructive = true,
                        onClick = onConfirm,
                    )
                },
            )
        }
    }
}

@Composable
internal fun ConflictResolutionDialog(
    state: RootDialogState.ConflictResolution,
    palette: OnyxPalette,
    onResolve: (TransferConflictStrategy, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var applyToAll by remember(state.sourceName, state.currentIndex, state.total) { mutableStateOf(false) }
    val operationLabel = stringResource(
        if (state.operation == FileTransferOperation.COPY) {
            Res.string.label_operation_copy
        } else {
            Res.string.label_operation_move
        }
    )
    DialogWindow(
        onCloseRequest = onDismiss,
        title = stringResource(Res.string.label_conflict_resolution_title, state.currentIndex, state.total),
        state = rememberDialogState(width = 460.dp, height = 260.dp),
        resizable = false,
    ) {
        IntUiTheme(isDark = isSystemInDarkTheme()) {
            DialogFrame(
                palette = palette,
                title = stringResource(Res.string.label_conflict_resolution_title, state.currentIndex, state.total),
                body = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = operationLabel,
                            fontSize = 11.sp,
                            color = palette.mutedForeground,
                        )
                        Text(
                            text = stringResource(
                                Res.string.label_conflict_resolution_message,
                                state.sourceName,
                                state.targetLocation,
                            ),
                            fontSize = 12.sp,
                            color = palette.foreground,
                        )
                        ApplyToAllToggle(
                            checked = applyToAll,
                            text = stringResource(Res.string.message_apply_to_remaining_conflicts),
                            palette = palette,
                            onToggle = { applyToAll = !applyToAll },
                        )
                    }
                },
                actions = {
                    DialogTextButton(
                        text = stringResource(Res.string.action_close_menu),
                        palette = palette,
                        onClick = onDismiss,
                    )
                    DialogTextButton(
                        text = stringResource(Res.string.action_skip),
                        palette = palette,
                        onClick = { onResolve(TransferConflictStrategy.SKIP, applyToAll) },
                    )
                    DialogTextButton(
                        text = stringResource(Res.string.action_keep_both),
                        palette = palette,
                        onClick = { onResolve(TransferConflictStrategy.KEEP_BOTH, applyToAll) },
                    )
                    DialogTextButton(
                        text = stringResource(Res.string.action_overwrite),
                        palette = palette,
                        emphasized = true,
                        onClick = { onResolve(TransferConflictStrategy.OVERWRITE, applyToAll) },
                    )
                },
            )
        }
    }
}

@Composable
internal fun CreateDirectoriesDialog(
    state: RootDialogState.CreateDirectories,
    palette: OnyxPalette,
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
        color = palette.foreground,
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
                palette = palette,
                title = stringResource(Res.string.label_create_directories_title),
                body = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = state.location,
                            fontSize = 11.sp,
                            color = palette.mutedForeground,
                        )
                        Text(
                            text = stringResource(Res.string.label_create_directories_description),
                            fontSize = 12.sp,
                            color = palette.foreground,
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
                                cursorBrush = SolidColor(palette.accent),
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 170.dp, max = 220.dp)
                                            .background(palette.inputBackground, RoundedCornerShape(6.dp))
                                            .border(1.dp, palette.outlineVariant, RoundedCornerShape(6.dp))
                                            .padding(horizontal = 10.dp, vertical = 9.dp)
                                            .verticalScroll(inputScrollState),
                                    ) {
                                        if (draftFieldValue.text.isBlank()) {
                                            Text(
                                                text = stringResource(Res.string.label_create_directories_placeholder),
                                                fontSize = 12.sp,
                                                lineHeight = 17.sp,
                                                color = palette.disabledForeground,
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
                            color = palette.mutedForeground,
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
                        palette = palette,
                        onClick = onDismiss,
                    )
                    DialogTextButton(
                        text = stringResource(Res.string.action_new_directory),
                        palette = palette,
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
    palette: OnyxPalette,
    title: String,
    body: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.appBackground)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.foreground,
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
    palette: OnyxPalette,
    emphasized: Boolean = false,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val background = when {
        destructive && emphasized -> Color(0xFFD74E4E)
        emphasized -> palette.accent
        else -> palette.surfaceVariant
    }
    val contentColor = if (emphasized) Color.White else palette.foreground
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .background(background, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = contentColor,
        )
    }
}

@Composable
internal fun ApplyToAllToggle(
    checked: Boolean,
    text: String,
    palette: OnyxPalette,
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
                .border(1.dp, palette.outline, RoundedCornerShape(4.dp))
                .background(if (checked) palette.accent else Color.Transparent, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text(text = "✓", fontSize = 10.sp, color = Color.White)
            }
        }
        Text(
            text = text,
            fontSize = 12.sp,
            color = palette.foreground,
        )
    }
}

