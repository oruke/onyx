package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.component.RootDialogState
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
import onyx.composeapp.generated.resources.action_cleanup_invalid_locations
import onyx.composeapp.generated.resources.action_close_menu
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
import onyx.composeapp.generated.resources.label_list_font_size
import onyx.composeapp.generated.resources.label_list_row_height
import onyx.composeapp.generated.resources.label_locale_chinese
import onyx.composeapp.generated.resources.label_locale_english
import onyx.composeapp.generated.resources.label_locale_japanese
import onyx.composeapp.generated.resources.label_locale_system
import onyx.composeapp.generated.resources.label_location_history
import onyx.composeapp.generated.resources.label_mode_details
import onyx.composeapp.generated.resources.label_mode_gallery
import onyx.composeapp.generated.resources.label_setting_disabled
import onyx.composeapp.generated.resources.label_setting_enabled
import onyx.composeapp.generated.resources.label_setting_hide
import onyx.composeapp.generated.resources.label_setting_show
import onyx.composeapp.generated.resources.label_settings_appearance
import onyx.composeapp.generated.resources.label_settings_columns
import onyx.composeapp.generated.resources.label_settings_general
import onyx.composeapp.generated.resources.label_settings_layout
import onyx.composeapp.generated.resources.label_settings_shortcuts
import onyx.composeapp.generated.resources.label_settings_title
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

private enum class SettingsCategory { GENERAL, LAYOUT, APPEARANCE, COLUMNS, SHORTCUTS }

@Composable
internal fun SettingsDialog(
    state: RootDialogState.Settings,
    onDraftChange: (OnyxSettings) -> Unit,
    onCleanupInvalidLocations: () -> Unit,
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
