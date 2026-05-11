package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.streams.toList

class JvmSystemMenuService(
    private val materializer: SystemFileMaterializer,
) : SystemMenuService {
    override suspend fun listActions(entries: List<VFile>): List<SystemMenuAction> = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext emptyList()
        when (currentHostPlatform()) {
            HostPlatform.LINUX -> listLinuxServiceActions(entries)
            HostPlatform.WINDOWS -> listWindowsShellActions(entries)
            HostPlatform.MACOS -> emptyList()
            HostPlatform.OTHER -> emptyList()
        }
    }

    override suspend fun execute(
        action: SystemMenuAction,
        entries: List<VFile>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val targetEntries = entries.materializeEntries()
                ?: throw IllegalStateException("System menu action requires materializable files")
            when {
                action.children.isNotEmpty() -> throw UnsupportedOperationException(
                    "System menu group cannot be executed directly: ${action.id}"
                )
                action.id.startsWith(LINUX_ACTION_PREFIX) -> runLinuxServiceAction(action, targetEntries)
                action.id.startsWith(WINDOWS_ACTION_PREFIX) -> runWindowsShellAction(action, targetEntries)
                else -> throw UnsupportedOperationException("Unsupported system menu action: ${action.id}")
            }
        }
    }

    private suspend fun List<VFile>.materializeEntries(): List<VFile>? {
        if (any { entry -> !materializer.supports(entry) }) return null
        return map { entry ->
            materializer.materialize(entry).getOrElse { return null }
        }
    }

    private fun listLinuxServiceActions(entries: List<VFile>): List<SystemMenuAction> {
        val mimeTypes = entries.map { entry ->
            when {
                entry.kind == VFileKind.DIRECTORY -> "inode/directory"
                else -> entry.systemLocalPathOrNull()
                    ?.let { path -> queryMimeType(path.toString()) }
                    ?: entry.guessSystemMimeType()
            }
        }
        if (mimeTypes.any { mimeType -> mimeType == null }) return emptyList()

        return serviceMenuFiles()
            .asSequence()
            .flatMap { file -> file.toLinuxServiceActions(mimeTypes.filterNotNull(), entries.size) }
            .distinctBy { action -> action.displayName to action.command }
            .toList()
    }

    private fun Path.toLinuxServiceActions(
        mimeTypes: List<String>,
        targetCount: Int,
    ): Sequence<SystemMenuAction> {
        val entry = readDesktopEntry()
        val desktopFields = entry.groups["Desktop Entry"].orEmpty()
        if (desktopFields["Type"] != "Service") return emptySequence()
        if (desktopFields["Hidden"].isTrue()) return emptySequence()
        if (!desktopFields.mimeList().supportsAll(mimeTypes)) return emptySequence()
        if (!desktopFields.acceptsTargetCount(targetCount)) return emptySequence()

        return desktopFields["Actions"]
            .orEmpty()
            .split(";")
            .asSequence()
            .mapNotNull { actionId ->
                val id = actionId.trim().takeIf { value -> value.isNotBlank() } ?: return@mapNotNull null
                val fields = entry.groups["Desktop Action $id"].orEmpty()
                val exec = fields["Exec"]?.takeIf { value -> value.isNotBlank() } ?: return@mapNotNull null
                val name = localizedValue(fields, "Name") ?: return@mapNotNull null
                val tryExec = fields["TryExec"]
                if (tryExec != null && !isExecutableAvailable(tryExec)) return@mapNotNull null
                SystemMenuAction(
                    id = "$LINUX_ACTION_PREFIX${this}#$id",
                    displayName = name,
                    command = exec,
                    iconPath = fields["Icon"],
                )
            }
    }

    private fun runLinuxServiceAction(
        action: SystemMenuAction,
        entries: List<VFile>,
    ) {
        val targets = entries.map { entry -> entry.requireSystemLocalPath("system menu actions") }
        val command = desktopExecArguments(action, targets)
        ProcessBuilder(command)
            .directory(targets.first().parent?.toFile() ?: File("."))
            .start()
    }

    private fun listWindowsShellActions(entries: List<VFile>): List<SystemMenuAction> {
        if (entries.size != 1) return emptyList()
        val entry = entries.single()
        val keys = windowsShellKeys(entry)
        val shellActions = keys
            .asSequence()
            .flatMap { key -> windowsShellVerbKeys(key).asSequence() }
            .mapNotNull { key -> key.toWindowsShellAction() }
        return shellActions
            .distinctBy { action -> action.displayName to action.command }
            .toList()
    }

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

    private fun windowsShellVerbKeys(parentKey: String): List<String> {
        val output = commandOutput("reg", "query", parentKey) ?: return emptyList()
        return output
            .lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.startsWith("HKEY", ignoreCase = true) && line != parentKey }
            .toList()
    }

    private fun String.toWindowsShellAction(depth: Int = 0): SystemMenuAction? {
        val values = queryRegistryValues(this)
        if (!values.shouldIncludeWindowsShellVerb()) return null
        val rawDisplayName = values.namedData("MUIVerb") ?: values.defaultValue()?.data
        val displayName = rawDisplayName.toWindowsMenuLabel() ?: return null
        val children = if (depth < MAX_SYSTEM_MENU_DEPTH) {
            values.toWindowsShellChildActions(depth + 1)
        } else {
            emptyList()
        }
        if (children.isNotEmpty()) {
            return SystemMenuAction(
                id = "$WINDOWS_ACTION_PREFIX$this",
                displayName = displayName,
                command = "",
                children = children,
            )
        }
        val command = queryRegistryDefault("$this\\command")?.takeIf { value -> value.isNotBlank() } ?: return null
        return SystemMenuAction(
            id = "$WINDOWS_ACTION_PREFIX$this",
            displayName = displayName,
            command = command,
        )
    }

    private fun runWindowsShellAction(
        action: SystemMenuAction,
        entries: List<VFile>,
    ) {
        val target = entries.single().requireSystemLocalPath("system menu actions").toString()
        val commandLine = action.command.toWindowsCommandLine(target)
        val script = Files.createTempFile("onyx-shell-action-", ".cmd")
        Files.writeString(script, "@echo off\r\n$commandLine\r\n", StandardCharsets.UTF_8)
        script.toFile().deleteOnExit()
        ProcessBuilder("cmd.exe", "/d", "/c", "call \"${script}\"")
            .directory(File(target).parentFile ?: File("."))
            .start()
    }

    private fun queryRegistryDefault(key: String): String? {
        return queryRegistryValues(key, "/ve").defaultData()
    }

    private fun queryRegistryValues(key: String, vararg args: String): List<RegistryValue> {
        val output = commandOutput("reg", "query", key, *args) ?: return emptyList()
        return output
            .lineSequence()
            .mapNotNull { line -> line.toRegistryValue() }
            .toList()
    }

    private fun String.toRegistryValue(): RegistryValue? {
        val parts = trim().split(Regex("\\s{2,}"), limit = 3)
        if (parts.size < 2 || !parts[1].startsWith("REG_", ignoreCase = true)) return null
        return RegistryValue(
            name = parts[0].trim(),
            type = parts[1].trim(),
            data = parts.getOrNull(2)?.trim().orEmpty(),
        )
    }

    private fun List<RegistryValue>.shouldIncludeWindowsShellVerb(): Boolean {
        if (hasRegistryValue("Extended")) return false
        if (hasRegistryValue("LegacyDisable")) return false
        if (hasRegistryValue("OnlyInBrowserWindow")) return false
        if (hasRegistryValue("ProgrammaticAccessOnly")) return false
        if (hasRegistryValue("NeverDefault")) return false
        return true
    }

    private fun List<RegistryValue>.hasRegistryValue(name: String): Boolean {
        return any { value -> value.name.equals(name, ignoreCase = true) }
    }

    private fun List<RegistryValue>.namedData(name: String): String? {
        return firstOrNull { value -> value.name.equals(name, ignoreCase = true) }
            ?.data
            ?.takeUnless { value -> value.isWindowsRegistryUnsetValue() }
    }

    private fun List<RegistryValue>.defaultData(): String? {
        return defaultValue()
            ?.data
            ?.takeUnless { value -> value.isWindowsRegistryUnsetValue() }
    }

    private fun List<RegistryValue>.defaultValue(): RegistryValue? {
        return firstOrNull { value -> value.isDefault }
    }

    private fun List<RegistryValue>.toWindowsShellChildActions(depth: Int): List<SystemMenuAction> {
        val subCommandActions = namedData("SubCommands")
            ?.split(";")
            .orEmpty()
            .map { value -> value.trim() }
            .filter { value -> value.isNotBlank() }
            .mapNotNull { commandId -> commandId.toWindowsCommandStoreAction(depth) }
        val extendedSubCommandActions = namedData("ExtendedSubCommandsKey")
            ?.let { key -> windowsExtendedSubCommandKeys(key) }
            .orEmpty()
            .asSequence()
            .flatMap { key -> windowsShellVerbKeys(key).asSequence() }
            .mapNotNull { key -> key.toWindowsShellAction(depth) }
            .toList()
        return (subCommandActions + extendedSubCommandActions)
            .distinctBy { action -> action.displayName to action.command }
    }

    private fun String.toWindowsCommandStoreAction(depth: Int): SystemMenuAction? {
        return windowsCommandStoreKeys(this)
            .firstNotNullOfOrNull { key ->
                if (queryRegistryValues(key).isEmpty()) null else key.toWindowsShellAction(depth)
            }
    }

    private fun windowsCommandStoreKeys(commandId: String): List<String> {
        return listOf(
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\CommandStore\\shell\\$commandId",
            "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\CommandStore\\shell\\$commandId",
        )
    }

    private fun windowsExtendedSubCommandKeys(key: String): List<String> {
        val trimmedKey = key.trim().trim('\\')
        if (trimmedKey.isBlank()) return emptyList()
        return if (trimmedKey.startsWith("HK", ignoreCase = true)) {
            listOf(trimmedKey)
        } else {
            listOf(
                "HKCU\\Software\\Classes\\$trimmedKey",
                "HKCR\\$trimmedKey",
            )
        }
    }

    private fun String?.toWindowsMenuLabel(): String? {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (value.isWindowsRegistryUnsetValue()) return null
        if (value.startsWith("@")) return null
        return value.replace("&", "").takeIf { it.isNotBlank() }
    }

    private fun String.isWindowsRegistryUnsetValue(): Boolean {
        return isBlank() ||
            contains("not set", ignoreCase = true) ||
            contains("未设置") ||
            contains("未設定")
    }

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

    private fun Map<String, String>.mimeList(): List<String> {
        return get("MimeType")
            .orEmpty()
            .split(";")
            .map { value -> value.trim() }
            .filter { value -> value.isNotBlank() }
    }

    private fun Map<String, String>.acceptsTargetCount(targetCount: Int): Boolean {
        val min = get("X-KDE-MinNumberOfUrls")?.toIntOrNull()
        val max = get("X-KDE-MaxNumberOfUrls")?.toIntOrNull()
        return (min == null || targetCount >= min) && (max == null || targetCount <= max)
    }

    private fun List<String>.supportsAll(mimeTypes: List<String>): Boolean {
        return isEmpty() || mimeTypes.all { mimeType -> any { pattern -> pattern.matchesMime(mimeType) } }
    }

    private fun String.matchesMime(mimeType: String): Boolean {
        if (this == "all/all" || this == "all/allfiles" || this == "*/*") return true
        if (endsWith("/*")) return mimeType.startsWith(removeSuffix("*"))
        return equals(mimeType, ignoreCase = true)
    }

    private fun queryMimeType(location: String): String? {
        return commandOutput("xdg-mime", "query", "filetype", location)
            ?.takeIf { value -> value.contains("/") }
    }

    private fun serviceMenuFiles(): List<Path> {
        return serviceMenuDirs().flatMap { dir ->
            if (!Files.isDirectory(dir)) {
                emptyList()
            } else {
                Files.list(dir).use { stream ->
                    stream.filter { path -> path.fileName.toString().endsWith(".desktop") }.toList()
                }
            }
        }
    }

    private fun serviceMenuDirs(): List<Path> {
        val home = System.getProperty("user.home")
        return listOf(
            Path.of(home, ".local/share/kio/servicemenus"),
            Path.of(home, ".local/share/kservices5/ServiceMenus"),
            Path.of("/usr/share/kio/servicemenus"),
            Path.of("/usr/share/kservices5/ServiceMenus"),
        )
    }

    private fun desktopExecArguments(
        action: SystemMenuAction,
        targets: List<Path>,
    ): List<String> {
        val fileArgs = targets.map { path -> path.toString() }
        val uriArgs = targets.map { path -> path.toUri().toString() }
        var containsTargetPlaceholder = false
        val args = splitCommandLine(action.command).flatMap { token ->
            when (token) {
                "%F" -> {
                    containsTargetPlaceholder = true
                    fileArgs
                }
                "%U" -> {
                    containsTargetPlaceholder = true
                    uriArgs
                }
                "%f" -> {
                    containsTargetPlaceholder = true
                    fileArgs.take(1)
                }
                "%u" -> {
                    containsTargetPlaceholder = true
                    uriArgs.take(1)
                }
                else -> {
                    val expanded = token
                        .replace("%f", fileArgs.firstOrNull().orEmpty())
                        .replace("%u", uriArgs.firstOrNull().orEmpty())
                        .replace("%c", action.displayName)
                        .replace("%i", "")
                        .replace("%k", "")
                        .replace("%%", "%")
                    if (expanded != token && (token.contains("%f") || token.contains("%u"))) {
                        containsTargetPlaceholder = true
                    }
                    listOf(expanded).filter { value -> value.isNotBlank() }
                }
            }
        }
        return if (containsTargetPlaceholder) args else args + fileArgs
    }

    private fun splitCommandLine(command: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaping = false

        for (char in command) {
            when {
                escaping -> {
                    current.append(char)
                    escaping = false
                }
                char == '\\' -> escaping = true
                quote != null && char == quote -> quote = null
                quote == null && (char == '\'' || char == '"') -> quote = char
                quote == null && char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        args += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) {
            args += current.toString()
        }
        return args
    }

    private fun isExecutableAvailable(name: String): Boolean {
        val candidate = Path.of(name)
        if (candidate.isAbsolute) return Files.isExecutable(candidate)
        return System.getenv("PATH")
            .orEmpty()
            .split(File.pathSeparator)
            .map { dir -> Path.of(dir, name) }
            .any { path -> Files.isExecutable(path) }
    }

    private fun currentHostPlatform(): HostPlatform {
        val osName = System.getProperty("os.name").lowercase(Locale.ROOT)
        return when {
            osName.contains("mac") || osName.contains("darwin") -> HostPlatform.MACOS
            osName.contains("win") -> HostPlatform.WINDOWS
            osName.contains("nux") || osName.contains("nix") || osName.contains("linux") -> HostPlatform.LINUX
            else -> HostPlatform.OTHER
        }
    }

    private enum class HostPlatform {
        WINDOWS,
        MACOS,
        LINUX,
        OTHER,
    }

    private data class RegistryValue(
        val name: String,
        val type: String,
        val data: String,
    ) {
        val isDefault: Boolean
            get() = name.equals("(Default)", ignoreCase = true) ||
                name.equals("(默认)", ignoreCase = true) ||
                name.equals("(預設)", ignoreCase = true)
    }

    private companion object {
        const val LINUX_ACTION_PREFIX = "linux:"
        const val WINDOWS_ACTION_PREFIX = "windows:"
        const val MAX_SYSTEM_MENU_DEPTH = 4
        val WINDOWS_TARGET_PLACEHOLDERS = listOf("%1", "%L", "%l", "%V", "%v", "%I", "%i")
    }
}

internal data class DesktopEntryFile(
    val groups: Map<String, Map<String, String>>,
)

internal fun Path.readDesktopEntry(): DesktopEntryFile {
    return runCatching {
        val groups = linkedMapOf<String, MutableMap<String, String>>()
        var currentGroup: MutableMap<String, String>? = null
        Files.newBufferedReader(this).useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                when {
                    line.isBlank() || line.startsWith("#") -> Unit
                    line.startsWith("[") && line.endsWith("]") -> {
                        val groupName = line.removePrefix("[").removeSuffix("]")
                        currentGroup = groups.getOrPut(groupName) { linkedMapOf() }
                    }
                    else -> {
                        val separatorIndex = line.indexOf('=')
                        if (separatorIndex > 0) {
                            currentGroup?.put(line.substring(0, separatorIndex), line.substring(separatorIndex + 1))
                        }
                    }
                }
            }
        }
        DesktopEntryFile(groups)
    }.getOrDefault(DesktopEntryFile(emptyMap()))
}

internal fun localizedValue(
    fields: Map<String, String>,
    key: String,
): String? {
    val locale = Locale.getDefault()
    val keys = buildList {
        if (locale.language.isNotBlank() && locale.country.isNotBlank()) {
            add("$key[${locale.language}_${locale.country}]")
        }
        if (locale.language.isNotBlank()) {
            add("$key[${locale.language}]")
        }
        add(key)
    }
    return keys.firstNotNullOfOrNull { candidate ->
        fields[candidate]?.takeIf { value -> value.isNotBlank() }
    }
}

internal fun String?.isTrue(): Boolean {
    return equals("true", ignoreCase = true)
}

internal fun desktopApplicationDirs(): List<Path> {
    val home = System.getProperty("user.home")
    return listOf(
        Path.of(home, ".local/share/applications"),
        Path.of(home, ".local/share/flatpak/exports/share/applications"),
        Path.of("/usr/local/share/applications"),
        Path.of("/usr/share/applications"),
        Path.of("/var/lib/flatpak/exports/share/applications"),
    )
}

private fun commandOutput(vararg command: String): String? {
    return runCatching {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .apply { environment()["LC_ALL"] = "C" }
            .start()
        val output = process.inputStream.readBytes().decodePlatformProcessOutput().trim()
        if (process.waitFor() == 0) output else null
    }.getOrNull()
}
