package com.oruke.onyx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.oruke.onyx.core.model.FileLabelMatcher
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_apply
import onyx.composeapp.generated.resources.label_rule_min_size
import onyx.composeapp.generated.resources.label_rule_max_size
import onyx.composeapp.generated.resources.label_rule_size_invalid
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/**
 * 编辑规则的字节范围，非法或未提交的输入不会改变设置草稿。
 * @param matcher 组件设置草稿中已接受的匹配条件。
 * @param onChange 发送已校验的完整条件。
 */
@Composable
internal fun FileLabelSizeFields(matcher: FileLabelMatcher, onChange: (FileLabelMatcher) -> Unit) {
    var minimum by remember(matcher.minSizeBytes) { mutableStateOf(matcher.minSizeBytes?.toString().orEmpty()) }
    var maximum by remember(matcher.maxSizeBytes) { mutableStateOf(matcher.maxSizeBytes?.toString().orEmpty()) }
    val minValue = minimum.toLongOrNull()
    val maxValue = maximum.toLongOrNull()
    val minValid = minimum.isBlank() || (minimum.all { it in '0'..'9' } && minValue != null)
    val maxValid = maximum.isBlank() || (maximum.all { it in '0'..'9' } && maxValue != null)
    val invalid = !minValid || !maxValid || (minValue != null && maxValue != null && minValue > maxValue)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabeledCustomizationField(stringResource(Res.string.label_rule_min_size), minimum) { minimum = it }
        LabeledCustomizationField(stringResource(Res.string.label_rule_max_size), maximum) { maximum = it }
        if (invalid) Text(stringResource(Res.string.label_rule_size_invalid), color = LocalOnyxPalette.current.error)
        DialogTextButton(stringResource(Res.string.action_apply), enabled = !invalid) {
            onChange(matcher.copy(minSizeBytes = minValue, maxSizeBytes = maxValue))
        }
    }
}
