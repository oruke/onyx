package com.oruke.onyx.app.filesystem

import com.oruke.onyx.vfs.api.OpenWithApp
import com.sun.jna.Memory
import com.sun.jna.platform.win32.Version
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Windows “打开方式”注册表兜底解析器。
 *
 * 资源管理器优先使用 Shell 关联处理器；注册表里的 ProgId 通常描述的是文件类型，
 * 例如 `ExampleEditor.yaml` 可能会显示成“Yaml 源文件”。这里仅作为 Shell 枚举失败时的兜底，
 * 并尽量从命令目标 exe 的版本信息中恢复真实应用名。
 */
internal class WindowsOpenWithRegistryResolver {
    /**
     * 查询指定扩展名对应的注册表“打开方式”应用。
     *
     * @param extension 带点的文件扩展名，例如 `.yaml`。
     * @return 去重后的候选应用列表。
     */
    fun listApplications(extension: String): List<OpenWithApp> {
        val normalizedExtension = extension.takeIf { value -> value.startsWith(".") } ?: return emptyList()
        val progIds = linkedSetOf<String>()
        queryRegistryDefault("HKCR\\$normalizedExtension")?.let { progIds += it }
        progIds += queryRegistryValueNames("HKCR\\$normalizedExtension\\OpenWithProgids")
        progIds += queryRegistryValueNames(
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\FileExts\\$normalizedExtension\\OpenWithProgids",
        )

        val applicationExecutables = linkedSetOf<String>()
        applicationExecutables += queryRegistryOpenWithExecutables("HKCR\\$normalizedExtension\\OpenWithList")
        applicationExecutables += queryRegistryOpenWithExecutables(
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\FileExts\\$normalizedExtension\\OpenWithList",
        )

        val progIdApps = progIds
            .asSequence()
            .mapNotNull { progId -> progId.toWindowsOpenWithApp() }
        val applicationApps = applicationExecutables
            .asSequence()
            .mapNotNull { executableName -> executableName.toWindowsApplicationOpenWithApp() }

        return (progIdApps + applicationApps)
            .distinctBy { app -> app.command.lowercase(Locale.ROOT) }
            .sortedBy { app -> app.displayName.lowercase(Locale.getDefault()) }
            .toList()
    }

    /**
     * 执行注册表 command 模板。
     *
     * @param location 需要打开的本地文件路径。
     * @param commandTemplate 注册表中保存的 open command 模板。
     * @return 启动结果。
     */
    suspend fun runCommandTemplate(
        location: String,
        commandTemplate: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val command = commandTemplate.toWindowsCommandFor(location)
            ProcessBuilder("cmd", "/c", command)
                .directory(File(location).parentFile ?: File("."))
                .start()
            Unit
        }
    }

    /**
     * 将 ProgId 注册表项转换为“打开方式”应用。
     *
     * @return 有 open command 时返回候选应用，否则返回 `null`。
     */
    private fun String.toWindowsOpenWithApp(): OpenWithApp? {
        if (isWindowsRegistryUnsetValue() || isWindowsRegistryDefaultName()) return null
        val command = windowsProgIdCommandKeys(this)
            .firstNotNullOfOrNull { key -> queryRegistryDefault(key) }
            ?: return null
        val iconPath = windowsProgIdIconKeys(this).firstNotNullOfOrNull { key -> queryRegistryDefault(key) }
            ?: command.toExecutablePathOrName()
        val displayName = command.readExecutableDisplayName()
            ?: windowsProgIdDisplayKeys(this).firstNotNullOfOrNull { key -> queryRegistryDefault(key)?.toWindowsMenuLabel() }
            ?: this
        return OpenWithApp(
            id = this,
            displayName = displayName,
            command = command,
            iconPath = iconPath,
        )
    }

    /**
     * 将 `Applications\<exe>` 注册表项转换为“打开方式”应用。
     *
     * @return 注册了 open command 的应用；无命令时返回 `null`。
     */
    private fun String.toWindowsApplicationOpenWithApp(): OpenWithApp? {
        val executableName = trim().takeIf { value -> value.endsWith(".exe", ignoreCase = true) } ?: return null
        val command = windowsApplicationCommandKeys(executableName)
            .firstNotNullOfOrNull { key -> queryRegistryDefault(key) }
            ?: return null
        val displayName = command.readExecutableDisplayName()
            ?: windowsApplicationDisplayKeys(executableName)
                .firstNotNullOfOrNull { key -> queryRegistryNamedData(key, "FriendlyAppName")?.toWindowsMenuLabel() }
            ?: executableName.removeSuffix(".exe").replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
            }
        return OpenWithApp(
            id = "application:$executableName",
            displayName = displayName,
            command = command,
            iconPath = windowsApplicationIconKeys(executableName)
                .firstNotNullOfOrNull { key -> queryRegistryDefault(key) }
                ?: command.toExecutablePathOrName(),
        )
    }

    /**
     * 查询注册表键默认值。
     *
     * @param key 注册表键路径。
     * @return 非空且非系统占位的数据。
     */
    private fun queryRegistryDefault(key: String): String? {
        val output = runWindowsRegistryQuery(key, "/ve") ?: return null
        return output
            .lineSequence()
            .mapNotNull { line -> line.toRegistryValue()?.data }
            .firstOrNull { value -> !value.isWindowsRegistryUnsetValue() }
    }

    /**
     * 查询指定注册表值的数据。
     *
     * @param key 注册表键路径。
     * @param name 值名称。
     * @return 非空且非系统占位的数据。
     */
    private fun queryRegistryNamedData(
        key: String,
        name: String,
    ): String? {
        val output = runWindowsRegistryQuery(key, "/v", name) ?: return null
        return output
            .lineSequence()
            .mapNotNull { line -> line.toRegistryValue() }
            .firstOrNull { value -> value.name.equals(name, ignoreCase = true) }
            ?.data
            ?.takeUnless { value -> value.isWindowsRegistryUnsetValue() }
    }

    /**
     * 查询注册表键下所有值名。
     *
     * @param key 注册表键路径。
     * @return 可作为 ProgId 使用的值名列表。
     */
    private fun queryRegistryValueNames(key: String): List<String> {
        val output = runWindowsRegistryQuery(key) ?: return emptyList()
        return output
            .lineSequence()
            .mapNotNull { line -> line.toRegistryValue()?.name }
            .filter { name -> !name.isWindowsRegistryUnsetValue() && !name.isWindowsRegistryDefaultName() }
            .distinct()
            .toList()
    }

    /**
     * 查询 Windows `OpenWithList` 中记录的可执行文件名。
     *
     * @param key `OpenWithList` 注册表路径。
     * @return 去重后的 exe 名称列表。
     */
    private fun queryRegistryOpenWithExecutables(key: String): List<String> {
        val output = runWindowsRegistryQuery(key) ?: return emptyList()
        return output
            .lineSequence()
            .mapNotNull { line -> line.toRegistryValue() }
            .filterNot { value -> value.name.equals("MRUList", ignoreCase = true) }
            .map { value -> value.data.trim() }
            .filter { value -> value.endsWith(".exe", ignoreCase = true) && !value.isWindowsRegistryUnsetValue() }
            .distinctBy { value -> value.lowercase(Locale.ROOT) }
            .toList()
    }

    /**
     * 执行 `reg query` 并读取标准输出。
     *
     * @param args 传给 `reg query` 的参数。
     * @return 命令成功时的输出；失败时返回 `null`。
     */
    private fun runWindowsRegistryQuery(vararg args: String): String? {
        return runCatching {
            val process = ProcessBuilder(listOf("reg", "query") + args.toList())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.readBytes().decodePlatformProcessOutput()
            if (process.waitFor() == 0) output else null
        }.getOrNull()
    }

    /**
     * 从命令模板中提取可执行文件并读取版本描述。
     *
     * @return exe 的 `FileDescription` 或 `ProductName`；无法读取时返回 `null`。
     */
    private fun String.readExecutableDisplayName(): String? {
        val executable = toExecutablePathOrName()?.let { value -> Path.of(value) } ?: return null
        if (!Files.isRegularFile(executable)) return null
        return readVersionString(executable, "FileDescription")
            ?: readVersionString(executable, "ProductName")
    }

    /**
     * 读取 Windows 可执行文件版本资源中的字符串。
     *
     * @param executable 可执行文件路径。
     * @param key 版本资源字符串键，例如 `FileDescription`。
     * @return 版本资源字符串；读取失败时返回 `null`。
     */
    private fun readVersionString(
        executable: Path,
        key: String,
    ): String? {
        return runCatching {
            val ignoredHandle = IntByReference()
            val size = Version.INSTANCE.GetFileVersionInfoSize(executable.toString(), ignoredHandle)
            if (size <= 0) return null
            val data = Memory(size.toLong())
            if (!Version.INSTANCE.GetFileVersionInfo(executable.toString(), 0, size, data)) return null
            val translationsRef = PointerByReference()
            val translationsSizeRef = IntByReference()
            val translation = if (
                Version.INSTANCE.VerQueryValue(data, "\\VarFileInfo\\Translation", translationsRef, translationsSizeRef) &&
                translationsSizeRef.value >= TRANSLATION_BLOCK_SIZE
            ) {
                val pointer = translationsRef.value
                "%04x%04x".format(Locale.ROOT, pointer.getShort(0).toInt() and 0xffff, pointer.getShort(2).toInt() and 0xffff)
            } else {
                DEFAULT_VERSION_TRANSLATION
            }
            val valueRef = PointerByReference()
            val valueSizeRef = IntByReference()
            val subBlock = "\\StringFileInfo\\$translation\\$key"
            if (!Version.INSTANCE.VerQueryValue(data, subBlock, valueRef, valueSizeRef)) return null
            valueRef.value?.getWideString(0)?.toWindowsMenuLabel()
        }.getOrNull()
    }

    /**
     * 解析 `reg query` 单行输出。
     *
     * @return 注册表值；非值行返回 `null`。
     */
    private fun String.toRegistryValue(): RegistryValue? {
        val trimmed = trim()
        if (trimmed.isBlank() || trimmed.startsWith("HKEY", ignoreCase = true)) return null
        val parts = trimmed.split(Regex("\\s{2,}"), limit = 3)
        if (parts.size < 2 || !parts[1].startsWith("REG_", ignoreCase = true)) return null
        return RegistryValue(
            name = parts[0],
            data = parts.getOrNull(2)?.trim().orEmpty(),
        )
    }

    /**
     * 将注册表 command 模板替换为实际命令。
     *
     * @param location 目标文件路径。
     * @return 可交给 `cmd /c` 的命令字符串。
     */
    private fun String.toWindowsCommandFor(location: String): String {
        val target = "\"${location.replace("\"", "")}\""
        val hadPlaceholder = WINDOWS_TARGET_PLACEHOLDERS.any { placeholder -> contains(placeholder) }
        val command = WINDOWS_TARGET_PLACEHOLDERS.fold(this) { current, placeholder ->
            current.replace(placeholder, target)
        }
        return if (hadPlaceholder) command else "$command $target"
    }

    /**
     * 从注册表 command 模板中提取 exe 路径或 exe 文件名。
     *
     * @return exe 路径或名称；无法解析时返回 `null`。
     */
    private fun String.toExecutablePathOrName(): String? {
        val value = trim().takeIf { it.isNotBlank() } ?: return null
        if (value.startsWith("\"")) {
            return value.substringAfter("\"")
                .substringBefore("\"")
                .takeIf { path -> path.endsWith(".exe", ignoreCase = true) }
        }
        return WINDOWS_EXE_PREFIX.find(value)?.groupValues?.getOrNull(1)
    }

    /**
     * 清理 Windows 菜单文本。
     *
     * @return 可直接显示的名称；资源引用或空值返回 `null`。
     */
    private fun String?.toWindowsMenuLabel(): String? {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (value.isWindowsRegistryUnsetValue()) return null
        if (value.startsWith("@")) return null
        return value.replace("&", "").takeIf { it.isNotBlank() }
    }

    /**
     * 判断字符串是否为 Windows 注册表空值占位。
     *
     * @return 是占位值时返回 `true`。
     */
    private fun String.isWindowsRegistryUnsetValue(): Boolean {
        return isBlank() ||
            contains("value not set", ignoreCase = true) ||
            contains("not set", ignoreCase = true) ||
            contains("未设置") ||
            contains("数值未设置") ||
            contains("未設定") ||
            contains("値は設定されていません")
    }

    /**
     * 判断注册表值名是否为默认值标签。
     *
     * @return 是默认值标签时返回 `true`。
     */
    private fun String.isWindowsRegistryDefaultName(): Boolean {
        return equals("(Default)", ignoreCase = true) ||
            equals("(默认)", ignoreCase = true) ||
            equals("(預設)", ignoreCase = true)
    }

    /**
     * 返回 ProgId 打开命令的注册表搜索路径。
     *
     * @param progId 文件类型 ProgId。
     * @return 注册表键路径列表。
     */
    private fun windowsProgIdCommandKeys(progId: String): List<String> {
        return listOf(
            "HKCU\\Software\\Classes\\$progId\\shell\\open\\command",
            "HKCR\\$progId\\shell\\open\\command",
        )
    }

    /**
     * 返回 ProgId 显示名称的注册表搜索路径。
     *
     * @param progId 文件类型 ProgId。
     * @return 注册表键路径列表。
     */
    private fun windowsProgIdDisplayKeys(progId: String): List<String> {
        return listOf(
            "HKCU\\Software\\Classes\\$progId",
            "HKCR\\$progId",
        )
    }

    /**
     * 返回 ProgId 图标的注册表搜索路径。
     *
     * @param progId 文件类型 ProgId。
     * @return 注册表键路径列表。
     */
    private fun windowsProgIdIconKeys(progId: String): List<String> {
        return listOf(
            "HKCU\\Software\\Classes\\$progId\\DefaultIcon",
            "HKCR\\$progId\\DefaultIcon",
        )
    }

    /**
     * 返回 Windows Applications 打开命令的注册表搜索路径。
     *
     * @param executableName 可执行文件名。
     * @return 注册表键路径列表。
     */
    private fun windowsApplicationCommandKeys(executableName: String): List<String> {
        return listOf(
            "HKCU\\Software\\Classes\\Applications\\$executableName\\shell\\open\\command",
            "HKCR\\Applications\\$executableName\\shell\\open\\command",
        )
    }

    /**
     * 返回 Windows Applications 显示名称的注册表搜索路径。
     *
     * @param executableName 可执行文件名。
     * @return 注册表键路径列表。
     */
    private fun windowsApplicationDisplayKeys(executableName: String): List<String> {
        return listOf(
            "HKCU\\Software\\Classes\\Applications\\$executableName",
            "HKCR\\Applications\\$executableName",
        )
    }

    /**
     * 返回 Windows Applications 图标的注册表搜索路径。
     *
     * @param executableName 可执行文件名。
     * @return 注册表键路径列表。
     */
    private fun windowsApplicationIconKeys(executableName: String): List<String> {
        return listOf(
            "HKCU\\Software\\Classes\\Applications\\$executableName\\DefaultIcon",
            "HKCR\\Applications\\$executableName\\DefaultIcon",
        )
    }

    /**
     * `reg query` 解析出的值。
     */
    private data class RegistryValue(
        /** 注册表值名。 */
        val name: String,
        /** 注册表值数据。 */
        val data: String,
    )

    private companion object {
        /** 注册表 command 中常见的目标文件占位符。 */
        val WINDOWS_TARGET_PLACEHOLDERS = listOf("%1", "%L", "%l")

        /** 从命令模板头部提取 exe 路径或名称的正则。 */
        val WINDOWS_EXE_PREFIX = Regex("""(?i)^(.+?\.exe)\b""")

        /** Windows 版本资源翻译块字节数。 */
        const val TRANSLATION_BLOCK_SIZE = 4

        /** 缺失翻译表时使用的英语资源默认编码。 */
        const val DEFAULT_VERSION_TRANSLATION = "040904b0"
    }
}
