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

/**
 * JVM 平台“打开方式”服务调度器，按当前系统委托到 Linux、macOS 或 Windows 实现。
 *
 * @param materializer 系统文件物化器，用于把虚拟文件转成本地文件。
 * @param linuxOpenWithService Linux desktop entry 打开方式服务。
 * @param windowsAssociationService Windows Shell 关联处理器服务。
 * @param windowsRegistryResolver Windows 注册表兜底解析器。
 */
internal class JvmPlatformOpenWithService(
    private val materializer: SystemFileMaterializer,
    private val linuxOpenWithService: OpenWithService = JvmLinuxOpenWithService(materializer),
    private val windowsAssociationService: WindowsOpenWithAssociationService = WindowsOpenWithAssociationService(),
    private val windowsRegistryResolver: WindowsOpenWithRegistryResolver = WindowsOpenWithRegistryResolver(),
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
            HostPlatform.WINDOWS -> openWindowsWith(targetEntry.location, app)
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

    /**
     * 查询 Windows “打开方式”候选应用。
     *
     * @param entry 需要查询打开方式的虚拟文件。
     * @return Shell 关联处理器优先、注册表兜底的应用列表。
     */
    private suspend fun listWindowsApplications(entry: VFile): List<OpenWithApp> = withContext(Dispatchers.IO) {
        val extension = entry.name.substringAfterLast('.', "")
            .takeIf { value -> value.isNotBlank() }
            ?.let { value -> ".$value" }
            ?: return@withContext emptyList()
        val apps = windowsAssociationService.listApps(extension)
            .ifEmpty { windowsRegistryResolver.listApplications(extension) }
        apps
            .distinctBy { app -> app.command.lowercase(Locale.ROOT) }
            .take(MAX_WINDOWS_APPLICATIONS)
            .toList()
    }

    /**
     * 使用 Windows “打开方式”应用打开本地文件。
     *
     * @param location 需要打开的本地文件路径。
     * @param app 用户选择的打开方式应用。
     * @return 打开结果。
     */
    private suspend fun openWindowsWith(
        location: String,
        app: OpenWithApp,
    ): Result<Unit> {
        return if (windowsAssociationService.isAssociationCommand(app.command)) {
            withContext(Dispatchers.IO) {
                windowsAssociationService.openWith(Path.of(location), app)
            }
        } else {
            windowsRegistryResolver.runCommandTemplate(location, app.command)
        }
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

    private companion object {
        const val MAC_APP_SCAN_DEPTH = 2
        const val MAX_MAC_APPLICATIONS = 120
        const val MAX_WINDOWS_APPLICATIONS = 120
    }
}
