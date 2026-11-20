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
import com.oruke.onyx.app.component.PaneCommand
import com.oruke.onyx.core.model.CommandShortcutModifierSetting
import com.oruke.onyx.core.model.CommandShortcutOverride
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_command_palette
import onyx.composeapp.generated.resources.action_copy
import onyx.composeapp.generated.resources.action_cut
import onyx.composeapp.generated.resources.action_delete_selected
import onyx.composeapp.generated.resources.action_filter
import onyx.composeapp.generated.resources.action_focus_address_bar
import onyx.composeapp.generated.resources.action_go_up
import onyx.composeapp.generated.resources.action_new_directory
import onyx.composeapp.generated.resources.action_new_file
import onyx.composeapp.generated.resources.action_open
import onyx.composeapp.generated.resources.action_open_settings
import onyx.composeapp.generated.resources.action_paste
import onyx.composeapp.generated.resources.action_quick_open
import onyx.composeapp.generated.resources.action_redo
import onyx.composeapp.generated.resources.action_refresh_active
import onyx.composeapp.generated.resources.action_rename
import onyx.composeapp.generated.resources.action_select_all
import onyx.composeapp.generated.resources.action_show_search_panel
import onyx.composeapp.generated.resources.action_toggle_favorite
import onyx.composeapp.generated.resources.action_toggle_preview_pane
import onyx.composeapp.generated.resources.action_undo
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
import onyx.composeapp.generated.resources.shortcut_key_k
import onyx.composeapp.generated.resources.shortcut_key_l
import onyx.composeapp.generated.resources.shortcut_key_n
import onyx.composeapp.generated.resources.shortcut_key_p
import onyx.composeapp.generated.resources.shortcut_key_v
import onyx.composeapp.generated.resources.shortcut_key_x
import onyx.composeapp.generated.resources.shortcut_key_y
import onyx.composeapp.generated.resources.shortcut_key_z
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
    UndoLastOperation(OnyxShortcut(OnyxShortcutKey.Z, setOf(OnyxShortcutModifier.PRIMARY))),
    RedoLastOperation(OnyxShortcut(OnyxShortcutKey.Y, setOf(OnyxShortcutModifier.PRIMARY))),
    Refresh(OnyxShortcut(OnyxShortcutKey.F5)),
    CloseMenu(OnyxShortcut(OnyxShortcutKey.ESCAPE)),
    Filter(OnyxShortcut(OnyxShortcutKey.F, setOf(OnyxShortcutModifier.PRIMARY))),
    CreateDirectories(OnyxShortcut(OnyxShortcutKey.ENTER, setOf(OnyxShortcutModifier.PRIMARY))),
    SelectAll(OnyxShortcut(OnyxShortcutKey.A, setOf(OnyxShortcutModifier.PRIMARY))),
    GoUp(OnyxShortcut(OnyxShortcutKey.BACKSPACE)),
    ToggleFavorite(OnyxShortcut(OnyxShortcutKey.D, setOf(OnyxShortcutModifier.PRIMARY))),
    OpenSettings(OnyxShortcut(OnyxShortcutKey.COMMA, setOf(OnyxShortcutModifier.PRIMARY))),
    CommandPalette(OnyxShortcut(OnyxShortcutKey.P, setOf(OnyxShortcutModifier.PRIMARY, OnyxShortcutModifier.SHIFT))),
    QuickOpen(OnyxShortcut(OnyxShortcutKey.K, setOf(OnyxShortcutModifier.PRIMARY))),
    ShowSearchPanel(OnyxShortcut(OnyxShortcutKey.F, setOf(OnyxShortcutModifier.PRIMARY, OnyxShortcutModifier.SHIFT))),
    FocusAddressBar(OnyxShortcut(OnyxShortcutKey.L, setOf(OnyxShortcutModifier.PRIMARY))),
    TogglePreviewPane(OnyxShortcut(OnyxShortcutKey.P, setOf(OnyxShortcutModifier.ALT))),
}

/**
 * 判断键位是否为主回车键或数字小键盘回车键。
 */
internal val Key.isConfirmEnterKey: Boolean
    get() = this == Key.Enter || this == Key.NumPadEnter

/**
 * 将 UI 命令映射为组件层面板命令。
 *
 * @return 对应面板命令；非面板命令返回 `null`。
 */
@Suppress("CyclomaticComplexMethod") // 穷举应用命令到面板命令的声明式映射。
internal fun OnyxCommand.toPaneCommand(): PaneCommand? {
    return when (this) {
        OnyxCommand.OpenSelection -> PaneCommand.OPEN_SELECTION
        OnyxCommand.RenameSelection -> PaneCommand.RENAME_SELECTION
        OnyxCommand.NewFile -> PaneCommand.NEW_FILE
        OnyxCommand.NewDirectory -> PaneCommand.NEW_DIRECTORY
        OnyxCommand.DeleteSelection -> PaneCommand.DELETE_SELECTION
        OnyxCommand.CopySelection -> PaneCommand.COPY_SELECTION
        OnyxCommand.CutSelection -> PaneCommand.CUT_SELECTION
        OnyxCommand.Paste -> PaneCommand.PASTE
        OnyxCommand.UndoLastOperation -> PaneCommand.UNDO_LAST_OPERATION
        OnyxCommand.RedoLastOperation -> PaneCommand.REDO_LAST_OPERATION
        OnyxCommand.Refresh -> PaneCommand.REFRESH
        OnyxCommand.CloseMenu -> PaneCommand.CLOSE_MENU
        OnyxCommand.Filter -> PaneCommand.FILTER
        OnyxCommand.CreateDirectories -> PaneCommand.CREATE_DIRECTORIES
        OnyxCommand.SelectAll -> PaneCommand.SELECT_ALL
        OnyxCommand.GoUp -> PaneCommand.GO_UP
        OnyxCommand.ToggleFavorite -> PaneCommand.TOGGLE_FAVORITE
        OnyxCommand.OpenSettings -> PaneCommand.OPEN_SETTINGS
        OnyxCommand.CommandPalette -> PaneCommand.COMMAND_PALETTE
        OnyxCommand.QuickOpen,
        OnyxCommand.ShowSearchPanel,
        OnyxCommand.FocusAddressBar,
        OnyxCommand.TogglePreviewPane -> null
    }
}

/**
 * 将组件层面板命令映射为 UI 命令。
 *
 * @return 对应 UI 命令；没有快捷键展示需求的命令返回 `null`。
 */
@Suppress("CyclomaticComplexMethod") // 穷举面板命令到应用命令的声明式映射。
internal fun PaneCommand.toOnyxCommand(): OnyxCommand? {
    return when (this) {
        PaneCommand.OPEN_SELECTION -> OnyxCommand.OpenSelection
        PaneCommand.RENAME_SELECTION -> OnyxCommand.RenameSelection
        PaneCommand.NEW_FILE -> OnyxCommand.NewFile
        PaneCommand.NEW_DIRECTORY -> OnyxCommand.NewDirectory
        PaneCommand.DELETE_SELECTION -> OnyxCommand.DeleteSelection
        PaneCommand.COPY_SELECTION -> OnyxCommand.CopySelection
        PaneCommand.CUT_SELECTION -> OnyxCommand.CutSelection
        PaneCommand.PASTE -> OnyxCommand.Paste
        PaneCommand.UNDO_LAST_OPERATION -> OnyxCommand.UndoLastOperation
        PaneCommand.REDO_LAST_OPERATION -> OnyxCommand.RedoLastOperation
        PaneCommand.REFRESH -> OnyxCommand.Refresh
        PaneCommand.CLOSE_MENU -> OnyxCommand.CloseMenu
        PaneCommand.FILTER -> OnyxCommand.Filter
        PaneCommand.CREATE_DIRECTORIES -> OnyxCommand.CreateDirectories
        PaneCommand.SELECT_ALL -> OnyxCommand.SelectAll
        PaneCommand.GO_UP -> OnyxCommand.GoUp
        PaneCommand.TOGGLE_FAVORITE -> OnyxCommand.ToggleFavorite
        PaneCommand.OPEN_SETTINGS -> OnyxCommand.OpenSettings
        PaneCommand.COMMAND_PALETTE -> OnyxCommand.CommandPalette
        PaneCommand.OPEN_SELECTION_IN_NEW_TAB,
        PaneCommand.OPEN_SELECTION_IN_NEW_WINDOW -> null
    }
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
    BACKSPACE(Key.Backspace),
    TAB(Key.Tab),
    SPACE(Key.Spacebar),
    INSERT(Key.Insert),
    HOME(Key.MoveHome),
    END(Key.MoveEnd),
    PAGE_UP(Key.PageUp),
    PAGE_DOWN(Key.PageDown),
    COMMA(Key.Comma),
    PERIOD(Key.Period),
    SLASH(Key.Slash),
    BACKSLASH(Key.Backslash),
    SEMICOLON(Key.Semicolon),
    MINUS(Key.Minus),
    EQUALS(Key.Equals),
    LEFT_BRACKET(Key.LeftBracket),
    RIGHT_BRACKET(Key.RightBracket),
    F1(Key.F1),
    F2(Key.F2),
    F3(Key.F3),
    F4(Key.F4),
    F5(Key.F5),
    F6(Key.F6),
    F7(Key.F7),
    F8(Key.F8),
    F9(Key.F9),
    F10(Key.F10),
    F11(Key.F11),
    F12(Key.F12),
    DIGIT_0(Key.Zero),
    DIGIT_1(Key.One),
    DIGIT_2(Key.Two),
    DIGIT_3(Key.Three),
    DIGIT_4(Key.Four),
    DIGIT_5(Key.Five),
    DIGIT_6(Key.Six),
    DIGIT_7(Key.Seven),
    DIGIT_8(Key.Eight),
    DIGIT_9(Key.Nine),
    A(Key.A),
    B(Key.B),
    C(Key.C),
    D(Key.D),
    E(Key.E),
    F(Key.F),
    G(Key.G),
    H(Key.H),
    I(Key.I),
    J(Key.J),
    K(Key.K),
    L(Key.L),
    M(Key.M),
    N(Key.N),
    O(Key.O),
    P(Key.P),
    Q(Key.Q),
    R(Key.R),
    S(Key.S),
    T(Key.T),
    U(Key.U),
    V(Key.V),
    W(Key.W),
    X(Key.X),
    Y(Key.Y),
    Z(Key.Z),
}

/**
 * 判断按键本身是否为修饰键（Ctrl/Shift/Alt/Meta），用于快捷键录制时区分纯修饰键按下与不支持按键。
 */
internal fun Key.isShortcutModifierKey(): Boolean {
    return this == Key.CtrlLeft ||
        this == Key.CtrlRight ||
        this == Key.ShiftLeft ||
        this == Key.ShiftRight ||
        this == Key.AltLeft ||
        this == Key.AltRight ||
        this == Key.MetaLeft ||
        this == Key.MetaRight
}

internal fun KeyEvent.toOnyxShortcutOrNull(): OnyxShortcut? {
    if (type != KeyEventType.KeyDown) return null
    val pressedKey = if (key == Key.NumPadEnter) Key.Enter else key
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
        OnyxCommandSpec(OnyxCommand.UndoLastOperation, Res.string.action_undo, AllIconsKeys.Actions.Back),
        OnyxCommandSpec(OnyxCommand.RedoLastOperation, Res.string.action_redo, AllIconsKeys.Actions.Forward),
        OnyxCommandSpec(OnyxCommand.Refresh, Res.string.action_refresh_active, AllIconsKeys.Actions.Refresh),
        OnyxCommandSpec(OnyxCommand.Filter, Res.string.action_filter, AllIconsKeys.Actions.Find),
        OnyxCommandSpec(OnyxCommand.SelectAll, Res.string.action_select_all, AllIconsKeys.Actions.ListFiles),
        OnyxCommandSpec(OnyxCommand.GoUp, Res.string.action_go_up, AllIconsKeys.Actions.Back),
        OnyxCommandSpec(OnyxCommand.ToggleFavorite, Res.string.action_toggle_favorite, AllIconsKeys.Nodes.HomeFolder),
        OnyxCommandSpec(OnyxCommand.OpenSettings, Res.string.action_open_settings, AllIconsKeys.General.GearPlain),
        OnyxCommandSpec(OnyxCommand.CommandPalette, Res.string.action_command_palette, AllIconsKeys.Actions.Find),
        OnyxCommandSpec(OnyxCommand.QuickOpen, Res.string.action_quick_open, AllIconsKeys.Actions.Find),
        OnyxCommandSpec(OnyxCommand.ShowSearchPanel, Res.string.action_show_search_panel, AllIconsKeys.Actions.Find),
        OnyxCommandSpec(OnyxCommand.FocusAddressBar, Res.string.action_focus_address_bar, AllIconsKeys.Actions.Edit),
        OnyxCommandSpec(OnyxCommand.TogglePreviewPane, Res.string.action_toggle_preview_pane, AllIconsKeys.Actions.Preview),
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

    /** 按命令枚举查找对应的元数据定义。 */
    fun findSpec(command: OnyxCommand): OnyxCommandSpec? {
        return paneCommands.firstOrNull { spec -> spec.command == command }
    }
}

internal fun KeyEvent.matchesCommand(
    command: OnyxCommand,
    shortcuts: OnyxCommandShortcutMap = OnyxCommandShortcutMap.Default,
): Boolean {
    val shortcut = shortcuts.shortcutFor(command) ?: return false
    val keyMatches = key == shortcut.key.composeKey ||
        (shortcut.key == OnyxShortcutKey.ENTER && key.isConfirmEnterKey)
    if (type != KeyEventType.KeyDown || !keyMatches) return false
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
@Suppress("CyclomaticComplexMethod") // 键位名称是无嵌套的穷举展示映射。
private fun shortcutKeyLabel(key: OnyxShortcutKey): String {
    return when (key) {
        OnyxShortcutKey.ENTER -> stringResource(Res.string.shortcut_key_enter)
        OnyxShortcutKey.ESCAPE -> stringResource(Res.string.shortcut_key_escape)
        OnyxShortcutKey.DELETE -> stringResource(Res.string.shortcut_key_delete)
        OnyxShortcutKey.BACKSPACE -> stringResource(Res.string.shortcut_key_backspace)
        OnyxShortcutKey.TAB -> "Tab"
        OnyxShortcutKey.SPACE -> "Space"
        OnyxShortcutKey.INSERT -> "Insert"
        OnyxShortcutKey.HOME -> "Home"
        OnyxShortcutKey.END -> "End"
        OnyxShortcutKey.PAGE_UP -> "PgUp"
        OnyxShortcutKey.PAGE_DOWN -> "PgDn"
        OnyxShortcutKey.COMMA -> stringResource(Res.string.shortcut_key_comma)
        OnyxShortcutKey.PERIOD -> "."
        OnyxShortcutKey.SLASH -> "/"
        OnyxShortcutKey.BACKSLASH -> "\\"
        OnyxShortcutKey.SEMICOLON -> ";"
        OnyxShortcutKey.MINUS -> "-"
        OnyxShortcutKey.EQUALS -> "="
        OnyxShortcutKey.LEFT_BRACKET -> "["
        OnyxShortcutKey.RIGHT_BRACKET -> "]"
        OnyxShortcutKey.F1 -> "F1"
        OnyxShortcutKey.F2 -> stringResource(Res.string.shortcut_key_f2)
        OnyxShortcutKey.F3 -> "F3"
        OnyxShortcutKey.F4 -> "F4"
        OnyxShortcutKey.F5 -> stringResource(Res.string.shortcut_key_f5)
        OnyxShortcutKey.F6 -> "F6"
        OnyxShortcutKey.F7 -> "F7"
        OnyxShortcutKey.F8 -> "F8"
        OnyxShortcutKey.F9 -> "F9"
        OnyxShortcutKey.F10 -> "F10"
        OnyxShortcutKey.F11 -> "F11"
        OnyxShortcutKey.F12 -> "F12"
        OnyxShortcutKey.DIGIT_0 -> "0"
        OnyxShortcutKey.DIGIT_1 -> "1"
        OnyxShortcutKey.DIGIT_2 -> "2"
        OnyxShortcutKey.DIGIT_3 -> "3"
        OnyxShortcutKey.DIGIT_4 -> "4"
        OnyxShortcutKey.DIGIT_5 -> "5"
        OnyxShortcutKey.DIGIT_6 -> "6"
        OnyxShortcutKey.DIGIT_7 -> "7"
        OnyxShortcutKey.DIGIT_8 -> "8"
        OnyxShortcutKey.DIGIT_9 -> "9"
        OnyxShortcutKey.A -> stringResource(Res.string.shortcut_key_a)
        OnyxShortcutKey.B -> "B"
        OnyxShortcutKey.C -> stringResource(Res.string.shortcut_key_c)
        OnyxShortcutKey.D -> stringResource(Res.string.shortcut_key_d)
        OnyxShortcutKey.E -> "E"
        OnyxShortcutKey.F -> stringResource(Res.string.shortcut_key_f)
        OnyxShortcutKey.G -> "G"
        OnyxShortcutKey.H -> "H"
        OnyxShortcutKey.I -> "I"
        OnyxShortcutKey.J -> "J"
        OnyxShortcutKey.K -> stringResource(Res.string.shortcut_key_k)
        OnyxShortcutKey.L -> stringResource(Res.string.shortcut_key_l)
        OnyxShortcutKey.M -> "M"
        OnyxShortcutKey.N -> stringResource(Res.string.shortcut_key_n)
        OnyxShortcutKey.O -> "O"
        OnyxShortcutKey.P -> stringResource(Res.string.shortcut_key_p)
        OnyxShortcutKey.Q -> "Q"
        OnyxShortcutKey.R -> "R"
        OnyxShortcutKey.S -> "S"
        OnyxShortcutKey.T -> "T"
        OnyxShortcutKey.U -> "U"
        OnyxShortcutKey.V -> stringResource(Res.string.shortcut_key_v)
        OnyxShortcutKey.W -> "W"
        OnyxShortcutKey.X -> stringResource(Res.string.shortcut_key_x)
        OnyxShortcutKey.Y -> stringResource(Res.string.shortcut_key_y)
        OnyxShortcutKey.Z -> stringResource(Res.string.shortcut_key_z)
    }
}

private fun isMacOs(): Boolean {
    return System.getProperty("os.name").contains("mac", ignoreCase = true)
}
