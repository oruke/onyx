package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.oruke.onyx.core.model.UserCommandPlacement
import com.oruke.onyx.ui.theme.LocalOnyxPalette
import onyx.composeapp.generated.resources.Res
import onyx.composeapp.generated.resources.label_settings_user_commands
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * 渲染配置在工具栏的命令菜单，不挤占地址栏和文件区域。
 * @param runtime 面板状态与根组件动作入口。
 */
@Composable
internal fun UserCommandToolbar(runtime: PaneSurfaceRuntime) {
    val commands = runtime.model.userCommands[UserCommandPlacement.TOOLBAR].orEmpty()
    if (commands.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.label_settings_user_commands)
    ToolbarIconButton(enabled = true, tooltip = label, onClick = { runtime.model.onActivate(); expanded = !expanded }) {
        Icon(AllIconsKeys.Actions.Execute, label)
    }
    if (expanded) {
        Popup(onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
            val scroll = rememberScrollState()
            Box(Modifier.heightIn(max = 360.dp).background(LocalOnyxPalette.current.floatingSurface)) {
                Column(Modifier.verticalScroll(scroll).padding(start = 6.dp, top = 6.dp, end = 18.dp, bottom = 6.dp)) {
                    commands.forEach { command ->
                        DialogTextButton(command.name, enabled = command.executable.isNotBlank()) {
                            expanded = false
                            runtime.model.actions.onUserCommand(command.id, null)
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}
