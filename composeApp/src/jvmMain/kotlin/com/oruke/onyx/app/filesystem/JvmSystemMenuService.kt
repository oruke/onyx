package com.oruke.onyx.app.filesystem

import com.oruke.onyx.app.cache.PlatformMenuCacheScope
import com.oruke.onyx.app.cache.PlatformMenuCacheService
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
import com.oruke.onyx.vfs.api.SystemMenuService
import com.oruke.onyx.vfs.api.SystemMenuAction
import com.oruke.onyx.vfs.api.SystemFileMaterializer
import com.oruke.onyx.vfs.api.VfsProvider

internal class JvmSystemMenuService(
    private val materializer: SystemFileMaterializer,
    private val menuCacheService: PlatformMenuCacheService,
) : SystemMenuService {
    /** Windows Shell COM 菜单桥接，优先用于读取 Explorer 同源的动态右键菜单。 */
    private val windowsShellComMenuBridge = JvmWindowsShellComMenuBridge()

    override suspend fun listActions(entries: List<VFile>): List<SystemMenuAction> = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext emptyList()
        when (currentHostPlatform()) {
            HostPlatform.LINUX -> listCachedSystemActions(entries, "linux-services") {
                listLinuxServiceActions(entries)
            }
            HostPlatform.WINDOWS -> listWindowsShellActionsWithCache(entries)
            HostPlatform.MACOS -> listCachedSystemActions(entries, "macos-services") {
                listMacServiceActions(entries)
            }
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
                action.id.startsWith(LINUX_SCRIPT_ACTION_PREFIX) -> runLinuxScriptAction(action, targetEntries)
                action.id.startsWith(LINUX_SERVICE_ACTION_PREFIX) -> runLinuxServiceAction(action, targetEntries)
                action.id.startsWith(MACOS_SERVICE_ACTION_PREFIX) -> runMacServiceAction(action, targetEntries)
                action.id.startsWith(WINDOWS_COM_ACTION_PREFIX) -> windowsShellComMenuBridge
                    .execute(action, targetEntries)
                    .getOrThrow()
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
            .plus(listLinuxScriptActions())
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
                    id = "$LINUX_SERVICE_ACTION_PREFIX${this}#$id",
                    displayName = name,
                    command = exec,
                    iconPath = fields["Icon"],
                )
            }
    }

    /**
     * 查询 Nautilus Scripts 目录，并保留目录层级作为级联菜单。
     *
     * @return 可展示的 GNOME/Nautilus 脚本动作。
     */
    private fun listLinuxScriptActions(): List<SystemMenuAction> {
        return linuxScriptDirs()
            .asSequence()
            .filter { dir -> Files.isDirectory(dir) }
            .flatMap { dir -> dir.toLinuxScriptActions(root = dir, depth = 0).asSequence() }
            .toList()
    }

    /**
     * 将 Nautilus Scripts 目录转换为系统菜单动作。
     *
     * @param root 脚本根目录。
     * @param depth 当前递归深度。
     * @return 当前目录下可展示的脚本动作。
     */
    private fun Path.toLinuxScriptActions(
        root: Path,
        depth: Int,
    ): List<SystemMenuAction> {
        if (depth > MAX_SYSTEM_MENU_DEPTH) return emptyList()
        return runCatching {
            Files.list(this).use { stream ->
                stream
                    .filter { path -> !path.fileName.toString().startsWith(".") }
                    .sorted { left, right -> left.fileName.toString().compareTo(right.fileName.toString(), ignoreCase = true) }
                    .map { path ->
                        when {
                            Files.isDirectory(path) -> {
                                val children = path.toLinuxScriptActions(root, depth + 1)
                                if (children.isEmpty()) {
                                    null
                                } else {
                                    SystemMenuAction(
                                        id = "$LINUX_SCRIPT_GROUP_PREFIX${root.relativize(path)}",
                                        displayName = path.fileName.toString(),
                                        command = "",
                                        children = children,
                                    )
                                }
                            }
                            Files.isRegularFile(path) && Files.isExecutable(path) -> SystemMenuAction(
                                id = "$LINUX_SCRIPT_ACTION_PREFIX${path.toAbsolutePath()}",
                                displayName = path.fileName.toString(),
                                command = path.toAbsolutePath().toString(),
                            )
                            else -> null
                        }
                    }
                    .filter { action -> action != null }
                    .map { action -> action!! }
                    .toList()
            }
        }.getOrDefault(emptyList())
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

    /**
     * 执行 Nautilus Scripts 动作，并补齐 Nautilus 约定的环境变量。
     *
     * @param action 被点击的脚本动作。
     * @param entries 命令作用的文件条目。
     */
    private fun runLinuxScriptAction(
        action: SystemMenuAction,
        entries: List<VFile>,
    ) {
        val targets = entries.map { entry -> entry.requireSystemLocalPath("system menu scripts") }
        val currentDirectory = targets.first().parent ?: Path.of(System.getProperty("user.home"))
        val processBuilder = ProcessBuilder(action.command)
            .directory(currentDirectory.toFile())
        processBuilder.environment().apply {
            put("NAUTILUS_SCRIPT_SELECTED_FILE_PATHS", targets.joinToString("\n") { path -> path.toString() })
            put("NAUTILUS_SCRIPT_SELECTED_URIS", targets.joinToString("\n") { path -> path.toUri().toString() })
            put("NAUTILUS_SCRIPT_CURRENT_URI", currentDirectory.toUri().toString())
            put("NAUTILUS_SCRIPT_WINDOW_GEOMETRY", "")
        }
        processBuilder.start()
    }

    /**
     * 查询 macOS Services 目录中的 Automator Workflow。
     *
     * @param entries 当前选中的文件条目。
     * @return 可展示的 macOS Services 动作。
     */
    private fun listMacServiceActions(entries: List<VFile>): List<SystemMenuAction> {
        if (entries.any { entry -> !materializer.supports(entry) }) return emptyList()
        return macServiceWorkflows()
            .asSequence()
            .mapNotNull { path -> path.toMacServiceAction() }
            .distinctBy { action -> action.displayName to action.command }
            .sortedBy { action -> action.displayName.lowercase(Locale.getDefault()) }
            .toList()
    }

    /**
     * 将 Automator Workflow 包转换为系统菜单动作。
     *
     * @return 可展示的 macOS Service 动作。
     */
    private fun Path.toMacServiceAction(): SystemMenuAction? {
        if (!Files.isDirectory(this)) return null
        val displayName = fileName.toString()
            .removeSuffix(".workflow")
            .takeIf { name -> name.isNotBlank() }
            ?: return null
        return SystemMenuAction(
            id = "$MACOS_SERVICE_ACTION_PREFIX${toAbsolutePath()}",
            displayName = displayName,
            command = toAbsolutePath().toString(),
            iconPath = toAbsolutePath().toString(),
        )
    }

    /**
     * 执行 macOS Automator Workflow 形式的 Service。
     *
     * @param action 被点击的 Service 动作。
     * @param entries 命令作用的文件条目。
     */
    private fun runMacServiceAction(
        action: SystemMenuAction,
        entries: List<VFile>,
    ) {
        val workflow = Path.of(action.command)
        check(Files.isDirectory(workflow)) {
            "macOS Service workflow is not available: ${action.command}"
        }
        entries
            .map { entry -> entry.requireSystemLocalPath("macOS Services") }
            .forEach { target ->
                ProcessBuilder("automator", "-i", target.toString(), workflow.toString())
                    .directory(target.parent?.toFile() ?: File("."))
                    .start()
            }
    }

    /**
     * 通过缓存读取 Windows 静态注册表菜单，缓存缺失时同步扫描注册表。
     *
     * @param entries 需要查询系统右键菜单的文件条目。
     * @return 可展示的 Windows 系统菜单动作列表。
     */
    private suspend fun listWindowsShellActionsWithCache(entries: List<VFile>): List<SystemMenuAction> {
        val targetEntries = entries.materializeEntries() ?: return emptyList()
        return listCachedSystemActions(targetEntries, "windows-registry-icon-v2") {
            listWindowsShellActions(targetEntries)
        }
    }

    /**
     * 按文件类型和选择数量读取平台菜单缓存，缓存缺失时执行真实平台扫描。
     *
     * @param entries 当前菜单作用的文件条目。
     * @param sourceKey 菜单来源标识。
     * @param loader 缓存缺失时执行的平台菜单扫描函数。
     * @return 可展示的系统菜单动作列表。
     */
    private suspend fun listCachedSystemActions(
        entries: List<VFile>,
        sourceKey: String,
        loader: suspend () -> List<SystemMenuAction>,
    ): List<SystemMenuAction> {
        return menuCacheService.cachedOrLoad(entries.toMenuCacheScope(sourceKey), loader)
    }

    /**
     * 生成平台菜单缓存作用域。
     *
     * @param sourceKey 菜单来源标识。
     * @return 由平台、来源、选择数量和文件类型组成的缓存作用域。
     */
    private fun List<VFile>.toMenuCacheScope(sourceKey: String): PlatformMenuCacheScope {
        val entrySignature = joinToString(separator = "|") { entry ->
            val extension = entry.name.substringAfterLast('.', "")
                .takeIf { value -> value.isNotBlank() }
                ?.lowercase(Locale.ROOT)
                .orEmpty()
            val mimeType = if (entry.kind == VFileKind.DIRECTORY) {
                "inode/directory"
            } else {
                entry.guessSystemMimeType().orEmpty()
            }
            "${entry.kind.name}:$extension:$mimeType"
        }
        return PlatformMenuCacheScope(
            platform = currentHostPlatform().toCachePlatform(),
            scopeKey = "$sourceKey;count=$size;entries=$entrySignature",
        )
    }

    /**
     * 转换为缓存层使用的平台标识。
     *
     * @return 缓存表中的平台字符串。
     */
    private fun HostPlatform.toCachePlatform(): String {
        return when (this) {
            HostPlatform.WINDOWS -> "windows"
            HostPlatform.MACOS -> "macos"
            HostPlatform.LINUX -> "linux"
            HostPlatform.OTHER -> "other"
        }
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
        val registryIconPath = values.namedData("Icon") ?: queryRegistryDefault("$this\\DefaultIcon")
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
                iconPath = registryIconPath,
                children = children,
            )
        }
        val command = queryRegistryDefault("$this\\command")?.takeIf { value -> value.isNotBlank() } ?: return null
        return SystemMenuAction(
            id = "$WINDOWS_ACTION_PREFIX$this",
            displayName = displayName,
            command = command,
            iconPath = registryIconPath ?: command.toWindowsExecutableIconPath(),
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
            contains("value not set", ignoreCase = true) ||
            contains("not set", ignoreCase = true) ||
            contains("未设置") ||
            contains("数值未设置") ||
            contains("未設定") ||
            contains("値が設定されていません")
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

    /**
     * 从 Windows shell command 模板中提取可作为图标来源的 exe 路径。
     *
     * @return 可执行文件路径或名称；无法解析时返回 `null`。
     */
    private fun String.toWindowsExecutableIconPath(): String? {
        val value = trim().takeIf { text -> text.isNotBlank() } ?: return null
        if (value.startsWith("\"")) {
            return value.substringAfter("\"")
                .substringBefore("\"")
                .takeIf { path -> path.endsWith(".exe", ignoreCase = true) }
        }
        return WINDOWS_EXE_PREFIX.find(value)?.groupValues?.getOrNull(1)
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
                runCatching {
                    Files.list(dir).use { stream ->
                        stream.filter { path -> path.fileName.toString().endsWith(".desktop") }.toList()
                    }
                }.getOrDefault(emptyList())
            }
        }
    }

    /**
     * 返回 KDE ServiceMenu 的 XDG 搜索目录。
     *
     * @return ServiceMenu 目录候选列表。
     */
    private fun serviceMenuDirs(): List<Path> {
        val home = System.getProperty("user.home")
        val dataHome = System.getenv("XDG_DATA_HOME")
            ?.takeIf { value -> value.isNotBlank() }
            ?.let { value -> Path.of(value) }
            ?: Path.of(home, ".local/share")
        val dataDirs = System.getenv("XDG_DATA_DIRS")
            ?.split(File.pathSeparator)
            ?.mapNotNull { value -> value.takeIf { it.isNotBlank() }?.let { Path.of(it) } }
            ?.takeIf { dirs -> dirs.isNotEmpty() }
            ?: listOf(Path.of("/usr/local/share"), Path.of("/usr/share"))
        return (listOf(dataHome) + dataDirs).flatMap { base ->
            listOf(
                base.resolve("kio/servicemenus"),
                base.resolve("kservices5/ServiceMenus"),
                base.resolve("kservices6/ServiceMenus"),
            )
        }.distinct()
    }

    /**
     * 返回 GNOME/Nautilus Scripts 搜索目录。
     *
     * @return 脚本目录候选列表。
     */
    private fun linuxScriptDirs(): List<Path> {
        val home = System.getProperty("user.home")
        val dataHome = System.getenv("XDG_DATA_HOME")
            ?.takeIf { value -> value.isNotBlank() }
            ?.let { value -> Path.of(value) }
            ?: Path.of(home, ".local/share")
        return listOf(dataHome.resolve("nautilus/scripts")).distinct()
    }

    /**
     * 返回 macOS Services 搜索目录。
     *
     * @return Services 目录候选列表。
     */
    private fun macServiceDirs(): List<Path> {
        val home = System.getProperty("user.home")
        return listOf(
            Path.of(home, "Library/Services"),
            Path.of("/Library/Services"),
            Path.of("/System/Library/Services"),
        )
    }

    /**
     * 返回 macOS 可执行 Service workflow 列表。
     *
     * 除了传统 `Library/Services`，这里还读取应用包内的 `Contents/Library/Services`，
     * 因为不少 Finder Services / Quick Actions 是随应用包一起注册的。
     *
     * @return 可展示的 workflow 文件列表。
     */
    private fun macServiceWorkflows(): List<Path> {
        return (macServiceDirs() + macBundledServiceDirs())
            .distinct()
            .flatMap { dir -> dir.workflowFiles() }
    }

    /**
     * 返回应用包内的 macOS Service 目录。
     *
     * @return 应用包 `Contents/Library/Services` 目录列表。
     */
    private fun macBundledServiceDirs(): List<Path> {
        return macApplicationDirs()
            .flatMap { dir ->
                runCatching {
                    Files.list(dir).use { stream ->
                        stream
                            .filter { path -> path.fileName.toString().endsWith(".app", ignoreCase = true) }
                            .map { path -> path.resolve("Contents/Library/Services") }
                            .filter { path -> Files.isDirectory(path) }
                            .toList()
                    }
                }.getOrDefault(emptyList())
            }
    }

    /**
     * 返回 macOS 应用搜索目录。
     *
     * @return 应用目录候选列表。
     */
    private fun macApplicationDirs(): List<Path> {
        val home = System.getProperty("user.home")
        return listOf(
            Path.of(home, "Applications"),
            Path.of("/Applications"),
            Path.of("/System/Applications"),
        ).filter { dir -> Files.isDirectory(dir) }
    }

    /**
     * 读取目录下的 Automator workflow。
     *
     * @return 当前目录中的 workflow 文件列表。
     */
    private fun Path.workflowFiles(): List<Path> {
        if (!Files.isDirectory(this)) return emptyList()
        return runCatching {
            Files.list(this).use { stream ->
                stream
                    .filter { path -> path.fileName.toString().endsWith(".workflow", ignoreCase = true) }
                    .toList()
            }
        }.getOrDefault(emptyList())
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
        /** Linux KDE/Freedesktop ServiceMenu 动作 id 前缀。 */
        const val LINUX_SERVICE_ACTION_PREFIX = "linux-service:"
        /** Linux Nautilus Scripts 可执行动作 id 前缀。 */
        const val LINUX_SCRIPT_ACTION_PREFIX = "linux-script:"
        /** Linux Nautilus Scripts 级联分组 id 前缀。 */
        const val LINUX_SCRIPT_GROUP_PREFIX = "linux-script-group:"
        /** macOS Automator Service 动作 id 前缀。 */
        const val MACOS_SERVICE_ACTION_PREFIX = "macos-service:"
        /** Windows Shell COM 动作 id 前缀。 */
        const val WINDOWS_COM_ACTION_PREFIX = JvmWindowsShellComMenuBridge.WINDOWS_COM_ACTION_PREFIX
        /** Windows 注册表静态 shell 动作 id 前缀。 */
        const val WINDOWS_ACTION_PREFIX = "windows:"
        /** 级联系统菜单读取最大深度，防止异常菜单结构造成无限递归。 */
        const val MAX_SYSTEM_MENU_DEPTH = 4
        /** Windows shell command 常见目标占位符。 */
        val WINDOWS_TARGET_PLACEHOLDERS = listOf("%1", "%L", "%l", "%V", "%v", "%I", "%i")
        /** 从 Windows shell command 头部提取 exe 路径或名称的正则。 */
        val WINDOWS_EXE_PREFIX = Regex("""(?i)^(.+?\.exe)\b""")
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
