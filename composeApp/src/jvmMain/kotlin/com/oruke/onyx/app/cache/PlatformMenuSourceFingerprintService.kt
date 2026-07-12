package com.oruke.onyx.app.cache

import com.oruke.onyx.app.filesystem.decodePlatformProcessOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** 平台菜单来源指纹服务，用于检测注册表、ServiceMenu 和 Services 目录是否发生变化。 */
internal class PlatformMenuSourceFingerprintService {
    /**
     * 返回当前宿主平台标识。
     *
     * @return 平台标识；未知平台返回 `other`。
     */
    fun currentPlatform(): String {
        val osName = System.getProperty("os.name").lowercase(Locale.ROOT)
        return when {
            osName.contains("mac") || osName.contains("darwin") -> PLATFORM_MACOS
            osName.contains("win") -> PLATFORM_WINDOWS
            osName.contains("nux") || osName.contains("nix") || osName.contains("linux") -> PLATFORM_LINUX
            else -> PLATFORM_OTHER
        }
    }

    /**
     * 计算当前平台菜单来源的全局指纹。
     *
     * @return 平台菜单来源哈希；用于判断是否需要让菜单缓存失效。
     */
    suspend fun currentFingerprint(): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val platform = currentPlatform()
        digest.appendText(platform)
        when (platform) {
            PLATFORM_WINDOWS -> appendWindowsRegistryFingerprint(digest)
            PLATFORM_LINUX -> appendPathSources(digest, linuxMenuSourceDirs())
            PLATFORM_MACOS -> appendPathSources(digest, macMenuSourceDirs())
            else -> digest.appendText("unsupported")
        }
        digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /**
     * 返回当前平台可以通过文件系统监听的菜单来源目录。
     *
     * Windows 右键菜单的主来源是注册表，不能用 `WatchService` 直接监听，因此返回空列表并继续交给指纹轮询兜底。
     *
     * @return 可注册到文件系统监听器的目录列表。
     */
    fun currentWatchDirectories(): List<Path> {
        return when (currentPlatform()) {
            PLATFORM_LINUX -> linuxMenuSourceDirs()
            PLATFORM_MACOS -> macMenuSourceDirs()
            else -> emptyList()
        }
    }

    /**
     * 返回当前平台可以通过 Windows 注册表通知监听的菜单来源键。
     *
     * @return Windows 平台返回注册表键路径；其他平台返回空列表。
     */
    fun currentRegistryWatchKeyPaths(): List<String> {
        return if (currentPlatform() == PLATFORM_WINDOWS) {
            windowsRegistrySourceKeys()
        } else {
            emptyList()
        }
    }

    /**
     * 写入 Windows 右键菜单相关注册表源摘要。
     *
     * @param digest 正在构建的哈希对象。
     */
    private fun appendWindowsRegistryFingerprint(digest: MessageDigest) {
        windowsRegistrySourceKeys().forEach { key ->
            digest.appendText(key)
            digest.appendText(runCommand("reg", "query", key, "/s").orEmpty())
        }
    }

    /**
     * 写入文件目录源摘要。
     *
     * @param digest 正在构建的哈希对象。
     * @param dirs 需要参与检测的目录列表。
     */
    private fun appendPathSources(
        digest: MessageDigest,
        dirs: List<Path>,
    ) {
        dirs.distinct().forEach { dir ->
            digest.appendText(dir.toAbsolutePath().toString())
            appendPathFingerprint(digest, dir, depth = 0, remaining = MAX_FINGERPRINT_PATHS)
        }
    }

    /**
     * 递归写入目录或文件的轻量指纹。
     *
     * @param digest 正在构建的哈希对象。
     * @param path 当前路径。
     * @param depth 当前递归深度。
     * @param remaining 当前源最多还允许读取的路径数量。
     * @return 扣除本次读取后的剩余路径预算。
     */
    private fun appendPathFingerprint(
        digest: MessageDigest,
        path: Path,
        depth: Int,
        remaining: Int,
    ): Int {
        var nextRemaining = remaining
        if (remaining > 0) {
            digest.appendText(path.name)
            nextRemaining = remaining - 1
            when {
                !path.exists() -> digest.appendText("missing")
                path.isRegularFile() -> {
                    val size = runCatching { Files.size(path).toString() }.getOrDefault("0")
                    digest.appendText(size)
                }

                path.isDirectory() && depth < MAX_FINGERPRINT_DEPTH -> runCatching {
                    Files.list(path).use { stream ->
                        stream
                            .sorted { left, right -> left.name.compareTo(right.name, ignoreCase = true) }
                            .forEach { child ->
                                nextRemaining = appendPathFingerprint(digest, child, depth + 1, nextRemaining)
                            }
                    }
                }

                else -> Unit
            }
            val modifiedAt = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
            digest.appendText(modifiedAt.toString())
        }
        return nextRemaining
    }

    /**
     * 返回 Windows 静态右键菜单常见注册表来源。
     *
     * @return 注册表键路径列表。
     */
    private fun windowsRegistrySourceKeys(): List<String> {
        return listOf(
            "HKCU\\Software\\Classes\\*\\shell",
            "HKCR\\*\\shell",
            "HKCU\\Software\\Classes\\Directory\\shell",
            "HKCR\\Directory\\shell",
            "HKCU\\Software\\Classes\\Folder\\shell",
            "HKCR\\Folder\\shell",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\CommandStore\\shell",
            "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\CommandStore\\shell",
        )
    }

    /**
     * 返回 Linux 菜单注册源目录。
     *
     * @return XDG desktop、KDE ServiceMenu 与 Nautilus Scripts 目录列表。
     */
    private fun linuxMenuSourceDirs(): List<Path> {
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
                base.resolve("applications"),
                base.resolve("kio/servicemenus"),
                base.resolve("kservices5/ServiceMenus"),
                base.resolve("kservices6/ServiceMenus"),
            )
        } + listOf(dataHome.resolve("nautilus/scripts"))
    }

    /**
     * 返回 macOS Services 与应用包目录。
     *
     * @return 可能影响 Services / Quick Actions 的目录列表。
     */
    private fun macMenuSourceDirs(): List<Path> {
        val home = System.getProperty("user.home")
        return listOf(
            Path.of(home, "Library/Services"),
            Path.of("/Library/Services"),
            Path.of("/System/Library/Services"),
            Path.of(home, "Applications"),
            Path.of("/Applications"),
            Path.of("/System/Applications"),
        )
    }

    /**
     * 执行系统命令并读取文本输出。
     *
     * @param command 命令及参数。
     * @return 命令成功时返回输出；失败时返回 `null`。
     */
    private fun runCommand(vararg command: String): String? {
        return runCatching {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.readBytes().decodePlatformProcessOutput().trim()
            if (process.waitFor() == 0) output else null
        }.getOrNull()
    }

    /**
     * 将文本追加到哈希输入中。
     *
     * @param text 需要追加的文本。
     */
    private fun MessageDigest.appendText(text: String) {
        update(text.toByteArray(StandardCharsets.UTF_8))
        update(0.toByte())
    }

    private companion object {
        /** Windows 平台标识。 */
        const val PLATFORM_WINDOWS = "windows"

        /** Linux 平台标识。 */
        const val PLATFORM_LINUX = "linux"

        /** macOS 平台标识。 */
        const val PLATFORM_MACOS = "macos"

        /** 未支持平台标识。 */
        const val PLATFORM_OTHER = "other"

        /** 单次目录指纹最多读取的路径数量，避免启动时扫完整个 Applications 树。 */
        const val MAX_FINGERPRINT_PATHS = 2_000

        /** 目录指纹递归深度，覆盖 `.app/Contents/Library/Services` 与 KDE 菜单目录。 */
        const val MAX_FINGERPRINT_DEPTH = 4
    }
}
