package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
        return keys
            .asSequence()
            .flatMap { key -> windowsShellVerbKeys(key).asSequence() }
            .mapNotNull { key -> key.toWindowsShellAction() }
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

    private fun String.toWindowsShellAction(): SystemMenuAction? {
        val command = queryRegistryDefault("$this\\command")?.takeIf { value -> value.isNotBlank() } ?: return null
        val verb = substringAfterLast("\\")
        val displayName = queryRegistryDefault(this)
            ?.takeIf { value -> value.isNotBlank() }
            ?.removePrefix("&")
            ?: verb
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
        val hasTargetPlaceholder = WINDOWS_TARGET_PLACEHOLDERS.any { placeholder -> action.command.contains(placeholder) }
        val command = WINDOWS_TARGET_PLACEHOLDERS.fold(action.command) { current, placeholder ->
            current.replace(placeholder, "\"${target.replace("\"", "\\\"")}\"")
        }
        val commandLine = if (hasTargetPlaceholder) command else "$command \"${target.replace("\"", "\\\"")}\""
        ProcessBuilder("cmd", "/c", commandLine)
            .directory(File(target).parentFile ?: File("."))
            .start()
    }

    private fun queryRegistryDefault(key: String): String? {
        val output = commandOutput("reg", "query", key, "/ve") ?: return null
        return output
            .lineSequence()
            .mapNotNull { line -> line.toRegistryData() }
            .firstOrNull { value -> value.isNotBlank() }
    }

    private fun String.toRegistryData(): String? {
        val parts = trim().split(Regex("\\s{2,}"), limit = 3)
        if (parts.size < 3 || !parts[1].startsWith("REG_", ignoreCase = true)) return null
        return parts[2].trim()
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
        val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
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

    private companion object {
        const val LINUX_ACTION_PREFIX = "linux:"
        const val WINDOWS_ACTION_PREFIX = "windows:"
        val WINDOWS_TARGET_PLACEHOLDERS = listOf("%1", "%L", "%l")
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
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output else null
    }.getOrNull()
}
