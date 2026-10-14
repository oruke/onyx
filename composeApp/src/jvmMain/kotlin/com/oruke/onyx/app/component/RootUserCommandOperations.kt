package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import kotlinx.coroutines.launch

/**
 * 使用面板业务上下文规划外部命令，在组件层展示启动或退出失败。
 * @param intent 用户主动执行的命令标识与目标条目。
 */
internal fun DefaultRootComponent.executeUserCommand(intent: RootIntent.ExecuteUserCommand) {
    activatePane(intent.paneId)
    val command = settings.value.userCommands.firstOrNull { it.id == intent.id } ?: return
    val selection = intent.entries ?: selectedEntriesInPane(intent.paneId)
    val affected = selection.mapNotNull { it.parentLocation }.toSet() + paneState(intent.paneId).location
    val invocation =
        dependencies.customization.commands.renderInvocation(command, paneState(intent.paneId).location, selection)
    scope.launch {
        dependencies.customization.commandLauncher.execute(invocation)
            .onSuccess { code ->
                if (code != 0) showActivePaneOperationFailure(
                    LocalizedException(
                        I18nMessage(
                            MessageKey.MSG_USER_COMMAND_EXIT_FAILED,
                            command.name,
                            code
                        )
                    )
                )
                refreshDirectories(affected)
            }
            .onFailure(::showActivePaneOperationFailure)
    }
}
