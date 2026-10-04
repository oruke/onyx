package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.FileLabelMatcher
import com.oruke.onyx.core.model.FileLabelRule
import com.oruke.onyx.core.model.OnyxSettings
import java.util.UUID
import com.oruke.onyx.core.model.UserCommandDefinition

/**
 * 给设置草稿增加一条独立规则，新增时不改变已保存设置。
 * @param name 新规则的本地化初始名称。
 * @return 包含新规则的草稿。
 */
internal fun OnyxSettings.appendFileLabelRule(name: String): OnyxSettings = copy(
    fileLabelRules = fileLabelRules + FileLabelRule(UUID.randomUUID().toString(), name, FileLabelMatcher()),
)

/**
 * 替换草稿中的规则，保持规则优先顺序。
 * @param rule 已编辑的规则。
 * @return 更新后的设置草稿。
 */
internal fun OnyxSettings.replaceFileLabelRule(rule: FileLabelRule): OnyxSettings = copy(
    fileLabelRules = fileLabelRules.map { if (it.id == rule.id) rule else it },
)

/**
 * 增加一条待配置的命令，仅修改设置草稿。
 * @param name 新命令的本地化名称。
 * @return 新命令追加后的草稿。
 */
internal fun OnyxSettings.appendUserCommand(name: String): OnyxSettings = copy(
    userCommands = userCommands + UserCommandDefinition(UUID.randomUUID().toString(), name, ""),
)

/**
 * 替换命令草稿，保留列表顺序和其它命令。
 * @param command 当前编辑的命令配置。
 * @return 更新后的设置草稿。
 */
internal fun OnyxSettings.replaceUserCommand(command: UserCommandDefinition): OnyxSettings = copy(
    userCommands = userCommands.map { if (it.id == command.id) command else it },
)
