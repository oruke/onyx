package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.rememberDialogState
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_apply
import onyx.composeapp.generated.resources.action_close_window
import onyx.composeapp.generated.resources.action_reset_current_page
import onyx.composeapp.generated.resources.label_settings_cat_automation
import onyx.composeapp.generated.resources.label_settings_cat_general
import onyx.composeapp.generated.resources.label_settings_cat_operations
import onyx.composeapp.generated.resources.label_settings_cat_shortcuts
import onyx.composeapp.generated.resources.label_settings_cat_theme
import onyx.composeapp.generated.resources.label_settings_cat_views
import onyx.composeapp.generated.resources.label_settings_search_placeholder
import onyx.composeapp.generated.resources.label_settings_title
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

/**
 * 绘制可调整大小的设置窗口，支持实时主题预览、6 大高内聚分类、搜索过滤与当前页恢复默认。
 */
@Composable
internal fun SettingsDialog(
    state: RootDialogState.Settings,
    onDraftChange: (OnyxSettings) -> Unit,
    onCleanupInvalidLocations: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    initialWidth: Int = DEFAULT_SETTINGS_WIDTH,
    initialHeight: Int = DEFAULT_SETTINGS_HEIGHT,
    onWindowSizeChanged: ((width: Int, height: Int) -> Unit)? = null,
) {
    val title = stringResource(Res.string.label_settings_title)
    var category by remember { mutableStateOf(SettingsCategory.GENERAL) }
    var searchQuery by remember { mutableStateOf("") }
    val dialogState = rememberDialogState(width = initialWidth.dp, height = initialHeight.dp)

    LaunchedEffect(dialogState) {
        snapshotFlow { dialogState.size }.collect { size ->
            val width = size.width
            val height = size.height
            if (width != Dp.Unspecified && height != Dp.Unspecified) {
                onWindowSizeChanged?.invoke(width.value.toInt(), height.value.toInt())
            }
        }
    }

    OnyxDialogWindow(
        onCloseRequest = onDismiss,
        title = title,
        state = dialogState,
        resizable = true,
        settings = state.draft,
        minimumWidthPx = SETTINGS_MIN_WIDTH_PX,
        minimumHeightPx = SETTINGS_MIN_HEIGHT_PX,
        scaleMinimumSizeWithUi = true,
        windowName = "onyx-settings",
    ) {
        val appearance = LocalOnyxAppearance.current
        SettingsDialogLayout(
            title = title,
            draft = state.draft,
            category = category,
            onCategoryChange = { nextCategory ->
                category = nextCategory
                if (searchQuery.isNotBlank()) {
                    searchQuery = ""
                }
            },
            onDraftChange = onDraftChange,
            onCleanupInvalidLocations = onCleanupInvalidLocations,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            bodyFontSize = appearance.listFontSize,
            labelFontSize = appearance.headerFontSize,
            searchQuery = searchQuery,
            onSearchQueryChange = { nextQuery -> searchQuery = nextQuery },
            showTitlePrefix = false,
        )
    }
}

/**
 * 采用与主窗口、任务中心、搜索窗口完全一致的扁平紧凑分栏风格：
 * 顶栏、左导航栏、右内容区、底动作栏均通过 1px [Divider] 扁平分割，无外圈悬浮套框。
 */
@Suppress("LongParameterList")
@Composable
internal fun SettingsDialogLayout(
    title: String,
    draft: OnyxSettings,
    category: SettingsCategory,
    onCategoryChange: (SettingsCategory) -> Unit,
    onDraftChange: (OnyxSettings) -> Unit,
    onCleanupInvalidLocations: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    bodyFontSize: TextUnit,
    labelFontSize: TextUnit,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    showTitlePrefix: Boolean = true,
) {
    val palette = LocalOnyxPalette.current
    val navScrollState = rememberScrollState()
    val contentScrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.appBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SettingsBreadcrumbTitle(
                title = title,
                category = category,
                showTitlePrefix = showTitlePrefix,
                bodyFontSize = bodyFontSize,
            )
            OnyxTextInput(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = stringResource(Res.string.label_settings_search_placeholder),
                fontSize = labelFontSize,
                height = 26.dp,
                modifier = Modifier.width(220.dp),
            )
        }
        Divider(Orientation.Horizontal, color = palette.outlineVariant)
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            SettingsNavigation(
                category = category,
                onCategoryChange = onCategoryChange,
                fontSize = bodyFontSize,
                modifier = Modifier
                    .width(SETTINGS_SIDEBAR_WIDTH_DP.dp)
                    .fillMaxHeight()
                    .verticalScroll(navScrollState),
            )
            Divider(Orientation.Vertical, color = palette.outlineVariant, modifier = Modifier.fillMaxHeight())
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(palette.appBackground)
                    .verticalScroll(contentScrollState)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SettingsCategoryContent(
                    category = category,
                    draft = draft,
                    onDraftChange = onDraftChange,
                    onCleanupInvalidLocations = onCleanupInvalidLocations,
                    bodyFontSize = bodyFontSize,
                    labelFontSize = labelFontSize,
                    searchQuery = searchQuery,
                )
            }
        }
        Divider(Orientation.Horizontal, color = palette.outlineVariant)
        SettingsDialogFooterBar(
            bodyFontSize = bodyFontSize,
            onResetCurrentPage = { onDraftChange(draft.resetCategoryToDefaults(category)) },
            onDismiss = onDismiss,
            onConfirm = onConfirm,
        )
    }
}

@Composable
private fun SettingsBreadcrumbTitle(
    title: String,
    category: SettingsCategory,
    showTitlePrefix: Boolean,
    bodyFontSize: TextUnit,
) {
    val palette = LocalOnyxPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showTitlePrefix) {
            Text(
                text = title,
                fontSize = (bodyFontSize.value + 1).sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.foreground,
            )
            Text(
                text = "/  " + settingsCategoryLabel(category),
                fontSize = bodyFontSize,
                color = palette.mutedForeground,
            )
        } else {
            Text(
                text = settingsCategoryLabel(category),
                fontSize = (bodyFontSize.value + 1).sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.foreground,
            )
        }
    }
}

@Composable
private fun SettingsDialogFooterBar(
    bodyFontSize: TextUnit,
    onResetCurrentPage: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        DialogTextButton(
            text = stringResource(Res.string.action_reset_current_page),
            fontSize = bodyFontSize,
            onClick = onResetCurrentPage,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DialogTextButton(
                text = stringResource(Res.string.action_close_window),
                fontSize = bodyFontSize,
                onClick = onDismiss,
            )
            DialogTextButton(
                text = stringResource(Res.string.action_apply),
                emphasized = true,
                fontSize = bodyFontSize,
                onClick = onConfirm,
            )
        }
    }
}

/**
 * 绘制设置分类左侧扁平导航栏，与主窗口 [PaneSidebar] 的背景和内边距风格完全一致。
 */
@Composable
private fun SettingsNavigation(
    category: SettingsCategory,
    onCategoryChange: (SettingsCategory) -> Unit,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalOnyxPalette.current
    Column(
        modifier = modifier
            .background(palette.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SettingsCategory.entries.forEach { candidate ->
            SettingsNavItem(
                text = settingsCategoryLabel(candidate),
                selected = category == candidate,
                accent = palette.accent,
                foreground = palette.foreground,
                fontSize = fontSize,
                onClick = { onCategoryChange(candidate) },
            )
        }
    }
}

/**
 * 解析设置分类标签。
 */
@Composable
private fun settingsCategoryLabel(category: SettingsCategory): String = when (category) {
    SettingsCategory.GENERAL -> stringResource(Res.string.label_settings_cat_general)
    SettingsCategory.THEME_APPEARANCE -> stringResource(Res.string.label_settings_cat_theme)
    SettingsCategory.VIEWS_BROWSING -> stringResource(Res.string.label_settings_cat_views)
    SettingsCategory.OPERATIONS_SAFETY -> stringResource(Res.string.label_settings_cat_operations)
    SettingsCategory.AUTOMATION -> stringResource(Res.string.label_settings_cat_automation)
    SettingsCategory.SHORTCUTS_ADVANCED -> stringResource(Res.string.label_settings_cat_shortcuts)
}

private const val DEFAULT_SETTINGS_WIDTH = 860
private const val DEFAULT_SETTINGS_HEIGHT = 640
private const val SETTINGS_MIN_WIDTH_PX = 680
private const val SETTINGS_MIN_HEIGHT_PX = 480
private const val SETTINGS_SIDEBAR_WIDTH_DP = 168
