package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import com.oruke.onyx.vfs.api.OpenWithApp
import com.oruke.onyx.vfs.api.OpenWithService
import com.oruke.onyx.vfs.api.ExternalOpenService
import com.oruke.onyx.vfs.api.SystemFileMaterializer
import com.oruke.onyx.vfs.api.VfsProtocol

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
        val applicationExecutables = linkedSetOf<String>()
        applicationExecutables += queryRegistryOpenWithExecutables("HKCR\\$extension\\OpenWithList")
        applicationExecutables += queryRegistryOpenWithExecutables(
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\FileExts\\$extension\\OpenWithList"
        )

        val progIdApps = progIds
            .asSequence()
            .mapNotNull { progId -> progId.toWindowsOpenWithApp() }
        val applicationApps = applicationExecutables
            .asSequence()
            .mapNotNull { executableName -> executableName.toWindowsApplicationOpenWithApp() }

        (progIdApps + applicationApps)
            .distinctBy { app -> app.command.lowercase(Locale.ROOT) }
            .sortedBy { app -> app.displayName.lowercase(Locale.getDefault()) }
            .take(MAX_WINDOWS_APPLICATIONS)
            .toList()
    }

    private fun String.toWindowsOpenWithApp(): OpenWithApp? {
        if (isWindowsRegistryUnsetValue() || isWindowsRegistryDefaultName()) return null
        val command = windowsProgIdCommandKeys(this)
            .firstNotNullOfOrNull { key -> queryRegistryDefault(key) }
            ?: return null
        val displayName = windowsProgIdDisplayKeys(this)
            .firstNotNullOfOrNull { key -> queryRegistryDefault(key)?.toWindowsMenuLabel() }
            ?: this
        return OpenWithApp(
            id = this,
            displayName = displayName,
            command = command,
            iconPath = windowsProgIdIconKeys(this).firstNotNullOfOrNull { key -> queryRegistryDefault(key) },
        )
    }

    /**
     * 将 Windows `Applications\<exe>` 注册表项转换为“打开方式”应用。
     *
     * @return 注册了 open command 的应用；无命令时返回 `null`。
     */
    private fun String.toWindowsApplicationOpenWithApp(): OpenWithApp? {
        val executableName = trim().takeIf { value -> value.endsWith(".exe", ignoreCase = true) } ?: return null
        val command = windowsApplicationCommandKeys(executableName)
            .firstNotNullOfOrNull { key -> queryRegistryDefault(key) }
            ?: return null
        val displayName = windowsApplicationDisplayKeys(executableName)
            .firstNotNullOfOrNull { key -> queryRegistryNamedData(key, "FriendlyAppName")?.toWindowsMenuLabel() }
            ?: executableName.removeSuffix(".exe").replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
            }
        return OpenWithApp(
            id = "application:$executableName",
            displayName = displayName,
            command = command,
            iconPath = windowsApplicationIconKeys(executableName)
                .firstNotNullOfOrNull { key -> queryRegistryDefault(key) },
        )
    }

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
     * @param key 注册表路径。
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
            contains("value not set", ignoreCase = true) ||
            contains("not set", ignoreCase = true) ||
            contains("未设置") ||
            contains("数值未设置") ||
            contains("未設定") ||
            contains("値が設定されていません")
    }

    private fun String.isWindowsRegistryDefaultName(): Boolean {
        return equals("(Default)", ignoreCase = true) ||
            equals("(默认)", ignoreCase = true) ||
            equals("(預設)", ignoreCase = true)
    }

    /**
     * 返回 ProgId 打开命令的注册表搜索路径，先查用户级关联，再查系统级关联。
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
     * @param executableName 可执行文件名，例如 `notepad.exe`。
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
