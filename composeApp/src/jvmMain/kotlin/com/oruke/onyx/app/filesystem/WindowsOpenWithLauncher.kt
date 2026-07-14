package com.oruke.onyx.app.filesystem

import java.nio.file.Files
import java.nio.file.Path

/**
 * 使用明确的 Windows 应用程序打开本地文件。
 */
internal fun interface WindowsOpenWithLauncher {
    /**
     * 启动应用程序并把目标文件作为独立参数传入。
     *
     * @param executable 用户已选择的应用程序可执行路径。
     * @param target 需要打开的本地文件路径。
     * @return 进程启动结果。
     */
    fun launch(executable: Path, target: Path): Result<Unit>
}

/**
 * 基于独立进程的 Windows 打开方式启动器。
 */
internal class ProcessWindowsOpenWithLauncher : WindowsOpenWithLauncher {
    /**
     * 校验应用与文件仍然存在后，直接启动选中的应用程序。
     *
     * @param executable 用户已选择的应用程序可执行路径。
     * @param target 需要打开的本地文件路径。
     * @return 进程启动结果。
     */
    override fun launch(executable: Path, target: Path): Result<Unit> = runCatching {
        require(Files.isRegularFile(executable)) {
            "Windows association executable is not available: $executable"
        }
        require(Files.isRegularFile(target)) {
            "Open-with target is not available: $target"
        }
        ProcessBuilder(executable.toString(), target.toString())
            .directory(target.parent?.toFile())
            .start()
        Unit
    }
}
