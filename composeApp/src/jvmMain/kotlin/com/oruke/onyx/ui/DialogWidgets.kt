package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import com.oruke.onyx.ui.theme.rememberOnyxButtonStyle
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalTextStyle
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.defaultButtonStyle
import org.jetbrains.jewel.ui.theme.outlinedButtonStyle

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

/**
 * 统一使用 Jewel 默认按钮组件（[DefaultButton] / [OutlinedButton]）渲染对话框与设置操作按钮。
 */
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
    val modifier = Modifier
        .padding(start = 6.dp)
        .then(
            if (focused) {
                Modifier.border(1.5.dp, palette.accent, ButtonFocusShape)
            } else {
                Modifier
            },
        )
    val compact = fontSize.value <= 11f
    if (emphasized) {
        DefaultButton(
            onClick = onClick,
            enabled = enabled,
            style = rememberOnyxButtonStyle(JewelTheme.defaultButtonStyle, compact = compact),
            modifier = modifier,
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
            style = rememberOnyxButtonStyle(JewelTheme.outlinedButtonStyle, compact = compact),
            modifier = modifier,
        ) {
            Text(
                text = text,
                fontSize = fontSize,
                color = when {
                    !enabled -> palette.disabledForeground
                    destructive -> palette.error
                    else -> Color.Unspecified
                },
                style = LocalTextStyle.current,
            )
        }
    }
}

private val ButtonFocusShape = RoundedCornerShape(4.dp)

@Composable
internal fun ApplyToAllToggle(
    checked: Boolean,
    text: String,
    onToggle: () -> Unit,
) {
    CheckboxRow(
        text = text,
        checked = checked,
        onCheckedChange = { onToggle() },
    )
}
