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
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_with_shortcut
import onyx.composeapp.generated.resources.shortcut_key_c
import onyx.composeapp.generated.resources.shortcut_key_delete
import onyx.composeapp.generated.resources.shortcut_key_enter
import onyx.composeapp.generated.resources.shortcut_key_escape
import onyx.composeapp.generated.resources.shortcut_key_f
import onyx.composeapp.generated.resources.shortcut_key_f2
import onyx.composeapp.generated.resources.shortcut_key_f5
import onyx.composeapp.generated.resources.shortcut_key_n
import onyx.composeapp.generated.resources.shortcut_key_v
import onyx.composeapp.generated.resources.shortcut_key_x
import onyx.composeapp.generated.resources.shortcut_modifier_alt
import onyx.composeapp.generated.resources.shortcut_modifier_cmd
import onyx.composeapp.generated.resources.shortcut_modifier_ctrl
import onyx.composeapp.generated.resources.shortcut_modifier_shift
import onyx.composeapp.generated.resources.shortcut_separator
import org.jetbrains.compose.resources.stringResource

internal enum class OnyxCommand(val shortcut: OnyxShortcut? = null) {
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
}

internal data class OnyxShortcut(
    val key: OnyxShortcutKey,
    val modifiers: Set<OnyxShortcutModifier> = emptySet(),
)

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
    N(Key.N),
    C(Key.C),
    X(Key.X),
    V(Key.V),
    F(Key.F),
}

internal fun KeyEvent.matchesCommand(command: OnyxCommand): Boolean {
    val shortcut = command.shortcut ?: return false
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
internal fun onyxCommandShortcutHint(command: OnyxCommand): String? {
    val shortcut = command.shortcut ?: return null
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
        OnyxShortcutKey.N -> stringResource(Res.string.shortcut_key_n)
        OnyxShortcutKey.C -> stringResource(Res.string.shortcut_key_c)
        OnyxShortcutKey.X -> stringResource(Res.string.shortcut_key_x)
        OnyxShortcutKey.V -> stringResource(Res.string.shortcut_key_v)
        OnyxShortcutKey.F -> stringResource(Res.string.shortcut_key_f)
    }
}

private fun isMacOs(): Boolean {
    return System.getProperty("os.name").contains("mac", ignoreCase = true)
}
