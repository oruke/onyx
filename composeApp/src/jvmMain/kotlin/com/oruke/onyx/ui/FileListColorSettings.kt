package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.core.model.CustomThemePreset
import com.oruke.onyx.core.model.FileListColors
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.OnyxThemeColors
import com.oruke.onyx.core.model.OnyxThemePreset
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.ONYX_ACCENT_SWATCHES
import com.oruke.onyx.ui.theme.OnyxPalette
import com.oruke.onyx.ui.theme.resolveEffectiveDarkTheme
import com.oruke.onyx.ui.theme.resolveOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_delete
import onyx.composeapp.generated.resources.action_reset
import onyx.composeapp.generated.resources.action_save_custom_theme
import onyx.composeapp.generated.resources.action_update_custom_theme
import onyx.composeapp.generated.resources.label_custom_theme_name_placeholder
import onyx.composeapp.generated.resources.label_custom_themes_section
import onyx.composeapp.generated.resources.label_file_list_background
import onyx.composeapp.generated.resources.label_file_name_color
import onyx.composeapp.generated.resources.label_inactive_selection_color
import onyx.composeapp.generated.resources.label_selection_color
import onyx.composeapp.generated.resources.label_theme_color_accent
import onyx.composeapp.generated.resources.label_theme_color_app_bg
import onyx.composeapp.generated.resources.label_theme_color_foreground
import onyx.composeapp.generated.resources.label_theme_color_header_bg
import onyx.composeapp.generated.resources.label_theme_color_outline
import onyx.composeapp.generated.resources.label_theme_color_surface
import onyx.composeapp.generated.resources.label_theme_preset_default
import onyx.composeapp.generated.resources.label_theme_preset_dracula_purple
import onyx.composeapp.generated.resources.label_theme_preset_forest_emerald
import onyx.composeapp.generated.resources.label_theme_preset_midnight_oled
import onyx.composeapp.generated.resources.label_theme_preset_nordic_frost
import onyx.composeapp.generated.resources.label_theme_preset_solarized_warm
import onyx.composeapp.generated.resources.label_zebra_color
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/**
 * 渲染 6 套内置精选主题预设的可视化微缩预览网格（每行 3 张卡片）。
 * 点击内置预设会立即切换预设并清空临时颜色覆盖，确保预设配色完整生效。
 */
@Composable
internal fun ThemePresetPickerGrid(draft: OnyxSettings, onChange: (OnyxSettings) -> Unit) {
    val isDark = resolveEffectiveDarkTheme(draft.themeMode, isSystemInDarkTheme())
    val hasActiveCustom = draft.activeCustomThemeId != null
    val rows = OnyxThemePreset.entries.chunked(PRESET_GRID_COLUMNS)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { rowPresets ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowPresets.forEach { preset ->
                    val previewPalette = resolveOnyxPalette(
                        themePreset = preset,
                        dark = isDark,
                        overrides = OnyxThemeColors(),
                    )
                    ThemePreviewCardItem(
                        label = themePresetLabel(preset),
                        previewPalette = previewPalette,
                        selected = !hasActiveCustom && draft.themePreset == preset,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onChange(
                                draft.copy(
                                    themePreset = preset,
                                    activeCustomThemeId = null,
                                    themeColors = OnyxThemeColors(),
                                    fileListColors = FileListColors(),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * 渲染自定义主题保存栏（输入名称保存为自定义主题、更新当前自定义主题）与已保存的自定义主题卡片网格。
 */
@Composable
internal fun CustomThemeManagerSection(draft: OnyxSettings, onChange: (OnyxSettings) -> Unit) {
    val isDark = resolveEffectiveDarkTheme(draft.themeMode, isSystemInDarkTheme())
    var customNameInput by remember { mutableStateOf("") }
    val activeCustomTheme = draft.customThemes.firstOrNull { it.id == draft.activeCustomThemeId }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OnyxTextInput(
                value = customNameInput,
                onValueChange = { customNameInput = it },
                placeholder = stringResource(Res.string.label_custom_theme_name_placeholder),
                modifier = Modifier.weight(1f),
            )
            DialogTextButton(
                text = stringResource(Res.string.action_save_custom_theme),
                emphasized = true,
                fontSize = 11.sp,
                onClick = {
                    val trimmed = customNameInput.trim()
                    val finalName = trimmed.ifEmpty { "自定义主题 ${draft.customThemes.size + 1}" }
                    val newPreset = CustomThemePreset(
                        id = "custom-theme-${System.currentTimeMillis()}",
                        name = finalName,
                        themeMode = draft.themeMode,
                        basePreset = draft.themePreset,
                        themeColors = draft.themeColors,
                        fileListColors = draft.fileListColors,
                    )
                    customNameInput = ""
                    onChange(
                        draft.copy(
                            customThemes = draft.customThemes + newPreset,
                            activeCustomThemeId = newPreset.id,
                        ),
                    )
                },
            )
            if (activeCustomTheme != null) {
                DialogTextButton(
                    text = stringResource(Res.string.action_update_custom_theme),
                    fontSize = 11.sp,
                    onClick = {
                        val updatedName = customNameInput.trim().ifEmpty { activeCustomTheme.name }
                        val updatedList = draft.customThemes.map { candidate ->
                            if (candidate.id == activeCustomTheme.id) {
                                candidate.copy(
                                    name = updatedName,
                                    themeMode = draft.themeMode,
                                    basePreset = draft.themePreset,
                                    themeColors = draft.themeColors,
                                    fileListColors = draft.fileListColors,
                                )
                            } else {
                                candidate
                            }
                        }
                        customNameInput = ""
                        onChange(draft.copy(customThemes = updatedList))
                    },
                )
            }
        }

        if (draft.customThemes.isNotEmpty()) {
            SavedCustomThemeGrid(draft = draft, isDark = isDark, onChange = onChange)
        }
    }
}

@Composable
private fun SavedCustomThemeGrid(
    draft: OnyxSettings,
    isDark: Boolean,
    onChange: (OnyxSettings) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Text(
        text = stringResource(Res.string.label_custom_themes_section),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = palette.foreground,
    )
    val rows = draft.customThemes.chunked(PRESET_GRID_COLUMNS)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { customTheme ->
                    val previewDark = resolveEffectiveDarkTheme(customTheme.themeMode, isDark)
                    val previewPalette = resolveOnyxPalette(
                        themePreset = customTheme.basePreset,
                        dark = previewDark,
                        overrides = customTheme.themeColors,
                    )
                    ThemePreviewCardItem(
                        label = customTheme.name,
                        previewPalette = previewPalette,
                        selected = draft.activeCustomThemeId == customTheme.id,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onChange(
                                draft.copy(
                                    themeMode = customTheme.themeMode,
                                    themePreset = customTheme.basePreset,
                                    themeColors = customTheme.themeColors,
                                    fileListColors = customTheme.fileListColors,
                                    activeCustomThemeId = customTheme.id,
                                ),
                            )
                        },
                        onDelete = {
                            val remaining = draft.customThemes.filterNot { it.id == customTheme.id }
                            val nextActiveId = if (draft.activeCustomThemeId == customTheme.id) {
                                null
                            } else {
                                draft.activeCustomThemeId
                            }
                            onChange(
                                draft.copy(
                                    customThemes = remaining,
                                    activeCustomThemeId = nextActiveId,
                                ),
                            )
                        },
                    )
                }
                repeat(PRESET_GRID_COLUMNS - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 渲染单张扁平紧凑主题预览卡片（支持内置预设与用户自定义主题，带微缩桌面窗口色块预览、即时悬停高亮及可选删除按钮）。
 */
@Composable
private fun ThemePreviewCardItem(
    label: String,
    previewPalette: OnyxPalette,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val currentPalette = LocalOnyxPalette.current
    val hoverSrc = remember { MutableInteractionSource() }
    val clickSrc = remember { MutableInteractionSource() }
    val hovered by hoverSrc.collectIsHoveredAsState()
    val cardBg = when {
        selected -> currentPalette.selectionBackground.copy(alpha = 0.55f)
        hovered -> currentPalette.rowHoverBackground
        else -> currentPalette.surfaceVariant
    }
    val borderColor = when {
        selected -> currentPalette.accent
        hovered -> currentPalette.outline
        else -> currentPalette.outlineVariant
    }

    Column(
        modifier = modifier
            .hoverable(hoverSrc)
            .pointerHoverIcon(PointerIcon.Hand)
            .background(cardBg, PresetCardShape)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = borderColor,
                shape = PresetCardShape,
            )
            .clickable(interactionSource = clickSrc, indication = null, onClick = onClick)
            .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        MiniThemeWindowPreview(previewPalette = previewPalette)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) currentPalette.accent else currentPalette.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(previewPalette.accent, CircleShape)
                        .border(1.dp, currentPalette.outlineVariant, CircleShape),
                )
                if (onDelete != null) {
                    DeleteCustomThemeBadge(onDelete = onDelete)
                }
            }
        }
    }
}

@Composable
private fun MiniThemeWindowPreview(previewPalette: OnyxPalette) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(MiniPreviewShape)
            .background(previewPalette.appBackground)
            .border(1.dp, previewPalette.outlineVariant, MiniPreviewShape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(previewPalette.headerBackground),
        )
        Row(modifier = Modifier.fillMaxWidth().weight(1f).padding(3.dp)) {
            Box(
                modifier = Modifier
                    .width(16.dp)
                    .fillMaxHeight()
                    .background(previewPalette.surfaceVariant, RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(3.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(previewPalette.surface, RoundedCornerShape(2.dp))
                    .padding(3.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = 0.65f)
                        .height(3.dp)
                        .background(previewPalette.accent, RoundedCornerShape(1.dp)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = 0.85f)
                        .height(3.dp)
                        .background(previewPalette.foreground.copy(alpha = 0.55f), RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

@Composable
private fun DeleteCustomThemeBadge(onDelete: () -> Unit) {
    val currentPalette = LocalOnyxPalette.current
    val hoverSrc = remember { MutableInteractionSource() }
    val clickSrc = remember { MutableInteractionSource() }
    val delHovered by hoverSrc.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .hoverable(hoverSrc)
            .pointerHoverIcon(PointerIcon.Hand)
            .background(
                if (delHovered) currentPalette.error.copy(alpha = 0.16f) else Color.Transparent,
                RoundedCornerShape(3.dp),
            )
            .clickable(interactionSource = clickSrc, indication = null, onClick = onDelete)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.action_delete),
            fontSize = 9.sp,
            color = if (delHovered) currentPalette.error else currentPalette.mutedForeground,
        )
    }
}

/**
 * 渲染 8 色精选强调色快捷点选色盘，带即时悬停边框高亮与手型光标。
 */
@Composable
internal fun AccentSwatchRow(draft: OnyxSettings, onChange: (OnyxSettings) -> Unit) {
    val palette = LocalOnyxPalette.current
    val currentAccent = draft.themeColors.accentArgb
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ONYX_ACCENT_SWATCHES.forEach { swatchArgb ->
            val hoverSrc = remember { MutableInteractionSource() }
            val clickSrc = remember { MutableInteractionSource() }
            val hovered by hoverSrc.collectIsHoveredAsState()
            val isSelected = currentAccent == swatchArgb
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .hoverable(hoverSrc)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .background(Color(swatchArgb), CircleShape)
                    .border(
                        width = if (isSelected || hovered) 2.dp else 1.dp,
                        color = when {
                            isSelected -> palette.foreground
                            hovered -> palette.accent
                            else -> palette.outline
                        },
                        shape = CircleShape,
                    )
                    .clickable(interactionSource = clickSrc, indication = null) {
                        onChange(draft.copy(themeColors = draft.themeColors.copy(accentArgb = swatchArgb)))
                    },
            )
        }
        if (currentAccent != null) {
            DialogTextButton(text = stringResource(Res.string.action_reset), fontSize = 11.sp) {
                onChange(draft.copy(themeColors = draft.themeColors.copy(accentArgb = null)))
            }
        }
    }
}

/**
 * 编辑全局界面颜色覆盖（窗口底层背景、面板背景、表头背景、主要文字、强调色、边框线），采用紧凑两列排版。
 */
@Composable
internal fun GlobalThemeColorSettings(draft: OnyxSettings, onChange: (OnyxSettings) -> Unit) {
    val palette = LocalOnyxPalette.current
    val colors = draft.themeColors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ColorOverrideField(
                    label = stringResource(Res.string.label_theme_color_app_bg),
                    value = colors.appBackgroundArgb,
                    defaultPreviewColor = palette.appBackground,
                ) {
                    onChange(draft.copy(themeColors = colors.copy(appBackgroundArgb = it)))
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                ColorOverrideField(
                    label = stringResource(Res.string.label_theme_color_surface),
                    value = colors.surfaceArgb,
                    defaultPreviewColor = palette.surface,
                ) {
                    onChange(draft.copy(themeColors = colors.copy(surfaceArgb = it)))
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ColorOverrideField(
                    label = stringResource(Res.string.label_theme_color_header_bg),
                    value = colors.headerBackgroundArgb,
                    defaultPreviewColor = palette.headerBackground,
                ) {
                    onChange(draft.copy(themeColors = colors.copy(headerBackgroundArgb = it)))
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                ColorOverrideField(
                    label = stringResource(Res.string.label_theme_color_foreground),
                    value = colors.foregroundArgb,
                    defaultPreviewColor = palette.foreground,
                ) {
                    onChange(draft.copy(themeColors = colors.copy(foregroundArgb = it)))
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ColorOverrideField(
                    label = stringResource(Res.string.label_theme_color_accent),
                    value = colors.accentArgb,
                    defaultPreviewColor = palette.accent,
                ) {
                    onChange(draft.copy(themeColors = colors.copy(accentArgb = it)))
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                ColorOverrideField(
                    label = stringResource(Res.string.label_theme_color_outline),
                    value = colors.outlineArgb,
                    defaultPreviewColor = palette.outline,
                ) {
                    onChange(draft.copy(themeColors = colors.copy(outlineArgb = it)))
                }
            }
        }
    }
}

/**
 * 编辑文件列表区域独立颜色，采用紧凑两列排版，支持带透明度的选中背景与斑马纹调色。
 */
@Composable
internal fun FileListColorSettings(draft: OnyxSettings, onChange: (OnyxSettings) -> Unit) {
    val palette = LocalOnyxPalette.current
    val colors = draft.fileListColors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ColorOverrideField(
                    label = stringResource(Res.string.label_file_list_background),
                    value = colors.backgroundArgb,
                    defaultPreviewColor = palette.surface,
                ) {
                    onChange(draft.copy(fileListColors = colors.copy(backgroundArgb = it)))
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                ColorOverrideField(
                    label = stringResource(Res.string.label_file_name_color),
                    value = colors.nameArgb,
                    defaultPreviewColor = palette.foreground,
                ) {
                    onChange(draft.copy(fileListColors = colors.copy(nameArgb = it)))
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ColorOverrideField(
                    label = stringResource(Res.string.label_selection_color),
                    value = colors.selectionArgb,
                    defaultPreviewColor = palette.selectionBackground,
                ) {
                    onChange(draft.copy(fileListColors = colors.copy(selectionArgb = it)))
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                ColorOverrideField(
                    label = stringResource(Res.string.label_inactive_selection_color),
                    value = colors.inactiveSelectionArgb,
                    defaultPreviewColor = palette.inactiveSelectionBackground,
                ) {
                    onChange(draft.copy(fileListColors = colors.copy(inactiveSelectionArgb = it)))
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ColorOverrideField(
                    label = stringResource(Res.string.label_zebra_color),
                    value = colors.zebraArgb,
                    defaultPreviewColor = palette.surfaceVariant,
                ) {
                    onChange(draft.copy(fileListColors = colors.copy(zebraArgb = it)))
                }
            }
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/** 解析主题预设对应的本地化名称。 */
@Composable
private fun themePresetLabel(preset: OnyxThemePreset): String = when (preset) {
    OnyxThemePreset.DEFAULT -> stringResource(Res.string.label_theme_preset_default)
    OnyxThemePreset.MIDNIGHT_OLED -> stringResource(Res.string.label_theme_preset_midnight_oled)
    OnyxThemePreset.NORDIC_FROST -> stringResource(Res.string.label_theme_preset_nordic_frost)
    OnyxThemePreset.DRACULA_PURPLE -> stringResource(Res.string.label_theme_preset_dracula_purple)
    OnyxThemePreset.SOLARIZED_WARM -> stringResource(Res.string.label_theme_preset_solarized_warm)
    OnyxThemePreset.FOREST_EMERALD -> stringResource(Res.string.label_theme_preset_forest_emerald)
}

private const val PRESET_GRID_COLUMNS = 3
private val PresetCardShape = RoundedCornerShape(4.dp)
private val MiniPreviewShape = RoundedCornerShape(3.dp)
