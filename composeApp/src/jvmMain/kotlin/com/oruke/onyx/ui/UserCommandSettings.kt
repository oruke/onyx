package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.component.appendUserCommand
import com.oruke.onyx.app.component.replaceUserCommand
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.UserCommandDefinition
import com.oruke.onyx.core.model.UserCommandPlacement
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.action_add_command
import onyx.composeapp.generated.resources.action_delete
import onyx.composeapp.generated.resources.label_new_command
import onyx.composeapp.generated.resources.label_user_command_name
import onyx.composeapp.generated.resources.label_user_command_executable
import onyx.composeapp.generated.resources.label_user_command_arguments
import onyx.composeapp.generated.resources.label_user_command_directory
import onyx.composeapp.generated.resources.label_user_command_hint
import onyx.composeapp.generated.resources.label_user_command_toolbar
import onyx.composeapp.generated.resources.label_user_command_context_menu
import onyx.composeapp.generated.resources.label_user_command_palette
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Text

/**
 * 管理自定义命令草稿，配置在应用设置后才对工具栏和菜单生效。
 * @param draft 当前完整设置草稿。
 * @param onChange 更新根组件草稿的事件。
 */
@Composable
internal fun UserCommandSettings(draft: OnyxSettings, onChange: (OnyxSettings) -> Unit) {
    val newName = stringResource(Res.string.label_new_command)
    Text(stringResource(Res.string.label_user_command_hint))
    DialogTextButton(stringResource(Res.string.action_add_command)) { onChange(draft.appendUserCommand(newName)) }
    draft.userCommands.forEach { command ->
        SettingsSection(command.name) {
            UserCommandFields(command) { onChange(draft.replaceUserCommand(it)) }
            DialogTextButton(stringResource(Res.string.action_delete), destructive = true) {
                onChange(draft.copy(userCommands = draft.userCommands.filterNot { it.id == command.id }))
            }
        }
    }
}

/**
 * 编辑程序、逐行参数、目录和展示位置，输入内容不交由 Shell 解析。
 * @param command 当前命令草稿。
 * @param onChange 单命令更新事件。
 */
@Composable
private fun UserCommandFields(command: UserCommandDefinition, onChange: (UserCommandDefinition) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LabeledCustomizationField(
            stringResource(Res.string.label_user_command_name),
            command.name
        ) { onChange(command.copy(name = it)) }
        LabeledCustomizationField(
            stringResource(Res.string.label_user_command_executable),
            command.executable
        ) { onChange(command.copy(executable = it)) }
        Text(stringResource(Res.string.label_user_command_arguments))
        val palette = LocalOnyxPalette.current
        BasicTextField(
            value = command.argumentTemplate.joinToString("\n"),
            onValueChange = {
                val arguments = if (it.isEmpty()) emptyList() else it.lines()
                onChange(command.copy(argumentTemplate = arguments))
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 180.dp)
                .background(palette.inputBackground).border(1.dp, palette.outlineVariant).padding(6.dp),
            textStyle = TextStyle(color = palette.foreground, fontSize = LocalOnyxAppearance.current.listFontSize),
            cursorBrush = SolidColor(palette.accent),
        )
        LabeledCustomizationField(
            stringResource(Res.string.label_user_command_directory),
            command.workingDirectoryTemplate.orEmpty()
        ) {
            onChange(command.copy(workingDirectoryTemplate = it.ifBlank { null }))
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(
                UserCommandPlacement.TOOLBAR to Res.string.label_user_command_toolbar,
                UserCommandPlacement.CONTEXT_MENU to Res.string.label_user_command_context_menu,
                UserCommandPlacement.COMMAND_PALETTE to Res.string.label_user_command_palette,
            ).forEach { (placement, label) ->
                SettingsOption(placement in command.placements, stringResource(label)) {
                    val placements = if (placement in command.placements) {
                        command.placements - placement
                    } else command.placements + placement
                    onChange(command.copy(placements = placements))
                }
            }
        }
    }
}
