package com.oruke.onyx.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import org.jetbrains.jewel.ui.component.Text

// ── 带 Hover 动画的 Tab ─────────────────────────────────────────────────

@Composable
internal fun HoverTab(
    text: String,
    selected: Boolean,
    accent: Color,
    foreground: Color,
    fontSize: TextUnit = 11.sp,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bgColor by animateColorAsState(
        targetValue = when {
            selected -> accent.copy(alpha = 0.15f)
            isHovered -> accent.copy(alpha = 0.08f)
            else -> Color.Transparent
        },
        animationSpec = tween(150),
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            selected -> accent
            isHovered -> accent.copy(alpha = 0.4f)
            else -> Color.Transparent
        },
        animationSpec = tween(150),
    )
    val textColor by animateColorAsState(
        targetValue = when {
            selected -> accent
            isHovered -> accent.copy(alpha = 0.8f)
            else -> foreground
        },
        animationSpec = tween(150),
    )

    Box(
        modifier = Modifier
            .hoverable(interactionSource)
            .background(bgColor, RoundedCornerShape(4.dp))
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            fontSize = fontSize,
            color = textColor,
        )
    }
}

// ── 带 Hover 动画的按钮 ─────────────────────────────────────────────────

@Composable
internal fun HoverButton(
    text: String,
    emphasized: Boolean,
    accent: Color,
    surface: Color,
    foreground: Color,
    fontSize: TextUnit = 12.sp,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val restBg = if (emphasized) accent else surface
    val hoverBg = if (emphasized) accent.copy(alpha = 0.85f) else accent.copy(alpha = 0.12f)
    val contentColor = if (emphasized) Color.White else foreground

    val bgColor by animateColorAsState(
        targetValue = if (isHovered) hoverBg else restBg,
        animationSpec = tween(150),
    )

    Box(
        modifier = Modifier
            .hoverable(interactionSource)
            .background(bgColor, RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = fontSize,
            color = contentColor,
        )
    }
}

// ── 工具组件 ────────────────────────────────────────────────────────────

@Composable
internal fun LabeledField(
    label: String,
    value: String,
    labelFontSize: TextUnit = 11.sp,
    inputFontSize: TextUnit = 12.sp,
    onValueChange: (String) -> Unit,
) {
    val palette = LocalOnyxPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            fontSize = labelFontSize,
            color = palette.mutedForeground,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.inputBackground, RoundedCornerShape(4.dp))
                .border(1.dp, palette.outlineVariant, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            textStyle = TextStyle(color = palette.foreground, fontSize = inputFontSize),
            singleLine = true,
            cursorBrush = SolidColor(palette.accent),
        )
    }
}
