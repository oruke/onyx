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
import com.oruke.onyx.core.model.OnyxThemeMode
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_cleanup_invalid_locations
import onyx.composeapp.generated.resources.action_layout_dual_horizontal
import onyx.composeapp.generated.resources.action_layout_dual_vertical
import onyx.composeapp.generated.resources.action_layout_single
import onyx.composeapp.generated.resources.action_restore_factory_defaults
import onyx.composeapp.generated.resources.action_save_custom_theme
import onyx.composeapp.generated.resources.label_auto_close_task_center
import onyx.composeapp.generated.resources.label_auto_show_task_center
import onyx.composeapp.generated.resources.label_close_to_tray
import onyx.composeapp.generated.resources.label_close_to_tray_exit
import onyx.composeapp.generated.resources.label_close_to_tray_minimize
import onyx.composeapp.generated.resources.label_column_modified
import onyx.composeapp.generated.resources.label_column_name
import onyx.composeapp.generated.resources.label_column_size
import onyx.composeapp.generated.resources.label_column_type
import onyx.composeapp.generated.resources.label_confirm_before_delete
import onyx.composeapp.generated.resources.label_custom_themes_section
import onyx.composeapp.generated.resources.label_default_layout_mode
import onyx.composeapp.generated.resources.label_default_show_hidden
import onyx.composeapp.generated.resources.label_default_view_mode
import onyx.composeapp.generated.resources.label_delete_mode
import onyx.composeapp.generated.resources.label_delete_mode_move_to_trash
import onyx.composeapp.generated.resources.label_delete_mode_permanent
import onyx.composeapp.generated.resources.label_directories_first
import onyx.composeapp.generated.resources.label_double_click_blank_up
import onyx.composeapp.generated.resources.label_file_icon_style
import onyx.composeapp.generated.resources.label_file_icon_system
import onyx.composeapp.generated.resources.label_file_icon_vector
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
import onyx.composeapp.generated.resources.label_natural_sort
import onyx.composeapp.generated.resources.label_open_item_click_mode
import onyx.composeapp.generated.resources.label_open_item_double_click
import onyx.composeapp.generated.resources.label_open_item_single_click
import onyx.composeapp.generated.resources.label_restore_session_startup
import onyx.composeapp.generated.resources.label_section_browsing_sorting
import onyx.composeapp.generated.resources.label_section_delete_safety
import onyx.composeapp.generated.resources.label_section_density_typography
import onyx.composeapp.generated.resources.label_section_details_columns
import onyx.composeapp.generated.resources.label_section_diagnostics_maintenance
import onyx.composeapp.generated.resources.label_section_file_list_colors
import onyx.composeapp.generated.resources.label_section_global_colors
import onyx.composeapp.generated.resources.label_section_language_startup
import onyx.composeapp.generated.resources.label_section_mouse_navigation
import onyx.composeapp.generated.resources.label_section_task_feedback
import onyx.composeapp.generated.resources.label_section_theme_mode_preset
import onyx.composeapp.generated.resources.label_section_workspace_layout
import onyx.composeapp.generated.resources.label_setting_disabled
import onyx.composeapp.generated.resources.label_setting_enabled
import onyx.composeapp.generated.resources.label_setting_hide
import onyx.composeapp.generated.resources.label_setting_show
import onyx.composeapp.generated.resources.label_settings_cat_automation
import onyx.composeapp.generated.resources.label_settings_cat_general
import onyx.composeapp.generated.resources.label_settings_cat_operations
import onyx.composeapp.generated.resources.label_settings_cat_shortcuts
import onyx.composeapp.generated.resources.label_settings_cat_theme
import onyx.composeapp.generated.resources.label_settings_cat_views
import onyx.composeapp.generated.resources.label_settings_file_labels
import onyx.composeapp.generated.resources.label_settings_shortcuts
import onyx.composeapp.generated.resources.label_settings_user_commands
import onyx.composeapp.generated.resources.label_show_file_extensions
import onyx.composeapp.generated.resources.label_sidebar_tree_visibility
import onyx.composeapp.generated.resources.label_sidebar_visibility
import onyx.composeapp.generated.resources.label_status_bar_visibility
import onyx.composeapp.generated.resources.label_theme_accent_swatch
import onyx.composeapp.generated.resources.label_theme_mode
import onyx.composeapp.generated.resources.label_theme_mode_dark
import onyx.composeapp.generated.resources.label_theme_mode_light
import onyx.composeapp.generated.resources.label_theme_mode_system
import onyx.composeapp.generated.resources.label_theme_preset
import onyx.composeapp.generated.resources.label_ui_scale
import onyx.composeapp.generated.resources.label_verbose_logging
import onyx.composeapp.generated.resources.label_verbose_logging_hint
import onyx.composeapp.generated.resources.label_zebra_stripe
import onyx.composeapp.generated.resources.search_empty_result
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/**
 * 分派当前设置分类内容；当搜索框非空时跨分类筛选并展示匹配的设置分组卡片。
 */
@Composable
internal fun SettingsCategoryContent(
    category: SettingsCategory,
    draft: OnyxSettings,
    onDraftChange: (OnyxSettings) -> Unit,
    onCleanupInvalidLocations: () -> Unit,
    bodyFontSize: TextUnit,
    labelFontSize: TextUnit,
    searchQuery: String = "",
) {
    val trimmedQuery = searchQuery.trim()
    if (trimmedQuery.isEmpty()) {
        RenderCategoryPage(
            category = category,
            draft = draft,
            onDraftChange = onDraftChange,
            onCleanupInvalidLocations = onCleanupInvalidLocations,
            bodyFontSize = bodyFontSize,
            labelFontSize = labelFontSize,
        )
        return
    }

    val matchingCategories = SettingsCategory.entries.filter { candidate ->
        categoryMatchesQuery(candidate, trimmedQuery)
    }
    if (matchingCategories.isEmpty()) {
        Text(
            text = stringResource(Res.string.search_empty_result),
            fontSize = bodyFontSize,
            color = LocalOnyxPalette.current.mutedForeground,
        )
        return
    }
    matchingCategories.forEach { matchedCategory ->
        RenderCategoryPage(
            category = matchedCategory,
            draft = draft,
            onDraftChange = onDraftChange,
            onCleanupInvalidLocations = onCleanupInvalidLocations,
            bodyFontSize = bodyFontSize,
            labelFontSize = labelFontSize,
        )
    }
}

/**
 * 渲染单个分类页的全部分组卡片。
 */
@Composable
private fun RenderCategoryPage(
    category: SettingsCategory,
    draft: OnyxSettings,
    onDraftChange: (OnyxSettings) -> Unit,
    onCleanupInvalidLocations: () -> Unit,
    bodyFontSize: TextUnit,
    labelFontSize: TextUnit,
) {
    when (category) {
        SettingsCategory.GENERAL -> GeneralSettingsContent(
            draft = draft,
            onDraftChange = onDraftChange,
            labelFontSize = labelFontSize,
        )

        SettingsCategory.THEME_APPEARANCE -> ThemeAppearanceSettingsContent(
            draft = draft,
            onDraftChange = onDraftChange,
            bodyFontSize = bodyFontSize,
            labelFontSize = labelFontSize,
        )

        SettingsCategory.VIEWS_BROWSING -> ViewsBrowsingSettingsContent(
            draft = draft,
            onDraftChange = onDraftChange,
            labelFontSize = labelFontSize,
        )

        SettingsCategory.OPERATIONS_SAFETY -> OperationsSafetySettingsContent(
            draft = draft,
            onDraftChange = onDraftChange,
            onCleanupInvalidLocations = onCleanupInvalidLocations,
            bodyFontSize = bodyFontSize,
            labelFontSize = labelFontSize,
        )

        SettingsCategory.AUTOMATION -> AutomationSettingsContent(
            draft = draft,
            onDraftChange = onDraftChange,
            labelFontSize = labelFontSize,
        )

        SettingsCategory.SHORTCUTS_ADVANCED -> ShortcutsAdvancedSettingsContent(
            draft = draft,
            onDraftChange = onDraftChange,
            bodyFontSize = bodyFontSize,
            labelFontSize = labelFontSize,
        )
    }
}

/**
 * 绘制常规与启动设置卡片（界面语言、关闭窗口最小化到托盘、启动时恢复会话）。
 */
@Composable
private fun GeneralSettingsContent(
    draft: OnyxSettings,
    onDraftChange: (OnyxSettings) -> Unit,
    labelFontSize: TextUnit,
) {
    val enabledText = stringResource(Res.string.label_setting_enabled)
    val disabledText = stringResource(Res.string.label_setting_disabled)
    SettingsGroupCard(
        title = stringResource(Res.string.label_section_language_startup),
        fontSize = labelFontSize,
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
        SettingsSection(stringResource(Res.string.label_close_to_tray), labelFontSize) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsOption(
                    selected = draft.closeToTray,
                    text = stringResource(Res.string.label_close_to_tray_minimize),
                    fontSize = labelFontSize,
                    onClick = { onDraftChange(draft.copy(closeToTray = true)) },
                )
                SettingsOption(
                    selected = !draft.closeToTray,
                    text = stringResource(Res.string.label_close_to_tray_exit),
                    fontSize = labelFontSize,
                    onClick = { onDraftChange(draft.copy(closeToTray = false)) },
                )
            }
        }
        SettingsToggleRow(
            title = stringResource(Res.string.label_restore_session_startup),
            value = draft.restoreSessionOnStartup,
            enabledText = enabledText,
            disabledText = disabledText,
            labelFontSize = labelFontSize,
            onValueChange = { value -> onDraftChange(draft.copy(restoreSessionOnStartup = value)) },
        )
    }
}

/**
 * 绘制主题与外观分类：
 * 1. 明暗模式、6 套精选预设、保存/切换自定义命名主题、快捷强调色盘
 * 2. 全局界面颜色两列可视化取色器（支持 Alpha 透明度）
 * 3. 文件列表颜色两列可视化取色器（支持 Alpha 透明度）
 * 4. 界面缩放、列表行高、字号与文件图标样式
 */
@Composable
private fun ThemeAppearanceSettingsContent(
    draft: OnyxSettings,
    onDraftChange: (OnyxSettings) -> Unit,
    bodyFontSize: TextUnit,
    labelFontSize: TextUnit,
) {
    SettingsGroupCard(
        title = stringResource(Res.string.label_section_theme_mode_preset),
        fontSize = labelFontSize,
    ) {
        SettingsSection(stringResource(Res.string.label_theme_mode), labelFontSize) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                themeModeOptions().forEach { (mode, label) ->
                    SettingsOption(
                        selected = draft.themeMode == mode,
                        text = stringResource(label),
                        fontSize = labelFontSize,
                        onClick = { onDraftChange(draft.copy(themeMode = mode)) },
                    )
                }
            }
        }
        SettingsSection(stringResource(Res.string.label_theme_preset), labelFontSize) {
            ThemePresetPickerGrid(draft = draft, onChange = onDraftChange)
        }
        CustomThemeManagerSection(draft = draft, onChange = onDraftChange)
        SettingsSection(stringResource(Res.string.label_theme_accent_swatch), labelFontSize) {
            AccentSwatchRow(draft = draft, onChange = onDraftChange)
        }
    }

    SettingsGroupCard(
        title = stringResource(Res.string.label_section_global_colors),
        fontSize = labelFontSize,
    ) {
        GlobalThemeColorSettings(draft = draft, onChange = onDraftChange)
    }

    SettingsGroupCard(
        title = stringResource(Res.string.label_section_file_list_colors),
        fontSize = labelFontSize,
    ) {
        FileListColorSettings(draft = draft, onChange = onDraftChange)
    }

    SettingsGroupCard(
        title = stringResource(Res.string.label_section_density_typography),
        fontSize = labelFontSize,
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
        SettingsSection(stringResource(Res.string.label_file_icon_style), labelFontSize) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsOption(
                    selected = draft.useSystemFileIcons,
                    text = stringResource(Res.string.label_file_icon_system),
                    fontSize = labelFontSize,
                    onClick = { onDraftChange(draft.copy(useSystemFileIcons = true)) },
                )
                SettingsOption(
                    selected = !draft.useSystemFileIcons,
                    text = stringResource(Res.string.label_file_icon_vector),
                    fontSize = labelFontSize,
                    onClick = { onDraftChange(draft.copy(useSystemFileIcons = false)) },
                )
            }
        }
        SettingsToggleRow(
            title = stringResource(Res.string.label_zebra_stripe),
            value = draft.zebraStripeEnabled,
            enabledText = stringResource(Res.string.label_setting_show),
            disabledText = stringResource(Res.string.label_setting_hide),
            labelFontSize = labelFontSize,
            onValueChange = { value -> onDraftChange(draft.copy(zebraStripeEnabled = value)) },
        )
    }
}

/**
 * 绘制视图与浏览分类（默认布局、面板显隐、隐藏文件与扩展名显示、自然排序与文件夹置顶、详情列）。
 */
@Composable
private fun ViewsBrowsingSettingsContent(
    draft: OnyxSettings,
    onDraftChange: (OnyxSettings) -> Unit,
    labelFontSize: TextUnit,
) {
    val showText = stringResource(Res.string.label_setting_show)
    val hideText = stringResource(Res.string.label_setting_hide)
    val enabledText = stringResource(Res.string.label_setting_enabled)
    val disabledText = stringResource(Res.string.label_setting_disabled)

    SettingsGroupCard(
        title = stringResource(Res.string.label_section_workspace_layout),
        fontSize = labelFontSize,
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
        SettingsToggleRow(
            title = stringResource(Res.string.label_sidebar_visibility),
            value = draft.sidebarVisible,
            enabledText = showText,
            disabledText = hideText,
            labelFontSize = labelFontSize,
            onValueChange = { value -> onDraftChange(draft.copy(sidebarVisible = value)) },
        )
        SettingsToggleRow(
            title = stringResource(Res.string.label_sidebar_tree_visibility),
            value = draft.sidebarTreeVisible,
            enabledText = showText,
            disabledText = hideText,
            labelFontSize = labelFontSize,
            onValueChange = { value -> onDraftChange(draft.copy(sidebarTreeVisible = value)) },
        )
        SettingsToggleRow(
            title = stringResource(Res.string.label_status_bar_visibility),
            value = draft.statusBarVisible,
            enabledText = showText,
            disabledText = hideText,
            labelFontSize = labelFontSize,
            onValueChange = { value -> onDraftChange(draft.copy(statusBarVisible = value)) },
        )
    }

    SettingsGroupCard(
        title = stringResource(Res.string.label_section_browsing_sorting),
        fontSize = labelFontSize,
    ) {
        SettingsToggleRow(
            title = stringResource(Res.string.label_default_show_hidden),
            value = draft.defaultShowHiddenItems,
            enabledText = showText,
            disabledText = hideText,
            labelFontSize = labelFontSize,
            onValueChange = { value -> onDraftChange(draft.copy(defaultShowHiddenItems = value)) },
        )
        SettingsToggleRow(
            title = stringResource(Res.string.label_show_file_extensions),
            value = draft.showFileExtensions,
            enabledText = showText,
            disabledText = hideText,
            labelFontSize = labelFontSize,
            onValueChange = { value -> onDraftChange(draft.copy(showFileExtensions = value)) },
        )
        SettingsToggleRow(
            title = stringResource(Res.string.label_natural_sort),
            value = draft.naturalSortEnabled,
            enabledText = enabledText,
            disabledText = disabledText,
            labelFontSize = labelFontSize,
            onValueChange = { value -> onDraftChange(draft.copy(naturalSortEnabled = value)) },
        )
        SettingsToggleRow(
            title = stringResource(Res.string.label_directories_first),
            value = draft.directoriesFirst,
            enabledText = enabledText,
            disabledText = disabledText,
            labelFontSize = labelFontSize,
            onValueChange = { value -> onDraftChange(draft.copy(directoriesFirst = value)) },
        )
    }

    SettingsGroupCard(
        title = stringResource(Res.string.label_section_details_columns),
        fontSize = labelFontSize,
    ) {
        ColumnsSettingsContent(draft = draft, onDraftChange = onDraftChange, labelFontSize = labelFontSize)
    }
}

/**
 * 绘制操作与安全分类（单击/双击打开、双击空白返回上级、删除策略、删除前确认提醒、清理失效历史）。
 */
@Composable
private fun OperationsSafetySettingsContent(
    draft: OnyxSettings,
    onDraftChange: (OnyxSettings) -> Unit,
    onCleanupInvalidLocations: () -> Unit,
    bodyFontSize: TextUnit,
    labelFontSize: TextUnit,
) {
    val enabledText = stringResource(Res.string.label_setting_enabled)
    val disabledText = stringResource(Res.string.label_setting_disabled)

    SettingsGroupCard(
        title = stringResource(Res.string.label_section_mouse_navigation),
        fontSize = labelFontSize,
    ) {
        SettingsSection(stringResource(Res.string.label_open_item_click_mode), labelFontSize) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsOption(
                    selected = !draft.singleClickToOpen,
                    text = stringResource(Res.string.label_open_item_double_click),
                    fontSize = labelFontSize,
                    onClick = { onDraftChange(draft.copy(singleClickToOpen = false)) },
                )
                SettingsOption(
                    selected = draft.singleClickToOpen,
                    text = stringResource(Res.string.label_open_item_single_click),
                    fontSize = labelFontSize,
                    onClick = { onDraftChange(draft.copy(singleClickToOpen = true)) },
                )
            }
        }
        SettingsToggleRow(
            title = stringResource(Res.string.label_double_click_blank_up),
            value = draft.doubleClickEmptySpaceToNavigateUp,
            enabledText = enabledText,
            disabledText = disabledText,
            labelFontSize = labelFontSize,
            onValueChange = { value ->
                onDraftChange(draft.copy(doubleClickEmptySpaceToNavigateUp = value))
            },
        )
    }

    SettingsGroupCard(
        title = stringResource(Res.string.label_section_delete_safety),
        fontSize = labelFontSize,
    ) {
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
        SettingsToggleRow(
            title = stringResource(Res.string.label_confirm_before_delete),
            value = draft.confirmBeforeDelete,
            enabledText = enabledText,
            disabledText = disabledText,
            labelFontSize = labelFontSize,
            onValueChange = { value -> onDraftChange(draft.copy(confirmBeforeDelete = value)) },
        )
        SettingsSection(stringResource(Res.string.label_location_history), labelFontSize) {
            DialogTextButton(
                text = stringResource(Res.string.action_cleanup_invalid_locations),
                fontSize = bodyFontSize,
                onClick = onCleanupInvalidLocations,
            )
        }
    }

    TaskFeedbackSettingsCard(
        draft = draft,
        onDraftChange = onDraftChange,
        labelFontSize = labelFontSize,
        enabledText = enabledText,
        disabledText = disabledText,
    )
}

/**
 * 绘制标记与自动化分类（文件颜色标签规则、自定义外部命令）。
 */
@Composable
private fun AutomationSettingsContent(
    draft: OnyxSettings,
    onDraftChange: (OnyxSettings) -> Unit,
    labelFontSize: TextUnit,
) {
    SettingsGroupCard(
        title = stringResource(Res.string.label_settings_file_labels),
        fontSize = labelFontSize,
    ) {
        FileLabelSettings(draft = draft, onChange = onDraftChange)
    }

    SettingsGroupCard(
        title = stringResource(Res.string.label_settings_user_commands),
        fontSize = labelFontSize,
    ) {
        UserCommandSettings(draft = draft, onChange = onDraftChange)
    }
}

/**
 * 绘制快捷键与高级分类（键盘快捷键录制、详细诊断日志开关、恢复全部默认设置）。
 */
@Composable
private fun ShortcutsAdvancedSettingsContent(
    draft: OnyxSettings,
    onDraftChange: (OnyxSettings) -> Unit,
    bodyFontSize: TextUnit,
    labelFontSize: TextUnit,
) {
    SettingsGroupCard(
        title = stringResource(Res.string.label_settings_shortcuts),
        fontSize = labelFontSize,
    ) {
        CommandShortcutSettings(
            settings = draft,
            onDraftChange = onDraftChange,
            fontSize = bodyFontSize,
            labelFontSize = labelFontSize,
        )
    }

    SettingsGroupCard(
        title = stringResource(Res.string.label_section_diagnostics_maintenance),
        fontSize = labelFontSize,
    ) {
        VerboseLoggingSettings(labelFontSize = labelFontSize)
        DialogTextButton(
            text = stringResource(Res.string.action_restore_factory_defaults),
            destructive = true,
            fontSize = bodyFontSize,
            onClick = { onDraftChange(draft.resetAllPreferencesToDefaults()) },
        )
    }
}

/**
 * 绘制仅在当前进程生效的详细日志开关。
 */
@Composable
private fun VerboseLoggingSettings(labelFontSize: TextUnit) {
    val palette = LocalOnyxPalette.current
    var verboseOn by remember { mutableStateOf(OnyxLogger.verboseEnabled) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsToggleRow(
            title = stringResource(Res.string.label_verbose_logging),
            value = verboseOn,
            enabledText = stringResource(Res.string.label_setting_enabled),
            disabledText = stringResource(Res.string.label_setting_disabled),
            labelFontSize = labelFontSize,
            onValueChange = { enabled ->
                OnyxLogger.verboseEnabled = enabled
                verboseOn = enabled
            },
        )
        Text(
            text = stringResource(Res.string.label_verbose_logging_hint),
            fontSize = 10.sp,
            color = palette.mutedForeground,
        )
    }
}

/**
 * 绘制详情列可见性设置，名称列始终保持可见。
 */
@Composable
private fun ColumnsSettingsContent(
    draft: OnyxSettings,
    onDraftChange: (OnyxSettings) -> Unit,
    labelFontSize: TextUnit,
) {
    val showText = stringResource(Res.string.label_setting_show)
    val hideText = stringResource(Res.string.label_setting_hide)
    columnOptions().forEach { (column, label) ->
        val isName = column == DetailsColumn.NAME
        val visible = column !in draft.hiddenDetailsColumns
        SettingsToggleRow(
            title = stringResource(label),
            value = visible,
            enabledText = showText,
            disabledText = hideText,
            labelFontSize = labelFontSize,
            enabled = !isName,
            onValueChange = { nextVisible ->
                if (!isName) {
                    val updated = if (nextVisible) {
                        draft.hiddenDetailsColumns - column
                    } else {
                        draft.hiddenDetailsColumns + column
                    }
                    onDraftChange(draft.copy(hiddenDetailsColumns = updated))
                }
            },
        )
    }
}

/** 返回语言选项及其资源标签。 */
private fun localeOptions(): List<Pair<AppLocale, StringResource>> = listOf(
    AppLocale.SYSTEM to Res.string.label_locale_system,
    AppLocale.ENGLISH to Res.string.label_locale_english,
    AppLocale.SIMPLIFIED_CHINESE to Res.string.label_locale_chinese,
    AppLocale.JAPANESE to Res.string.label_locale_japanese,
)

/** 返回明暗主题模式选项。 */
private fun themeModeOptions(): List<Pair<OnyxThemeMode, StringResource>> = listOf(
    OnyxThemeMode.SYSTEM to Res.string.label_theme_mode_system,
    OnyxThemeMode.LIGHT to Res.string.label_theme_mode_light,
    OnyxThemeMode.DARK to Res.string.label_theme_mode_dark,
)

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

/** 返回详情列及其标签。 */
private fun columnOptions(): List<Pair<DetailsColumn, StringResource>> = listOf(
    DetailsColumn.NAME to Res.string.label_column_name,
    DetailsColumn.TYPE to Res.string.label_column_type,
    DetailsColumn.SIZE to Res.string.label_column_size,
    DetailsColumn.MODIFIED to Res.string.label_column_modified,
)

/**
 * 判断指定设置分类下的本地化标签是否包含搜索关键字（忽略大小写）。
 */
@Composable
private fun categoryMatchesQuery(category: SettingsCategory, query: String): Boolean {
    val keywords = when (category) {
        SettingsCategory.GENERAL -> listOf(
            stringResource(Res.string.label_settings_cat_general),
            stringResource(Res.string.label_section_language_startup),
            stringResource(Res.string.label_language),
            stringResource(Res.string.label_close_to_tray),
            stringResource(Res.string.label_restore_session_startup),
        )

        SettingsCategory.THEME_APPEARANCE -> listOf(
            stringResource(Res.string.label_settings_cat_theme),
            stringResource(Res.string.label_section_theme_mode_preset),
            stringResource(Res.string.label_theme_mode),
            stringResource(Res.string.label_theme_preset),
            stringResource(Res.string.label_custom_themes_section),
            stringResource(Res.string.action_save_custom_theme),
            stringResource(Res.string.label_theme_accent_swatch),
            stringResource(Res.string.label_section_density_typography),
            stringResource(Res.string.label_ui_scale),
            stringResource(Res.string.label_list_row_height),
            stringResource(Res.string.label_list_font_size),
            stringResource(Res.string.label_zebra_stripe),
            stringResource(Res.string.label_file_icon_style),
            stringResource(Res.string.label_section_global_colors),
            stringResource(Res.string.label_section_file_list_colors),
        )

        SettingsCategory.VIEWS_BROWSING -> listOf(
            stringResource(Res.string.label_settings_cat_views),
            stringResource(Res.string.label_section_workspace_layout),
            stringResource(Res.string.label_default_layout_mode),
            stringResource(Res.string.label_default_view_mode),
            stringResource(Res.string.label_sidebar_visibility),
            stringResource(Res.string.label_sidebar_tree_visibility),
            stringResource(Res.string.label_status_bar_visibility),
            stringResource(Res.string.label_section_browsing_sorting),
            stringResource(Res.string.label_default_show_hidden),
            stringResource(Res.string.label_show_file_extensions),
            stringResource(Res.string.label_natural_sort),
            stringResource(Res.string.label_directories_first),
            stringResource(Res.string.label_section_details_columns),
        )

        SettingsCategory.OPERATIONS_SAFETY -> listOf(
            stringResource(Res.string.label_settings_cat_operations),
            stringResource(Res.string.label_section_mouse_navigation),
            stringResource(Res.string.label_open_item_click_mode),
            stringResource(Res.string.label_double_click_blank_up),
            stringResource(Res.string.label_section_delete_safety),
            stringResource(Res.string.label_delete_mode),
            stringResource(Res.string.label_confirm_before_delete),
            stringResource(Res.string.label_location_history),
            stringResource(Res.string.label_section_task_feedback),
            stringResource(Res.string.label_auto_show_task_center),
            stringResource(Res.string.label_auto_close_task_center),
        )

        SettingsCategory.AUTOMATION -> listOf(
            stringResource(Res.string.label_settings_cat_automation),
            stringResource(Res.string.label_settings_file_labels),
            stringResource(Res.string.label_settings_user_commands),
        )

        SettingsCategory.SHORTCUTS_ADVANCED -> listOf(
            stringResource(Res.string.label_settings_cat_shortcuts),
            stringResource(Res.string.label_settings_shortcuts),
            stringResource(Res.string.label_section_diagnostics_maintenance),
            stringResource(Res.string.label_verbose_logging),
            stringResource(Res.string.action_restore_factory_defaults),
        )
    }
    return keywords.any { keyword -> keyword.contains(query, ignoreCase = true) }
}
