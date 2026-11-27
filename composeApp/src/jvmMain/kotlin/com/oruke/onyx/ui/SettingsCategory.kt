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
            autoShowTaskCenter = defaults.autoShowTaskCenter,
            autoCloseTaskCenterWhenFinished = defaults.autoCloseTaskCenterWhenFinished,
        )

        SettingsCategory.AUTOMATION -> copy(
            fileLabelRules = defaults.fileLabelRules,
            userCommands = defaults.userCommands,
        )

        SettingsCategory.SHORTCUTS_ADVANCED -> copy(
            commandShortcutOverrides = defaults.commandShortcutOverrides,
            verboseLoggingEnabled = defaults.verboseLoggingEnabled,
            maxHeapMemoryMb = defaults.maxHeapMemoryMb,
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

/**
 * 将设置窗口编辑后的偏好字段三路合并进当前最新的运行期 [OnyxSettings]。
 *
 * 仅提交设置页负责的偏好与显式变更（如自定义主题、位置清理），绝不覆写设置打开期间由主窗口、
 * 搜索窗口或任务中心异步产生的搜索历史、最近访问、收藏、远程连接、侧栏折叠、窗口尺寸及未在设置页修改的任务窗自动关闭开关。
 *
 * @param draft 用户确认应用时的设置草稿。
 * @param initialDraft 打开设置窗口瞬间记录的基准草稿。
 * @return 保留最新运行期状态并合入全部偏好修改的新设置。
 */
internal fun OnyxSettings.mergeEditableSettingsFrom(
    draft: OnyxSettings,
    initialDraft: OnyxSettings,
): OnyxSettings {
    val autoCloseChangedInSettings = draft.autoCloseTaskCenterWhenFinished != initialDraft.autoCloseTaskCenterWhenFinished
    val favoritesCleanedInSettings = draft.favoriteLocations != initialDraft.favoriteLocations
    val recentsCleanedInSettings = draft.recentLocations != initialDraft.recentLocations
    val removedFavorites = if (favoritesCleanedInSettings) {
        initialDraft.favoriteLocations.toSet() - draft.favoriteLocations.toSet()
    } else {
        emptySet()
    }
    val removedRecents = if (recentsCleanedInSettings) {
        initialDraft.recentLocations.toSet() - draft.recentLocations.toSet()
    } else {
        emptySet()
    }
    return copy(
        preferredLocale = draft.preferredLocale,
        closeToTray = draft.closeToTray,
        restoreSessionOnStartup = draft.restoreSessionOnStartup,
        themeMode = draft.themeMode,
        themePreset = draft.themePreset,
        themeColors = draft.themeColors,
        customThemes = draft.customThemes,
        activeCustomThemeId = draft.activeCustomThemeId,
        fileListColors = draft.fileListColors,
        uiScale = draft.uiScale,
        listRowHeightDp = draft.listRowHeightDp,
        listFontSizeSp = draft.listFontSizeSp,
        zebraStripeEnabled = draft.zebraStripeEnabled,
        useSystemFileIcons = draft.useSystemFileIcons,
        defaultLayoutMode = draft.defaultLayoutMode,
        defaultViewMode = draft.defaultViewMode,
        sidebarVisible = draft.sidebarVisible,
        sidebarTreeVisible = draft.sidebarTreeVisible,
        statusBarVisible = draft.statusBarVisible,
        defaultShowHiddenItems = draft.defaultShowHiddenItems,
        showFileExtensions = draft.showFileExtensions,
        naturalSortEnabled = draft.naturalSortEnabled,
        directoriesFirst = draft.directoriesFirst,
        hiddenDetailsColumns = draft.hiddenDetailsColumns,
        singleClickToOpen = draft.singleClickToOpen,
        doubleClickEmptySpaceToNavigateUp = draft.doubleClickEmptySpaceToNavigateUp,
        deleteMode = draft.deleteMode,
        confirmBeforeDelete = draft.confirmBeforeDelete,
        autoShowTaskCenter = draft.autoShowTaskCenter,
        autoCloseTaskCenterWhenFinished = if (autoCloseChangedInSettings) {
            draft.autoCloseTaskCenterWhenFinished
        } else {
            autoCloseTaskCenterWhenFinished
        },
        fileLabelRules = draft.fileLabelRules,
        userCommands = draft.userCommands,
        commandShortcutOverrides = draft.commandShortcutOverrides,
        verboseLoggingEnabled = draft.verboseLoggingEnabled,
        maxHeapMemoryMb = draft.maxHeapMemoryMb,
        favoriteLocations = if (removedFavorites.isEmpty()) {
            favoriteLocations
        } else {
            favoriteLocations.filterNot { it in removedFavorites }
        },
        recentLocations = if (removedRecents.isEmpty()) {
            recentLocations
        } else {
            recentLocations.filterNot { it in removedRecents }
        },
    )
}
