package com.oruke.onyx.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * 统一单行文本输入框，按实际字号行高弹性撑开最小高度以避免 150% DPI 或大字号下垂直裁切文字。
 *
 * @param value 当前文本值。
 * @param onValueChange 文本变更回调。
 * @param modifier 外部布局修饰符。
 * @param fontSize 输入字号，默认跟随 [LocalOnyxAppearance.current.listFontSize]。
 * @param height 期望最小高度（当字号行高更高时自动抬高）。
 * @param password 是否以密码掩码显示。
 * @param enabled 是否启用编辑。
 * @param placeholder 空态占位提示。
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun OnyxTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = LocalOnyxAppearance.current.listFontSize,
    height: Dp = 28.dp,
    password: Boolean = false,
    enabled: Boolean = true,
    placeholder: String? = null,
) {
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }

    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            fieldValue = TextFieldValue(value, TextRange(value.length))
        }
    }

    val palette = LocalOnyxPalette.current
    val density = LocalDensity.current
    val minHeight = maxOf(height, with(density) { (fontSize * 1.45f).toDp() } + 10.dp)
    TextField(
        value = fieldValue,
        enabled = enabled,
        onValueChange = { nextValue ->
            fieldValue = nextValue
            if (nextValue.text != value) {
                onValueChange(nextValue.text)
            }
        },
        placeholder = placeholder?.let { text ->
            { Text(text = text, fontSize = fontSize, color = palette.mutedForeground) }
        },
        modifier = modifier.heightIn(min = minHeight),
        textStyle = TextStyle(fontSize = fontSize),
        visualTransformation = if (password) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
    )
}
