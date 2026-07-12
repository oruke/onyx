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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
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

@Composable
internal fun CommandShortcutSettings(
    settings: OnyxSettings,
    onDraftChange: (OnyxSettings) -> Unit,
    fontSize: TextUnit,
    labelFontSize: TextUnit,
) {
    var recordingCommand by remember { mutableStateOf<OnyxCommand?>(null) }
    val focusRequester = remember { FocusRequester() }
    val shortcutMap = remember(settings.commandShortcutOverrides) {
        commandShortcutMapFromSettings(settings.commandShortcutOverrides)
    }

    LaunchedEffect(recordingCommand) {
        if (recordingCommand != null) {
            focusRequester.requestFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                val command = recordingCommand ?: return@onPreviewKeyEvent false
                if (event.key == Key.Escape) {
                    recordingCommand = null
                    true
                } else {
                    val shortcut = event.toOnyxShortcutOrNull() ?: return@onPreviewKeyEvent false
                    onDraftChange(settings.withCommandShortcut(command, shortcut))
                    recordingCommand = null
                    true
                }
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OnyxCommandRegistry.paneCommands.forEach { spec ->
            CommandShortcutRow(
                spec = spec,
                shortcut = shortcutMap.shortcutFor(spec.command),
                recording = recordingCommand == spec.command,
                fontSize = fontSize,
                labelFontSize = labelFontSize,
                onCapture = { recordingCommand = spec.command },
                onReset = {
                    recordingCommand = null
                    onDraftChange(settings.resetCommandShortcut(spec.command))
                },
                onDisable = {
                    recordingCommand = null
                    onDraftChange(settings.withCommandShortcut(spec.command, shortcut = null))
                },
            )
        }
    }
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

private fun OnyxSettings.withCommandShortcut(
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
