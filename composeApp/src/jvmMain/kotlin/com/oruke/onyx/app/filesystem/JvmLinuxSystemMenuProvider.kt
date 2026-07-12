package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.SystemMenuAction
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.toList

/**
 * Linux KDE ServiceMenu 与 Nautilus Scripts 菜单提供器。
 */
internal class JvmLinuxSystemMenuProvider {
    /**
     * 查询当前条目适用的 Linux 桌面菜单动作。
     *
     * @param entries 当前菜单目标。
     * @return ServiceMenu 与脚本动作。
     */
    fun listActions(entries: List<VFile>): List<SystemMenuAction> {
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

    /**
     * 执行 Linux 菜单叶子动作。
     *
     * @param action 待执行动作。
     * @param entries 当前菜单目标。
     */
    fun execute(action: SystemMenuAction, entries: List<VFile>) {
        if (action.id.startsWith(SCRIPT_ACTION_PREFIX)) {
            runLinuxScriptAction(action, entries)
        } else {
            runLinuxServiceAction(action, entries)
        }
    }

    /**
     * 将 desktop Service 文件转换为适用动作。
     *
     * @param mimeTypes 当前目标 MIME 类型。
     * @param targetCount 当前选择数量。
     * @return 适用动作序列。
     */
    private fun Path.toLinuxServiceActions(
        mimeTypes: List<String>,
        targetCount: Int,
    ): Sequence<SystemMenuAction> {
        val entry = readDesktopEntry()
        val desktopFields = entry.groups["Desktop Entry"].orEmpty()
        val applicable = desktopFields["Type"] == "Service" &&
            !desktopFields["Hidden"].isTrue() &&
            desktopFields.mimeList().supportsAll(mimeTypes) &&
            desktopFields.acceptsTargetCount(targetCount)
        return if (applicable) {
            desktopFields["Actions"].orEmpty().split(";").asSequence()
                .mapNotNull { actionId -> actionId.toLinuxServiceAction(entry) }
        } else {
            emptySequence()
        }
    }

    /**
     * 将 Desktop Service 的动作标识转换为系统菜单动作。
     *
     * @param entry 已解析 desktop 文件。
     * @return 配置完整且可执行时返回动作。
     */
    private fun String.toLinuxServiceAction(entry: DesktopEntryFile): SystemMenuAction? {
        val id = trim().takeIf { value -> value.isNotBlank() }
        val fields = id?.let { value -> entry.groups["Desktop Action $value"].orEmpty() }.orEmpty()
        val exec = fields["Exec"]?.takeIf { value -> value.isNotBlank() }
        val name = localizedValue(fields, "Name")
        val tryExec = fields["TryExec"]
        val executableAvailable = tryExec == null || isExecutableAvailable(tryExec)
        val actionParts = id?.let { actionId ->
            exec?.let { command -> name?.let { label -> Triple(actionId, command, label) } }
        }
        return actionParts?.takeIf { executableAvailable }?.let { (actionId, command, label) ->
            SystemMenuAction(
                id = "$SERVICE_ACTION_PREFIX${this@toLinuxServiceAction}#$actionId",
                displayName = label,
                command = command,
                iconPath = fields["Icon"],
            )
        }
    }

    /**
     * 查询 Nautilus Scripts 并保留目录层级。
     *
     * @return 可展示脚本动作。
     */
    private fun listLinuxScriptActions(): List<SystemMenuAction> {
        return linuxScriptDirs()
            .asSequence()
            .filter { dir -> Files.isDirectory(dir) }
            .flatMap { dir -> dir.toLinuxScriptActions(root = dir, depth = 0).asSequence() }
            .toList()
    }

    /**
     * 将 Nautilus Scripts 目录转换为级联动作。
     *
     * @param root 脚本根目录。
     * @param depth 当前递归深度。
     * @return 当前目录下可展示动作。
     */
    private fun Path.toLinuxScriptActions(root: Path, depth: Int): List<SystemMenuAction> {
        if (depth > MAX_SYSTEM_MENU_DEPTH) return emptyList()
        return runCatching {
            Files.list(this).use { stream ->
                stream
                    .filter { path -> !path.fileName.toString().startsWith(".") }
                    .sorted { left, right ->
                        left.fileName.toString().compareTo(right.fileName.toString(), ignoreCase = true)
                    }
                    .map { path ->
                        when {
                            Files.isDirectory(path) -> {
                                val children = path.toLinuxScriptActions(root, depth + 1)
                                children.takeIf { values -> values.isNotEmpty() }?.let {
                                    SystemMenuAction(
                                        id = "$SCRIPT_GROUP_PREFIX${root.relativize(path)}",
                                        displayName = path.fileName.toString(),
                                        command = "",
                                        children = children,
                                    )
                                }
                            }
                            Files.isRegularFile(path) && Files.isExecutable(path) -> SystemMenuAction(
                                id = "$SCRIPT_ACTION_PREFIX${path.toAbsolutePath()}",
                                displayName = path.fileName.toString(),
                                command = path.toAbsolutePath().toString(),
                            )
                            else -> null
                        }
                    }
                    .filter { action -> action != null }
                    .map { action -> checkNotNull(action) }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 执行 KDE/Freedesktop ServiceMenu 动作。
     *
     * @param action 被点击动作。
     * @param entries 命令目标。
     */
    private fun runLinuxServiceAction(action: SystemMenuAction, entries: List<VFile>) {
        val targets = entries.map { entry -> entry.requireSystemLocalPath("system menu actions") }
        ProcessBuilder(desktopExecArguments(action, targets))
            .directory(targets.first().parent?.toFile() ?: File("."))
            .start()
    }

    /**
     * 执行 Nautilus Scripts 动作并补齐约定环境变量。
     *
     * @param action 被点击脚本动作。
     * @param entries 命令目标。
     */
    private fun runLinuxScriptAction(action: SystemMenuAction, entries: List<VFile>) {
        val targets = entries.map { entry -> entry.requireSystemLocalPath("system menu scripts") }
        val currentDirectory = targets.first().parent ?: Path.of(System.getProperty("user.home"))
        val processBuilder = ProcessBuilder(action.command).directory(currentDirectory.toFile())
        processBuilder.environment().apply {
            put("NAUTILUS_SCRIPT_SELECTED_FILE_PATHS", targets.joinToString("\n") { path -> path.toString() })
            put("NAUTILUS_SCRIPT_SELECTED_URIS", targets.joinToString("\n") { path -> path.toUri().toString() })
            put("NAUTILUS_SCRIPT_CURRENT_URI", currentDirectory.toUri().toString())
            put("NAUTILUS_SCRIPT_WINDOW_GEOMETRY", "")
        }
        processBuilder.start()
    }

    /**
     * 读取 desktop 文件声明的 MIME 类型。
     *
     * @return MIME 类型列表。
     */
    private fun Map<String, String>.mimeList(): List<String> {
        return get("MimeType").orEmpty().split(";")
            .map { value -> value.trim() }
            .filter { value -> value.isNotBlank() }
    }

    /**
     * 判断 ServiceMenu 是否接受当前选择数量。
     *
     * @param targetCount 当前选择数量。
     * @return 数量符合约束时返回 `true`。
     */
    private fun Map<String, String>.acceptsTargetCount(targetCount: Int): Boolean {
        val min = get("X-KDE-MinNumberOfUrls")?.toIntOrNull()
        val max = get("X-KDE-MaxNumberOfUrls")?.toIntOrNull()
        return (min == null || targetCount >= min) && (max == null || targetCount <= max)
    }

    /**
     * 判断菜单 MIME 模式是否覆盖全部目标。
     *
     * @param mimeTypes 当前目标 MIME 类型。
     * @return 全部支持时返回 `true`。
     */
    private fun List<String>.supportsAll(mimeTypes: List<String>): Boolean {
        return isEmpty() || mimeTypes.all { mimeType -> any { pattern -> pattern.matchesMime(mimeType) } }
    }

    /**
     * 判断 MIME 模式是否匹配具体 MIME 类型。
     *
     * @param mimeType 具体 MIME 类型。
     * @return 匹配时返回 `true`。
     */
    private fun String.matchesMime(mimeType: String): Boolean {
        if (this == "all/all" || this == "all/allfiles" || this == "*/*") return true
        if (endsWith("/*")) return mimeType.startsWith(removeSuffix("*"))
        return equals(mimeType, ignoreCase = true)
    }

    /**
     * 使用 xdg-mime 查询本地文件类型。
     *
     * @param location 本地文件路径。
     * @return MIME 类型。
     */
    private fun queryMimeType(location: String): String? {
        return commandOutput("xdg-mime", "query", "filetype", location)?.takeIf { value -> value.contains("/") }
    }

    /**
     * 搜集 XDG ServiceMenu desktop 文件。
     *
     * @return desktop 文件列表。
     */
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
     * @return ServiceMenu 目录候选。
     */
    private fun serviceMenuDirs(): List<Path> {
        val home = System.getProperty("user.home")
        val dataHome = System.getenv("XDG_DATA_HOME")
            ?.takeIf { value -> value.isNotBlank() }
            ?.let(Path::of)
            ?: Path.of(home, ".local/share")
        val dataDirs = System.getenv("XDG_DATA_DIRS")
            ?.split(File.pathSeparator)
            ?.mapNotNull { value -> value.takeIf { it.isNotBlank() }?.let(Path::of) }
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
     * @return 脚本目录候选。
     */
    private fun linuxScriptDirs(): List<Path> {
        val home = System.getProperty("user.home")
        val dataHome = System.getenv("XDG_DATA_HOME")
            ?.takeIf { value -> value.isNotBlank() }
            ?.let(Path::of)
            ?: Path.of(home, ".local/share")
        return listOf(dataHome.resolve("nautilus/scripts")).distinct()
    }

    /**
     * 展开 desktop Exec 字段并填入文件目标。
     *
     * @param action 菜单动作。
     * @param targets 本地目标路径。
     * @return ProcessBuilder 参数。
     */
    private fun desktopExecArguments(action: SystemMenuAction, targets: List<Path>): List<String> {
        val fileArgs = targets.map { path -> path.toString() }
        val uriArgs = targets.map { path -> path.toUri().toString() }
        var containsTargetPlaceholder = false
        val args = splitCommandLine(action.command).flatMap { token ->
            when (token) {
                "%F" -> fileArgs.also { containsTargetPlaceholder = true }
                "%U" -> uriArgs.also { containsTargetPlaceholder = true }
                "%f" -> fileArgs.take(1).also { containsTargetPlaceholder = true }
                "%u" -> uriArgs.take(1).also { containsTargetPlaceholder = true }
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

    /**
     * 按桌面 Exec 语法拆分命令行。
     *
     * @param command 命令模板。
     * @return 命令参数。
     */
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
        if (current.isNotEmpty()) args += current.toString()
        return args
    }

    /**
     * 判断 TryExec 程序是否存在且可执行。
     *
     * @param name 可执行文件路径或名称。
     * @return 可执行时返回 `true`。
     */
    private fun isExecutableAvailable(name: String): Boolean {
        val candidate = Path.of(name)
        if (candidate.isAbsolute) return Files.isExecutable(candidate)
        return System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { dir -> Path.of(dir, name) }
            .any { path -> Files.isExecutable(path) }
    }

    internal companion object {
        /** KDE/Freedesktop ServiceMenu 动作 ID 前缀。 */
        const val SERVICE_ACTION_PREFIX = "linux-service:"
        /** Nautilus Scripts 可执行动作 ID 前缀。 */
        const val SCRIPT_ACTION_PREFIX = "linux-script:"
        /** Nautilus Scripts 级联分组 ID 前缀。 */
        private const val SCRIPT_GROUP_PREFIX = "linux-script-group:"
        /** 脚本级联菜单最大递归深度。 */
        private const val MAX_SYSTEM_MENU_DEPTH = 4
    }
}
