package com.oruke.onyx.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.component.RootIntent
import com.oruke.onyx.core.model.OnyxSettings
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_rename_preset_name
import onyx.composeapp.generated.resources.action_save_preset
import onyx.composeapp.generated.resources.action_delete_preset
import onyx.composeapp.generated.resources.action_new_preset
import org.jetbrains.compose.resources.stringResource

/**
 * 渲染预设选择与管理，所有操作经根组件状态流转和持久化。
 * @param state 当前批量重命名草稿。
 * @param settings 已保存的预设列表。
 * @param onIntent 根组件事件入口。
 */
@Composable
internal fun BatchRenamePresetBar(
    state: RootDialogState.BatchRename,
    settings: OnyxSettings,
    onIntent: (RootIntent) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            settings.batchRenamePresets.forEach { preset ->
                SettingsOption(
                    state.selectedPresetId == preset.id,
                    preset.name
                ) { onIntent(RootIntent.LoadBatchRenamePreset(preset.id)) }
            }
        }
        LabeledCustomizationField(stringResource(Res.string.label_rename_preset_name), state.presetName) {
            onIntent(RootIntent.UpdateBatchRenamePresetName(it))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DialogTextButton(stringResource(Res.string.action_new_preset)) {
                onIntent(RootIntent.NewBatchRenamePreset)
            }
            DialogTextButton(
                stringResource(Res.string.action_save_preset),
                enabled = state.presetName.isNotBlank() && state.validationMessage == null
            ) {
                onIntent(RootIntent.SaveBatchRenamePreset)
            }
            DialogTextButton(
                stringResource(Res.string.action_delete_preset),
                enabled = state.selectedPresetId != null
            ) {
                onIntent(RootIntent.DeleteBatchRenamePreset)
            }
        }
    }
}
