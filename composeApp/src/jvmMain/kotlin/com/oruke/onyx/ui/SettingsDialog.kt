package com.oruke.onyx.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

private enum class SettingsCategory { GENERAL, LAYOUT, APPEARANCE, COLUMNS }

@Composable
internal fun SettingsDialog(
    state: RootDialogState.Settings,
    onDraftChange: (OnyxSettings) -> Unit,
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

            Column(
                modifier = Modifier.fillMaxSize().background(palette.appBackground).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(title, fontSize = (bodyFs.value + 1).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = palette.foreground)

                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // ── 左侧分类导航 ──
                    Column(
                        modifier = Modifier.width(120.dp).fillMaxHeight().padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        SettingsCategory.entries.forEach { cat ->
                            SettingsNavItem(
                                text = when (cat) {
                                    SettingsCategory.GENERAL -> stringResource(Res.string.label_settings_general)
                                    SettingsCategory.LAYOUT -> stringResource(Res.string.label_settings_layout)
                                    SettingsCategory.APPEARANCE -> stringResource(Res.string.label_settings_appearance)
                                    SettingsCategory.COLUMNS -> stringResource(Res.string.label_settings_columns)
                                },
                                selected = category == cat,
                                accent = palette.accent,
                                foreground = palette.foreground,
                                fontSize = bodyFs,
                                onClick = { category = cat },
                            )
                        }
                    }

                    Divider(org.jetbrains.jewel.ui.Orientation.Vertical, modifier = Modifier.fillMaxHeight().width(1.dp))

                    // ── 右侧内容区 ──
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp)
                            .verticalScroll(rememberScrollState()),
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
