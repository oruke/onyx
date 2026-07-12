package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.SystemMenuAction
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Windows 静态注册表 Shell 菜单提供器。
 */
internal class JvmWindowsRegistryMenuProvider {
    /**
     * 读取单个本地条目适用的静态 Shell 动作。
     *
     * @param entries 当前菜单目标。
     * @return 去重后的注册表菜单动作。
     */
    fun listActions(entries: List<VFile>): List<SystemMenuAction> {
        if (entries.size != 1) return emptyList()
        val keys = windowsShellKeys(entries.single())
        return keys.asSequence()
            .flatMap { key -> windowsShellVerbKeys(key).asSequence() }
            .mapNotNull { key -> key.toWindowsShellAction() }
            .distinctBy { action -> action.displayName to action.command }
            .toList()
    }

    /**
     * 执行注册表 Shell 叶子动作。
     *
     * @param action 待执行动作。
     * @param entries 当前菜单目标。
     */
    fun execute(action: SystemMenuAction, entries: List<VFile>) {
        val target = entries.single().requireSystemLocalPath("system menu actions").toString()
        val commandLine = action.command.toWindowsCommandLine(target)
        val script = Files.createTempFile("onyx-shell-action-", ".cmd")
        Files.writeString(script, "@echo off\r\n$commandLine\r\n", StandardCharsets.UTF_8)
        script.toFile().deleteOnExit()
        ProcessBuilder("cmd.exe", "/d", "/c", "call \"${script}\"")
            .directory(File(target).parentFile ?: File("."))
            .start()
    }

    /**
     * 生成条目类型对应的注册表 shell 根键。
     *
     * @param entry 当前文件条目。
     * @return 需要扫描的注册表键。
     */
    private fun windowsShellKeys(entry: VFile): List<String> {
        return if (entry.kind == VFileKind.DIRECTORY) {
            if (entry.systemLocalPathOrNull() == null) return emptyList()
            listOf(
                "HKCU\\Software\\Classes\\Directory\\shell",
                "HKCR\\Directory\\shell",
                "HKCU\\Software\\Classes\\Folder\\shell",
                "HKCR\\Folder\\shell",
            )
        } else {
            val extension = entry.name.substringAfterLast('.', "")
                .takeIf { value -> value.isNotBlank() }
                ?.let { value -> ".$value" }
            buildList {
                add("HKCU\\Software\\Classes\\*\\shell")
                add("HKCR\\*\\shell")
                if (extension != null) {
                    add("HKCU\\Software\\Classes\\$extension\\shell")
                    add("HKCR\\$extension\\shell")
                }
            }
        }
    }

    /**
     * 查询 shell 根键下的 verb 键。
     *
     * @param parentKey shell 根键。
     * @return verb 注册表键列表。
     */
    private fun windowsShellVerbKeys(parentKey: String): List<String> {
        val output = commandOutput("reg", "query", parentKey) ?: return emptyList()
        return output.lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.startsWith("HKEY", ignoreCase = true) && line != parentKey }
            .toList()
    }

    /**
     * 将注册表 verb 键转换为菜单动作。
     *
     * @param depth 当前级联深度。
     * @return 可展示动作，配置不完整时返回 `null`。
     */
    private fun String.toWindowsShellAction(depth: Int = 0): SystemMenuAction? {
        val values = queryRegistryValues(this)
        val rawDisplayName = values.namedData("MUIVerb") ?: values.defaultValue()?.data
        val displayName = rawDisplayName.toWindowsMenuLabel()
        return displayName
            ?.takeIf { values.shouldIncludeWindowsShellVerb() }
            ?.let { label -> buildWindowsShellAction(values, label, depth) }
    }

    /**
     * 根据注册表值构建级联菜单或叶子命令。
     *
     * @param values 当前 verb 键值。
     * @param displayName 菜单显示名称。
     * @param depth 当前级联深度。
     * @return 可展示菜单动作。
     */
    private fun String.buildWindowsShellAction(
        values: List<RegistryValue>,
        displayName: String,
        depth: Int,
    ): SystemMenuAction? {
        val registryIconPath = values.namedData("Icon") ?: queryRegistryDefault("$this\\DefaultIcon")
        val children = if (depth < MAX_SYSTEM_MENU_DEPTH) {
            values.toWindowsShellChildActions(depth + 1)
        } else {
            emptyList()
        }
        val command = queryRegistryDefault("$this\\command")?.takeIf { value -> value.isNotBlank() }
        return if (children.isNotEmpty()) {
            SystemMenuAction(
                id = "$ACTION_PREFIX$this",
                displayName = displayName,
                command = "",
                iconPath = registryIconPath,
                children = children,
            )
        } else {
            command?.let { executableCommand ->
                SystemMenuAction(
                    id = "$ACTION_PREFIX$this",
                    displayName = displayName,
                    command = executableCommand,
                    iconPath = registryIconPath ?: executableCommand.toWindowsExecutableIconPath(),
                )
            }
        }
    }

    /**
     * 查询注册表键默认值。
     *
     * @param key 注册表键。
     * @return 默认值数据。
     */
    private fun queryRegistryDefault(key: String): String? {
        return queryRegistryValues(key, "/ve").defaultData()
    }

    /**
     * 查询注册表键值。
     *
     * @param key 注册表键。
     * @param args 传给 reg query 的附加参数。
     * @return 已解析键值列表。
     */
    private fun queryRegistryValues(key: String, vararg args: String): List<RegistryValue> {
        val output = commandOutput("reg", "query", key, *args) ?: return emptyList()
        return output.lineSequence().mapNotNull { line -> line.toRegistryValue() }.toList()
    }

    /**
     * 解析一行 reg query 输出。
     *
     * @return 注册表键值，非键值行返回 `null`。
     */
    private fun String.toRegistryValue(): RegistryValue? {
        val parts = trim().split(Regex("\\s{2,}"), limit = 3)
        if (parts.size < 2 || !parts[1].startsWith("REG_", ignoreCase = true)) return null
        return RegistryValue(parts[0].trim(), parts[1].trim(), parts.getOrNull(2)?.trim().orEmpty())
    }

    /**
     * 判断 verb 是否允许出现在普通右键菜单。
     *
     * @return 可展示时返回 `true`。
     */
    private fun List<RegistryValue>.shouldIncludeWindowsShellVerb(): Boolean {
        val hiddenFlags = listOf(
            "Extended",
            "LegacyDisable",
            "OnlyInBrowserWindow",
            "ProgrammaticAccessOnly",
            "NeverDefault",
        )
        return hiddenFlags.none { name -> hasRegistryValue(name) }
    }

    /**
     * 判断键值集合是否包含指定名称。
     *
     * @param name 键值名称。
     * @return 存在时返回 `true`。
     */
    private fun List<RegistryValue>.hasRegistryValue(name: String): Boolean {
        return any { value -> value.name.equals(name, ignoreCase = true) }
    }

    /**
     * 读取指定名称的有效数据。
     *
     * @param name 键值名称。
     * @return 有效数据。
     */
    private fun List<RegistryValue>.namedData(name: String): String? {
        return firstOrNull { value -> value.name.equals(name, ignoreCase = true) }
            ?.data
            ?.takeUnless { value -> value.isWindowsRegistryUnsetValue() }
    }

    /**
     * 读取默认键值的有效数据。
     *
     * @return 默认值数据。
     */
    private fun List<RegistryValue>.defaultData(): String? {
        return defaultValue()?.data?.takeUnless { value -> value.isWindowsRegistryUnsetValue() }
    }

    /**
     * 查找默认键值。
     *
     * @return 默认键值。
     */
    private fun List<RegistryValue>.defaultValue(): RegistryValue? {
        return firstOrNull { value -> value.isDefault }
    }

    /**
     * 解析 SubCommands 与 ExtendedSubCommandsKey 子菜单。
     *
     * @param depth 子菜单深度。
     * @return 子菜单动作。
     */
    private fun List<RegistryValue>.toWindowsShellChildActions(depth: Int): List<SystemMenuAction> {
        val commandStoreActions = namedData("SubCommands")
            ?.split(";")
            .orEmpty()
            .map { value -> value.trim() }
            .filter { value -> value.isNotBlank() }
            .mapNotNull { commandId -> commandId.toWindowsCommandStoreAction(depth) }
        val extendedActions = namedData("ExtendedSubCommandsKey")
            ?.let(::windowsExtendedSubCommandKeys)
            .orEmpty()
            .asSequence()
            .flatMap { key -> windowsShellVerbKeys(key).asSequence() }
            .mapNotNull { key -> key.toWindowsShellAction(depth) }
            .toList()
        return (commandStoreActions + extendedActions).distinctBy { action -> action.displayName to action.command }
    }

    /**
     * 从 Explorer CommandStore 解析动作。
     *
     * @param depth 子菜单深度。
     * @return 可展示动作。
     */
    private fun String.toWindowsCommandStoreAction(depth: Int): SystemMenuAction? {
        return windowsCommandStoreKeys(this).firstNotNullOfOrNull { key ->
            if (queryRegistryValues(key).isEmpty()) null else key.toWindowsShellAction(depth)
        }
    }

    /**
     * 构建 CommandStore 的用户级和机器级键。
     *
     * @param commandId CommandStore 命令 ID。
     * @return 注册表键候选。
     */
    private fun windowsCommandStoreKeys(commandId: String): List<String> {
        return listOf(
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\CommandStore\\shell\\$commandId",
            "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\CommandStore\\shell\\$commandId",
        )
    }

    /**
     * 展开 ExtendedSubCommandsKey 的注册表键候选。
     *
     * @param key 注册表相对键或完整键。
     * @return 可查询键列表。
     */
    private fun windowsExtendedSubCommandKeys(key: String): List<String> {
        val trimmedKey = key.trim().trim('\\')
        if (trimmedKey.isBlank()) return emptyList()
        return if (trimmedKey.startsWith("HK", ignoreCase = true)) {
            listOf(trimmedKey)
        } else {
            listOf("HKCU\\Software\\Classes\\$trimmedKey", "HKCR\\$trimmedKey")
        }
    }

    /**
     * 清理注册表菜单标签。
     *
     * @return 可直接展示的标签。
     */
    private fun String?.toWindowsMenuLabel(): String? {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (value.isWindowsRegistryUnsetValue() || value.startsWith("@")) return null
        return value.replace("&", "").takeIf { it.isNotBlank() }
    }

    /**
     * 判断 reg 输出是否表示空值。
     *
     * @return 表示未设置时返回 `true`。
     */
    private fun String.isWindowsRegistryUnsetValue(): Boolean {
        return isBlank() ||
            contains("value not set", ignoreCase = true) ||
            contains("not set", ignoreCase = true) ||
            contains("未设置") ||
            contains("数值未设置") ||
            contains("未設定") ||
            contains("値が設定されていません")
    }

    /**
     * 将目标路径填入 shell command 模板。
     *
     * @param target 本地目标路径。
     * @return 可执行命令行。
     */
    private fun String.toWindowsCommandLine(target: String): String {
        val quotedTarget = "\"${target.replace("\"", "")}\""
        var commandLine = this
        var hasTargetPlaceholder = false
        WINDOWS_TARGET_PLACEHOLDERS.forEach { placeholder ->
            val quotedPlaceholder = "\"$placeholder\""
            if (commandLine.contains(quotedPlaceholder)) {
                commandLine = commandLine.replace(quotedPlaceholder, quotedTarget)
                hasTargetPlaceholder = true
            }
            if (commandLine.contains(placeholder)) {
                commandLine = commandLine.replace(placeholder, quotedTarget)
                hasTargetPlaceholder = true
            }
        }
        return if (hasTargetPlaceholder) commandLine else "$commandLine $quotedTarget"
    }

    /**
     * 从 shell command 模板中提取图标来源 exe。
     *
     * @return 可执行文件路径或名称。
     */
    private fun String.toWindowsExecutableIconPath(): String? {
        val value = trim().takeIf { text -> text.isNotBlank() }
        return value?.let { command ->
            if (command.startsWith("\"")) {
                command.substringAfter("\"").substringBefore("\"")
                    .takeIf { path -> path.endsWith(".exe", ignoreCase = true) }
            } else {
                WINDOWS_EXE_PREFIX.find(command)?.groupValues?.getOrNull(1)
            }
        }
    }

    /** 注册表查询结果中的单个键值。 */
    private data class RegistryValue(
        /** 键值名称。 */
        val name: String,
        /** 注册表值类型。 */
        val type: String,
        /** 键值数据。 */
        val data: String,
    ) {
        /** 是否为本地化 reg 输出中的默认键值。 */
        val isDefault: Boolean
            get() = name.equals("(Default)", ignoreCase = true) ||
                name.equals("(默认)", ignoreCase = true) ||
                name.equals("(預設)", ignoreCase = true)
    }

    internal companion object {
        /** Windows 注册表静态动作 ID 前缀。 */
        const val ACTION_PREFIX = "windows:"
        /** 级联菜单最大读取深度。 */
        private const val MAX_SYSTEM_MENU_DEPTH = 4
        /** shell command 常见目标占位符。 */
        private val WINDOWS_TARGET_PLACEHOLDERS = listOf("%1", "%L", "%l", "%V", "%v", "%I", "%i")
        /** shell command 头部 exe 提取规则。 */
        private val WINDOWS_EXE_PREFIX = Regex("""(?i)^(.+?\.exe)\b""")
    }
}
