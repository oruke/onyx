package com.oruke.onyx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.core.model.AppLocale
import com.oruke.onyx.core.model.DeleteMode
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_cleanup_invalid_locations
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
import onyx.composeapp.generated.resources.label_sidebar_tree_visibility
import onyx.composeapp.generated.resources.label_sidebar_visibility
import onyx.composeapp.generated.resources.label_status_bar_visibility
import onyx.composeapp.generated.resources.label_ui_scale
import onyx.composeapp.generated.resources.label_verbose_logging
import onyx.composeapp.generated.resources.label_verbose_logging_hint
import onyx.composeapp.generated.resources.label_zebra_stripe
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/**
 * 分派当前设置分类内容。
 *
 * @param category 当前分类。
 * @param draft 当前设置草稿。
 * @param onDraftChange 草稿变化回调。
 * @param onCleanupInvalidLocations 清理无效位置回调。
 * @param bodyFontSize 正文字号。
 * @param labelFontSize 标签字号。
 */
@Composable
internal fun SettingsCategoryContent(
    category: SettingsCategory,
    draft: OnyxSettings,
    onDraftChange: (OnyxSettings) -> Unit,
    onCleanupInvalidLocations: () -> Unit,
    bodyFontSize: TextUnit,
    labelFontSize: TextUnit,
) {
    when (category) {
        SettingsCategory.GENERAL -> GeneralSettingsContent(
            draft,
            onDraftChange,
            onCleanupInvalidLocations,
            bodyFontSize,
            labelFontSize,
        )
        SettingsCategory.LAYOUT -> LayoutSettingsContent(draft, onDraftChange, labelFontSize)
        SettingsCategory.APPEARANCE -> AppearanceSettingsContent(
            draft,
            onDraftChange,
            bodyFontSize,
            labelFontSize,
        )
        SettingsCategory.COLUMNS -> ColumnsSettingsContent(draft, onDraftChange, labelFontSize)
        SettingsCategory.SHORTCUTS -> CommandShortcutSettings(
            settings = draft,
            onDraftChange = onDraftChange,
            fontSize = bodyFontSize,
            labelFontSize = labelFontSize,
        )
    }
}

/**
 * 绘制语言、删除策略、历史清理和日志设置。
 *
 * @param draft 当前设置草稿。
 * @param onDraftChange 草稿变化回调。
 * @param onCleanupInvalidLocations 清理无效位置回调。
 * @param bodyFontSize 正文字号。
 * @param labelFontSize 标签字号。
 */
@Composable
private fun GeneralSettingsContent(
    draft: OnyxSettings,
    onDraftChange: (OnyxSettings) -> Unit,
    onCleanupInvalidLocations: () -> Unit,
    bodyFontSize: TextUnit,
    labelFontSize: TextUnit,
) {
    SettingsSection(stringResource(Res.string.label_language), labelFontSize) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            localeOptions().forEach { (locale, label) ->
                SettingsOption(
                    selected = draft.preferredLocale == locale,
                    text = stringResource(label),
                    fontSize = labelFontSize,
                    onClick = { onDraftChange(draft.copy(preferredLocale = locale)) },
                )
            }
        }
    }
    SettingsSection(stringResource(Res.string.label_delete_mode), labelFontSize) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingsOption(
                selected = draft.deleteMode == DeleteMode.MOVE_TO_TRASH_PREFERRED,
                text = stringResource(Res.string.label_delete_mode_move_to_trash),
                fontSize = labelFontSize,
                onClick = { onDraftChange(draft.copy(deleteMode = DeleteMode.MOVE_TO_TRASH_PREFERRED)) },
            )
            SettingsOption(
                selected = draft.deleteMode == DeleteMode.PERMANENT,
                text = stringResource(Res.string.label_delete_mode_permanent),
                fontSize = labelFontSize,
                onClick = { onDraftChange(draft.copy(deleteMode = DeleteMode.PERMANENT)) },
            )
        }
    }
    SettingsSection(stringResource(Res.string.label_location_history), labelFontSize) {
        DialogTextButton(
            text = stringResource(Res.string.action_cleanup_invalid_locations),
            fontSize = bodyFontSize,
            onClick = onCleanupInvalidLocations,
        )
    }
    VerboseLoggingSettings(labelFontSize)
}

/**
 * 返回语言选项及其资源标签。
 *
 * @return 保持界面顺序的语言选项。
 */
private fun localeOptions(): List<Pair<AppLocale, StringResource>> = listOf(
    AppLocale.SYSTEM to Res.string.label_locale_system,
    AppLocale.ENGLISH to Res.string.label_locale_english,
    AppLocale.SIMPLIFIED_CHINESE to Res.string.label_locale_chinese,
    AppLocale.JAPANESE to Res.string.label_locale_japanese,
)

/**
 * 绘制仅在当前进程生效的详细日志开关。
 *
 * @param labelFontSize 标签字号。
 */
@Composable
private fun VerboseLoggingSettings(labelFontSize: TextUnit) {
    val palette = LocalOnyxPalette.current
    SettingsSection(stringResource(Res.string.label_verbose_logging), labelFontSize) {
        var verboseOn by remember { mutableStateOf(OnyxLogger.verboseEnabled) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsOption(
                    selected = verboseOn,
                    text = stringResource(Res.string.label_setting_enabled),
                    fontSize = labelFontSize,
                    onClick = {
                        OnyxLogger.verboseEnabled = true
                        verboseOn = true
                    },
                )
                SettingsOption(
                    selected = !verboseOn,
                    text = stringResource(Res.string.label_setting_disabled),
                    fontSize = labelFontSize,
                    onClick = {
                        OnyxLogger.verboseEnabled = false
                        verboseOn = false
                    },
                )
            }
            Text(
                text = stringResource(Res.string.label_verbose_logging_hint),
                fontSize = 10.sp,
                color = palette.mutedForeground,
            )
        }
    }
}

/**
 * 绘制默认布局、视图和可见性设置。
 *
 * @param draft 当前设置草稿。
 * @param onDraftChange 草稿变化回调。
 * @param labelFontSize 标签字号。
 */
@Composable
private fun LayoutSettingsContent(
    draft: OnyxSettings,
    onDraftChange: (OnyxSettings) -> Unit,
    labelFontSize: TextUnit,
) {
    SettingsSection(stringResource(Res.string.label_default_layout_mode), labelFontSize) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            layoutModeOptions().forEach { (mode, label) ->
                SettingsOption(
                    selected = draft.defaultLayoutMode == mode,
                    text = stringResource(label),
                    fontSize = labelFontSize,
                    onClick = { onDraftChange(draft.copy(defaultLayoutMode = mode)) },
                )
            }
        }
    }
    SettingsSection(stringResource(Res.string.label_default_view_mode), labelFontSize) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            viewModeOptions().forEach { (mode, label) ->
                SettingsOption(
                    selected = draft.defaultViewMode == mode,
                    text = stringResource(label),
                    fontSize = labelFontSize,
                    onClick = { onDraftChange(draft.copy(defaultViewMode = mode)) },
                )
            }
        }
    }
    BooleanSettingsSection(
        title = stringResource(Res.string.label_sidebar_visibility),
        value = draft.sidebarVisible,
        labelFontSize = labelFontSize,
        onValueChange = { value -> onDraftChange(draft.copy(sidebarVisible = value)) },
    )
    BooleanSettingsSection(
        title = stringResource(Res.string.label_sidebar_tree_visibility),
        value = draft.sidebarTreeVisible,
        labelFontSize = labelFontSize,
        onValueChange = { value -> onDraftChange(draft.copy(sidebarTreeVisible = value)) },
    )
    BooleanSettingsSection(
        title = stringResource(Res.string.label_status_bar_visibility),
        value = draft.statusBarVisible,
        labelFontSize = labelFontSize,
        onValueChange = { value -> onDraftChange(draft.copy(statusBarVisible = value)) },
    )
}

/** 返回布局模式选项。 */
private fun layoutModeOptions(): List<Pair<PaneLayoutMode, StringResource>> = listOf(
    PaneLayoutMode.SINGLE to Res.string.action_layout_single,
    PaneLayoutMode.DUAL_VERTICAL to Res.string.action_layout_dual_vertical,
    PaneLayoutMode.DUAL_HORIZONTAL to Res.string.action_layout_dual_horizontal,
)

/** 返回默认视图模式选项。 */
private fun viewModeOptions(): List<Pair<ViewMode, StringResource>> = listOf(
    ViewMode.DETAILS to Res.string.label_mode_details,
    ViewMode.GALLERY to Res.string.label_mode_gallery,
)

/**
 * 绘制显示/隐藏二选一设置。
 *
 * @param title 设置标题。
 * @param value 当前布尔值。
 * @param labelFontSize 标签字号。
 * @param onValueChange 值变化回调。
 */
@Composable
private fun BooleanSettingsSection(
    title: String,
    value: Boolean,
    labelFontSize: TextUnit,
    onValueChange: (Boolean) -> Unit,
) {
    SettingsSection(title, labelFontSize) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingsOption(
                selected = value,
                text = stringResource(Res.string.label_setting_show),
                fontSize = labelFontSize,
                onClick = { onValueChange(true) },
            )
            SettingsOption(
                selected = !value,
                text = stringResource(Res.string.label_setting_hide),
                fontSize = labelFontSize,
                onClick = { onValueChange(false) },
            )
        }
    }
}

/**
 * 绘制缩放、行高、字号和斑马纹设置。
 *
 * @param draft 当前设置草稿。
 * @param onDraftChange 草稿变化回调。
 * @param bodyFontSize 正文字号。
 * @param labelFontSize 标签字号。
 */
@Composable
private fun AppearanceSettingsContent(
    draft: OnyxSettings,
    onDraftChange: (OnyxSettings) -> Unit,
    bodyFontSize: TextUnit,
    labelFontSize: TextUnit,
) {
    SettingsSection(stringResource(Res.string.label_ui_scale), labelFontSize) {
        SliderRow(
            label = "${draft.uiScale}%",
            value = draft.uiScale,
            min = 75,
            max = 200,
            fontSize = bodyFontSize,
            onValueChange = { value -> onDraftChange(draft.copy(uiScale = value)) },
        )
    }
    SettingsSection(stringResource(Res.string.label_list_row_height), labelFontSize) {
        SliderRow(
            label = "${draft.listRowHeightDp}dp",
            value = draft.listRowHeightDp,
            min = 16,
            max = 40,
            fontSize = bodyFontSize,
            onValueChange = { value -> onDraftChange(draft.copy(listRowHeightDp = value)) },
        )
    }
    SettingsSection(stringResource(Res.string.label_list_font_size), labelFontSize) {
        SliderRow(
            label = "${draft.listFontSizeSp}sp",
            value = draft.listFontSizeSp,
            min = 10,
            max = 18,
            fontSize = bodyFontSize,
            onValueChange = { value -> onDraftChange(draft.copy(listFontSizeSp = value)) },
        )
    }
    BooleanSettingsSection(
        title = stringResource(Res.string.label_zebra_stripe),
        value = draft.zebraStripeEnabled,
        labelFontSize = labelFontSize,
        onValueChange = { value -> onDraftChange(draft.copy(zebraStripeEnabled = value)) },
    )
}

/**
 * 绘制详情列可见性设置，名称列始终可见。
 *
 * @param draft 当前设置草稿。
 * @param onDraftChange 草稿变化回调。
 * @param labelFontSize 标签字号。
 */
@Composable
private fun ColumnsSettingsContent(
    draft: OnyxSettings,
    onDraftChange: (OnyxSettings) -> Unit,
    labelFontSize: TextUnit,
) {
    columnOptions().forEach { (column, label) ->
        val isName = column == DetailsColumn.NAME
        val visible = column !in draft.hiddenDetailsColumns
        SettingsSection(stringResource(label), labelFontSize) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsOption(
                    selected = visible,
                    text = stringResource(Res.string.label_setting_show),
                    fontSize = labelFontSize,
                    onClick = {
                        if (!isName) {
                            onDraftChange(draft.copy(hiddenDetailsColumns = draft.hiddenDetailsColumns - column))
                        }
                    },
                )
                SettingsOption(
                    selected = !visible && !isName,
                    text = stringResource(Res.string.label_setting_hide),
                    fontSize = labelFontSize,
                    onClick = {
                        if (!isName) {
                            onDraftChange(draft.copy(hiddenDetailsColumns = draft.hiddenDetailsColumns + column))
                        }
                    },
                )
            }
        }
    }
}

/** 返回详情列及其标签。 */
private fun columnOptions(): List<Pair<DetailsColumn, StringResource>> = listOf(
    DetailsColumn.NAME to Res.string.label_column_name,
    DetailsColumn.TYPE to Res.string.label_column_type,
    DetailsColumn.SIZE to Res.string.label_column_size,
    DetailsColumn.MODIFIED to Res.string.label_column_modified,
)
