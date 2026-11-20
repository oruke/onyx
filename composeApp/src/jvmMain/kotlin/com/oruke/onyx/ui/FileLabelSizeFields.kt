package com.oruke.onyx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.oruke.onyx.core.model.FileLabelMatcher
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_rule_max_size
import onyx.composeapp.generated.resources.label_rule_min_size
import onyx.composeapp.generated.resources.label_rule_size_invalid
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/**
 * 编辑文件标签规则的字节大小范围，合法输入即时同步至设置草稿（无需额外点击内层应用按钮），
 * 非法输入实时展示错误提示且不破坏已有合法范围。
 *
 * @param matcher 组件设置草稿中已接受的匹配条件。
 * @param onChange 发送已校验的完整条件。
 */
@Composable
internal fun FileLabelSizeFields(
    matcher: FileLabelMatcher,
    onChange: (FileLabelMatcher) -> Unit,
) {
    var minimum by remember { mutableStateOf(matcher.minSizeBytes?.toString().orEmpty()) }
    var maximum by remember { mutableStateOf(matcher.maxSizeBytes?.toString().orEmpty()) }

    LaunchedEffect(matcher.minSizeBytes, matcher.maxSizeBytes) {
        val expectedMin = matcher.minSizeBytes?.toString().orEmpty()
        val expectedMax = matcher.maxSizeBytes?.toString().orEmpty()
        if (minimum.toLongOrNull() != matcher.minSizeBytes && !(minimum.isBlank() && matcher.minSizeBytes == null)) {
            minimum = expectedMin
        }
        if (maximum.toLongOrNull() != matcher.maxSizeBytes && !(maximum.isBlank() && matcher.maxSizeBytes == null)) {
            maximum = expectedMax
        }
    }

    val minTrimmed = minimum.trim()
    val maxTrimmed = maximum.trim()
    val minValue = minTrimmed.toLongOrNull()
    val maxValue = maxTrimmed.toLongOrNull()
    val minValid = minTrimmed.isEmpty() || (minTrimmed.all { it in '0'..'9' } && minValue != null)
    val maxValid = maxTrimmed.isEmpty() || (maxTrimmed.all { it in '0'..'9' } && maxValue != null)
    val invalid = !minValid || !maxValid || (minValue != null && maxValue != null && minValue > maxValue)

    fun syncIfValid(nextMinText: String, nextMaxText: String) {
        val trimmedMin = nextMinText.trim()
        val trimmedMax = nextMaxText.trim()
        val parsedMin = if (trimmedMin.isEmpty()) null else trimmedMin.toLongOrNull()
        val parsedMax = if (trimmedMax.isEmpty()) null else trimmedMax.toLongOrNull()
        val okMin = trimmedMin.isEmpty() || (trimmedMin.all { it in '0'..'9' } && parsedMin != null)
        val okMax = trimmedMax.isEmpty() || (trimmedMax.all { it in '0'..'9' } && parsedMax != null)
        if (okMin && okMax && (parsedMin == null || parsedMax == null || parsedMin <= parsedMax)) {
            if (parsedMin != matcher.minSizeBytes || parsedMax != matcher.maxSizeBytes) {
                onChange(matcher.copy(minSizeBytes = parsedMin, maxSizeBytes = parsedMax))
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabeledCustomizationField(stringResource(Res.string.label_rule_min_size), minimum) { next ->
            minimum = next
            syncIfValid(next, maximum)
        }
        LabeledCustomizationField(stringResource(Res.string.label_rule_max_size), maximum) { next ->
            maximum = next
            syncIfValid(minimum, next)
        }
        if (invalid) {
            Text(
                text = stringResource(Res.string.label_rule_size_invalid),
                color = LocalOnyxPalette.current.errorForeground,
            )
        }
    }
}
