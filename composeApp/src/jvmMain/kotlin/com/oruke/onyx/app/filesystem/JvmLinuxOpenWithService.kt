package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

class JvmLinuxOpenWithService(
    private val materializer: SystemFileMaterializer,
) : OpenWithService {
    override fun supports(entry: VFile): Boolean {
        return materializer.supports(entry)
    }

    override suspend fun listApps(entry: VFile): List<OpenWithApp> = withContext(Dispatchers.IO) {
        val mimeType = entry.systemLocalPathOrNull()
            ?.let { path -> queryMimeType(path.toString()) }
            ?: entry.guessSystemMimeType()
            ?: return@withContext emptyList()
        queryRegisteredDesktopIds(mimeType)
            .mapNotNull { desktopId ->
                val desktopFile = resolveDesktopFile(desktopId) ?: return@mapNotNull null
                desktopFile.toOpenWithApp(desktopId)
            }
            .distinctBy { app -> app.id }
    }

    override suspend fun openWith(entry: VFile, app: OpenWithApp): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val targetEntry = materializer.materialize(entry).getOrThrow()
            val target = targetEntry.requireSystemLocalPath("system open-with")
            val desktopFile = Path.of(app.command)
            check(Files.isRegularFile(desktopFile)) {
                "Desktop entry is not available: ${app.id}"
            }
            ProcessBuilder("gio", "launch", desktopFile.toString(), target.toString())
                .directory(target.parent?.toFile() ?: File("."))
                .start()
            Unit
        }
    }

    override suspend fun openWithChooser(entry: VFile): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val targetEntry = materializer.materialize(entry).getOrThrow()
            val target = targetEntry.requireSystemLocalPath("system open-with")
            val targetUri = target.toUri().toString()
            val process = ProcessBuilder(
                "gdbus",
                "call",
                "--session",
                "--dest",
                "org.freedesktop.portal.Desktop",
                "--object-path",
                "/org/freedesktop/portal/desktop",
                "--method",
                "org.freedesktop.portal.OpenURI.OpenURI",
                "",
                targetUri,
                "{'ask': <true>}",
            )
                .directory(target.parent?.toFile() ?: File("."))
                .start()
            val output = process.inputStream.readBytes().decodePlatformProcessOutput().trim()
            check(process.waitFor() == 0) {
                output.ifBlank { "System open-with chooser failed" }
            }
            Unit
        }
    }

    private fun queryMimeType(filePath: String): String? {
        return commandOutput("xdg-mime", "query", "filetype", filePath)
            ?.takeIf { value -> value.contains("/") }
    }

    private fun queryRegisteredDesktopIds(mimeType: String): List<String> {
        val output = commandOutput("gio", "mime", mimeType) ?: return emptyList()
        val recommended = linkedSetOf<String>()
        val registered = linkedSetOf<String>()
        var defaultApp: String? = null
        var section: MutableSet<String>? = null

        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("Default application for") ->
                    defaultApp = line.substringAfter(":").trim().takeDesktopId()
                line == "Recommended applications:" -> section = recommended
                line == "Registered applications:" -> section = registered
                line.endsWith(".desktop") -> section?.add(line)
            }
        }

        return (listOfNotNull(defaultApp) + recommended + registered).distinct()
    }

    private fun resolveDesktopFile(name: String): Path? {
        return desktopApplicationDirs()
            .asSequence()
            .map { dir -> dir.resolve(name) }
            .firstOrNull { path -> Files.isRegularFile(path) }
    }

    private fun Path.toOpenWithApp(desktopId: String): OpenWithApp? {
        val entry = readDesktopEntry()
        val fields = entry.groups["Desktop Entry"].orEmpty()
        if (fields["Hidden"].isTrue()) return null
        val name = localizedValue(fields, "Name") ?: desktopId.removeSuffix(".desktop")
        return OpenWithApp(
            id = desktopId,
            displayName = name,
            command = toString(),
            iconPath = fields["Icon"],
        )
    }

    private fun String.takeDesktopId(): String? {
        return takeIf { value -> value.isNotBlank() && value.endsWith(".desktop") }
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
}

class JvmPlatformOpenWithService(
    private val materializer: SystemFileMaterializer,
    private val linuxOpenWithService: OpenWithService = JvmLinuxOpenWithService(materializer),
) : OpenWithService {
    override fun supports(entry: VFile): Boolean {
        return materializer.supports(entry)
    }

    override suspend fun listApps(entry: VFile): List<OpenWithApp> {
        return when (currentHostPlatform()) {
            HostPlatform.LINUX -> linuxOpenWithService.listApps(entry)
            HostPlatform.MACOS -> listMacApplications()
            HostPlatform.WINDOWS -> listWindowsApplications(entry)
            HostPlatform.OTHER -> emptyList()
        }
    }

    override suspend fun openWith(entry: VFile, app: OpenWithApp): Result<Unit> {
        val targetEntry = materializer.materialize(entry).getOrElse { failure -> return Result.failure(failure) }
        return when (currentHostPlatform()) {
            HostPlatform.LINUX -> linuxOpenWithService.openWith(targetEntry, app)
            HostPlatform.MACOS -> runProcess("open", "-a", app.command.ifBlank { app.displayName }, targetEntry.location)
            HostPlatform.WINDOWS -> runWindowsCommandTemplate(targetEntry.location, app.command)
            HostPlatform.OTHER -> Result.failure(UnsupportedOperationException())
        }
    }

    override suspend fun openWithChooser(entry: VFile): Result<Unit> {
        val targetEntry = materializer.materialize(entry).getOrElse { failure -> return Result.failure(failure) }
        return when (currentHostPlatform()) {
            HostPlatform.LINUX -> linuxOpenWithService.openWithChooser(targetEntry)
            HostPlatform.WINDOWS -> runProcess("rundll32", "shell32.dll,OpenAs_RunDLL", targetEntry.location)
            HostPlatform.MACOS -> runMacOpenWithChooser(targetEntry.location)
            HostPlatform.OTHER -> Result.failure(UnsupportedOperationException())
        }
    }

    private suspend fun runProcess(vararg command: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ProcessBuilder(*command)
                .directory(File(command.last()).parentFile ?: File("."))
                .start()
            Unit
        }
    }

    private suspend fun runMacOpenWithChooser(location: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val script = """
                set onyxTarget to POSIX file "${location.appleScriptEscaped()}"
                set onyxApp to choose application
                tell onyxApp to open onyxTarget
            """.trimIndent()
            ProcessBuilder("osascript", "-e", script)
                .directory(File(location).parentFile ?: File("."))
                .start()
            Unit
        }
    }

    private suspend fun runWindowsCommandTemplate(
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

    private suspend fun listMacApplications(): List<OpenWithApp> = withContext(Dispatchers.IO) {
        val applicationDirs = listOf(
            Path.of("/Applications"),
            Path.of(System.getProperty("user.home"), "Applications"),
            Path.of("/System/Applications"),
        )
        applicationDirs
            .asSequence()
            .filter { dir -> Files.isDirectory(dir) }
            .flatMap { dir ->
                runCatching {
                    Files.walk(dir, MAC_APP_SCAN_DEPTH).use { stream ->
                        stream
                            .filter { path -> path.fileName.toString().endsWith(".app", ignoreCase = true) }
                            .map { path -> path.toOpenWithApp() }
                            .toList()
                    }
                }.getOrDefault(emptyList()).asSequence()
            }
            .distinctBy { app -> app.id }
            .sortedBy { app -> app.displayName.lowercase(Locale.getDefault()) }
            .take(MAX_MAC_APPLICATIONS)
            .toList()
    }

    private fun Path.toOpenWithApp(): OpenWithApp {
        val displayName = fileName.toString().removeSuffix(".app")
        return OpenWithApp(
            id = toString(),
            displayName = displayName,
            command = displayName,
            iconPath = toString(),
        )
    }

    private suspend fun listWindowsApplications(entry: VFile): List<OpenWithApp> = withContext(Dispatchers.IO) {
        val extension = File(entry.location).extension
            .takeIf { value -> value.isNotBlank() }
            ?.let { value -> ".$value" }
            ?: return@withContext emptyList()
        val progIds = linkedSetOf<String>()
        queryRegistryDefault("HKCR\\$extension")?.let { progIds += it }
        progIds += queryRegistryValueNames("HKCR\\$extension\\OpenWithProgids")
        progIds += queryRegistryValueNames(
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\FileExts\\$extension\\OpenWithProgids"
        )

        progIds
            .asSequence()
            .mapNotNull { progId -> progId.toWindowsOpenWithApp() }
            .distinctBy { app -> app.command.lowercase(Locale.ROOT) }
            .sortedBy { app -> app.displayName.lowercase(Locale.getDefault()) }
            .take(MAX_WINDOWS_APPLICATIONS)
            .toList()
    }

    private fun String.toWindowsOpenWithApp(): OpenWithApp? {
        if (isWindowsRegistryUnsetValue() || isWindowsRegistryDefaultName()) return null
        val command = queryRegistryDefault("HKCR\\$this\\shell\\open\\command")
            ?: return null
        val displayName = queryRegistryDefault("HKCR\\$this")
            ?.toWindowsMenuLabel()
            ?: this
        return OpenWithApp(
            id = this,
            displayName = displayName,
            command = command,
            iconPath = queryRegistryDefault("HKCR\\$this\\DefaultIcon"),
        )
    }

    private fun queryRegistryDefault(key: String): String? {
        val output = runWindowsRegistryQuery(key, "/ve") ?: return null
        return output
            .lineSequence()
            .mapNotNull { line -> line.toRegistryValue()?.data }
            .firstOrNull { value -> !value.isWindowsRegistryUnsetValue() }
    }

    private fun queryRegistryValueNames(key: String): List<String> {
        val output = runWindowsRegistryQuery(key) ?: return emptyList()
        return output
            .lineSequence()
            .mapNotNull { line -> line.toRegistryValue()?.name }
            .filter { name -> !name.isWindowsRegistryUnsetValue() && !name.isWindowsRegistryDefaultName() }
            .distinct()
            .toList()
    }

    private fun runWindowsRegistryQuery(vararg args: String): String? {
        return runCatching {
            val process = ProcessBuilder(listOf("reg", "query") + args.toList())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.readBytes().decodePlatformProcessOutput()
            if (process.waitFor() == 0) output else null
        }.getOrNull()
    }

    private fun String.toRegistryValue(): RegistryValue? {
        val trimmed = trim()
        if (trimmed.isBlank() || trimmed.startsWith("HKEY", ignoreCase = true)) return null
        val parts = trimmed.split(Regex("\\s{2,}"), limit = 3)
        if (parts.size < 2 || !parts[1].startsWith("REG_", ignoreCase = true)) return null
        return RegistryValue(
            name = parts[0],
            type = parts[1],
            data = parts.getOrNull(2)?.trim().orEmpty(),
        )
    }

    private fun String.toWindowsCommandFor(location: String): String {
        val target = "\"${location.replace("\"", "")}\""
        val hadPlaceholder = WINDOWS_TARGET_PLACEHOLDERS.any { placeholder -> contains(placeholder) }
        val command = WINDOWS_TARGET_PLACEHOLDERS.fold(this) { current, placeholder ->
            current.replace(placeholder, target)
        }
        return if (hadPlaceholder) command else "$command $target"
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

    private fun String.isWindowsRegistryDefaultName(): Boolean {
        return equals("(Default)", ignoreCase = true) ||
            equals("(默认)", ignoreCase = true) ||
            equals("(預設)", ignoreCase = true)
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

    private fun String.appleScriptEscaped(): String {
        return replace("\\", "\\\\").replace("\"", "\\\"")
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
    )

    private companion object {
        const val MAC_APP_SCAN_DEPTH = 2
        const val MAX_MAC_APPLICATIONS = 120
        const val MAX_WINDOWS_APPLICATIONS = 120
        val WINDOWS_TARGET_PLACEHOLDERS = listOf("%1", "%L", "%l")
    }
}
