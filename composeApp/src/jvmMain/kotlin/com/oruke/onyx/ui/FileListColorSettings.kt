package com.oruke.onyx.ui

import androidx.compose.runtime.Composable
import com.oruke.onyx.core.model.OnyxSettings
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_file_list_background
import onyx.composeapp.generated.resources.label_file_name_color
import onyx.composeapp.generated.resources.label_selection_color
import onyx.composeapp.generated.resources.label_inactive_selection_color
import onyx.composeapp.generated.resources.label_zebra_color
import org.jetbrains.compose.resources.stringResource

/**
 * 通过设置草稿编辑文件区域颜色，默认跟随系统主题。
 * @param draft 当前设置草稿。
 * @param onChange 草稿更新事件。
 */
@Composable
internal fun FileListColorSettings(draft: OnyxSettings, onChange: (OnyxSettings) -> Unit) {
    val colors = draft.fileListColors
    ColorOverrideField(stringResource(Res.string.label_file_list_background), colors.backgroundArgb) {
        onChange(draft.copy(fileListColors = colors.copy(backgroundArgb = it)))
    }
    ColorOverrideField(stringResource(Res.string.label_file_name_color), colors.nameArgb) {
        onChange(draft.copy(fileListColors = colors.copy(nameArgb = it)))
    }
    ColorOverrideField(stringResource(Res.string.label_selection_color), colors.selectionArgb) {
        onChange(draft.copy(fileListColors = colors.copy(selectionArgb = it)))
    }
    ColorOverrideField(stringResource(Res.string.label_inactive_selection_color), colors.inactiveSelectionArgb) {
        onChange(draft.copy(fileListColors = colors.copy(inactiveSelectionArgb = it)))
    }
    ColorOverrideField(stringResource(Res.string.label_zebra_color), colors.zebraArgb) {
        onChange(draft.copy(fileListColors = colors.copy(zebraArgb = it)))
    }
}
