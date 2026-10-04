package com.oruke.onyx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.component.appendFileLabelRule
import com.oruke.onyx.app.component.replaceFileLabelRule
import com.oruke.onyx.core.model.FileLabelRule
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.VFileKind
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_add_rule
import onyx.composeapp.generated.resources.action_delete
import onyx.composeapp.generated.resources.label_rule_name
import onyx.composeapp.generated.resources.label_rule_name_contains
import onyx.composeapp.generated.resources.label_rule_extension
import onyx.composeapp.generated.resources.label_rule_all_kinds
import onyx.composeapp.generated.resources.label_column_type_directory
import onyx.composeapp.generated.resources.label_column_type_file
import onyx.composeapp.generated.resources.label_rule_name_color
import onyx.composeapp.generated.resources.label_rule_background
import onyx.composeapp.generated.resources.label_new_rule
import onyx.composeapp.generated.resources.label_rule_order_hint
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/**
 * 在滚动设置页中管理规则，修改仅发送草稿事件，文件匹配在组件完成。
 * @param draft 当前设置草稿。
 * @param onChange 草稿更新事件。
 */
@Composable
internal fun FileLabelSettings(draft: OnyxSettings, onChange: (OnyxSettings) -> Unit) {
    val newName = stringResource(Res.string.label_new_rule)
    Text(stringResource(Res.string.label_rule_order_hint))
    DialogTextButton(stringResource(Res.string.action_add_rule)) { onChange(draft.appendFileLabelRule(newName)) }
    draft.fileLabelRules.forEach { rule ->
        SettingsSection(rule.name) {
            FileLabelRuleFields(rule) { onChange(draft.replaceFileLabelRule(it)) }
            DialogTextButton(stringResource(Res.string.action_delete), destructive = true) {
                onChange(draft.copy(fileLabelRules = draft.fileLabelRules.filterNot { it.id == rule.id }))
            }
        }
    }
}

/**
 * 绘制单条规则的名称、匹配条件及颜色。
 * @param rule 当前规则草稿。
 * @param onChange 单规则更新事件。
 */
@Composable
private fun FileLabelRuleFields(rule: FileLabelRule, onChange: (FileLabelRule) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LabeledCustomizationField(
            stringResource(Res.string.label_rule_name),
            rule.name
        ) { onChange(rule.copy(name = it)) }
        LabeledCustomizationField(
            stringResource(Res.string.label_rule_name_contains),
            rule.matcher.nameContains.orEmpty()
        ) {
            onChange(rule.copy(matcher = rule.matcher.copy(nameContains = it.ifBlank { null })))
        }
        LabeledCustomizationField(stringResource(Res.string.label_rule_extension), rule.matcher.extension.orEmpty()) {
            onChange(rule.copy(matcher = rule.matcher.copy(extension = it.ifBlank { null })))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsOption(rule.matcher.kind == null, stringResource(Res.string.label_rule_all_kinds)) {
                onChange(rule.copy(matcher = rule.matcher.copy(kind = null)))
            }
            SettingsOption(rule.matcher.kind == VFileKind.FILE, stringResource(Res.string.label_column_type_file)) {
                onChange(rule.copy(matcher = rule.matcher.copy(kind = VFileKind.FILE)))
            }
            SettingsOption(
                rule.matcher.kind == VFileKind.DIRECTORY,
                stringResource(Res.string.label_column_type_directory)
            ) {
                onChange(rule.copy(matcher = rule.matcher.copy(kind = VFileKind.DIRECTORY)))
            }
        }
        FileLabelSizeFields(rule.matcher) { matcher -> onChange(rule.copy(matcher = matcher)) }
        ColorOverrideField(stringResource(Res.string.label_rule_name_color), rule.textColorArgb) {
            onChange(
                rule.copy(
                    textColorArgb = it
                )
            )
        }
        ColorOverrideField(
            stringResource(Res.string.label_rule_background),
            rule.backgroundColorArgb
        ) { onChange(rule.copy(backgroundColorArgb = it)) }
    }
}

/**
 * 绘制设置草稿中的普通文本字段。
 * @param label 已本地化字段名称。
 * @param value 组件持有的当前草稿文本。
 * @param onChange 字段修改事件。
 */
@Composable
internal fun LabeledCustomizationField(label: String, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label)
        OnyxTextInput(value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth())
    }
}
