package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Linux "打开方式"服务 — 基于 xdg-mime 和 .desktop 文件。
 *
 * 实现逻辑：
 * 1. 通过 `xdg-mime query filetype <path>` 获取 MIME 类型
 * 2. 通过 `xdg-mime query default <mime>` 获取默认应用
 * 3. 扫描 /usr/share/applications/ 和 ~/.local/share/applications/ 获取候选应用
 * 4. 解析 .desktop 文件获取应用名称和启动命令
 */
class JvmLinuxOpenWithService : OpenWithService {

    override suspend fun listApps(entry: VFile): List<OpenWithApp> = withContext(Dispatchers.IO) {
        val mimeType = queryMimeType(entry.location) ?: return@withContext emptyList()
        val desktopFiles = findDesktopFilesForMime(mimeType)
        desktopFiles.mapNotNull { parseDesktopFile(it) }.distinctBy { it.id }
    }

    override suspend fun openWith(entry: VFile, app: OpenWithApp): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 解析命令行模板：替换 %f %F %u %U 为实际文件路径
            val command = app.command
                .replace("%f", "\"${entry.location}\"")
                .replace("%F", "\"${entry.location}\"")
                .replace("%u", "\"file://${entry.location}\"")
                .replace("%U", "\"file://${entry.location}\"")
                .replace("%i", "")
                .replace("%c", app.displayName)
                .replace("%k", "")
                .trim()

            // 如果命令中没有包含路径参数占位符，追加文件路径
            val finalCommand = if (!app.command.contains("%f") &&
                !app.command.contains("%F") &&
                !app.command.contains("%u") &&
                !app.command.contains("%U")
            ) {
                "$command \"${entry.location}\""
            } else {
                command
            }

            ProcessBuilder("sh", "-c", finalCommand)
                .directory(File(entry.location).parentFile ?: File("."))
                .start()
            Unit
        }
    }

    override suspend fun openWithChooser(entry: VFile): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 尝试使用 xdg-open 的选择器或桌面环境自带的选择器
            val desktopEnv = System.getenv("XDG_CURRENT_DESKTOP")?.lowercase() ?: ""
            val command = when {
                desktopEnv.contains("kde") || desktopEnv.contains("plasma") ->
                    "kioclient exec \"${entry.location}\""
                desktopEnv.contains("gnome") || desktopEnv.contains("unity") ->
                    "gio open \"${entry.location}\""
                else ->
                    "xdg-open \"${entry.location}\""
            }
            ProcessBuilder("sh", "-c", command)
                .directory(File(entry.location).parentFile ?: File("."))
                .start()
            Unit
        }
    }

    private fun queryMimeType(filePath: String): String? {
        return try {
            // 优先使用 xdg-mime
            val process = ProcessBuilder("xdg-mime", "query", "filetype", filePath)
                .redirectErrorStream(true)
                .start()
            val result = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (result.contains("/")) result else {
                // 降级使用 file --mime-type
                val fallback = ProcessBuilder("file", "--brief", "--mime-type", filePath)
                    .redirectErrorStream(true)
                    .start()
                val fbResult = fallback.inputStream.bufferedReader().readText().trim()
                fallback.waitFor()
                if (fbResult.contains("/")) fbResult else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findDesktopFilesForMime(mimeType: String): List<Path> {
        val apps = mutableListOf<Path>()

        // 1. 查询 xdg-mime query default 获取默认应用
        try {
            val process = ProcessBuilder("xdg-mime", "query", "default", mimeType)
                .redirectErrorStream(true)
                .start()
            val defaultApp = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (defaultApp.isNotBlank() && defaultApp.endsWith(".desktop")) {
                resolveDesktopFile(defaultApp)?.let { apps.add(it) }
            }
        } catch (_: Exception) {}

        // 2. 扫描 applications 目录
        val searchDirs = listOf(
            Path.of("/usr/share/applications"),
            Path.of("/usr/local/share/applications"),
            Path.of(System.getProperty("user.home"), ".local/share/applications"),
            Path.of("/var/lib/flatpak/exports/share/applications"),
            Path.of(System.getProperty("user.home"), ".local/share/flatpak/exports/share/applications"),
        )

        for (dir in searchDirs) {
            if (!Files.isDirectory(dir)) continue
            try {
                Files.list(dir).use { stream ->
                    stream.filter { it.toString().endsWith(".desktop") }
                        .forEach { desktopPath ->
                            if (desktopFileSupportsMime(desktopPath, mimeType)) {
                                apps.add(desktopPath)
                            }
                        }
                }
            } catch (_: Exception) {}
        }

        return apps.distinctBy { it.fileName.toString() }
    }

    private fun resolveDesktopFile(name: String): Path? {
        val searchDirs = listOf(
            Path.of("/usr/share/applications"),
            Path.of("/usr/local/share/applications"),
            Path.of(System.getProperty("user.home"), ".local/share/applications"),
            Path.of("/var/lib/flatpak/exports/share/applications"),
        )
        for (dir in searchDirs) {
            val candidate = dir.resolve(name)
            if (Files.exists(candidate)) return candidate
        }
        return null
    }

    private fun desktopFileSupportsMime(path: Path, mimeType: String): Boolean {
        return try {
            val lines = Files.readAllLines(path)
            var inDesktopEntry = false
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed == "[Desktop Entry]") {
                    inDesktopEntry = true
                    continue
                }
                if (trimmed.startsWith("[") && trimmed != "[Desktop Entry]") {
                    inDesktopEntry = false
                    continue
                }
                if (inDesktopEntry && trimmed.startsWith("MimeType=")) {
                    val mimeList = trimmed.removePrefix("MimeType=").split(";")
                    return mimeList.any { it.trim().equals(mimeType, ignoreCase = true) }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun parseDesktopFile(path: Path): OpenWithApp? {
        return try {
            val lines = Files.readAllLines(path)
            var inDesktopEntry = false
            var name: String? = null
            var exec: String? = null
            var icon: String? = null
            var noDisplay = false
            var hidden = false
            var type: String? = null

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed == "[Desktop Entry]") {
                    inDesktopEntry = true
                    continue
                }
                if (trimmed.startsWith("[") && trimmed != "[Desktop Entry]") {
                    break // 只解析 [Desktop Entry] 段
                }
                if (!inDesktopEntry) continue

                when {
                    trimmed.startsWith("Name=") -> name = trimmed.removePrefix("Name=")
                    trimmed.startsWith("Exec=") -> exec = trimmed.removePrefix("Exec=")
                    trimmed.startsWith("Icon=") -> icon = trimmed.removePrefix("Icon=")
                    trimmed.startsWith("NoDisplay=") -> noDisplay = trimmed.removePrefix("NoDisplay=").trim().equals("true", ignoreCase = true)
                    trimmed.startsWith("Hidden=") -> hidden = trimmed.removePrefix("Hidden=").trim().equals("true", ignoreCase = true)
                    trimmed.startsWith("Type=") -> type = trimmed.removePrefix("Type=").trim()
                }
            }

            // 过滤非应用类型和隐藏条目
            if (type != null && type != "Application") return null
            if (noDisplay || hidden) return null
            if (name == null || exec == null) return null

            OpenWithApp(
                id = path.fileName.toString(),
                displayName = name,
                command = exec,
                iconPath = icon,
            )
        } catch (_: Exception) {
            null
        }
    }
}

class JvmPlatformOpenWithService(
    private val linuxOpenWithService: OpenWithService = JvmLinuxOpenWithService(),
) : OpenWithService {
    override suspend fun listApps(entry: VFile): List<OpenWithApp> {
        return when (currentHostPlatform()) {
            HostPlatform.LINUX -> linuxOpenWithService.listApps(entry)
            HostPlatform.MACOS -> listMacApplications()
            HostPlatform.WINDOWS -> listWindowsApplications(entry)
            HostPlatform.OTHER -> emptyList()
        }
    }

    override suspend fun openWith(entry: VFile, app: OpenWithApp): Result<Unit> {
        return when (currentHostPlatform()) {
            HostPlatform.LINUX -> linuxOpenWithService.openWith(entry, app)
            HostPlatform.MACOS -> runProcess("open", "-a", app.command.ifBlank { app.displayName }, entry.location)
            HostPlatform.WINDOWS -> runWindowsCommandTemplate(entry.location, app.command)
            HostPlatform.OTHER -> Result.failure(UnsupportedOperationException())
        }
    }

    override suspend fun openWithChooser(entry: VFile): Result<Unit> {
        return when (currentHostPlatform()) {
            HostPlatform.LINUX -> linuxOpenWithService.openWithChooser(entry)
            HostPlatform.WINDOWS -> runProcess("rundll32", "shell32.dll,OpenAs_RunDLL", entry.location)
            HostPlatform.MACOS -> runMacOpenWithChooser(entry.location)
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
        queryRegistryDefault("HKCR\\$extension")?.takeIf { value -> value.isNotBlank() }?.let { progIds += it }
        progIds += queryRegistryValueNames("HKCR\\$extension\\OpenWithProgids")
        progIds += queryRegistryValueNames(
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\FileExts\\$extension\\OpenWithProgids"
        )

        progIds
            .asSequence()
            .mapNotNull { progId -> progId.toWindowsOpenWithApp() }
            .distinctBy { app -> app.command.lowercase(Locale.getDefault()) }
            .sortedBy { app -> app.displayName.lowercase(Locale.getDefault()) }
            .take(MAX_WINDOWS_APPLICATIONS)
            .toList()
    }

    private fun String.toWindowsOpenWithApp(): OpenWithApp? {
        val command = queryRegistryDefault("HKCR\\$this\\shell\\open\\command")
            ?.takeIf { value -> value.isNotBlank() }
            ?: return null
        val displayName = queryRegistryDefault("HKCR\\$this")
            ?.takeIf { value -> value.isNotBlank() }
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
            .firstOrNull { value -> value.isNotBlank() }
    }

    private fun queryRegistryValueNames(key: String): List<String> {
        val output = runWindowsRegistryQuery(key) ?: return emptyList()
        return output
            .lineSequence()
            .mapNotNull { line -> line.toRegistryValue()?.name }
            .filter { name -> name.isNotBlank() && !name.equals("(Default)", ignoreCase = true) }
            .distinct()
            .toList()
    }

    private fun runWindowsRegistryQuery(vararg args: String): String? {
        return runCatching {
            val process = ProcessBuilder(listOf("reg", "query") + args.toList())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
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
        val target = "\"${location.replace("\"", "\\\"")}\""
        val hadPlaceholder = WINDOWS_TARGET_PLACEHOLDERS.any { placeholder -> contains(placeholder) }
        val command = WINDOWS_TARGET_PLACEHOLDERS.fold(this) { current, placeholder ->
            current.replace(placeholder, target)
        }
        return if (hadPlaceholder) command else "$command $target"
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
