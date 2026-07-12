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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun DialogFrame(
    title: String,
    body: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalOnyxPalette.current.appBackground)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = LocalOnyxPalette.current.foreground,
        )
        Box(modifier = Modifier.weight(1f, fill = true)) {
            body()
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions()
        }
    }
}

@Composable
internal fun DialogTextButton(
    text: String,
    emphasized: Boolean = false,
    destructive: Boolean = false,
    focused: Boolean = false,
    enabled: Boolean = true,
    fontSize: TextUnit = 12.sp,
    onClick: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val baseBg = when {
        destructive && emphasized -> palette.error
        emphasized -> palette.accent
        else -> palette.surfaceVariant
    }
    val bg by animateColorAsState(
        if (hovered || focused) baseBg.copy(alpha = baseBg.alpha * 0.85f) else baseBg, tween(120),
    )
    val contentColor = when {
        !enabled -> palette.disabledForeground
        emphasized -> Color.White
        destructive -> palette.error
        else -> palette.foreground
    }
    val focusBorder = if (focused) Modifier.border(1.5.dp, palette.accent, RoundedCornerShape(6.dp)) else Modifier
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .hoverable(src, enabled = enabled)
            .then(if (enabled) Modifier else Modifier.alpha(DISABLED_CONTROL_ALPHA))
            .background(bg, RoundedCornerShape(6.dp))
            .then(focusBorder)
            .clickable(src, null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, fontSize = fontSize, color = contentColor)
    }
}

private const val DISABLED_CONTROL_ALPHA = 0.62f

@Composable
internal fun ApplyToAllToggle(
    checked: Boolean,
    text: String,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .border(1.dp, LocalOnyxPalette.current.outline, RoundedCornerShape(4.dp))
                .background(
                    if (checked) LocalOnyxPalette.current.accent else Color.Transparent,
                    RoundedCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text(text = "✓", fontSize = 10.sp, color = Color.White)
            }
        }
        Text(
            text = text,
            fontSize = 12.sp,
            color = LocalOnyxPalette.current.foreground,
        )
    }
}
