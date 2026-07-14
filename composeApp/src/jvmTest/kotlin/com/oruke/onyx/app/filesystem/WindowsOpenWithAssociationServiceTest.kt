package com.oruke.onyx.app.filesystem

import com.oruke.onyx.vfs.api.OpenWithApp
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Windows 打开方式关联服务测试。
 */
class WindowsOpenWithAssociationServiceTest {
    /**
     * 校验点击菜单应用后直接把该应用与目标文件交给启动器。
     *
     * @return 无返回值。
     */
    @Test
    fun launchesSelectedAssociationExecutableDirectly() {
        var invocation: Pair<Path, Path>? = null
        val service = WindowsOpenWithAssociationService { executable, target ->
            invocation = executable to target
            Result.success(Unit)
        }
        val executable = Path.of("example-editor.exe")
        val target = Path.of("archive.zip")
        val app = OpenWithApp(
            id = "windows-assoc:test",
            displayName = "Example Editor",
            command = "windows-assoc-handler:$executable",
            iconPath = null,
        )

        val result = service.openWith(target, app)

        assertTrue(result.isSuccess)
        assertEquals(executable to target, invocation)
    }

    /**
     * 校验空应用路径会直接失败，且不会调用启动器或系统选择器。
     *
     * @return 无返回值。
     */
    @Test
    fun rejectsEmptyAssociationExecutable() {
        var launchCount = 0
        val service = WindowsOpenWithAssociationService { _, _ ->
            launchCount += 1
            Result.success(Unit)
        }
        val app = OpenWithApp(
            id = "windows-assoc:invalid",
            displayName = "Invalid App",
            command = "windows-assoc-handler:",
            iconPath = null,
        )

        val result = service.openWith(Path.of("archive.zip"), app)

        assertTrue(result.isFailure)
        assertEquals(0, launchCount)
    }
}
