package com.oruke.onyx.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.TextUnit
import com.oruke.onyx.core.model.OnyxSettings
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_auto_close_task_center
import onyx.composeapp.generated.resources.label_auto_show_task_center
import onyx.composeapp.generated.resources.label_section_task_feedback
import org.jetbrains.compose.resources.stringResource

/**
 * 绘制后台任务弹窗与自动收起策略设置卡片。
 *
 * @param draft 当前设置草稿。
 * @param onDraftChange 修改草稿回调。
 * @param labelFontSize 设置标签字号。
 * @param enabledText 启用态文案。
 * @param disabledText 禁用态文案。
 */
@Composable
internal fun TaskFeedbackSettingsCard(
    draft: OnyxSettings,
    onDraftChange: (OnyxSettings) -> Unit,
    labelFontSize: TextUnit,
    enabledText: String,
    disabledText: String,
) {
    SettingsGroupCard(
        title = stringResource(Res.string.label_section_task_feedback),
        fontSize = labelFontSize,
    ) {
        SettingsToggleRow(
            title = stringResource(Res.string.label_auto_show_task_center),
            value = draft.autoShowTaskCenter,
            enabledText = enabledText,
            disabledText = disabledText,
            labelFontSize = labelFontSize,
            onValueChange = { value ->
                onDraftChange(draft.copy(autoShowTaskCenter = value))
            },
        )
        SettingsToggleRow(
            title = stringResource(Res.string.label_auto_close_task_center),
            value = draft.autoCloseTaskCenterWhenFinished,
            enabledText = enabledText,
            disabledText = disabledText,
            labelFontSize = labelFontSize,
            onValueChange = { value ->
                onDraftChange(draft.copy(autoCloseTaskCenterWhenFinished = value))
            },
        )
    }
}
