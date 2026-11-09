package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.rememberOnyxButtonStyle
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalTextStyle
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.defaultButtonStyle
import org.jetbrains.jewel.ui.theme.outlinedButtonStyle

/**
 * 绘制设置窗口左侧扁平紧凑分类导航条目，与主窗口 [SidebarLocationItem] 保持完全一致的即时悬停与选中反馈，
 * 不使用 `animateColorAsState` 跨越 `Color.Transparent`，彻底消除悬停闪烁。
 *
 * @param text 分类名称。
 * @param selected 当前是否选中。
 * @param accent 主题强调色。
 * @param foreground 默认文字色。
 * @param fontSize 字号。
 * @param onClick 点击切换回调。
 */
@Suppress("UnusedParameter")
@Composable
internal fun SettingsNavItem(
    text: String,
    selected: Boolean,
    accent: Color,
    foreground: Color,
    fontSize: TextUnit,
    onClick: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val hoverSrc = remember { MutableInteractionSource() }
    val clickSrc = remember { MutableInteractionSource() }
    val hovered by hoverSrc.collectIsHoveredAsState()
    val background = when {
        selected -> palette.selectionBackground
        hovered -> palette.rowHoverBackground.copy(alpha = 0.28f)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(hoverSrc)
            .background(background, SettingsNavShape)
            .clickable(interactionSource = clickSrc, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            fontSize = fontSize,
            color = if (selected) palette.selectionForeground else foreground,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/**
 * 绘制扁平紧凑模式的设置分组区块（小标题 + 1px 水平分割线 + 紧凑设置项），与整体工具窗口风格统一。
 *
 * @param title 分组标题。
 * @param subtitle 可选的补充说明文案。
 * @param fontSize 标题字号。
 * @param content 分组内部设置项内容。
 */
@Composable
internal fun SettingsGroupCard(
    title: String,
    subtitle: String? = null,
    fontSize: TextUnit = 12.sp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                fontSize = fontSize,
                color = palette.foreground,
                fontWeight = FontWeight.SemiBold,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = palette.mutedForeground,
                )
            }
        }
        Divider(
            Orientation.Horizontal,
            color = palette.outlineVariant,
            modifier = Modifier.fillMaxWidth().height(1.dp),
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/**
 * 绘制扁平紧凑的布尔设置行：左侧标题、整行即时轻量悬停高亮、右侧使用 Jewel 默认按钮组件切换。
 *
 * @param title 设置项名称。
 * @param value 当前布尔值。
 * @param enabledText 启用态按钮文案。
 * @param disabledText 禁用态按钮文案。
 * @param labelFontSize 字号。
 * @param enabled 是否允许修改。
 * @param onValueChange 值变更回调。
 */
@Composable
internal fun SettingsToggleRow(
    title: String,
    value: Boolean,
    enabledText: String,
    disabledText: String,
    labelFontSize: TextUnit,
    enabled: Boolean = true,
    onValueChange: (Boolean) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val hoverSrc = remember { MutableInteractionSource() }
    val hovered by hoverSrc.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(hoverSrc, enabled = enabled)
            .background(
                if (hovered && enabled) palette.rowHoverBackground.copy(alpha = 0.24f) else Color.Transparent,
                SettingsRowShape,
            )
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            fontSize = labelFontSize,
            color = if (enabled) palette.foreground else palette.disabledForeground,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SettingsOption(
                selected = value,
                text = enabledText,
                fontSize = labelFontSize,
                enabled = enabled,
                onClick = { if (enabled) onValueChange(true) },
            )
            SettingsOption(
                selected = !value,
                text = disabledText,
                fontSize = labelFontSize,
                enabled = enabled,
                onClick = { if (enabled) onValueChange(false) },
            )
        }
    }
}

/**
 * 绘制带标题的紧凑设置区块。
 *
 * @param title 区块标题。
 * @param fontSize 标题字号。
 * @param content 区块内容。
 */
@Composable
internal fun SettingsSection(
    title: String,
    fontSize: TextUnit = 11.sp,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = title,
            fontSize = fontSize,
            color = LocalOnyxPalette.current.foreground,
            fontWeight = FontWeight.Medium,
        )
        content()
    }
}

/**
 * 使用 Jewel 默认按钮组件（选中态 [DefaultButton]、未选中态 [OutlinedButton]）渲染紧凑设置选项，
 * 与任务中心和搜索窗口的筛选按钮组件完全一致。
 *
 * @param selected 是否选中。
 * @param text 选项文案。
 * @param fontSize 文案字号。
 * @param enabled 是否启用。
 * @param onClick 点击回调。
 */
@Composable
internal fun SettingsOption(
    selected: Boolean,
    text: String,
    fontSize: TextUnit = 11.sp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (selected) {
        DefaultButton(
            onClick = onClick,
            enabled = enabled,
            style = rememberOnyxButtonStyle(JewelTheme.defaultButtonStyle, compact = true),
        ) {
            Text(
                text = text,
                fontSize = fontSize,
                style = LocalTextStyle.current,
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            style = rememberOnyxButtonStyle(JewelTheme.outlinedButtonStyle, compact = true),
        ) {
            Text(
                text = text,
                fontSize = fontSize,
                style = LocalTextStyle.current,
            )
        }
    }
}

/**
 * 绘制带即时轻量悬停反馈与紧凑滑块的标准滑块行。
 *
 * @param label 当前数值文案（如 `100%`）。
 * @param value 当前值。
 * @param min 最小值。
 * @param max 最大值。
 * @param fontSize 标签字号。
 * @param onValueChange 值变化回调。
 */
@Composable
internal fun SliderRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    fontSize: TextUnit,
    onValueChange: (Int) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val hoverSrc = remember { MutableInteractionSource() }
    val hovered by hoverSrc.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(hoverSrc)
            .background(
                if (hovered) palette.rowHoverBackground.copy(alpha = 0.24f) else Color.Transparent,
                SettingsRowShape,
            )
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            color = if (hovered) palette.accent else palette.foreground,
            modifier = Modifier.width(SettingsSliderLabelWidth),
        )
        SettingsScaleSlider(value = value, min = min, max = max, parentHovered = hovered, onValueChange = onValueChange)
    }
}

/**
 * 绘制支持点击、拖动与即时悬停高亮的扁平紧凑整数滑块。
 */
@Composable
private fun SettingsScaleSlider(
    value: Int,
    min: Int,
    max: Int,
    parentHovered: Boolean,
    onValueChange: (Int) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val hoverSrc = remember { MutableInteractionSource() }
    val sliderHovered by hoverSrc.collectIsHoveredAsState()
    val isHighlighted = parentHovered || sliderHovered
    val clampedValue = value.coerceIn(min, max)
    val fraction = ((clampedValue - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    val density = LocalDensity.current
    val sliderWidthPx = with(density) { SettingsSliderWidth.toPx() }
    Box(
        modifier = Modifier
            .width(SettingsSliderWidth)
            .height(SettingsSliderTouchHeight)
            .hoverable(hoverSrc)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(min, max, sliderWidthPx) {
                detectDragGestures { change, _ ->
                    onValueChange(sliderValueForPosition(change.position.x, sliderWidthPx, min, max))
                }
            }
            .pointerInput(min, max, sliderWidthPx) {
                detectTapGestures { offset ->
                    onValueChange(sliderValueForPosition(offset.x, sliderWidthPx, min, max))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(SettingsSliderTrackHeight)
                .background(palette.outlineVariant, SettingsSliderTrackShape),
        )
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(SettingsSliderTrackHeight)
                .background(
                    if (isHighlighted) palette.accentVariant else palette.accent,
                    SettingsSliderTrackShape,
                ),
        )
        Box(
            Modifier
                .offset(x = (fraction * (SettingsSliderWidth - SettingsSliderThumbSize).value).dp)
                .size(SettingsSliderThumbSize)
                .background(
                    if (isHighlighted) palette.accentVariant else palette.accent,
                    CircleShape,
                ),
        )
    }
}

/**
 * 将滑块水平坐标换算为约束后的整数值。
 */
private fun sliderValueForPosition(
    positionPx: Float,
    sliderWidthPx: Float,
    min: Int,
    max: Int,
): Int {
    val fraction = (positionPx / sliderWidthPx).coerceIn(0f, 1f)
    return (min + (fraction * (max - min)).toInt()).coerceIn(min, max)
}

private val SettingsNavShape = RoundedCornerShape(4.dp)
private val SettingsRowShape = RoundedCornerShape(4.dp)
private val SettingsSliderLabelWidth = 52.dp
private val SettingsSliderWidth = 240.dp
private val SettingsSliderTouchHeight = 16.dp
private val SettingsSliderTrackHeight = 4.dp
private val SettingsSliderTrackShape = RoundedCornerShape(2.dp)
private val SettingsSliderThumbSize = 10.dp
