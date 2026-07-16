package com.oruke.onyx.app.filesystem

import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path

/**
 * 系统本地文件启动器，隔离平台默认打开与可执行文件启动差异。
 */
internal fun interface SystemFileOpenLauncher {
    /**
     * 打开指定本地文件。
     *
     * @param path 待打开的绝对本地路径。
     * @return 启动结果。
     */
    fun open(path: Path): Result<Unit>
}

/**
 * JVM 系统文件启动器。
 *
 * Windows 的 `Desktop.open()` 无法打开 `.exe`，因此可执行文件必须直接创建进程；
 * 普通文件及其他平台继续使用 Desktop API 交给系统默认应用。
 *
 * @param isWindowsHost 当前是否运行在 Windows。
 * @param executableStarter Windows 可执行文件进程启动函数。
 * @param desktopOpener 系统默认应用打开函数。
 */
internal class JvmSystemFileOpenLauncher(
    /** 当前宿主是否为 Windows。 */
    private val isWindowsHost: Boolean = detectWindowsHost(),
    /** 直接启动 Windows 可执行文件的函数。 */
    private val executableStarter: (Path) -> Unit = ::startWindowsExecutable,
    /** 通过 Desktop API 打开普通文件的函数。 */
    private val desktopOpener: (Path) -> Unit = ::openWithDesktop,
) : SystemFileOpenLauncher {
    /**
     * 根据平台和文件类型选择直接执行或系统默认打开。
     *
     * @param path 待打开的绝对本地路径。
     * @return 启动结果。
     */
    override fun open(path: Path): Result<Unit> = runCatching {
        require(Files.isRegularFile(path)) {
            "System-open target is not available: $path"
        }
        if (isWindowsHost && path.isWindowsExecutable()) {
            executableStarter(path)
        } else {
            desktopOpener(path)
        }
    }
}

/**
 * 检测当前 JVM 是否运行在 Windows。
 *
 * @return Windows 返回 `true`。
 */
private fun detectWindowsHost(): Boolean {
    return System.getProperty("os.name").startsWith("windows", ignoreCase = true)
}

/**
 * 判断路径是否为 Windows EXE 文件。
 *
 * @return 文件名以 `.exe` 结尾时返回 `true`。
 */
private fun Path.isWindowsExecutable(): Boolean {
    return fileName?.toString()?.endsWith(".exe", ignoreCase = true) == true
}

/**
 * 直接创建 Windows 可执行文件进程。
 *
 * @param path 可执行文件路径。
 */
private fun startWindowsExecutable(path: Path) {
    ProcessBuilder(path.toString())
        .directory(path.parent?.toFile())
        .start()
}

/**
 * 使用系统默认应用打开普通文件。
 *
 * @param path 待打开文件路径。
 */
private fun openWithDesktop(path: Path) {
    check(Desktop.isDesktopSupported()) {
        "Desktop integration is not available"
    }
    val desktop = Desktop.getDesktop()
    check(desktop.isSupported(Desktop.Action.OPEN)) {
        "Open action is not supported on this platform"
    }
    desktop.open(path.toFile())
}
