package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.oruke.onyx.core.model.CommandShortcutOverride
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_shortcut_capture
import onyx.composeapp.generated.resources.label_shortcut_disabled
import onyx.composeapp.generated.resources.label_shortcut_reassigned_notice
import onyx.composeapp.generated.resources.label_shortcut_recording
import onyx.composeapp.generated.resources.label_shortcut_reset_default
import onyx.composeapp.generated.resources.label_shortcut_unsupported_key
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/**
 * 快捷键录制过程中的即时内联反馈状态。
 */
internal sealed interface ShortcutRecordingFeedback {
    /** 按下了不在命令快捷键支持白名单中的按键。 */
    data object UnsupportedKey : ShortcutRecordingFeedback

    /** 录制的快捷键与已有命令冲突，系统已自动从原命令迁移。 */
    data class Reassigned(
        /** 新绑定的快捷键组合。 */
        val shortcut: OnyxShortcut,
        /** 原先持有该快捷键的命令。 */
        val previousCommand: OnyxCommand,
    ) : ShortcutRecordingFeedback
}

/**
 * 渲染命令快捷键列表并支持录制、不支持键提示、冲突迁移提示、恢复默认与禁用。
 *
 * @param settings 当前设置草稿。
 * @param onDraftChange 更新设置草稿回调。
 * @param fontSize 正文字号。
 * @param labelFontSize 标签字号。
 * @param recordingCommand 由外层设置窗口提升的当前录制命令（用于拦截根级 Escape）。
 * @param onRecordingCommandChange 更新录制状态的回调；为空时退回局部状态管理。
 * @param feedback 由外层设置窗口提升的录制反馈；为空回调时退回局部状态管理。
 * @param onFeedbackChange 更新录制反馈的回调。
 */
@Composable
internal fun CommandShortcutSettings(
    settings: OnyxSettings,
    onDraftChange: (OnyxSettings) -> Unit,
    fontSize: TextUnit,
    labelFontSize: TextUnit,
    recordingCommand: OnyxCommand? = null,
    onRecordingCommandChange: ((OnyxCommand?) -> Unit)? = null,
    feedback: ShortcutRecordingFeedback? = null,
    onFeedbackChange: ((ShortcutRecordingFeedback?) -> Unit)? = null,
) {
    var localRecordingCommand by remember { mutableStateOf<OnyxCommand?>(null) }
    var localFeedback by remember { mutableStateOf<ShortcutRecordingFeedback?>(null) }
    val activeRecordingCommand = if (onRecordingCommandChange != null) recordingCommand else localRecordingCommand
    val setRecordingCommand: (OnyxCommand?) -> Unit = onRecordingCommandChange ?: { next ->
        localRecordingCommand = next
    }
    val activeFeedback = if (onFeedbackChange != null) feedback else localFeedback
    val setFeedback: (ShortcutRecordingFeedback?) -> Unit = onFeedbackChange ?: { next ->
        localFeedback = next
    }
    val focusRequester = remember { FocusRequester() }
    val shortcutMap = remember(settings.commandShortcutOverrides) {
        commandShortcutMapFromSettings(settings.commandShortcutOverrides)
    }

    LaunchedEffect(activeRecordingCommand) {
        if (activeRecordingCommand != null) {
            focusRequester.requestFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                handleShortcutRecordingKeyEvent(
                    event = event,
                    recordingCommand = activeRecordingCommand,
                    settings = settings,
                    onDraftChange = onDraftChange,
                    onRecordingCommandChange = setRecordingCommand,
                    onFeedback = setFeedback,
                )
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        activeFeedback?.let { currentFeedback ->
            ShortcutRecordingFeedbackBanner(
                feedback = currentFeedback,
                fontSize = labelFontSize,
            )
        }
        OnyxCommandRegistry.paneCommands.forEach { spec ->
            CommandShortcutRow(
                spec = spec,
                shortcut = shortcutMap.shortcutFor(spec.command),
                recording = activeRecordingCommand == spec.command,
                fontSize = fontSize,
                labelFontSize = labelFontSize,
                onCapture = {
                    setFeedback(null)
                    setRecordingCommand(spec.command)
                },
                onReset = {
                    setRecordingCommand(null)
                    setFeedback(null)
                    onDraftChange(settings.resetCommandShortcut(spec.command))
                },
                onDisable = {
                    setRecordingCommand(null)
                    setFeedback(null)
                    onDraftChange(settings.withCommandShortcut(spec.command, shortcut = null))
                },
            )
        }
    }
}

/**
 * 渲染快捷键录制的不支持按键或冲突迁移内联提示条。
 *
 * @param feedback 当前录制反馈。
 * @param fontSize 提示文字字号。
 */
@Composable
private fun ShortcutRecordingFeedbackBanner(
    feedback: ShortcutRecordingFeedback,
    fontSize: TextUnit,
) {
    val palette = LocalOnyxPalette.current
    val isWarning = feedback is ShortcutRecordingFeedback.UnsupportedKey
    val message = when (feedback) {
        ShortcutRecordingFeedback.UnsupportedKey -> stringResource(Res.string.label_shortcut_unsupported_key)
        is ShortcutRecordingFeedback.Reassigned -> {
            val shortcutText = onyxShortcutHint(feedback.shortcut).orEmpty()
            val previousLabel = OnyxCommandRegistry.findSpec(feedback.previousCommand)
                ?.let { spec -> stringResource(spec.label) }
                ?: feedback.previousCommand.name
            stringResource(Res.string.label_shortcut_reassigned_notice, shortcutText, previousLabel)
        }
    }
    val borderColor = if (isWarning) palette.error else palette.accent
    val textColor = if (isWarning) palette.errorForeground else palette.foreground
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("shortcut-recording-feedback")
            .background(palette.surfaceVariant, RoundedCornerShape(4.dp))
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            fontSize = fontSize,
            color = textColor,
        )
    }
}

/**
 * 统一处理快捷键录制期间的按键事件：按 Escape 取消录制，按纯修饰键等待组合主键，
 * 按不支持按键输出内联提示，按有效组合键写入草稿并在冲突迁移时返回提示。
 *
 * @param event 键盘事件。
 * @param recordingCommand 当前正在录制的命令；为 `null` 时不拦截。
 * @param settings 当前设置草稿。
 * @param onDraftChange 更新设置草稿回调。
 * @param onRecordingCommandChange 更新录制状态回调。
 * @param onFeedback 可选录制反馈回调（不支持按键 / 冲突自动迁移）。
 * @return 事件已被录制流程消费时返回 `true`。
 */
internal fun handleShortcutRecordingKeyEvent(
    event: KeyEvent,
    recordingCommand: OnyxCommand?,
    settings: OnyxSettings,
    onDraftChange: (OnyxSettings) -> Unit,
    onRecordingCommandChange: (OnyxCommand?) -> Unit,
    onFeedback: ((ShortcutRecordingFeedback?) -> Unit)? = null,
): Boolean {
    val command = recordingCommand ?: return false
    if (event.type != KeyEventType.KeyDown) {
        return false
    }
    if (event.key == Key.Escape) {
        onRecordingCommandChange(null)
        onFeedback?.invoke(null)
        return true
    }
    if (event.key.isShortcutModifierKey()) {
        return true
    }
    val shortcut = event.toOnyxShortcutOrNull()
    if (shortcut == null) {
        onFeedback?.invoke(ShortcutRecordingFeedback.UnsupportedKey)
        return true
    }
    val currentMap = commandShortcutMapFromSettings(settings.commandShortcutOverrides)
    val conflictingCommand = OnyxCommandRegistry.paneCommands
        .map { spec -> spec.command }
        .firstOrNull { existingCommand ->
            existingCommand != command && currentMap.shortcutFor(existingCommand) == shortcut
        }
    onDraftChange(settings.withCommandShortcut(command, shortcut))
    onRecordingCommandChange(null)
    onFeedback?.invoke(
        conflictingCommand?.let { previous ->
            ShortcutRecordingFeedback.Reassigned(shortcut = shortcut, previousCommand = previous)
        },
    )
    return true
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandShortcutRow(
    spec: OnyxCommandSpec,
    shortcut: OnyxShortcut?,
    recording: Boolean,
    fontSize: TextUnit,
    labelFontSize: TextUnit,
    onCapture: () -> Unit,
    onReset: () -> Unit,
    onDisable: () -> Unit,
) {
    val palette = LocalOnyxPalette.current
    val actionFontSize = LocalOnyxAppearance.current.feedbackFontSize
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(spec.label),
            fontSize = labelFontSize,
            color = palette.foreground,
            modifier = Modifier.weight(1f).widthIn(min = 110.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (recording) {
                stringResource(Res.string.label_shortcut_recording)
            } else {
                onyxShortcutHint(shortcut) ?: stringResource(Res.string.label_shortcut_disabled)
            },
            fontSize = fontSize,
            color = if (shortcut == null && !recording) palette.disabledForeground else palette.mutedForeground,
            modifier = Modifier.widthIn(min = 84.dp, max = 140.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DialogTextButton(
                text = stringResource(Res.string.label_shortcut_capture),
                fontSize = actionFontSize,
                focused = recording,
                modifier = Modifier.testTag("shortcut-capture-${spec.command.name}"),
                onClick = onCapture,
            )
            DialogTextButton(
                text = stringResource(Res.string.label_shortcut_reset_default),
                fontSize = actionFontSize,
                onClick = onReset,
            )
            DialogTextButton(
                text = stringResource(Res.string.label_shortcut_disabled),
                fontSize = actionFontSize,
                onClick = onDisable,
            )
        }
    }
}

internal fun OnyxSettings.withCommandShortcut(
    command: OnyxCommand,
    shortcut: OnyxShortcut?,
): OnyxSettings {
    val currentMap = commandShortcutMapFromSettings(commandShortcutOverrides)
    val overrides = commandShortcutOverrides
        .filterNot { override -> override.command == command.name }
        .toMutableList()

    if (shortcut != null) {
        OnyxCommandRegistry.paneCommands
            .map { spec -> spec.command }
            .filter { existingCommand ->
                existingCommand != command && currentMap.shortcutFor(existingCommand) == shortcut
            }
            .forEach { conflictingCommand ->
                overrides.removeAll { override -> override.command == conflictingCommand.name }
                overrides += conflictingCommand.toShortcutOverride(shortcut = null)
            }
    }

    if (shortcut != command.defaultShortcut) {
        overrides += command.toShortcutOverride(shortcut)
    }

    return copy(commandShortcutOverrides = overrides.normalizedCommandShortcutOverrides())
}

private fun OnyxSettings.resetCommandShortcut(command: OnyxCommand): OnyxSettings {
    return copy(
        commandShortcutOverrides = commandShortcutOverrides
            .filterNot { override -> override.command == command.name }
            .normalizedCommandShortcutOverrides(),
    )
}

private fun List<CommandShortcutOverride>.normalizedCommandShortcutOverrides(): List<CommandShortcutOverride> {
    return distinctBy { override -> override.command }.sortedBy { override -> override.command }
}
