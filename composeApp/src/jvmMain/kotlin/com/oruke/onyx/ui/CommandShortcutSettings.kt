package com.oruke.onyx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oruke.onyx.core.model.CommandShortcutOverride
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_shortcut_capture
import onyx.composeapp.generated.resources.label_shortcut_disabled
import onyx.composeapp.generated.resources.label_shortcut_recording
import onyx.composeapp.generated.resources.label_shortcut_reset_default
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/**
 * 渲染命令快捷键列表并支持录制、恢复默认与禁用。
 *
 * @param settings 当前设置草稿。
 * @param onDraftChange 更新设置草稿回调。
 * @param fontSize 正文字号。
 * @param labelFontSize 标签字号。
 * @param recordingCommand 由外层设置窗口提升的当前录制命令（用于拦截根级 Escape）。
 * @param onRecordingCommandChange 更新录制状态的回调；为空时退回局部状态管理。
 */
@Composable
internal fun CommandShortcutSettings(
    settings: OnyxSettings,
    onDraftChange: (OnyxSettings) -> Unit,
    fontSize: TextUnit,
    labelFontSize: TextUnit,
    recordingCommand: OnyxCommand? = null,
    onRecordingCommandChange: ((OnyxCommand?) -> Unit)? = null,
) {
    var localRecordingCommand by remember { mutableStateOf<OnyxCommand?>(null) }
    val activeRecordingCommand = if (onRecordingCommandChange != null) recordingCommand else localRecordingCommand
    val setRecordingCommand: (OnyxCommand?) -> Unit = onRecordingCommandChange ?: { next ->
        localRecordingCommand = next
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
                )
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OnyxCommandRegistry.paneCommands.forEach { spec ->
            CommandShortcutRow(
                spec = spec,
                shortcut = shortcutMap.shortcutFor(spec.command),
                recording = activeRecordingCommand == spec.command,
                fontSize = fontSize,
                labelFontSize = labelFontSize,
                onCapture = { setRecordingCommand(spec.command) },
                onReset = {
                    setRecordingCommand(null)
                    onDraftChange(settings.resetCommandShortcut(spec.command))
                },
                onDisable = {
                    setRecordingCommand(null)
                    onDraftChange(settings.withCommandShortcut(spec.command, shortcut = null))
                },
            )
        }
    }
}

/**
 * 统一处理快捷键录制期间的按键事件：按 Escape 取消录制而非关闭设置窗口，按有效组合键则写入草稿并退出录制。
 *
 * @param event 键盘事件。
 * @param recordingCommand 当前正在录制的命令；为 `null` 时不拦截。
 * @param settings 当前设置草稿。
 * @param onDraftChange 更新设置草稿回调。
 * @param onRecordingCommandChange 更新录制状态回调。
 * @return 事件已被录制流程消费时返回 `true`。
 */
internal fun handleShortcutRecordingKeyEvent(
    event: KeyEvent,
    recordingCommand: OnyxCommand?,
    settings: OnyxSettings,
    onDraftChange: (OnyxSettings) -> Unit,
    onRecordingCommandChange: (OnyxCommand?) -> Unit,
): Boolean {
    val command = recordingCommand ?: return false
    if (event.type != KeyEventType.KeyDown) {
        return false
    }
    if (event.key == Key.Escape) {
        onRecordingCommandChange(null)
        return true
    }
    val shortcut = event.toOnyxShortcutOrNull() ?: return false
    onDraftChange(settings.withCommandShortcut(command, shortcut))
    onRecordingCommandChange(null)
    return true
}

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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(spec.label),
            fontSize = labelFontSize,
            color = palette.foreground,
            modifier = Modifier.width(150.dp),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(
            text = if (recording) {
                stringResource(Res.string.label_shortcut_recording)
            } else {
                onyxShortcutHint(shortcut) ?: stringResource(Res.string.label_shortcut_disabled)
            },
            fontSize = fontSize,
            color = if (shortcut == null && !recording) palette.disabledForeground else palette.mutedForeground,
            modifier = Modifier.width(112.dp),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
        ) {
            DialogTextButton(
                text = stringResource(Res.string.label_shortcut_capture),
                fontSize = 10.sp,
                focused = recording,
                modifier = Modifier.testTag("shortcut-capture-${spec.command.name}"),
                onClick = onCapture,
            )
            DialogTextButton(
                text = stringResource(Res.string.label_shortcut_reset_default),
                fontSize = 10.sp,
                onClick = onReset,
            )
            DialogTextButton(
                text = stringResource(Res.string.label_shortcut_disabled),
                fontSize = 10.sp,
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
