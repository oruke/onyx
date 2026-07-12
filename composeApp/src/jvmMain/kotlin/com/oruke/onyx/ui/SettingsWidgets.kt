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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun SettingsNavItem(
    text: String,
    selected: Boolean,
    accent: Color,
    foreground: Color,
    fontSize: TextUnit,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val background by animateColorAsState(
        targetValue = when {
            selected -> accent.copy(alpha = 0.14f)
            hovered -> accent.copy(alpha = 0.07f)
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = SETTINGS_COLOR_ANIMATION_MILLIS),
    )
    val textColor by animateColorAsState(
        targetValue = when {
            selected -> accent
            hovered -> accent.copy(alpha = 0.8f)
            else -> foreground
        },
        animationSpec = tween(durationMillis = SETTINGS_COLOR_ANIMATION_MILLIS),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(src)
            .background(background, SettingsNavShape)
            .clickable(src, null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            fontSize = fontSize,
            color = textColor,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/**
 * 绘制带标题的设置区块。
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
 * 绘制可选中的紧凑设置选项。
 *
 * @param selected 是否选中。
 * @param text 选项文案。
 * @param fontSize 文案字号。
 * @param onClick 点击回调。
 */
@Composable
internal fun SettingsOption(
    selected: Boolean,
    text: String,
    fontSize: TextUnit = 11.sp,
    onClick: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val background by animateColorAsState(
        targetValue = when {
            selected -> palette.accent.copy(alpha = 0.14f)
            hovered -> palette.accent.copy(alpha = 0.07f)
            else -> palette.surface
        },
        animationSpec = tween(durationMillis = SETTINGS_COLOR_ANIMATION_MILLIS),
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            selected -> palette.accent
            hovered -> palette.accent.copy(alpha = 0.4f)
            else -> palette.outlineVariant
        },
        animationSpec = tween(durationMillis = SETTINGS_COLOR_ANIMATION_MILLIS),
    )
    val textColor by animateColorAsState(
        targetValue = when {
            selected -> palette.accent
            hovered -> palette.accent.copy(alpha = 0.85f)
            else -> palette.foreground
        },
        animationSpec = tween(durationMillis = SETTINGS_COLOR_ANIMATION_MILLIS),
    )
    Box(
        modifier = Modifier
            .hoverable(src)
            .background(background, SettingsOptionShape)
            .border(1.dp, borderColor, SettingsOptionShape)
            .clickable(src, null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = textColor, fontSize = fontSize)
    }
}

/**
 * 绘制带固定宽度标签的整数滑块。
 *
 * @param label 字段标签。
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = label,
            fontSize = fontSize,
            color = LocalOnyxPalette.current.foreground,
            modifier = Modifier.width(SettingsSliderLabelWidth),
        )
        SettingsScaleSlider(value, min, max, onValueChange)
    }
}

/**
 * 绘制支持点击和拖动的整数范围滑块。
 *
 * @param value 当前值。
 * @param min 最小值。
 * @param max 最大值。
 * @param onValueChange 值变化回调。
 */
@Composable
private fun SettingsScaleSlider(
    value: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
) {
    val clampedValue = value.coerceIn(min, max)
    val fraction = ((clampedValue - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    val density = LocalDensity.current
    val sliderWidthPx = with(density) { SettingsSliderWidth.toPx() }
    Box(
        modifier = Modifier
            .width(SettingsSliderWidth)
            .height(SettingsSliderTouchHeight)
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
                .background(LocalOnyxPalette.current.outlineVariant, SettingsSliderTrackShape)
        )
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(SettingsSliderTrackHeight)
                .background(LocalOnyxPalette.current.accent, SettingsSliderTrackShape)
        )
        Box(
            Modifier
                .offset(x = (fraction * (SettingsSliderWidth - SettingsSliderThumbSize).value).dp)
                .size(SettingsSliderThumbSize)
                .background(LocalOnyxPalette.current.accent, SettingsSliderThumbShape)
        )
    }
}

/**
 * 将滑块水平坐标换算为约束后的整数值。
 *
 * @param positionPx 指针水平坐标。
 * @param sliderWidthPx 滑块像素宽度。
 * @param min 最小值。
 * @param max 最大值。
 * @return 约束到范围内的整数值。
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

private const val SETTINGS_COLOR_ANIMATION_MILLIS = 120
private val SettingsNavShape = RoundedCornerShape(5.dp)
private val SettingsOptionShape = RoundedCornerShape(4.dp)
private val SettingsSliderLabelWidth = 52.dp
private val SettingsSliderWidth = 240.dp
private val SettingsSliderTouchHeight = 16.dp
private val SettingsSliderTrackHeight = 3.dp
private val SettingsSliderTrackShape = RoundedCornerShape(1.dp)
private val SettingsSliderThumbSize = 8.dp
private val SettingsSliderThumbShape = RoundedCornerShape(4.dp)
