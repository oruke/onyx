package com.oruke.onyx.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.component.RemoteConnectionDialogError
import com.oruke.onyx.app.component.RemoteConnectionDraft
import com.oruke.onyx.app.component.RemoteConnectionTestState
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.core.model.AppLocale
import com.oruke.onyx.core.model.CommandShortcutOverride
import com.oruke.onyx.core.model.DeleteMode
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.core.model.RemoteConnectionSavePolicy
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.resolve
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_apply
import onyx.composeapp.generated.resources.action_close_menu
import onyx.composeapp.generated.resources.action_cleanup_invalid_locations
import onyx.composeapp.generated.resources.action_delete_connection
import onyx.composeapp.generated.resources.action_edit_connection
import onyx.composeapp.generated.resources.action_open
import onyx.composeapp.generated.resources.action_new_connection
import onyx.composeapp.generated.resources.action_save_connection
import onyx.composeapp.generated.resources.action_test_connection
import onyx.composeapp.generated.resources.action_layout_dual_horizontal
import onyx.composeapp.generated.resources.action_layout_dual_vertical
import onyx.composeapp.generated.resources.action_layout_single
import onyx.composeapp.generated.resources.label_column_modified
import onyx.composeapp.generated.resources.label_column_name
import onyx.composeapp.generated.resources.label_column_size
import onyx.composeapp.generated.resources.label_column_type
import onyx.composeapp.generated.resources.label_default_layout_mode
import onyx.composeapp.generated.resources.label_default_view_mode
import onyx.composeapp.generated.resources.label_delete_mode
import onyx.composeapp.generated.resources.label_delete_mode_move_to_trash
import onyx.composeapp.generated.resources.label_delete_mode_permanent
import onyx.composeapp.generated.resources.label_language
import onyx.composeapp.generated.resources.label_location_history
import onyx.composeapp.generated.resources.label_list_font_size
import onyx.composeapp.generated.resources.label_list_row_height
import onyx.composeapp.generated.resources.label_locale_chinese
import onyx.composeapp.generated.resources.label_locale_english
import onyx.composeapp.generated.resources.label_locale_japanese
import onyx.composeapp.generated.resources.label_locale_system
import onyx.composeapp.generated.resources.label_mode_details
import onyx.composeapp.generated.resources.label_mode_gallery
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
import onyx.composeapp.generated.resources.label_remote_protocol_s3
import onyx.composeapp.generated.resources.label_remote_protocol_smb
import onyx.composeapp.generated.resources.label_remote_protocol_webdav
import onyx.composeapp.generated.resources.label_remote_protocol_webdavs
import onyx.composeapp.generated.resources.label_setting_disabled
import onyx.composeapp.generated.resources.label_setting_enabled
import onyx.composeapp.generated.resources.label_setting_hide
import onyx.composeapp.generated.resources.label_setting_show
import onyx.composeapp.generated.resources.label_settings_appearance
import onyx.composeapp.generated.resources.label_settings_columns
import onyx.composeapp.generated.resources.label_settings_connections
import onyx.composeapp.generated.resources.label_settings_general
import onyx.composeapp.generated.resources.label_settings_shortcuts
import onyx.composeapp.generated.resources.label_settings_layout
import onyx.composeapp.generated.resources.label_settings_title
import onyx.composeapp.generated.resources.label_shortcut_capture
import onyx.composeapp.generated.resources.label_shortcut_disabled
import onyx.composeapp.generated.resources.label_shortcut_recording
import onyx.composeapp.generated.resources.label_shortcut_reset_default
import onyx.composeapp.generated.resources.label_remote_credentials_save_do_not_save
import onyx.composeapp.generated.resources.label_remote_credentials_save_policy
import onyx.composeapp.generated.resources.label_remote_credentials_save_session
import onyx.composeapp.generated.resources.label_remote_credentials_save_system_keyring
import onyx.composeapp.generated.resources.label_remote_credentials_system_keyring_unavailable
import onyx.composeapp.generated.resources.label_sidebar_tree_visibility
import onyx.composeapp.generated.resources.label_sidebar_visibility
import onyx.composeapp.generated.resources.label_status_bar_visibility
import onyx.composeapp.generated.resources.label_ui_scale
import onyx.composeapp.generated.resources.label_verbose_logging
import onyx.composeapp.generated.resources.label_verbose_logging_hint
import onyx.composeapp.generated.resources.label_zebra_stripe
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Text

private enum class SettingsCategory { GENERAL, CONNECTIONS, LAYOUT, APPEARANCE, COLUMNS, SHORTCUTS }

@Composable
internal fun SettingsDialog(
    state: RootDialogState.Settings,
    onDraftChange: (OnyxSettings) -> Unit,
    onCleanupInvalidLocations: () -> Unit,
    onRemoteConnectionDraftChange: (RemoteConnectionDraft) -> Unit,
    onNewRemoteConnection: () -> Unit,
    onEditRemoteConnection: (RemoteConnectionProfile) -> Unit,
    onSaveRemoteConnection: () -> Unit,
    onTestRemoteConnection: () -> Unit,
    onDeleteRemoteConnection: (String) -> Unit,
    onOpenRemoteConnection: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    initialWidth: Int = 720,
    initialHeight: Int = 520,
    onWindowSizeChanged: ((width: Int, height: Int) -> Unit)? = null,
) {
    val draft = state.draft
    val title = stringResource(Res.string.label_settings_title)
    var category by remember { mutableStateOf(SettingsCategory.GENERAL) }

    val dialogState = rememberDialogState(width = initialWidth.dp, height = initialHeight.dp)

    LaunchedEffect(dialogState) {
        snapshotFlow { dialogState.size }
            .collect { size ->
                val w = size.width; val h = size.height
                if (w != Dp.Unspecified && h != Dp.Unspecified) {
                    onWindowSizeChanged?.invoke(w.value.toInt(), h.value.toInt())
                }
            }
    }

    DialogWindow(
        onCloseRequest = onDismiss,
        title = title,
        state = dialogState,
        resizable = true,
    ) {
        window.minimumSize = java.awt.Dimension(600, 420)
        IntUiTheme(isDark = isSystemInDarkTheme()) {
            val palette = LocalOnyxPalette.current
            val appearance = LocalOnyxAppearance.current
            val bodyFs = appearance.listFontSize
            val labelFs = appearance.headerFontSize
            val navScrollState = rememberScrollState()
            val contentScrollState = rememberScrollState()

            Column(
                modifier = Modifier.fillMaxSize().background(palette.appBackground).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(title, fontSize = (bodyFs.value + 1).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = palette.foreground)

                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // ── 左侧分类导航 ──
                    Column(
                        modifier = Modifier
                            .width(138.dp)
                            .fillMaxHeight()
                            .background(palette.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                            .border(1.dp, palette.outlineVariant, RoundedCornerShape(6.dp))
                            .padding(6.dp)
                            .verticalScroll(navScrollState),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        SettingsCategory.entries.forEach { cat ->
                            SettingsNavItem(
                                text = when (cat) {
                                    SettingsCategory.GENERAL -> stringResource(Res.string.label_settings_general)
                                    SettingsCategory.CONNECTIONS -> stringResource(Res.string.label_settings_connections)
                                    SettingsCategory.LAYOUT -> stringResource(Res.string.label_settings_layout)
                                    SettingsCategory.APPEARANCE -> stringResource(Res.string.label_settings_appearance)
                                    SettingsCategory.COLUMNS -> stringResource(Res.string.label_settings_columns)
                                    SettingsCategory.SHORTCUTS -> stringResource(Res.string.label_settings_shortcuts)
                                },
                                selected = category == cat,
                                accent = palette.accent,
                                foreground = palette.foreground,
                                fontSize = bodyFs,
                                onClick = { category = cat },
                            )
                        }
                    }

                    // ── 右侧内容区 ──
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(palette.surface, RoundedCornerShape(6.dp))
                            .border(1.dp, palette.outlineVariant, RoundedCornerShape(6.dp))
                            .padding(12.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(contentScrollState),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            when (category) {
                            SettingsCategory.GENERAL -> {
                                SettingsSection(stringResource(Res.string.label_language), labelFs) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf(
                                            AppLocale.SYSTEM to Res.string.label_locale_system,
                                            AppLocale.ENGLISH to Res.string.label_locale_english,
                                            AppLocale.SIMPLIFIED_CHINESE to Res.string.label_locale_chinese,
                                            AppLocale.JAPANESE to Res.string.label_locale_japanese,
                                        ).forEach { (locale, res) ->
                                            SettingsOption(
                                                selected = draft.preferredLocale == locale,
                                                text = stringResource(res),
                                                fontSize = labelFs,
                                                onClick = { onDraftChange(draft.copy(preferredLocale = locale)) },
                                            )
                                        }
                                    }
                                }
                                SettingsSection(stringResource(Res.string.label_delete_mode), labelFs) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        SettingsOption(draft.deleteMode == DeleteMode.MOVE_TO_TRASH_PREFERRED, stringResource(Res.string.label_delete_mode_move_to_trash), labelFs) { onDraftChange(draft.copy(deleteMode = DeleteMode.MOVE_TO_TRASH_PREFERRED)) }
                                        SettingsOption(draft.deleteMode == DeleteMode.PERMANENT, stringResource(Res.string.label_delete_mode_permanent), labelFs) { onDraftChange(draft.copy(deleteMode = DeleteMode.PERMANENT)) }
                                    }
                                }
                                SettingsSection(stringResource(Res.string.label_location_history), labelFs) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        DialogTextButton(
                                            text = stringResource(Res.string.action_cleanup_invalid_locations),
                                            fontSize = bodyFs,
                                            onClick = onCleanupInvalidLocations,
                                        )
                                    }
                                }

                                // ── 详细日志（运行时开关，不持久化） ──
                                SettingsSection(stringResource(Res.string.label_verbose_logging), labelFs) {
                                    var verboseOn by remember { mutableStateOf(OnyxLogger.verboseEnabled) }
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            SettingsOption(verboseOn, stringResource(Res.string.label_setting_enabled), labelFs) {
                                                OnyxLogger.verboseEnabled = true
                                                verboseOn = true
                                            }
                                            SettingsOption(!verboseOn, stringResource(Res.string.label_setting_disabled), labelFs) {
                                                OnyxLogger.verboseEnabled = false
                                                verboseOn = false
                                            }
                                        }
                                        Text(
                                            text = stringResource(Res.string.label_verbose_logging_hint),
                                            fontSize = 10.sp,
                                            color = palette.mutedForeground,
                                        )
                                    }
                                }
                            }

                            SettingsCategory.CONNECTIONS -> {
                                RemoteConnectionsSettings(
                                    connections = draft.remoteConnections,
                                    connectionDraft = state.remoteConnectionDraft,
                                    editingConnectionId = state.editingRemoteConnectionId,
                                    testState = state.remoteConnectionTestState,
                                    error = state.remoteConnectionError,
                                    onDraftChange = onRemoteConnectionDraftChange,
                                    onNew = onNewRemoteConnection,
                                    onEdit = onEditRemoteConnection,
                                    onSave = onSaveRemoteConnection,
                                    onTest = onTestRemoteConnection,
                                    onDelete = onDeleteRemoteConnection,
                                    onOpen = onOpenRemoteConnection,
                                    fontSize = bodyFs,
                                    labelFontSize = labelFs,
                                )
                            }

                            SettingsCategory.LAYOUT -> {
                                SettingsSection(stringResource(Res.string.label_default_layout_mode), labelFs) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        SettingsOption(draft.defaultLayoutMode == PaneLayoutMode.SINGLE, stringResource(Res.string.action_layout_single), labelFs) { onDraftChange(draft.copy(defaultLayoutMode = PaneLayoutMode.SINGLE)) }
                                        SettingsOption(draft.defaultLayoutMode == PaneLayoutMode.DUAL_VERTICAL, stringResource(Res.string.action_layout_dual_vertical), labelFs) { onDraftChange(draft.copy(defaultLayoutMode = PaneLayoutMode.DUAL_VERTICAL)) }
                                        SettingsOption(draft.defaultLayoutMode == PaneLayoutMode.DUAL_HORIZONTAL, stringResource(Res.string.action_layout_dual_horizontal), labelFs) { onDraftChange(draft.copy(defaultLayoutMode = PaneLayoutMode.DUAL_HORIZONTAL)) }
                                    }
                                }
                                SettingsSection(stringResource(Res.string.label_default_view_mode), labelFs) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        SettingsOption(draft.defaultViewMode == ViewMode.DETAILS, stringResource(Res.string.label_mode_details), labelFs) { onDraftChange(draft.copy(defaultViewMode = ViewMode.DETAILS)) }
                                        SettingsOption(draft.defaultViewMode == ViewMode.GALLERY, stringResource(Res.string.label_mode_gallery), labelFs) { onDraftChange(draft.copy(defaultViewMode = ViewMode.GALLERY)) }
                                    }
                                }
                                SettingsSection(stringResource(Res.string.label_sidebar_visibility), labelFs) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        SettingsOption(draft.sidebarVisible, stringResource(Res.string.label_setting_show), labelFs) { onDraftChange(draft.copy(sidebarVisible = true)) }
                                        SettingsOption(!draft.sidebarVisible, stringResource(Res.string.label_setting_hide), labelFs) { onDraftChange(draft.copy(sidebarVisible = false)) }
                                    }
                                }
                                SettingsSection(stringResource(Res.string.label_sidebar_tree_visibility), labelFs) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        SettingsOption(draft.sidebarTreeVisible, stringResource(Res.string.label_setting_show), labelFs) { onDraftChange(draft.copy(sidebarTreeVisible = true)) }
                                        SettingsOption(!draft.sidebarTreeVisible, stringResource(Res.string.label_setting_hide), labelFs) { onDraftChange(draft.copy(sidebarTreeVisible = false)) }
                                    }
                                }
                                SettingsSection(stringResource(Res.string.label_status_bar_visibility), labelFs) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        SettingsOption(draft.statusBarVisible, stringResource(Res.string.label_setting_show), labelFs) { onDraftChange(draft.copy(statusBarVisible = true)) }
                                        SettingsOption(!draft.statusBarVisible, stringResource(Res.string.label_setting_hide), labelFs) { onDraftChange(draft.copy(statusBarVisible = false)) }
                                    }
                                }
                            }

                            SettingsCategory.APPEARANCE -> {
                                SettingsSection(stringResource(Res.string.label_ui_scale), labelFs) {
                                    SliderRow("${draft.uiScale}%", draft.uiScale, 75, 200, bodyFs) { onDraftChange(draft.copy(uiScale = it)) }
                                }
                                SettingsSection(stringResource(Res.string.label_list_row_height), labelFs) {
                                    SliderRow("${draft.listRowHeightDp}dp", draft.listRowHeightDp, 16, 40, bodyFs) { onDraftChange(draft.copy(listRowHeightDp = it)) }
                                }
                                SettingsSection(stringResource(Res.string.label_list_font_size), labelFs) {
                                    SliderRow("${draft.listFontSizeSp}sp", draft.listFontSizeSp, 10, 18, bodyFs) { onDraftChange(draft.copy(listFontSizeSp = it)) }
                                }
                                SettingsSection(stringResource(Res.string.label_zebra_stripe), labelFs) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        SettingsOption(draft.zebraStripeEnabled, stringResource(Res.string.label_setting_show), labelFs) { onDraftChange(draft.copy(zebraStripeEnabled = true)) }
                                        SettingsOption(!draft.zebraStripeEnabled, stringResource(Res.string.label_setting_hide), labelFs) { onDraftChange(draft.copy(zebraStripeEnabled = false)) }
                                    }
                                }
                            }

                            SettingsCategory.COLUMNS -> {
                                val columnEntries = listOf(
                                    DetailsColumn.NAME to Res.string.label_column_name,
                                    DetailsColumn.TYPE to Res.string.label_column_type,
                                    DetailsColumn.SIZE to Res.string.label_column_size,
                                    DetailsColumn.MODIFIED to Res.string.label_column_modified,
                                )
                                columnEntries.forEach { (col, res) ->
                                    val isName = col == DetailsColumn.NAME
                                    val visible = !draft.hiddenDetailsColumns.contains(col)
                                    SettingsSection(stringResource(res), labelFs) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            SettingsOption(visible, stringResource(Res.string.label_setting_show), labelFs) {
                                                if (!isName) onDraftChange(draft.copy(hiddenDetailsColumns = draft.hiddenDetailsColumns - col))
                                            }
                                            SettingsOption(!visible && !isName, stringResource(Res.string.label_setting_hide), labelFs) {
                                                if (!isName) onDraftChange(draft.copy(hiddenDetailsColumns = draft.hiddenDetailsColumns + col))
                                            }
                                        }
                                    }
                                }
                            }

                            SettingsCategory.SHORTCUTS -> {
                                CommandShortcutSettings(
                                    settings = draft,
                                    onDraftChange = onDraftChange,
                                    fontSize = bodyFs,
                                    labelFontSize = labelFs,
                                )
                            }
                        }
                    }
                }
                }

                // ── 底部按钮 ──
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    DialogTextButton(stringResource(Res.string.action_close_menu), fontSize = bodyFs, onClick = onDismiss)
                    DialogTextButton(stringResource(Res.string.action_apply), emphasized = true, fontSize = bodyFs, onClick = onConfirm)
                }
            }
        }
    }
}

@Composable
private fun CommandShortcutSettings(
    settings: OnyxSettings,
    onDraftChange: (OnyxSettings) -> Unit,
    fontSize: TextUnit,
    labelFontSize: TextUnit,
) {
    var recordingCommand by remember { mutableStateOf<OnyxCommand?>(null) }
    val focusRequester = remember { FocusRequester() }
    val shortcutMap = remember(settings.commandShortcutOverrides) {
        commandShortcutMapFromSettings(settings.commandShortcutOverrides)
    }

    LaunchedEffect(recordingCommand) {
        if (recordingCommand != null) {
            focusRequester.requestFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                val command = recordingCommand ?: return@onPreviewKeyEvent false
                if (event.key == Key.Escape) {
                    recordingCommand = null
                    true
                } else {
                    val shortcut = event.toOnyxShortcutOrNull() ?: return@onPreviewKeyEvent false
                    onDraftChange(settings.withCommandShortcut(command, shortcut))
                    recordingCommand = null
                    true
                }
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OnyxCommandRegistry.paneCommands.forEach { spec ->
            CommandShortcutRow(
                spec = spec,
                shortcut = shortcutMap.shortcutFor(spec.command),
                recording = recordingCommand == spec.command,
                fontSize = fontSize,
                labelFontSize = labelFontSize,
                onCapture = { recordingCommand = spec.command },
                onReset = {
                    recordingCommand = null
                    onDraftChange(settings.resetCommandShortcut(spec.command))
                },
                onDisable = {
                    recordingCommand = null
                    onDraftChange(settings.withCommandShortcut(spec.command, shortcut = null))
                },
            )
        }
    }
}

@Composable
private fun CommandShortcutRow(
    spec: OnyxCommandSpec,
    shortcut: OnyxShortcut?,
    recording: Boolean,
    fontSize: TextUnit,
    labelFontSize: TextUnit,
    onCapture: () -> Unit,
    onReset: () -> Unit,
    onDisable: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(spec.label),
            fontSize = labelFontSize,
            color = palette.foreground,
            modifier = Modifier.width(150.dp),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(
            text = if (recording) {
                stringResource(Res.string.label_shortcut_recording)
            } else {
                onyxShortcutHint(shortcut) ?: stringResource(Res.string.label_shortcut_disabled)
            },
            fontSize = fontSize,
            color = if (shortcut == null && !recording) palette.disabledForeground else palette.mutedForeground,
            modifier = Modifier.width(112.dp),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
        ) {
            DialogTextButton(
                text = stringResource(Res.string.label_shortcut_capture),
                fontSize = 10.sp,
                focused = recording,
                onClick = onCapture,
            )
            DialogTextButton(
                text = stringResource(Res.string.label_shortcut_reset_default),
                fontSize = 10.sp,
                onClick = onReset,
            )
            DialogTextButton(
                text = stringResource(Res.string.label_shortcut_disabled),
                fontSize = 10.sp,
                onClick = onDisable,
            )
        }
    }
}

private fun OnyxSettings.withCommandShortcut(
    command: OnyxCommand,
    shortcut: OnyxShortcut?,
): OnyxSettings {
    val currentMap = commandShortcutMapFromSettings(commandShortcutOverrides)
    val overrides = commandShortcutOverrides
        .filterNot { override -> override.command == command.name }
        .toMutableList()

    if (shortcut != null) {
        OnyxCommandRegistry.paneCommands
            .map { spec -> spec.command }
            .filter { existingCommand -> existingCommand != command && currentMap.shortcutFor(existingCommand) == shortcut }
            .forEach { conflictingCommand ->
                overrides.removeAll { override -> override.command == conflictingCommand.name }
                overrides += conflictingCommand.toShortcutOverride(shortcut = null)
            }
    }

    if (shortcut != command.defaultShortcut) {
        overrides += command.toShortcutOverride(shortcut)
    }

    return copy(commandShortcutOverrides = overrides.normalizedCommandShortcutOverrides())
}

private fun OnyxSettings.resetCommandShortcut(command: OnyxCommand): OnyxSettings {
    return copy(
        commandShortcutOverrides = commandShortcutOverrides
            .filterNot { override -> override.command == command.name }
            .normalizedCommandShortcutOverrides(),
    )
}

private fun List<CommandShortcutOverride>.normalizedCommandShortcutOverrides(): List<CommandShortcutOverride> {
    return distinctBy { override -> override.command }.sortedBy { override -> override.command }
}

@Composable
private fun RemoteConnectionsSettings(
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
private fun SettingsTextField(
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

@Composable
private fun SettingsNavItem(
    text: String, selected: Boolean, accent: Color, foreground: Color,
    fontSize: TextUnit, onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg by animateColorAsState(when { selected -> accent.copy(alpha = 0.14f); hovered -> accent.copy(alpha = 0.07f); else -> Color.Transparent }, tween(120))
    val tc by animateColorAsState(when { selected -> accent; hovered -> accent.copy(alpha = 0.8f); else -> foreground }, tween(120))
    Box(
        modifier = Modifier.fillMaxWidth().hoverable(src)
            .background(bg, RoundedCornerShape(5.dp))
            .clickable(src, null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) { Text(text, fontSize = fontSize, color = tc, fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal) }
}

@Composable
private fun SettingsSection(title: String, fontSize: TextUnit = 11.sp, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = fontSize, color = LocalOnyxPalette.current.foreground, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        content()
    }
}

@Composable
private fun SettingsOption(selected: Boolean, text: String, fontSize: TextUnit = 11.sp, onClick: () -> Unit) {
    val palette = LocalOnyxPalette.current
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg by animateColorAsState(when { selected -> palette.accent.copy(alpha = 0.14f); hovered -> palette.accent.copy(alpha = 0.07f); else -> palette.surface }, tween(120))
    val border by animateColorAsState(when { selected -> palette.accent; hovered -> palette.accent.copy(alpha = 0.4f); else -> palette.outlineVariant }, tween(120))
    val tc by animateColorAsState(when { selected -> palette.accent; hovered -> palette.accent.copy(alpha = 0.85f); else -> palette.foreground }, tween(120))
    Box(
        modifier = Modifier.hoverable(src).background(bg, RoundedCornerShape(4.dp))
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .clickable(src, null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = tc, fontSize = fontSize) }
}

@Composable
private fun SliderRow(label: String, value: Int, min: Int, max: Int, fontSize: TextUnit, onValueChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, fontSize = fontSize, color = LocalOnyxPalette.current.foreground, modifier = Modifier.width(52.dp))
        SettingsScaleSlider(value, min, max, onValueChange)
    }
}

@Composable
private fun SettingsScaleSlider(value: Int, min: Int, max: Int, onValueChange: (Int) -> Unit) {
    val clampedValue = value.coerceIn(min, max)
    val fraction = ((clampedValue - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    val sliderWidthDp = 240
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sliderWidthPx = with(density) { sliderWidthDp.dp.toPx() }
    Box(
        modifier = Modifier.width(sliderWidthDp.dp).height(16.dp)
            .pointerInput(Unit) { detectDragGestures { change, _ -> val x = change.position.x.coerceIn(0f, sliderWidthPx); onValueChange((min + ((x / sliderWidthPx) * (max - min)).toInt()).coerceIn(min, max)) } }
            .pointerInput(Unit) { detectTapGestures { offset -> onValueChange((min + ((offset.x / sliderWidthPx).coerceIn(0f, 1f) * (max - min)).toInt()).coerceIn(min, max)) } },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(LocalOnyxPalette.current.outlineVariant, RoundedCornerShape(1.dp)))
        Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(LocalOnyxPalette.current.accent, RoundedCornerShape(1.dp)))
        Box(Modifier.offset(x = (fraction * (sliderWidthDp - 8)).dp).size(8.dp).background(LocalOnyxPalette.current.accent, RoundedCornerShape(4.dp)))
    }
}
