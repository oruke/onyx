package com.oruke.onyx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.oruke.onyx.core.model.BatchRenameCaseMode
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_batch_rename_find
import onyx.composeapp.generated.resources.label_batch_rename_replace
import onyx.composeapp.generated.resources.label_batch_rename_regex
import onyx.composeapp.generated.resources.label_batch_rename_counter_start
import onyx.composeapp.generated.resources.label_batch_rename_counter_digits
import onyx.composeapp.generated.resources.label_batch_rename_template_hint
import onyx.composeapp.generated.resources.label_case_keep
import onyx.composeapp.generated.resources.label_case_lowercase
import onyx.composeapp.generated.resources.label_case_uppercase
import onyx.composeapp.generated.resources.label_case_titlecase
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/**
 * 渲染模板预设的全部参数，切换或重新保存不会丢失序号和大小写规则。
 * @param editor 组件草稿与事件的表单适配器。
 */
@Composable
internal fun BatchRenameTemplateEditor(editor: BatchRenameEditorState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LabeledCustomizationField(stringResource(Res.string.label_batch_rename_find), editor.findText) {
            editor.findText = it
        }
        LabeledCustomizationField(stringResource(Res.string.label_batch_rename_replace), editor.replaceText) {
            editor.replaceText = it
        }
        Text(stringResource(Res.string.label_batch_rename_template_hint))
        SettingsOption(editor.useRegex, stringResource(Res.string.label_batch_rename_regex)) {
            editor.useRegex = !editor.useRegex
        }
        LabeledCustomizationField(stringResource(Res.string.label_batch_rename_counter_start), editor.counterStart) {
            editor.counterStart = it
        }
        LabeledCustomizationField(stringResource(Res.string.label_batch_rename_counter_digits), editor.counterDigits) {
            editor.counterDigits = it
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                BatchRenameCaseMode.KEEP to Res.string.label_case_keep,
                BatchRenameCaseMode.LOWER to Res.string.label_case_lowercase,
                BatchRenameCaseMode.UPPER to Res.string.label_case_uppercase,
                BatchRenameCaseMode.TITLE to Res.string.label_case_titlecase,
            ).forEach { (mode, resource) ->
                SettingsOption(editor.templateCaseMode == mode, stringResource(resource)) {
                    editor.templateCaseMode = mode
                }
            }
        }
    }
}
