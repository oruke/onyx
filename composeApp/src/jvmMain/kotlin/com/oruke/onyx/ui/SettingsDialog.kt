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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_apply
import onyx.composeapp.generated.resources.action_close_menu
import onyx.composeapp.generated.resources.label_settings_appearance
import onyx.composeapp.generated.resources.label_settings_columns
import onyx.composeapp.generated.resources.label_settings_general
import onyx.composeapp.generated.resources.label_settings_layout
import onyx.composeapp.generated.resources.label_settings_shortcuts
import onyx.composeapp.generated.resources.label_settings_title
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Text

/**
 * 绘制可调整大小的设置窗口。
 *
 * @param state 当前设置草稿状态。
 * @param onDraftChange 设置草稿变化回调。
 * @param onCleanupInvalidLocations 清理无效位置回调。
 * @param onConfirm 应用设置回调。
 * @param onDismiss 关闭窗口回调。
 * @param initialWidth 初始窗口宽度。
 * @param initialHeight 初始窗口高度。
 * @param onWindowSizeChanged 窗口尺寸变化回调。
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

    DialogWindow(
        onCloseRequest = onDismiss,
        title = title,
        state = dialogState,
        resizable = true,
    ) {
        window.minimumSize = java.awt.Dimension(SETTINGS_MIN_WIDTH_PX, SETTINGS_MIN_HEIGHT_PX)
        IntUiTheme(isDark = isSystemInDarkTheme()) {
            val appearance = LocalOnyxAppearance.current
            SettingsDialogLayout(
                title = title,
                draft = state.draft,
                category = category,
                onCategoryChange = { nextCategory -> category = nextCategory },
                onDraftChange = onDraftChange,
                onCleanupInvalidLocations = onCleanupInvalidLocations,
                onConfirm = onConfirm,
                onDismiss = onDismiss,
                bodyFontSize = appearance.listFontSize,
                labelFontSize = appearance.headerFontSize,
            )
        }
    }
}

/**
 * 组织设置窗口标题、分类导航、内容和底部动作。
 *
 * @param title 窗口标题。
 * @param draft 当前设置草稿。
 * @param category 当前分类。
 * @param onCategoryChange 分类变化回调。
 * @param onDraftChange 设置草稿变化回调。
 * @param onCleanupInvalidLocations 清理无效位置回调。
 * @param onConfirm 应用回调。
 * @param onDismiss 关闭回调。
 * @param bodyFontSize 正文字号。
 * @param labelFontSize 标签字号。
 */
@Composable
private fun SettingsDialogLayout(
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
) {
    val palette = LocalOnyxPalette.current
    val navScrollState = rememberScrollState()
    val contentScrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.appBackground)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            fontSize = (bodyFontSize.value + 1).sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.foreground,
        )
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingsNavigation(
                category = category,
                onCategoryChange = onCategoryChange,
                fontSize = bodyFontSize,
                modifier = Modifier
                    .width(138.dp)
                    .fillMaxHeight()
                    .verticalScroll(navScrollState),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(palette.surface, SettingsPanelShape)
                    .border(1.dp, palette.outlineVariant, SettingsPanelShape)
                    .padding(12.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(contentScrollState),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SettingsCategoryContent(
                        category = category,
                        draft = draft,
                        onDraftChange = onDraftChange,
                        onCleanupInvalidLocations = onCleanupInvalidLocations,
                        bodyFontSize = bodyFontSize,
                        labelFontSize = labelFontSize,
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DialogTextButton(
                text = stringResource(Res.string.action_close_menu),
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
 * 绘制设置分类导航。
 *
 * @param category 当前分类。
 * @param onCategoryChange 分类变化回调。
 * @param fontSize 导航字号。
 * @param modifier 布局修饰符。
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
            .background(palette.surfaceVariant.copy(alpha = 0.55f), SettingsPanelShape)
            .border(1.dp, palette.outlineVariant, SettingsPanelShape)
            .padding(6.dp),
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
 *
 * @param category 设置分类。
 * @return 已本地化分类名称。
 */
@Composable
private fun settingsCategoryLabel(category: SettingsCategory): String = when (category) {
    SettingsCategory.GENERAL -> stringResource(Res.string.label_settings_general)
    SettingsCategory.LAYOUT -> stringResource(Res.string.label_settings_layout)
    SettingsCategory.APPEARANCE -> stringResource(Res.string.label_settings_appearance)
    SettingsCategory.COLUMNS -> stringResource(Res.string.label_settings_columns)
    SettingsCategory.SHORTCUTS -> stringResource(Res.string.label_settings_shortcuts)
}

private const val DEFAULT_SETTINGS_WIDTH = 720
private const val DEFAULT_SETTINGS_HEIGHT = 520
private const val SETTINGS_MIN_WIDTH_PX = 600
private const val SETTINGS_MIN_HEIGHT_PX = 420
private val SettingsPanelShape = RoundedCornerShape(6.dp)
