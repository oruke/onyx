package com.oruke.onyx.ui

import com.oruke.onyx.core.model.OnyxSettings

/** 设置窗口左侧高内聚分类。 */
internal enum class SettingsCategory {
    /** 常规与启动（语言、关闭最小化到系统托盘、启动时恢复会话）。 */
    GENERAL,

    /** 主题与外观（明暗模式、6 套精选主题预设、强调色盘、缩放/字号/行高/图标、全局与列表颜色微调）。 */
    THEME_APPEARANCE,

    /** 视图与浏览（默认布局、默认视图、面板显隐、隐藏文件、文件扩展名、自然数字排序、文件夹优先、详情列）。 */
    VIEWS_BROWSING,

    /** 操作与安全（单击/双击打开、双击空白返回上级、删除策略、删除前确认提醒、清理失效位置历史）。 */
    OPERATIONS_SAFETY,

    /** 标记与自动化（文件颜色标签规则、自定义外部命令）。 */
    AUTOMATION,

    /** 快捷键与高级（键盘快捷键录制、详细诊断日志、恢复全部默认设置）。 */
    SHORTCUTS_ADVANCED,
}

/**
 * 将指定分类下的所有设置项恢复为出厂默认值，不影响其他分类与窗口尺寸/历史状态。
 *
 * @param category 待重置的设置分类。
 * @return 重置当前页后的新设置草稿。
 */
internal fun OnyxSettings.resetCategoryToDefaults(category: SettingsCategory): OnyxSettings {
    val defaults = OnyxSettings()
    return when (category) {
        SettingsCategory.GENERAL -> copy(
            preferredLocale = defaults.preferredLocale,
            closeToTray = defaults.closeToTray,
            restoreSessionOnStartup = defaults.restoreSessionOnStartup,
        )

        SettingsCategory.THEME_APPEARANCE -> copy(
            themeMode = defaults.themeMode,
            themePreset = defaults.themePreset,
            themeColors = defaults.themeColors,
            activeCustomThemeId = defaults.activeCustomThemeId,
            fileListColors = defaults.fileListColors,
            uiScale = defaults.uiScale,
            listRowHeightDp = defaults.listRowHeightDp,
            listFontSizeSp = defaults.listFontSizeSp,
            zebraStripeEnabled = defaults.zebraStripeEnabled,
            useSystemFileIcons = defaults.useSystemFileIcons,
        )

        SettingsCategory.VIEWS_BROWSING -> copy(
            defaultLayoutMode = defaults.defaultLayoutMode,
            defaultViewMode = defaults.defaultViewMode,
            sidebarVisible = defaults.sidebarVisible,
            sidebarTreeVisible = defaults.sidebarTreeVisible,
            statusBarVisible = defaults.statusBarVisible,
            defaultShowHiddenItems = defaults.defaultShowHiddenItems,
            showFileExtensions = defaults.showFileExtensions,
            naturalSortEnabled = defaults.naturalSortEnabled,
            directoriesFirst = defaults.directoriesFirst,
            hiddenDetailsColumns = defaults.hiddenDetailsColumns,
        )

        SettingsCategory.OPERATIONS_SAFETY -> copy(
            singleClickToOpen = defaults.singleClickToOpen,
            doubleClickEmptySpaceToNavigateUp = defaults.doubleClickEmptySpaceToNavigateUp,
            deleteMode = defaults.deleteMode,
            confirmBeforeDelete = defaults.confirmBeforeDelete,
        )

        SettingsCategory.AUTOMATION -> copy(
            fileLabelRules = defaults.fileLabelRules,
            userCommands = defaults.userCommands,
        )

        SettingsCategory.SHORTCUTS_ADVANCED -> copy(
            commandShortcutOverrides = defaults.commandShortcutOverrides,
        )
    }
}

/**
 * 将所有分类的偏好设置恢复为出厂默认值，同时保留用户收藏、历史、远程连接与窗口几何尺寸。
 *
 * @return 恢复默认偏好后的新设置草稿。
 */
internal fun OnyxSettings.resetAllPreferencesToDefaults(): OnyxSettings {
    return SettingsCategory.entries.fold(this) { acc, category ->
        acc.resetCategoryToDefaults(category)
    }
}

