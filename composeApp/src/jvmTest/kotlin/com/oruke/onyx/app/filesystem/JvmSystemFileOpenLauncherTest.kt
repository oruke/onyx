package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * JVM 系统文件启动器测试。
 */
class JvmSystemFileOpenLauncherTest {
    /**
     * 校验 Windows EXE 会直接创建进程，不经过 Desktop 文件关联。
     *
     * @return 无返回值。
     */
    @Test
    fun startsWindowsExecutableDirectly() {
        val executable = Files.createTempFile("onyx-open-", ".exe")
        try {
            var startedPath: Path? = null
            var desktopPath: Path? = null
            val launcher = JvmSystemFileOpenLauncher(
                isWindowsHost = true,
                executableStarter = { path -> startedPath = path },
                desktopOpener = { path -> desktopPath = path },
            )

            launcher.open(executable).getOrThrow()

            assertEquals(executable, startedPath)
            assertNull(desktopPath)
        } finally {
            Files.deleteIfExists(executable)
        }
    }

    /**
     * 校验 Windows 普通文件仍交给系统默认应用打开。
     *
     * @return 无返回值。
     */
    @Test
    fun opensRegularWindowsFileWithDesktop() {
        val document = Files.createTempFile("onyx-open-", ".txt")
        try {
            var startedPath: Path? = null
            var desktopPath: Path? = null
            val launcher = JvmSystemFileOpenLauncher(
                isWindowsHost = true,
                executableStarter = { path -> startedPath = path },
                desktopOpener = { path -> desktopPath = path },
            )

            launcher.open(document).getOrThrow()

            assertNull(startedPath)
            assertEquals(document, desktopPath)
        } finally {
            Files.deleteIfExists(document)
        }
    }

    /**
     * 校验非 Windows 平台不会对 EXE 启用 Windows 专用启动方式。
     *
     * @return 无返回值。
     */
    @Test
    fun opensExecutableWithDesktopOnOtherPlatforms() {
        val executable = Files.createTempFile("onyx-open-", ".exe")
        try {
            var startedPath: Path? = null
            var desktopPath: Path? = null
            val launcher = JvmSystemFileOpenLauncher(
                isWindowsHost = false,
                executableStarter = { path -> startedPath = path },
                desktopOpener = { path -> desktopPath = path },
            )

            launcher.open(executable).getOrThrow()

            assertNull(startedPath)
            assertEquals(executable, desktopPath)
        } finally {
            Files.deleteIfExists(executable)
        }
    }

    /**
     * 校验双击外部打开服务会把最终本地路径交给统一启动器。
     *
     * @return 无返回值。
     */
    @Test
    fun externalOpenServiceDelegatesToSystemLauncher() = runBlocking {
        val executable = Files.createTempFile("onyx-open-", ".exe")
        try {
            var openedPath: Path? = null
            val service = JvmDesktopExternalOpenService(
                materializer = null,
                fileOpenLauncher = SystemFileOpenLauncher { path ->
                    openedPath = path
                    Result.success(Unit)
                },
            )

            service.open(executable.toFileEntry()).getOrThrow()

            assertEquals(executable, openedPath)
        } finally {
            Files.deleteIfExists(executable)
        }
    }
}

/**
 * 将测试本地路径转换为统一文件条目。
 *
 * @return 对应本地文件条目。
 */
private fun Path.toFileEntry(): VFile {
    return VFile(
        id = toString(),
        name = fileName.toString(),
        location = toString(),
        parentLocation = parent?.toString(),
        kind = VFileKind.FILE,
        sizeBytes = null,
        modifiedAtEpochMillis = null,
        hidden = false,
        capabilities = emptySet(),
    )
}
