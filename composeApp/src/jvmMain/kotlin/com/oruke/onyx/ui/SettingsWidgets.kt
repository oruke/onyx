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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun SettingsNavItem(
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
internal fun SettingsSection(title: String, fontSize: TextUnit = 11.sp, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = fontSize, color = LocalOnyxPalette.current.foreground, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        content()
    }
}

@Composable
internal fun SettingsOption(selected: Boolean, text: String, fontSize: TextUnit = 11.sp, onClick: () -> Unit) {
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
internal fun SliderRow(label: String, value: Int, min: Int, max: Int, fontSize: TextUnit, onValueChange: (Int) -> Unit) {
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
