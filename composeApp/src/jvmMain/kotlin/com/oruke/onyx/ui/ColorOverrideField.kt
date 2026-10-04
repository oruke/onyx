package com.oruke.onyx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_apply
import onyx.composeapp.generated.resources.action_reset
import onyx.composeapp.generated.resources.label_color_hex_hint
import onyx.composeapp.generated.resources.label_color_hex_invalid
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/**
 * 编辑单个颜色覆盖；输入必须显式应用到设置草稿，空值恢复系统主题。
 * @param label 字段的本地化名称。
 * @param value 当前已接受的 ARGB 值。
 * @param onChange 将有效颜色写入组件设置草稿。
 */
@Composable
internal fun ColorOverrideField(label: String, value: Long?, onChange: (Long?) -> Unit) {
    var input by remember(value) { mutableStateOf(value?.toString(HEX_RADIX)?.padStart(ARGB_DIGITS, '0').orEmpty()) }
    val parsed = parseColorOverride(input)
    val invalid = input.isNotBlank() && parsed == null
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OnyxTextInput(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f))
            DialogTextButton(stringResource(Res.string.action_apply), enabled = input.isBlank() || parsed != null) {
                onChange(parsed)
            }
            DialogTextButton(stringResource(Res.string.action_reset)) { input = ""; onChange(null) }
        }
        Text(
            stringResource(if (invalid) Res.string.label_color_hex_invalid else Res.string.label_color_hex_hint),
            color = if (invalid) LocalOnyxPalette.current.error else LocalOnyxPalette.current.mutedForeground,
        )
    }
}

/**
 * 将用户输入的 RGB 或 ARGB 十六进制转换为持久化值。
 * @param input 可以包含前导井号的六位 RGB 或八位 ARGB。
 * @return 合法颜色；空输入和非法输入均为空，调用者负责区分并显示错误。
 */
internal fun parseColorOverride(input: String): Long? {
    val digits = input.trim().removePrefix("#")
    if (digits.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    return when (digits.length) {
        RGB_DIGITS -> digits.toLongOrNull(HEX_RADIX)?.let { it or OPAQUE_ALPHA_MASK }
        ARGB_DIGITS -> digits.toLongOrNull(HEX_RADIX)
        else -> null
    }
}

/** RGB 表示所需的十六进制位数。 */
private const val RGB_DIGITS = 6

/** ARGB 表示所需的十六进制位数。 */
private const val ARGB_DIGITS = 8

/** 颜色字段使用的进制。 */
private const val HEX_RADIX = 16

/** RGB 输入使用完全不透明的 Alpha。 */
private const val OPAQUE_ALPHA_MASK = 0xFF000000L
