package com.oruke.onyx.app.platform

import java.awt.GraphicsEnvironment
import java.nio.file.Files
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验证 Windows 任务栏分组标识服务、多窗口 `IPropertyStore` 绑定回收以及快捷方式 `AppUserModelID` 自愈对齐行为。
 */
class WindowsTaskbarGroupingServiceTest {

    /**
     * 验证仅打包版 `Onyx.exe` 会解析为任务栏固定重启命令，开发态 `java`/`javaw` 会被安全过滤。
     */
    @Test
    fun resolvesPackagedExecutablePathOnlyForOnyxLauncher() {
        val tempDir = Files.createTempDirectory("onyx-launcher-resolve-test")
        try {
            val packagedExe = tempDir.resolve("Onyx.exe")
            val javaExe = tempDir.resolve("bin").resolve("javaw.exe")

            val resolvedPackaged = WindowsTaskbarGroupingService.resolvePackagedExecutablePath(
                "\"${packagedExe.toAbsolutePath()}\"",
            )
            assertEquals(packagedExe.toAbsolutePath().normalize().toString(), resolvedPackaged)
            assertNull(WindowsTaskbarGroupingService.resolvePackagedExecutablePath(javaExe.toString()))
            assertNull(WindowsTaskbarGroupingService.resolvePackagedExecutablePath("   "))
            assertNull(WindowsTaskbarGroupingService.resolvePackagedExecutablePath(null))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    /**
     * 验证进程级显式 `AppUserModelID` 在 Windows 上成功注册并可通过原生 API 精确回读。
     */
    @Test
    fun initializesAndReadsProcessExplicitAppUserModelId() {
        if (!WindowsTaskbarGroupingService.isWindowsHost) {
            assertFalse(WindowsTaskbarGroupingService.initializeProcessIdentity())
            assertNull(WindowsTaskbarGroupingService.currentProcessAppUserModelId())
            return
        }

        assertTrue(WindowsTaskbarGroupingService.initializeProcessIdentity())
        assertEquals(
            WindowsTaskbarGroupingService.ONYX_APP_USER_MODEL_ID,
            WindowsTaskbarGroupingService.currentProcessAppUserModelId(),
        )
    }

    /**
     * 验证主窗口与新打开的目录窗口共享统一的窗口级 `System.AppUserModel.ID`，且副窗口关闭前会以 `VT_EMPTY` 回收属性而不影响主窗口。
     */
    @Test
    fun bindsSharedAppUserModelIdAcrossMultipleWindowsAndClearsOnClose() {
        if (!WindowsTaskbarGroupingService.isWindowsHost || GraphicsEnvironment.isHeadless()) {
            return
        }

        val fakeLauncherPath = "C:\\Users\\tester\\AppData\\Local\\Onyx\\Onyx.exe"
        SwingUtilities.invokeAndWait {
            val primaryWindow = JFrame("Onyx Primary Test")
            val secondaryWindow = JFrame("Onyx New Folder Window Test")
            var primaryBinding: AutoCloseable? = null
            var secondaryBinding: AutoCloseable? = null
            try {
                // 路径 A：先创建原生 Peer（addNotify），再安装窗口身份绑定
                primaryWindow.addNotify()
                primaryBinding = WindowsTaskbarGroupingService.installWindowIdentity(
                    window = primaryWindow,
                    relaunchExecutablePath = fakeLauncherPath,
                )

                // 路径 B：先安装窗口身份监听，再触发原生 Peer 创建（验证 DISPLAYABILITY_CHANGED 自动绑定）
                secondaryBinding = WindowsTaskbarGroupingService.installWindowIdentity(
                    window = secondaryWindow,
                    relaunchExecutablePath = fakeLauncherPath,
                )
                assertNull(WindowsTaskbarGroupingService.readWindowAppUserModelId(secondaryWindow))
                secondaryWindow.addNotify()

                val primaryHandle = WindowsTaskbarGroupingService.resolveWindowHandle(primaryWindow)
                val secondaryHandle = WindowsTaskbarGroupingService.resolveWindowHandle(secondaryWindow)
                assertNotNull(primaryHandle)
                assertNotNull(secondaryHandle)
                assertNotEquals(primaryHandle.pointer, secondaryHandle.pointer)

                assertEquals(
                    WindowsTaskbarGroupingService.ONYX_APP_USER_MODEL_ID,
                    WindowsTaskbarGroupingService.readWindowAppUserModelId(primaryWindow),
                )
                assertEquals(
                    WindowsTaskbarGroupingService.ONYX_APP_USER_MODEL_ID,
                    WindowsTaskbarGroupingService.readWindowAppUserModelId(secondaryWindow),
                )
                assertEquals(
                    "\"$fakeLauncherPath\"",
                    WindowsTaskbarGroupingService.readWindowRelaunchCommand(secondaryWindow),
                )

                // 模拟副窗口组合卸载（DisposableEffect.onDispose 先于窗口销毁执行）：擦除副窗口属性且不影响主窗口
                secondaryBinding.close()
                secondaryBinding = null

                assertNull(WindowsTaskbarGroupingService.readWindowAppUserModelId(secondaryWindow))
                assertNull(WindowsTaskbarGroupingService.readWindowRelaunchCommand(secondaryWindow))
                assertEquals(
                    WindowsTaskbarGroupingService.ONYX_APP_USER_MODEL_ID,
                    WindowsTaskbarGroupingService.readWindowAppUserModelId(primaryWindow),
                )
            } finally {
                secondaryBinding?.close()
                primaryBinding?.close()
                secondaryWindow.dispose()
                primaryWindow.dispose()
            }
        }
    }

    /**
     * 验证快捷方式自愈对齐器能够补齐缺失的 `System.AppUserModel.ID`、保持幂等，并严格跳过非目标快捷方式。
     */
    @Test
    fun reconcilesMatchingWindowsShortcutsIdempotentlyAndSkipsUnrelatedTargets() {
        val tempDir = Files.createTempDirectory("onyx-shortcut-appid-test")
        try {
            val onyxExe = Files.writeString(tempDir.resolve("Onyx.exe"), "stub")
            val otherExe = Files.writeString(tempDir.resolve("OtherApp.exe"), "stub")
            val onyxShortcut = tempDir.resolve("StartMenu").resolve("Onyx.lnk")
            val otherShortcut = tempDir.resolve("TaskBar").resolve("Other.lnk")
            val missingShortcut = tempDir.resolve("Missing.lnk")

            if (!WindowsTaskbarGroupingService.isWindowsHost) {
                assertEquals(
                    ShortcutReconcileOutcome.FAILED,
                    WindowsShortcutAppIdReconciler.reconcileShortcut(onyxShortcut, onyxExe.toString()),
                )
                return
            }

            assertEquals(
                ShortcutReconcileOutcome.MISSING,
                WindowsShortcutAppIdReconciler.reconcileShortcut(missingShortcut, onyxExe.toString()),
            )

            assertTrue(WindowsShortcutAppIdReconciler.createShortcutForTest(onyxShortcut, onyxExe))
            assertNull(WindowsShortcutAppIdReconciler.readShortcutAppUserModelId(onyxShortcut))

            // 首次对齐：成功补齐 Oruke.Onyx
            assertEquals(
                ShortcutReconcileOutcome.UPDATED,
                WindowsShortcutAppIdReconciler.reconcileShortcut(onyxShortcut, onyxExe.toString()),
            )
            assertEquals(
                WindowsTaskbarGroupingService.ONYX_APP_USER_MODEL_ID,
                WindowsShortcutAppIdReconciler.readShortcutAppUserModelId(onyxShortcut),
            )

            // 二次对齐：命中幂等检查，不重复重写
            assertEquals(
                ShortcutReconcileOutcome.ALREADY_ALIGNED,
                WindowsShortcutAppIdReconciler.reconcileShortcut(onyxShortcut, onyxExe.toString()),
            )

            // 目标不匹配的快捷方式：严格跳过，不污染其他应用快捷方式
            assertTrue(WindowsShortcutAppIdReconciler.createShortcutForTest(otherShortcut, otherExe))
            assertEquals(
                ShortcutReconcileOutcome.SKIPPED_TARGET_MISMATCH,
                WindowsShortcutAppIdReconciler.reconcileShortcut(otherShortcut, onyxExe.toString()),
            )
            assertNull(WindowsShortcutAppIdReconciler.readShortcutAppUserModelId(otherShortcut))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
