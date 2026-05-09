package com.oruke.onyx.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.oruke.onyx.core.model.CommandShortcutModifierSetting
import com.oruke.onyx.core.model.CommandShortcutOverride
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_command_palette
import onyx.composeapp.generated.resources.action_copy
import onyx.composeapp.generated.resources.action_cut
import onyx.composeapp.generated.resources.action_delete_selected
import onyx.composeapp.generated.resources.action_filter
import onyx.composeapp.generated.resources.action_go_up
import onyx.composeapp.generated.resources.action_new_directory
import onyx.composeapp.generated.resources.action_new_file
import onyx.composeapp.generated.resources.action_open
import onyx.composeapp.generated.resources.action_open_settings
import onyx.composeapp.generated.resources.action_paste
import onyx.composeapp.generated.resources.action_refresh_active
import onyx.composeapp.generated.resources.action_rename
import onyx.composeapp.generated.resources.action_select_all
import onyx.composeapp.generated.resources.action_toggle_favorite
import onyx.composeapp.generated.resources.label_with_shortcut
import onyx.composeapp.generated.resources.shortcut_key_a
import onyx.composeapp.generated.resources.shortcut_key_backspace
import onyx.composeapp.generated.resources.shortcut_key_c
import onyx.composeapp.generated.resources.shortcut_key_comma
import onyx.composeapp.generated.resources.shortcut_key_d
import onyx.composeapp.generated.resources.shortcut_key_delete
import onyx.composeapp.generated.resources.shortcut_key_enter
import onyx.composeapp.generated.resources.shortcut_key_escape
import onyx.composeapp.generated.resources.shortcut_key_f
import onyx.composeapp.generated.resources.shortcut_key_f2
import onyx.composeapp.generated.resources.shortcut_key_f5
import onyx.composeapp.generated.resources.shortcut_key_n
import onyx.composeapp.generated.resources.shortcut_key_p
import onyx.composeapp.generated.resources.shortcut_key_v
import onyx.composeapp.generated.resources.shortcut_key_x
import onyx.composeapp.generated.resources.shortcut_modifier_alt
import onyx.composeapp.generated.resources.shortcut_modifier_cmd
import onyx.composeapp.generated.resources.shortcut_modifier_ctrl
import onyx.composeapp.generated.resources.shortcut_modifier_shift
import onyx.composeapp.generated.resources.shortcut_separator
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

internal enum class OnyxCommand(val defaultShortcut: OnyxShortcut? = null) {
    OpenSelection(OnyxShortcut(OnyxShortcutKey.ENTER)),
    RenameSelection(OnyxShortcut(OnyxShortcutKey.F2)),
    NewFile(OnyxShortcut(OnyxShortcutKey.N, setOf(OnyxShortcutModifier.PRIMARY))),
    NewDirectory(OnyxShortcut(OnyxShortcutKey.N, setOf(OnyxShortcutModifier.PRIMARY, OnyxShortcutModifier.SHIFT))),
    DeleteSelection(OnyxShortcut(OnyxShortcutKey.DELETE)),
    CopySelection(OnyxShortcut(OnyxShortcutKey.C, setOf(OnyxShortcutModifier.PRIMARY))),
    CutSelection(OnyxShortcut(OnyxShortcutKey.X, setOf(OnyxShortcutModifier.PRIMARY))),
    Paste(OnyxShortcut(OnyxShortcutKey.V, setOf(OnyxShortcutModifier.PRIMARY))),
    Refresh(OnyxShortcut(OnyxShortcutKey.F5)),
    CloseMenu(OnyxShortcut(OnyxShortcutKey.ESCAPE)),
    Filter(OnyxShortcut(OnyxShortcutKey.F, setOf(OnyxShortcutModifier.PRIMARY))),
    CreateDirectories(OnyxShortcut(OnyxShortcutKey.ENTER, setOf(OnyxShortcutModifier.PRIMARY))),
    SelectAll(OnyxShortcut(OnyxShortcutKey.A, setOf(OnyxShortcutModifier.PRIMARY))),
    GoUp(OnyxShortcut(OnyxShortcutKey.BACKSPACE)),
    ToggleFavorite(OnyxShortcut(OnyxShortcutKey.D, setOf(OnyxShortcutModifier.PRIMARY))),
    OpenSettings(OnyxShortcut(OnyxShortcutKey.COMMA, setOf(OnyxShortcutModifier.PRIMARY))),
    CommandPalette(OnyxShortcut(OnyxShortcutKey.P, setOf(OnyxShortcutModifier.PRIMARY, OnyxShortcutModifier.SHIFT))),
}

internal data class OnyxShortcut(
    val key: OnyxShortcutKey,
    val modifiers: Set<OnyxShortcutModifier> = emptySet(),
)

internal data class OnyxCommandShortcutMap(
    private val overrides: Map<OnyxCommand, OnyxShortcut?> = emptyMap(),
) {
    fun shortcutFor(command: OnyxCommand): OnyxShortcut? {
        return if (overrides.containsKey(command)) {
            overrides[command]
        } else {
            command.defaultShortcut
        }
    }

    fun withShortcut(command: OnyxCommand, shortcut: OnyxShortcut?): OnyxCommandShortcutMap {
        return copy(overrides = overrides + (command to shortcut))
    }

    companion object {
        val Default = OnyxCommandShortcutMap()
    }
}

internal fun commandShortcutMapFromSettings(
    overrides: List<CommandShortcutOverride>,
): OnyxCommandShortcutMap {
    val mappedOverrides = overrides.mapNotNull { override ->
        val command = OnyxCommand.entries.firstOrNull { candidate -> candidate.name == override.command }
            ?: return@mapNotNull null
        val shortcut = override.key?.let { keyName ->
            val key = OnyxShortcutKey.entries.firstOrNull { candidate -> candidate.name == keyName }
                ?: return@mapNotNull null
            OnyxShortcut(
                key = key,
                modifiers = override.modifiers.mapNotNull { modifier -> modifier.toOnyxShortcutModifier() }.toSet(),
            )
        }
        command to shortcut
    }.toMap()
    return OnyxCommandShortcutMap(mappedOverrides)
}

internal fun OnyxCommand.toShortcutOverride(shortcut: OnyxShortcut?): CommandShortcutOverride {
    return CommandShortcutOverride(
        command = name,
        key = shortcut?.key?.name,
        modifiers = shortcut?.modifiers
            ?.map { modifier -> modifier.toSettingModifier() }
            ?.toSet()
            .orEmpty(),
    )
}

internal enum class OnyxShortcutModifier {
    PRIMARY,
    SHIFT,
    ALT,
}

internal enum class OnyxShortcutKey(val composeKey: Key) {
    ENTER(Key.Enter),
    ESCAPE(Key.Escape),
    DELETE(Key.Delete),
    F2(Key.F2),
    F5(Key.F5),
    BACKSPACE(Key.Backspace),
    COMMA(Key.Comma),
    A(Key.A),
    N(Key.N),
    C(Key.C),
    D(Key.D),
    P(Key.P),
    X(Key.X),
    V(Key.V),
    F(Key.F),
}

internal fun KeyEvent.toOnyxShortcutOrNull(): OnyxShortcut? {
    if (type != KeyEventType.KeyDown) return null
    val pressedKey = key
    val shortcutKey = OnyxShortcutKey.entries.firstOrNull { candidate -> candidate.composeKey == pressedKey }
        ?: return null
    return OnyxShortcut(
        key = shortcutKey,
        modifiers = buildSet {
            if (isCtrlPressed || isMetaPressed) add(OnyxShortcutModifier.PRIMARY)
            if (isShiftPressed) add(OnyxShortcutModifier.SHIFT)
            if (isAltPressed) add(OnyxShortcutModifier.ALT)
        },
    )
}

private fun CommandShortcutModifierSetting.toOnyxShortcutModifier(): OnyxShortcutModifier? {
    return when (this) {
        CommandShortcutModifierSetting.PRIMARY -> OnyxShortcutModifier.PRIMARY
        CommandShortcutModifierSetting.SHIFT -> OnyxShortcutModifier.SHIFT
        CommandShortcutModifierSetting.ALT -> OnyxShortcutModifier.ALT
    }
}

private fun OnyxShortcutModifier.toSettingModifier(): CommandShortcutModifierSetting {
    return when (this) {
        OnyxShortcutModifier.PRIMARY -> CommandShortcutModifierSetting.PRIMARY
        OnyxShortcutModifier.SHIFT -> CommandShortcutModifierSetting.SHIFT
        OnyxShortcutModifier.ALT -> CommandShortcutModifierSetting.ALT
    }
}

internal data class OnyxCommandSpec(
    val command: OnyxCommand,
    val label: StringResource,
    val iconKey: IconKey,
)

internal data class OnyxCommandState(
    val spec: OnyxCommandSpec,
    val shortcut: OnyxShortcut?,
    val enabled: Boolean,
)

internal object OnyxCommandRegistry {
    val paneCommands: List<OnyxCommandSpec> = listOf(
        OnyxCommandSpec(OnyxCommand.OpenSelection, Res.string.action_open, AllIconsKeys.Actions.MenuOpen),
        OnyxCommandSpec(OnyxCommand.RenameSelection, Res.string.action_rename, AllIconsKeys.Actions.Edit),
        OnyxCommandSpec(OnyxCommand.NewFile, Res.string.action_new_file, AllIconsKeys.FileTypes.Any_type),
        OnyxCommandSpec(OnyxCommand.NewDirectory, Res.string.action_new_directory, AllIconsKeys.Nodes.Folder),
        OnyxCommandSpec(OnyxCommand.DeleteSelection, Res.string.action_delete_selected, AllIconsKeys.General.Delete),
        OnyxCommandSpec(OnyxCommand.CopySelection, Res.string.action_copy, AllIconsKeys.Actions.Copy),
        OnyxCommandSpec(OnyxCommand.CutSelection, Res.string.action_cut, AllIconsKeys.Actions.MenuCut),
        OnyxCommandSpec(OnyxCommand.Paste, Res.string.action_paste, AllIconsKeys.Actions.MenuPaste),
        OnyxCommandSpec(OnyxCommand.Refresh, Res.string.action_refresh_active, AllIconsKeys.Actions.Refresh),
        OnyxCommandSpec(OnyxCommand.Filter, Res.string.action_filter, AllIconsKeys.Actions.Find),
        OnyxCommandSpec(OnyxCommand.SelectAll, Res.string.action_select_all, AllIconsKeys.Actions.ListFiles),
        OnyxCommandSpec(OnyxCommand.GoUp, Res.string.action_go_up, AllIconsKeys.Actions.Back),
        OnyxCommandSpec(OnyxCommand.ToggleFavorite, Res.string.action_toggle_favorite, AllIconsKeys.Nodes.HomeFolder),
        OnyxCommandSpec(OnyxCommand.OpenSettings, Res.string.action_open_settings, AllIconsKeys.General.GearPlain),
        OnyxCommandSpec(OnyxCommand.CommandPalette, Res.string.action_command_palette, AllIconsKeys.Actions.Find),
    )

    fun paneCommandStates(
        shortcuts: OnyxCommandShortcutMap = OnyxCommandShortcutMap.Default,
        isEnabled: (OnyxCommand) -> Boolean,
    ): List<OnyxCommandState> {
        return paneCommands.map { spec ->
            OnyxCommandState(
                spec = spec,
                shortcut = shortcuts.shortcutFor(spec.command),
                enabled = isEnabled(spec.command),
            )
        }
    }
}

internal fun KeyEvent.matchesCommand(
    command: OnyxCommand,
    shortcuts: OnyxCommandShortcutMap = OnyxCommandShortcutMap.Default,
): Boolean {
    val shortcut = shortcuts.shortcutFor(command) ?: return false
    if (type != KeyEventType.KeyDown || key != shortcut.key.composeKey) return false
    val expectsPrimary = OnyxShortcutModifier.PRIMARY in shortcut.modifiers
    val expectsShift = OnyxShortcutModifier.SHIFT in shortcut.modifiers
    val expectsAlt = OnyxShortcutModifier.ALT in shortcut.modifiers
    val hasPrimary = isCtrlPressed || isMetaPressed
    return hasPrimary == expectsPrimary &&
        isShiftPressed == expectsShift &&
        isAltPressed == expectsAlt
}

@Composable
internal fun onyxCommandShortcutHint(
    command: OnyxCommand,
    shortcuts: OnyxCommandShortcutMap = OnyxCommandShortcutMap.Default,
): String? {
    return onyxShortcutHint(shortcuts.shortcutFor(command))
}

@Composable
internal fun onyxShortcutHint(shortcut: OnyxShortcut?): String? {
    if (shortcut == null) return null
    val separator = stringResource(Res.string.shortcut_separator)
    val primary = if (OnyxShortcutModifier.PRIMARY in shortcut.modifiers) {
        shortcutModifierLabel(OnyxShortcutModifier.PRIMARY)
    } else {
        null
    }
    val shift = if (OnyxShortcutModifier.SHIFT in shortcut.modifiers) {
        shortcutModifierLabel(OnyxShortcutModifier.SHIFT)
    } else {
        null
    }
    val alt = if (OnyxShortcutModifier.ALT in shortcut.modifiers) {
        shortcutModifierLabel(OnyxShortcutModifier.ALT)
    } else {
        null
    }
    return listOfNotNull(primary, shift, alt, shortcutKeyLabel(shortcut.key)).joinToString(separator)
}

@Composable
internal fun onyxCommandTooltip(label: String, command: OnyxCommand): String {
    val shortcutHint = onyxCommandShortcutHint(command)
    return if (shortcutHint == null) {
        label
    } else {
        stringResource(Res.string.label_with_shortcut, label, shortcutHint)
    }
}

@Composable
internal fun onyxCommandTooltip(
    label: String,
    command: OnyxCommand,
    shortcuts: OnyxCommandShortcutMap,
): String {
    val shortcutHint = onyxCommandShortcutHint(command, shortcuts)
    return if (shortcutHint == null) {
        label
    } else {
        stringResource(Res.string.label_with_shortcut, label, shortcutHint)
    }
}

@Composable
private fun shortcutModifierLabel(modifier: OnyxShortcutModifier): String {
    return when (modifier) {
        OnyxShortcutModifier.PRIMARY -> {
            if (isMacOs()) {
                stringResource(Res.string.shortcut_modifier_cmd)
            } else {
                stringResource(Res.string.shortcut_modifier_ctrl)
            }
        }

        OnyxShortcutModifier.SHIFT -> stringResource(Res.string.shortcut_modifier_shift)
        OnyxShortcutModifier.ALT -> stringResource(Res.string.shortcut_modifier_alt)
    }
}

@Composable
private fun shortcutKeyLabel(key: OnyxShortcutKey): String {
    return when (key) {
        OnyxShortcutKey.ENTER -> stringResource(Res.string.shortcut_key_enter)
        OnyxShortcutKey.ESCAPE -> stringResource(Res.string.shortcut_key_escape)
        OnyxShortcutKey.DELETE -> stringResource(Res.string.shortcut_key_delete)
        OnyxShortcutKey.F2 -> stringResource(Res.string.shortcut_key_f2)
        OnyxShortcutKey.F5 -> stringResource(Res.string.shortcut_key_f5)
        OnyxShortcutKey.BACKSPACE -> stringResource(Res.string.shortcut_key_backspace)
        OnyxShortcutKey.COMMA -> stringResource(Res.string.shortcut_key_comma)
        OnyxShortcutKey.A -> stringResource(Res.string.shortcut_key_a)
        OnyxShortcutKey.N -> stringResource(Res.string.shortcut_key_n)
        OnyxShortcutKey.C -> stringResource(Res.string.shortcut_key_c)
        OnyxShortcutKey.D -> stringResource(Res.string.shortcut_key_d)
        OnyxShortcutKey.P -> stringResource(Res.string.shortcut_key_p)
        OnyxShortcutKey.X -> stringResource(Res.string.shortcut_key_x)
        OnyxShortcutKey.V -> stringResource(Res.string.shortcut_key_v)
        OnyxShortcutKey.F -> stringResource(Res.string.shortcut_key_f)
    }
}

private fun isMacOs(): Boolean {
    return System.getProperty("os.name").contains("mac", ignoreCase = true)
}
